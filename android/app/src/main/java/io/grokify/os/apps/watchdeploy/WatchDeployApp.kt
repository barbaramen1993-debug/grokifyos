package io.grokify.os.apps.watchdeploy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.GrokifyApi
import io.grokify.os.data.TokenStore
import io.grokify.os.ui.theme.GrokifyColors
import io.grokify.os.update.ApkUpdater
import io.grokify.os.wearbridge.WearApiKeySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone inner app: OTA-download the **wear** channel APK and install it on a
 * Galaxy Watch (wireless ADB). Also stubs a future Data panel for wear→phone telemetry.
 *
 * Available in **release** builds (developer tooling).
 */
@Composable
fun WatchDeployPane(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WatchDeployStore(context) }
    val adb = remember { WatchAdbClient(context) }
    val tokenStore = remember {
        val app = context.applicationContext
        if (app is GrokifyApp) app.tokenStore else TokenStore(app)
    }

    var hostPort by remember { mutableStateOf(store.hostPort.ifBlank { "" }) }
    var pairHostPort by remember { mutableStateOf(store.pairHostPort.ifBlank { "" }) }
    var pairCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Ready") }
    // Newest line first (top). Stored newest-first as well.
    var log by remember { mutableStateOf(normalizeLogOrder(store.lastLog)) }
    var devicesText by remember { mutableStateOf("—") }
    var wearInfo by remember { mutableStateOf("Not checked") }
    var wearDownloadUrl by remember { mutableStateOf("") }
    var wearSha by remember { mutableStateOf("") }
    var wearCode by remember { mutableStateOf(0) }
    var wearName by remember { mutableStateOf("") }
    var wearSize by remember { mutableStateOf(0L) }
    var wearUpdateAvailable by remember { mutableStateOf(false) }
    var adbReady by remember { mutableStateOf<Boolean?>(null) }
    var tab by remember { mutableStateOf(0) } // 0 deploy, 1 data stub
    var logCopiedFlash by remember { mutableStateOf(false) }
    var showAdvancedOta by remember { mutableStateOf(false) }
    // Track in-flight op so Cancel can kill hung adb + clear busy.
    var activeJob by remember { mutableStateOf<Job?>(null) }

    fun appendLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$ts] $line"
        // Newest at top
        val next = if (log.isBlank()) entry else "$entry\n$log"
        val trimmed = next.take(12_000)
        log = trimmed
        store.lastLog = trimmed
    }

    fun copyLog() {
        val body = log.ifBlank { "(empty log)" }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("watch_deploy_log", body))
        logCopiedFlash = true
        status = "Log copied"
    }

    fun cancelBusy(reason: String = "Cancelled") {
        adb.cancelRunning()
        val job = activeJob
        activeJob = null
        job?.cancel()
        busy = false
        progress = 0f
        status = reason
        appendLog(reason)
    }

    /**
     * Launch a cancellable op. Clears [busy] only for the job that still owns it
     * (so a cancelled job cannot unlock/steal a newer op).
     */
    fun runOp(label: String, block: suspend () -> Unit) {
        if (busy) return
        adb.clearCancel()
        val previous = activeJob
        activeJob = null
        previous?.cancel()
        busy = true
        progress = 0f
        status = label
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (activeJob === job) {
                    appendLog("cancelled")
                    status = "Cancelled"
                }
                throw e
            } catch (e: Exception) {
                if (activeJob === job) {
                    val msg = e.message.orEmpty()
                    if (msg.contains("Cancelled", ignoreCase = true)) {
                        appendLog("cancelled")
                        status = "Cancelled"
                    } else {
                        appendLog("$label failed: ${e.javaClass.simpleName}: $msg")
                        status = "$label failed"
                    }
                }
            } finally {
                if (activeJob === job) {
                    busy = false
                    progress = 0f
                    activeJob = null
                }
            }
        }
        activeJob = job
    }

    suspend fun deviceToken(): String? =
        tokenStore.tokenFlow.first()?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun fetchWearLatest(api: GrokifyApi, localCode: Int, localName: String): WearLatest {
        val json = withContext(Dispatchers.IO) {
            api.checkUpdate(
                versionCode = localCode,
                versionName = localName.ifBlank { "0" },
                channel = GrokifyApi.CHANNEL_WEAR,
            )
        }
        return parseWearLatest(json, api)
    }

    suspend fun downloadWear(
        token: String,
        api: GrokifyApi,
        latest: WearLatest,
    ): File {
        wearCode = latest.code
        wearName = latest.name
        wearSize = latest.size
        wearSha = latest.sha
        wearDownloadUrl = latest.downloadUrl
        wearUpdateAvailable = latest.updateAvailable
        wearInfo = latest.summary(store.lastInstalledVersionCode)
        val updater = ApkUpdater(context) { token }
        val result = withContext(Dispatchers.IO) {
            updater.download(
                downloadUrl = latest.downloadUrl,
                expectedSha256 = latest.sha.ifBlank { null },
                channel = ApkUpdater.CHANNEL_WEAR,
            ) { p ->
                progress = if (p < 0f) 0f else p
            }
        }
        progress = 1f
        appendLog(
            "downloaded ${result.bytes} bytes → ${result.file.name} " +
                "sha=${result.sha256.take(12)}…",
        )
        return result.file
    }

    suspend fun connectForInstall(): String {
        val target = WatchAdbClient.normalizeHostPort(hostPort)
            ?: throw IllegalStateException("Set Connect IP:port first (Wireless debugging)")
        status = "Connecting to watch…"
        appendLog("connect → $target")
        // Soft first; hard restart only if needed (same as Connect button).
        var cr = adb.connect(hostPort, forceRestart = false)
        if (cr.isSuccess) {
            appendLog("connect: ${cr.getOrNull()}")
        } else {
            appendLog("connect soft fail: ${cr.exceptionOrNull()?.message}")
            status = "Hard reconnect…"
            cr = adb.connect(hostPort, forceRestart = true)
            if (cr.isSuccess) {
                appendLog("reconnect: ${cr.getOrNull()}")
            } else {
                throw cr.exceptionOrNull()
                    ?: IllegalStateException(
                        "Connect failed — copy fresh Connect IP:port from Wireless debugging",
                    )
            }
        }
        store.hostPort = hostPort
        store.lastSerial = target
        return target
    }

    /**
     * Push vault SpaceXAI key to the watch via ADB broadcast + Wear Data Layer.
     * Wear applicationId matches phone, so Data Layer can deliver after this.
     * (Local function — must appear before [installOnWatch].)
     */
    suspend fun pushKeyToWatch(serial: String) {
        val key = HostApiKeyStore.getValue(context, ApiKeyIds.SPACEXAI).orEmpty()
            .ifBlank { HostApiKeyStore.getValue(context, ApiKeyIds.LEGACY_XAI).orEmpty() }
            .trim()
        if (key.isEmpty()) {
            appendLog("key push skipped: vault empty (Settings → SpaceXAI API key)")
            return
        }
        status = "Pushing SpaceXAI key…"
        // Data Layer (requires same package — now unified).
        runCatching {
            WearApiKeySync.pushKey(context, key)
            appendLog("Data Layer key put len=${key.length}")
        }.onFailure { appendLog("Data Layer push: ${it.message}") }

        // ADB broadcast — reliable right after install over wireless debug.
        val wearPkg = context.packageName // same applicationId as wear
        val inj = adb.injectSpaceXaiKey(serial, wearPkg, key)
        if (inj.isSuccess) {
            appendLog("ADB key inject ok len=${key.length}")
        } else {
            appendLog("ADB key inject: ${inj.exceptionOrNull()?.message}")
        }
        // Nudge wear activity so PhoneApiKeySync refreshes local prefs.
        adb.shell(
            serial,
            "am", "start",
            "-n", "$wearPkg/io.grokify.os.wear.MainActivity",
            "-a", "android.intent.action.MAIN",
        )
        delay(400)
    }

    suspend fun installOnWatch(apk: File, serial: String) {
        // Drop pre-unification wear packages so the user is not stuck on a broken Sync path.
        status = "Cleaning old wear packages…"
        for (pkg in WatchAdbClient.LEGACY_WEAR_PACKAGES) {
            val u = adb.uninstallQuiet(serial, pkg)
            appendLog("uninstall $pkg: ${u.getOrNull() ?: u.exceptionOrNull()?.message}")
        }

        status = "Installing on watch…"
        appendLog("install → $serial (${apk.length()} bytes)")
        val installResult = adb.install(serial, apk) { phase ->
            status = phase
        }
        if (installResult.isSuccess) {
            appendLog("install: ${installResult.getOrNull()}")
            if (wearCode > 0) {
                store.lastInstalledVersionCode = wearCode
                store.lastInstalledVersionName = wearName
            }
            // Immediately seed the vault key (ADB + Data Layer). Same package as phone now.
            pushKeyToWatch(serial)
            status = "Installed $wearName + key"
            wearInfo = "Installed $wearName (code $wearCode) on $serial"
            wearUpdateAvailable = false
        } else {
            val err = installResult.exceptionOrNull()
            appendLog("install failed: ${err?.message}")
            throw err ?: IllegalStateException("install failed")
        }
    }

    /** One-shot: check → download if needed → connect → install. */
    suspend fun updateAndInstall(forceRedownload: Boolean = false) {
        val token = deviceToken()
            ?: throw IllegalStateException("Save device token on Home first")
        val api = GrokifyApi { token }

        status = "Checking wear channel…"
        appendLog("update: checking OTA…")
        val latest = fetchWearLatest(
            api,
            store.lastInstalledVersionCode,
            store.lastInstalledVersionName,
        )
        wearCode = latest.code
        wearName = latest.name
        wearSize = latest.size
        wearSha = latest.sha
        wearDownloadUrl = latest.downloadUrl
        wearUpdateAvailable = latest.updateAvailable
        wearInfo = latest.summary(store.lastInstalledVersionCode)
        appendLog(
            "wear latest: ${latest.name} code=${latest.code} " +
                "available=${latest.updateAvailable}",
        )

        if (latest.code <= 0 || latest.downloadUrl.isBlank()) {
            throw IllegalStateException("No wear APK published yet")
        }

        val updater = ApkUpdater(context) { token }
        var apk = if (forceRedownload) null else updater.cachedApk(ApkUpdater.CHANNEL_WEAR)
        val needDownload = apk == null ||
            latest.updateAvailable ||
            store.lastInstalledVersionCode < latest.code ||
            forceRedownload

        if (needDownload) {
            status = "Downloading $wearName…"
            appendLog("update: downloading…")
            apk = downloadWear(token, api, latest)
        } else {
            appendLog("update: using cached APK (${apk!!.length()} bytes)")
            status = "Using cached APK…"
        }

        val serial = connectForInstall()
        installOnWatch(apk!!, serial)
    }

    // Prepare adb only. Do NOT hard-reconnect on open — after a phone OTA the saved
    // Connect port is often stale, and forceRestart+long timeouts locked the UI
    // looking like "won't connect". Soft probe if a port is saved; never block long.
    LaunchedEffect(Unit) {
        runOp("Preparing adb…") {
            status = "Preparing adb…"
            val r = adb.ensureReady()
            adbReady = r.isSuccess
            if (r.isSuccess) {
                val ver = adb.version().getOrNull()
                appendLog("adb ready${if (ver != null) ": $ver" else ""}")
                if (hostPort.isNotBlank()) {
                    // Soft only: if already "device", show Connected; if not, ask for port.
                    val c = adb.connect(hostPort, forceRestart = false)
                    if (c.isSuccess) {
                        appendLog("soft reconnect: ${c.getOrNull()}")
                        store.lastSerial = WatchAdbClient.normalizeHostPort(hostPort).orEmpty()
                        status = "Connected"
                        refreshDevices(adb) { devicesText = it; appendLog("devices:\n$it") }
                    } else {
                        appendLog(
                            "saved port not live (${c.exceptionOrNull()?.message}). " +
                                "Copy a fresh Connect IP:port from watch Wireless debugging.",
                        )
                        status = "adb ready — paste fresh Connect IP:port"
                        refreshDevices(adb) { devicesText = it }
                    }
                } else {
                    status = "adb ready — set Connect IP:port"
                }
            } else {
                appendLog("adb failed: ${r.exceptionOrNull()?.message}")
                status = "adb not available"
                adbReady = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            adb.cancelRunning()
            activeJob?.cancel()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back always works — cancel hung ops so we never trap the user.
            IconButton(
                onClick = {
                    if (busy) cancelBusy("Left while busy — op cancelled")
                    onBack()
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = GrokifyColors.GlowCyan,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Watch Deploy",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    text = "Developer · phone → watch OTA",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            if (busy) {
                TextButton(
                    onClick = { cancelBusy() },
                    colors = ButtonDefaults.textButtonColors(contentColor = GrokifyColors.GlowAmber),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Cancel")
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip("Deploy", selected = tab == 0) { tab = 0 }
            TabChip("Data", selected = tab == 1) { tab = 1 }
        }

        if (tab == 1) {
            DataStubPane(
                lastCode = store.lastInstalledVersionCode,
                lastName = store.lastInstalledVersionName,
            )
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status strip always visible
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = GrokifyColors.GlowCyan,
                    )
                }
                Text(
                    status,
                    color = GrokifyColors.GlowCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (busy && progress > 0f && progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = GrokifyColors.GlowMint,
                )
            }

            SectionCard("Connect (daily)") {
                Text(
                    "After a phone app update the watch Connect port is often still valid, " +
                        "but if Connect fails: watch → Developer options → Wireless debugging → " +
                        "copy the **current** IP:port (it changes when you toggle wireless debug). " +
                        "Same Wi‑Fi as phone. Pair only if unauthorized.",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = {
                        hostPort = it
                        store.hostPort = it
                    },
                    label = { Text("Connect IP:port") },
                    placeholder = { Text("192.168.1.40:37159") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                    enabled = !busy,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            runOp("Connecting…") {
                                // Soft first (fast if still online), then one hard restart.
                                var r = adb.connect(hostPort, forceRestart = false)
                                if (r.isFailure) {
                                    appendLog("soft connect failed — hard restart…")
                                    status = "Hard reconnect…"
                                    r = adb.connect(hostPort, forceRestart = true)
                                }
                                if (r.isSuccess) {
                                    appendLog("connect: ${r.getOrNull()}")
                                    store.hostPort = hostPort
                                    val serial = WatchAdbClient.normalizeHostPort(hostPort).orEmpty()
                                    if (serial.isNotBlank()) store.lastSerial = serial
                                    status = "Connected"
                                    refreshDevices(adb) { devicesText = it; appendLog("devices:\n$it") }
                                } else {
                                    val err = r.exceptionOrNull()?.message.orEmpty()
                                    appendLog("connect failed: $err")
                                    status = when {
                                        err.contains("unauthorized", ignoreCase = true) ->
                                            "Unauthorized — Allow on watch or Pair again"
                                        err.contains("refused", ignoreCase = true) ||
                                            err.contains("timeout", ignoreCase = true) ||
                                            err.contains("port", ignoreCase = true) ->
                                            "Bad/stale port — copy fresh Connect IP:port"
                                        else -> "Connect failed — see log"
                                    }
                                    refreshDevices(adb) { devicesText = it }
                                }
                            }
                        },
                        // Allow connect while adbReady is still null (first open); block only if known-bad.
                        enabled = !busy && hostPort.isNotBlank() && adbReady != false,
                        colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowCyan),
                        modifier = Modifier.weight(1f),
                    ) { Text("Connect") }
                    TextButton(
                        onClick = {
                            runOp("Refreshing devices…") {
                                if (adbReady != true) {
                                    val ready = adb.ensureReady()
                                    adbReady = ready.isSuccess
                                    if (ready.isFailure) {
                                        status = "adb not available"
                                        appendLog("adb: ${ready.exceptionOrNull()?.message}")
                                        return@runOp
                                    }
                                }
                                refreshDevices(adb) { devicesText = it; appendLog("devices:\n$it") }
                                status = "Devices refreshed"
                            }
                        },
                        enabled = !busy && adbReady != false,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" Devices")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            runOp("Killing adb…") {
                                val k = adb.killServer()
                                appendLog("kill-server: ${k.getOrNull() ?: k.exceptionOrNull()?.message}")
                                status = "adb server killed"
                                devicesText = "—"
                            }
                        },
                        enabled = !busy && adbReady == true,
                    ) { Text("Kill adb") }
                    TextButton(
                        onClick = {
                            runOp("Resetting keys…") {
                                val c = adb.clearKeys()
                                appendLog("reset keys: ${c.getOrNull() ?: c.exceptionOrNull()?.message}")
                                status = "Keys cleared — Pair again (step below)"
                                devicesText = "—"
                            }
                        },
                        enabled = !busy && adbReady == true,
                    ) { Text("Reset keys") }
                }
                Text(
                    "Devices:\n$devicesText",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            SectionCard("Pair (first time / after Reset keys)") {
                Text(
                    "Only when Connect says unauthorized or after Reset keys. " +
                        "Watch: Developer options → Wireless debugging → Pair new device. " +
                        "Enter that temporary IP:port + 6-digit code (expires ~1 min). " +
                        "Then use Connect with the main-screen Connect port (different port).",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairHostPort,
                    onValueChange = {
                        pairHostPort = it
                        store.pairHostPort = it
                    },
                    label = { Text("Pairing IP:port") },
                    placeholder = { Text("192.168.1.40:37123") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = { pairCode = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("6-digit pairing code") },
                    placeholder = { Text("123456") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                    enabled = !busy,
                )
                Button(
                    onClick = {
                        runOp("Pairing…") {
                            val r = adb.pair(pairHostPort, pairCode)
                            if (r.isSuccess) {
                                appendLog("pair: ${r.getOrNull()}")
                                store.pairHostPort = pairHostPort
                                pairCode = ""
                                val hostOnly = pairHostPort.substringBeforeLast(':').trim()
                                appendLog(
                                    "paired OK — open Wireless debugging main screen, " +
                                        "copy Connect IP:port (not the pair port), then Connect " +
                                        "or tap Update & install",
                                )
                                if (hostOnly.isNotBlank() && hostPort.isBlank()) {
                                    hostPort = hostOnly
                                    store.hostPort = hostOnly
                                }
                                status = "Paired — Connect or Update next"
                            } else {
                                appendLog("pair failed: ${r.exceptionOrNull()?.message}")
                                status = "Pair failed — fresh code? same Wi‑Fi?"
                            }
                        }
                    },
                    enabled = !busy && pairHostPort.isNotBlank() && pairCode.length == 6 && adbReady == true,
                    colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowViolet),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pair with watch") }
            }

            SectionCard("Wear OTA (channel=wear)") {
                Text(
                    "Last installed: " +
                        if (store.lastInstalledVersionCode > 0) {
                            "${store.lastInstalledVersionName} (code ${store.lastInstalledVersionCode})"
                        } else {
                            "none yet"
                        },
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
                Text(wearInfo, color = GrokifyColors.TextMuted, fontSize = 13.sp)
                if (wearUpdateAvailable) {
                    Text(
                        "Update available — one tap below",
                        color = GrokifyColors.GlowMint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Primary: one-shot check + download + install
                Button(
                    onClick = {
                        runOp("Updating watch…") {
                            updateAndInstall(forceRedownload = false)
                        }
                    },
                    enabled = !busy && adbReady == true && hostPort.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowMint),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Black,
                        )
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        if (wearUpdateAvailable) "Update & install"
                        else "Update & install",
                    )
                }
                Text(
                    "Checks the wear channel, downloads if newer (or missing cache), " +
                        "connects to the watch, installs, and pushes your SpaceXAI vault key. " +
                        "Needs Connect IP:port filled in.",
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                )

                Button(
                    onClick = {
                        runOp("Pushing API key…") {
                            val serial = connectForInstall()
                            pushKeyToWatch(serial)
                            status = "Key pushed to watch"
                        }
                    },
                    enabled = !busy && adbReady == true && hostPort.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowViolet),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Push SpaceXAI key to watch")
                }
                Text(
                    "Use if Carina still says “no key” after install. Phone vault id: spacexai_api_key.",
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                )

                TextButton(
                    onClick = { showAdvancedOta = !showAdvancedOta },
                    enabled = !busy,
                ) {
                    Text(if (showAdvancedOta) "Hide advanced" else "Advanced (check / download only)")
                }

                if (showAdvancedOta) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                runOp("Checking wear channel…") {
                                    val token = deviceToken()
                                        ?: throw IllegalStateException("Save device token on Home first")
                                    val api = GrokifyApi { token }
                                    val latest = fetchWearLatest(
                                        api,
                                        store.lastInstalledVersionCode,
                                        store.lastInstalledVersionName,
                                    )
                                    wearCode = latest.code
                                    wearName = latest.name
                                    wearSize = latest.size
                                    wearSha = latest.sha
                                    wearDownloadUrl = latest.downloadUrl
                                    wearUpdateAvailable = latest.updateAvailable
                                    if (latest.code <= 0) {
                                        wearInfo = "No wear APK published yet"
                                        appendLog("wear check: no latest")
                                    } else {
                                        wearInfo = latest.summary(store.lastInstalledVersionCode)
                                        appendLog(
                                            "wear latest: ${latest.name} code=${latest.code} " +
                                                "available=${latest.updateAvailable}",
                                        )
                                    }
                                    status = "Wear check done"
                                }
                            },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowViolet),
                            modifier = Modifier.weight(1f),
                        ) { Text("Check only") }
                        Button(
                            onClick = {
                                runOp("Downloading wear APK…") {
                                    val token = deviceToken()
                                        ?: throw IllegalStateException("Save device token on Home first")
                                    val api = GrokifyApi { token }
                                    val latest = if (wearDownloadUrl.isBlank() || wearCode <= 0) {
                                        fetchWearLatest(api, 0, "0")
                                    } else {
                                        WearLatest(
                                            code = wearCode,
                                            name = wearName,
                                            size = wearSize,
                                            sha = wearSha,
                                            downloadUrl = wearDownloadUrl,
                                            updateAvailable = wearUpdateAvailable,
                                        )
                                    }
                                    if (latest.code <= 0) {
                                        throw IllegalStateException("No wear APK published")
                                    }
                                    downloadWear(token, api, latest)
                                    wearInfo = "Downloaded $wearName"
                                    status = "Download complete"
                                }
                            },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = GrokifyColors.GlowCyan),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(" Download")
                        }
                    }
                    TextButton(
                        onClick = {
                            runOp("Installing cached APK…") {
                                val token = deviceToken()
                                val updater = ApkUpdater(context) { token }
                                val apk = updater.cachedApk(ApkUpdater.CHANNEL_WEAR)
                                    ?: throw IllegalStateException("No cached wear APK — use Update & install")
                                val serial = connectForInstall()
                                installOnWatch(apk, serial)
                            }
                        },
                        enabled = !busy && adbReady == true && hostPort.isNotBlank(),
                    ) { Text("Install cached only") }
                }
            }

            SectionCard("Log (newest first)") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (logCopiedFlash) "Copied" else "Long-press text to select · or Copy all",
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                    )
                    Row {
                        TextButton(onClick = { copyLog() }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(" Copy")
                        }
                        TextButton(
                            onClick = {
                                log = ""
                                store.lastLog = ""
                                logCopiedFlash = false
                                status = "Log cleared"
                            },
                        ) { Text("Clear") }
                    }
                }
                SelectionContainer {
                    Text(
                        log.ifBlank { "No log yet." },
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(GrokifyColors.Void)
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class WearLatest(
    val code: Int,
    val name: String,
    val size: Long,
    val sha: String,
    val downloadUrl: String,
    val updateAvailable: Boolean,
) {
    fun summary(lastInstalledCode: Int): String = buildString {
        if (code <= 0) {
            append("No wear APK published yet")
            return@buildString
        }
        append("$name (code $code)")
        if (size > 0) append(" · ${formatBytes(size)}")
        append(
            when {
                updateAvailable || lastInstalledCode < code -> " · newer than last install"
                lastInstalledCode == code -> " · same as last install"
                else -> " · same or older than last install"
            },
        )
    }
}

private fun parseWearLatest(json: JSONObject, api: GrokifyApi): WearLatest {
    val latest = json.optJSONObject("latest")
    if (latest == null) {
        return WearLatest(0, "", 0L, "", "", false)
    }
    val code = latest.optInt("version_code")
    val name = latest.optString("version_name")
    val size = latest.optLong("file_size")
    val sha = latest.optString("sha256")
    val url = latest.optString("download_url")
        .ifBlank { api.apkDownloadUrl(GrokifyApi.CHANNEL_WEAR) }
    val avail = json.optBoolean("update_available")
    return WearLatest(code, name, size, sha, url, avail)
}

@Composable
private fun DataStubPane(lastCode: Int, lastName: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Wear-reported data") {
            Text(
                "When the watch app starts sending payloads (health, context, assistant events), " +
                    "they will show up here. Deploy loop first — telemetry later.",
                color = GrokifyColors.TextMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (lastCode > 0) "Last deployed wear: $lastName (code $lastCode)"
                else "No wear install recorded on this phone yet.",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
            )
            Text(
                "Stub — no data channel yet.",
                color = GrokifyColors.GlowAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) GrokifyColors.GlowCyan.copy(alpha = 0.2f) else GrokifyColors.Panel
    val border = if (selected) GrokifyColors.GlowCyan else GrokifyColors.PanelBorder
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = GrokifyColors.TextPrimary),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp)),
    ) { Text(label) }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            color = GrokifyColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        content()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    focusedBorderColor = GrokifyColors.GlowCyan,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    focusedLabelColor = GrokifyColors.GlowCyan,
    unfocusedLabelColor = GrokifyColors.TextMuted,
    cursorColor = GrokifyColors.GlowCyan,
    focusedContainerColor = GrokifyColors.Void,
    unfocusedContainerColor = GrokifyColors.Void,
)

private suspend fun refreshDevices(adb: WatchAdbClient, onText: (String) -> Unit) {
    val r = adb.devices(readyOnly = false)
    onText(
        if (r.isSuccess) {
            val list = r.getOrNull().orEmpty()
            if (list.isEmpty()) "(none)"
            else list.joinToString("\n") { it.toString() }
        } else {
            "error: ${r.exceptionOrNull()?.message}"
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f MB", mb)
}

/**
 * Older builds appended logs (oldest first). New writes are newest-first.
 */
private fun normalizeLogOrder(raw: String): String {
    if (raw.isBlank()) return raw
    return raw.trim()
}
