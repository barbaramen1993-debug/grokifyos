package io.grokify.os.apps.watchdeploy

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around a bundled arm64 `adb` binary for phone → watch wireless install.
 *
 * Binary lives as `jniLibs/arm64-v8a/libadb.so` (AOSP/platform-tools style, Apache 2.0).
 *
 * **Important:** On modern Android, app-private dirs (`filesDir`, `cacheDir`) are often
 * mounted **noexec**, so copying the binary there yields `error=13 Permission denied`.
 * We exec the library **in place** from [Context.getApplicationInfo]'s `nativeLibraryDir`
 * (extracted jniLibs), which is always executable when `useLegacyPackaging = true`.
 * Writable state (HOME / keys / TMPDIR) stays under [filesDir]/adb.
 */
class WatchAdbClient(context: Context) {
    private val appCtx = context.applicationContext
    private val adbDir = File(appCtx.filesDir, "adb")
    private val homeDir = File(adbDir, "home")
    private val keysDir = File(homeDir, ".android")
    /** Resolved path used for ProcessBuilder; prefer nativeLibraryDir. */
    @Volatile
    private var adbExecutable: File? = null
    /** Live child process (if any) so [cancelRunning] can kill a hung adb. */
    @Volatile
    private var currentProcess: Process? = null
    @Volatile
    private var cancelRequested: Boolean = false

    data class CmdResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val combined: String
            get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()

        val ok: Boolean get() = exitCode == 0
    }

    /**
     * Abort any in-flight adb child and kill our isolated server.
     * Call when the user taps Cancel or leaves a stuck install.
     */
    fun cancelRunning() {
        cancelRequested = true
        try {
            currentProcess?.destroyForcibly()
        } catch (_: Exception) {
        }
        currentProcess = null
        try {
            killServerInternal()
        } catch (_: Exception) {
        }
    }

    /** Clear cancel flag before starting a new user-initiated operation. */
    fun clearCancel() {
        cancelRequested = false
    }

    private fun checkCancelled() {
        if (cancelRequested) {
            throw IOException("Cancelled")
        }
    }

    suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            adbDir.mkdirs()
            homeDir.mkdirs()
            keysDir.mkdirs()

            val exe = resolveAdbExecutable()
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "Bundled adb missing ($NATIVE_LIB). " +
                            "This build only ships arm64-v8a — device ABI=${Build.SUPPORTED_ABIS.joinToString()}",
                    ),
                )
            adbExecutable = exe

            // Smoke: adb version
            val ver = runAdb(listOf("version"), timeoutSec = 15)
            if (!ver.ok && ver.combined.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "adb failed to start from ${exe.absolutePath} (exit ${ver.exitCode}): ${ver.combined}",
                    ),
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Prefer extracted native lib path (executable mount). Never run from [filesDir] —
     * that directory is noexec on current Android and caused error=13 in the field.
     */
    private fun resolveAdbExecutable(): File? {
        val native = File(appCtx.applicationInfo.nativeLibraryDir, NATIVE_LIB)
        if (native.isFile && native.length() >= 1024L) {
            return native
        }

        val parent = File(appCtx.applicationInfo.nativeLibraryDir).parentFile
        val alt = parent?.listFiles()?.firstOrNull { dir ->
            File(dir, NATIVE_LIB).isFile
        }?.let { File(it, NATIVE_LIB) }
        if (alt != null && alt.isFile && alt.length() >= 1024L) {
            return alt
        }

        return null
    }

    suspend fun version(): Result<String> = withContext(Dispatchers.IO) {
        ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
        val r = runAdb(listOf("version"), timeoutSec = 15)
        if (r.combined.isNotBlank()) Result.success(r.combined.lineSequence().first().trim())
        else Result.failure(IOException("adb version empty (exit ${r.exitCode})"))
    }

    /** Kill the isolated adb server (port 5038). Safe to call anytime. */
    suspend fun killServer(): Result<String> = withContext(Dispatchers.IO) {
        ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
        val r = runAdb(listOf("kill-server"), timeoutSec = 15)
        // kill-server often prints nothing; treat as ok
        Result.success(r.combined.ifBlank { "kill-server ok (exit ${r.exitCode})" })
    }

    /**
     * Wipe RSA keys under our adb HOME. Use when the watch shows **unauthorized** or
     * re-pair keeps failing after a successful first pair.
     */
    suspend fun clearKeys(): Result<String> = withContext(Dispatchers.IO) {
        ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
        runCatching { killServerInternal() }
        var removed = 0
        if (keysDir.isDirectory) {
            keysDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.startsWith("adbkey") || f.name.contains("adb_"))) {
                    if (f.delete()) removed++
                }
            }
        }
        keysDir.mkdirs()
        Result.success("cleared $removed key file(s) under ${keysDir.absolutePath}")
    }

    /**
     * One-time wireless pairing: `adb pair host:pairingPort code`.
     *
     * Pairing is only needed once per phone+watch key set (or after [clearKeys]).
     * Day-to-day reconnect uses [connect] with the **connect** port from Wireless
     * debugging (that port changes when wireless debugging restarts).
     */
    suspend fun pair(hostPort: String, pairingCode: String): Result<String> =
        withContext(Dispatchers.IO) {
            ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
            val target = normalizeHostPort(hostPort, defaultPort = null)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Invalid pairing host:port (include the pairing port)"),
                )
            val code = pairingCode.trim().replace(" ", "")
            if (!code.matches(Regex("\\d{6}"))) {
                return@withContext Result.failure(
                    IllegalArgumentException("Pairing code must be 6 digits (got “$pairingCode”)"),
                )
            }

            // Stale adb server is the #1 cause of "worked once, won't pair again".
            killServerInternal()
            delay(400)

            val r = runAdb(listOf("pair", target, code), timeoutSec = 60)
            val msg = r.combined.ifBlank { "exit ${r.exitCode}" }
            val lower = msg.lowercase()
            if (lower.contains("successfully paired") ||
                (r.ok && lower.contains("paired") && !lower.contains("failed"))
            ) {
                Result.success(msg)
            } else if (r.ok && !lower.contains("fail") && !lower.contains("error") &&
                !lower.contains("unable") && !lower.contains("wrong")
            ) {
                Result.success(msg)
            } else {
                Result.failure(IOException(humanizeAdbError("pair", msg)))
            }
        }

    /**
     * `adb connect host:port` — after pairing, use the **connect** address from Wireless
     * debugging (not the temporary pairing port). Bare IP defaults to 5555.
     *
     * Soft path first (no kill-server). Hard restart only when [forceRestart] is true or
     * the first attempt fails — avoids long hangs that lock Watch Deploy after an app update.
     */
    suspend fun connect(hostPort: String, forceRestart: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
            val target = normalizeHostPort(hostPort)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid host:port"))

            // Fast path: already online as "device" — skip reconnect churn after OTA.
            if (!forceRestart) {
                val existing = deviceState(target)
                if (existing == "device") {
                    return@withContext Result.success("already connected (state=device)")
                }
            }

            if (forceRestart) {
                runCatching { runAdb(listOf("disconnect", target), timeoutSec = 8) }
                killServerInternal()
                delay(400)
            }

            var r = runAdb(listOf("connect", target), timeoutSec = CONNECT_TIMEOUT_SEC)
            var msg = r.combined.ifBlank { "exit ${r.exitCode}" }

            if (!isConnectSuccess(msg, r.ok)) {
                // Soft fail → hard recover once (stale server / half-open transport).
                runCatching { runAdb(listOf("disconnect", target), timeoutSec = 8) }
                killServerInternal()
                delay(450)
                r = runAdb(listOf("connect", target), timeoutSec = CONNECT_TIMEOUT_SEC)
                msg = r.combined.ifBlank { "exit ${r.exitCode}" }
            }

            if (!isConnectSuccess(msg, r.ok)) {
                return@withContext Result.failure(IOException(humanizeAdbError("connect", msg)))
            }

            // Wireless ADB often lands offline/missing for a second before "device".
            val state = awaitDeviceState(
                serial = target,
                timeoutMs = 3_500L,
                accept = { it == "device" || it == "unauthorized" },
            )
            when (state) {
                "device" -> Result.success("$msg (state=device)")
                "unauthorized" -> Result.failure(
                    IOException(
                        "Connected but unauthorized — tap Allow on the watch, " +
                            "or Reset keys + Pair again. ($msg)",
                    ),
                )
                "offline" -> Result.failure(
                    IOException(
                        "Connected but offline — toggle Wireless debugging on the watch, " +
                            "copy the **new** Connect IP:port (it changes), try again. ($msg)",
                    ),
                )
                null -> Result.failure(
                    IOException(
                        "Connect OK but watch not listed yet — re-copy Connect IP:port from " +
                            "Wireless debugging (port often changes after phone app updates). ($msg)",
                    ),
                )
                else -> Result.failure(
                    IOException("Watch state=$state after connect. ($msg)"),
                )
            }
        }

    /** Poll `adb devices` until [accept] matches or timeout. */
    private suspend fun awaitDeviceState(
        serial: String,
        timeoutMs: Long,
        accept: (String?) -> Boolean,
    ): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var last: String? = deviceState(serial)
        if (accept(last)) return last
        while (System.nanoTime() < deadline) {
            if (cancelRequested) return last
            delay(280)
            last = deviceState(serial)
            if (accept(last)) return last
        }
        return last
    }

    suspend fun disconnect(hostPort: String? = null): Result<String> = withContext(Dispatchers.IO) {
        ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
        val args = if (hostPort.isNullOrBlank()) {
            listOf("disconnect")
        } else {
            val t = normalizeHostPort(hostPort)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid host:port"))
            listOf("disconnect", t)
        }
        val r = runAdb(args, timeoutSec = 20)
        Result.success(r.combined.ifBlank { "exit ${r.exitCode}" })
    }

    /** Parsed serial lines from `adb devices` (excludes offline/unauthorized when [readyOnly]). */
    suspend fun devices(readyOnly: Boolean = false): Result<List<DeviceLine>> =
        withContext(Dispatchers.IO) {
            ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
            val r = runAdb(listOf("devices"), timeoutSec = 20)
            if (!r.ok && r.stdout.isBlank()) {
                return@withContext Result.failure(IOException("adb devices failed: ${r.combined}"))
            }
            val lines = parseDevices(r.stdout)
                .filter { !readyOnly || it.state == "device" }
            Result.success(lines)
        }

    private fun parseDevices(stdout: String): List<DeviceLine> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) null
                else DeviceLine(serial = parts[0], state = parts[1])
            }
            .toList()

    private fun deviceState(serial: String): String? {
        val r = runAdb(listOf("devices"), timeoutSec = 15)
        return parseDevices(r.stdout).firstOrNull { it.serial == serial }?.state
    }

    /**
     * Install APK on the watch.
     *
     * Strategy (most reliable over wireless Wear):
     * 1. Ensure serial is online (`device`)
     * 2. Try `adb install -r -t --no-incremental` (hard timeout)
     * 3. Fall back to `push` + `pm install -r -t` + cleanup
     *
     * [onPhase] reports short human labels for UI status (never blocks).
     * Call [cancelRunning] to abort; install returns failure with "Cancelled".
     */
    suspend fun install(
        serial: String,
        apk: File,
        onPhase: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            checkCancelled()
            ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
            if (!apk.isFile || apk.length() < 1024L) {
                return@withContext Result.failure(IllegalArgumentException("APK missing or too small"))
            }
            val ser = serial.trim()
            if (ser.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Empty device serial"))
            }

            // Preflight: must be "device"
            onPhase("Checking watch…")
            var state = deviceState(ser)
            if (state != "device") {
                // try a reconnect if serial looks like host:port
                if (ser.contains(':')) {
                    onPhase("Reconnecting…")
                    runAdb(listOf("connect", ser), timeoutSec = 20)
                    delay(400)
                    checkCancelled()
                    state = deviceState(ser)
                }
            }
            if (state == "unauthorized") {
                return@withContext Result.failure(
                    IOException(
                        "Watch is unauthorized — accept the RSA prompt on the watch, " +
                            "or Reset keys then Pair again",
                    ),
                )
            }
            if (state != "device") {
                return@withContext Result.failure(
                    IOException(
                        "Watch not ready (state=${state ?: "missing"}). " +
                            "Connect with the current Wireless debugging IP:port first.",
                    ),
                )
            }

            // Path 1: direct install (disable incremental — flaky on Wear over wifi)
            // Keep timeout tight so a hung wireless link cannot lock the phone UI for minutes.
            onPhase("Installing APK…")
            checkCancelled()
            val direct = runAdb(
                listOf(
                    "-s", ser,
                    "install",
                    "-r", // replace
                    "-t", // allow test packages (debug APKs)
                    "--no-incremental",
                    apk.absolutePath,
                ),
                timeoutSec = INSTALL_TIMEOUT_SEC,
            )
            checkCancelled()
            val directMsg = direct.combined.ifBlank { "exit ${direct.exitCode}" }
            if (isInstallSuccess(directMsg, direct.ok)) {
                return@withContext Result.success(directMsg.ifBlank { "Success" })
            }

            // Some older adb builds don't know --no-incremental — retry without it
            val lowerDirect = directMsg.lowercase()
            if (lowerDirect.contains("unknown option") || lowerDirect.contains("unrecognized")) {
                onPhase("Retry install…")
                val plain = runAdb(
                    listOf("-s", ser, "install", "-r", "-t", apk.absolutePath),
                    timeoutSec = INSTALL_TIMEOUT_SEC,
                )
                checkCancelled()
                val plainMsg = plain.combined.ifBlank { "exit ${plain.exitCode}" }
                if (isInstallSuccess(plainMsg, plain.ok)) {
                    return@withContext Result.success(plainMsg.ifBlank { "Success" })
                }
            }

            // Path 2: push + pm install (often more reliable wireless)
            onPhase("Pushing APK…")
            checkCancelled()
            val remote = "/data/local/tmp/grokify-wear.apk"
            val push = runAdb(
                listOf("-s", ser, "push", apk.absolutePath, remote),
                timeoutSec = PUSH_TIMEOUT_SEC,
            )
            checkCancelled()
            if (!push.ok && !push.combined.lowercase().contains("bytes")) {
                return@withContext Result.failure(
                    IOException(
                        "install failed (direct: $directMsg); push also failed: ${push.combined}",
                    ),
                )
            }

            onPhase("pm install…")
            val pm = runAdb(
                listOf("-s", ser, "shell", "pm", "install", "-r", "-t", remote),
                timeoutSec = PM_TIMEOUT_SEC,
            )
            // cleanup best-effort (short — never block cancel recovery)
            runAdb(listOf("-s", ser, "shell", "rm", "-f", remote), timeoutSec = 10)

            val pmMsg = pm.combined.ifBlank { "exit ${pm.exitCode}" }
            if (isInstallSuccess(pmMsg, pm.ok) || pmMsg.lowercase().contains("success")) {
                Result.success("pm install: $pmMsg")
            } else {
                Result.failure(
                    IOException(
                        "install failed.\n" +
                            "direct: $directMsg\n" +
                            "push: ${push.combined}\n" +
                            "pm: $pmMsg",
                    ),
                )
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (cancelRequested || msg.contains("Cancelled", ignoreCase = true)) {
                Result.failure(IOException("Cancelled"))
            } else {
                Result.failure(
                    IOException("install crashed: ${e.javaClass.simpleName}: ${e.message}", e),
                )
            }
        }
    }

    data class DeviceLine(val serial: String, val state: String) {
        val ready: Boolean get() = state == "device"
        override fun toString(): String = "$serial\t$state"
    }

    /**
     * Run an arbitrary `adb -s serial …` command (timeout in seconds).
     * Prefer higher-level helpers; this is for install post-steps.
     */
    suspend fun shell(
        serial: String,
        vararg shellArgs: String,
        timeoutSec: Long = 30,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            checkCancelled()
            ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
            val args = ArrayList<String>(shellArgs.size + 3)
            args.add("-s")
            args.add(serial.trim())
            args.add("shell")
            args.addAll(shellArgs)
            val r = runAdb(args, timeoutSec = timeoutSec)
            if (r.ok || r.combined.isNotBlank()) {
                Result.success(r.combined.ifBlank { "ok" })
            } else {
                Result.failure(IOException("shell failed exit=${r.exitCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Best-effort uninstall (missing package is fine). */
    suspend fun uninstallQuiet(serial: String, packageName: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                checkCancelled()
                ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
                val r = runAdb(
                    listOf("-s", serial.trim(), "uninstall", packageName),
                    timeoutSec = 45,
                )
                Result.success(r.combined.ifBlank { "exit ${r.exitCode}" })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Push SpaceXAI key into the wear app via broadcast to [KeyInjectReceiver].
     * [wearPackage] must match the installed wear applicationId (same as phone package).
     * Key is base64-encoded so shell metacharacters cannot break the command.
     */
    suspend fun injectSpaceXaiKey(
        serial: String,
        wearPackage: String,
        key: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            checkCancelled()
            ensureReady().exceptionOrNull()?.let { return@withContext Result.failure(it) }
            val cleaned = key.trim()
            if (cleaned.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Empty SpaceXAI key in vault"))
            }
            val b64 = android.util.Base64.encodeToString(
                cleaned.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            val component = "$wearPackage/io.grokify.os.wear.data.KeyInjectReceiver"
            // Explicit component so the broadcast is delivered on modern Android.
            val r = runAdb(
                listOf(
                    "-s", serial.trim(),
                    "shell", "am", "broadcast",
                    "-n", component,
                    "-a", "io.grokify.os.INJECT_SPACEXAI_KEY",
                    "--es", "value_b64", b64,
                ),
                timeoutSec = 30,
            )
            val msg = r.combined.ifBlank { "exit ${r.exitCode}" }
            // am broadcast usually prints "Broadcast completed: result=0"
            if (r.ok || msg.contains("Broadcast completed", ignoreCase = true)) {
                Result.success(msg)
            } else {
                Result.failure(IOException("key inject failed: $msg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun killServerInternal() {
        try {
            runAdb(listOf("kill-server"), timeoutSec = 10)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun isConnectSuccess(msg: String, exitOk: Boolean): Boolean {
        val lower = msg.lowercase()
        if (lower.contains("connected to") || lower.contains("already connected")) return true
        return exitOk &&
            !lower.contains("unable") &&
            !lower.contains("failed") &&
            !lower.contains("cannot") &&
            !lower.contains("refused") &&
            !lower.contains("timed out") &&
            !lower.contains("timeout")
    }

    private fun isInstallSuccess(msg: String, exitOk: Boolean): Boolean {
        val lower = msg.lowercase()
        if (lower.contains("success")) return true
        // rare: empty stdout with exit 0
        return exitOk && lower.isBlank()
    }

    private fun humanizeAdbError(op: String, raw: String): String {
        val lower = raw.lowercase()
        val hint = when {
            lower.contains("unauthorized") ->
                "Watch rejected the key — Accept on watch, or Reset keys + Pair again."
            lower.contains("protocol fault") || lower.contains("connection reset") ->
                "Connection dropped mid-$op — toggle Wireless debugging, copy fresh Connect port."
            lower.contains("refused") || lower.contains("timed out") || lower.contains("timeout") ->
                "Nothing listening — open Wireless debugging on the watch (same Wi‑Fi) and use the current Connect IP:port (it changes)."
            lower.contains("failed to authenticate") || lower.contains("wrong password") ||
                lower.contains("protocol failed") ->
                "Pairing code expired or wrong — open Pair new device again for a fresh code (codes last ~1 min)."
            lower.contains("no devices") || lower.contains("device offline") ->
                "Device offline — Connect first with the current port from Wireless debugging."
            else -> null
        }
        return buildString {
            append("adb $op failed: $raw")
            if (hint != null) append("\n→ $hint")
        }
    }

    private fun runAdb(args: List<String>, timeoutSec: Long): CmdResult {
        if (cancelRequested) {
            return CmdResult(-1, "", "Cancelled")
        }
        val exe = adbExecutable
            ?: File(appCtx.applicationInfo.nativeLibraryDir, NATIVE_LIB).also {
                adbExecutable = it
            }
        if (!exe.isFile) {
            return CmdResult(-1, "", "adb binary not found: ${exe.absolutePath}")
        }
        val cmd = ArrayList<String>(args.size + 1)
        cmd.add(exe.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
            .directory(adbDir)
            .redirectErrorStream(false)
        val env = pb.environment()
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = adbDir.absolutePath
        // Isolate from any system/Termux adb on 5037
        env["ANDROID_ADB_SERVER_PORT"] = ADB_SERVER_PORT
        env["ANDROID_DATA"] = adbDir.absolutePath
        env.remove("LD_LIBRARY_PATH")
        keysDir.mkdirs()

        val proc = try {
            pb.start()
        } catch (e: IOException) {
            return CmdResult(
                exitCode = 13,
                stdout = "",
                stderr = "Cannot exec ${exe.absolutePath}: ${e.message}",
            )
        }
        currentProcess = proc
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            try {
                proc.inputStream.bufferedReader().use { br ->
                    br.forEachLine { line ->
                        if (stdout.isNotEmpty()) stdout.append('\n')
                        stdout.append(line)
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { name = "adb-stdout"; isDaemon = true }
        val errThread = Thread {
            try {
                proc.errorStream.bufferedReader().use { br ->
                    br.forEachLine { line ->
                        if (stderr.isNotEmpty()) stderr.append('\n')
                        stderr.append(line)
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { name = "adb-stderr"; isDaemon = true }
        outThread.start()
        errThread.start()
        val finished = try {
            // Poll so cancelRequested / destroyForcibly is noticed promptly
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
            var done = false
            while (System.nanoTime() < deadline) {
                if (cancelRequested) {
                    proc.destroyForcibly()
                    break
                }
                if (proc.waitFor(250, TimeUnit.MILLISECONDS)) {
                    done = true
                    break
                }
            }
            done
        } catch (e: InterruptedException) {
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
            if (currentProcess === proc) currentProcess = null
            return CmdResult(-1, stdout.toString(), "interrupted: ${e.message}")
        }
        if (currentProcess === proc) currentProcess = null
        if (!finished) {
            proc.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            if (cancelRequested) {
                return CmdResult(-1, stdout.toString(), "Cancelled")
            }
            return CmdResult(
                exitCode = -1,
                stdout = stdout.toString(),
                stderr = (stderr.toString() + "\n(timeout after ${timeoutSec}s)").trim(),
            )
        }
        outThread.join(2000)
        errThread.join(2000)
        if (cancelRequested) {
            return CmdResult(-1, stdout.toString(), "Cancelled")
        }
        return CmdResult(
            exitCode = proc.exitValue(),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
        )
    }

    companion object {
        const val NATIVE_LIB = "libadb.so"
        const val DEFAULT_PORT = 5555
        /** Isolated server port so we never clash with desktop adb on 5037. */
        const val ADB_SERVER_PORT = "5038"
        /** Hard caps so a dead wireless link cannot freeze Watch Deploy for many minutes. */
        private const val INSTALL_TIMEOUT_SEC = 90L
        private const val PUSH_TIMEOUT_SEC = 90L
        private const val PM_TIMEOUT_SEC = 60L
        /** Per-attempt connect timeout (two attempts max → ~24s worst case, not 70s+). */
        private const val CONNECT_TIMEOUT_SEC = 12L

        /** Old wear package ids (pre Data-Layer same-package fix). */
        val LEGACY_WEAR_PACKAGES = listOf(
            "io.grokify.os.wear",
            "io.grokify.os.wear.debug",
        )

        /**
         * @param defaultPort port used when raw has no `:port`. Pass null to require an
         * explicit port (needed for pairing — must not silently use 5555).
         */
        fun normalizeHostPort(raw: String, defaultPort: Int? = DEFAULT_PORT): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val cleaned = s.removePrefix("adb://").trim()
            if (cleaned.contains("://")) return null
            return if (cleaned.contains(':')) {
                val host = cleaned.substringBeforeLast(':').trim()
                val port = cleaned.substringAfterLast(':').trim().toIntOrNull()
                if (host.isEmpty() || port == null || port !in 1..65535) null
                else "$host:$port"
            } else {
                if (cleaned.any { it.isWhitespace() }) null
                else if (defaultPort == null) null
                else "$cleaned:$defaultPort"
            }
        }
    }
}
