package io.grokify.os.wear.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import io.grokify.os.wear.BuildConfig
import io.grokify.os.wear.data.WearPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot OTA for the Wear app over LTE/Wi‑Fi:
 * check → download → open system installer.
 *
 * Auth uses the host device token (synced from phone, or pasted once).
 */
class WearSelfUpdater(context: Context) {

    private val app = context.applicationContext
    private val prefs = WearPrefs(app)
    private val busy = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(
        if (prefs.deviceToken.isNotBlank()) "Ready · v${BuildConfig.VERSION_NAME}"
        else "Need device token (sync from phone)",
    )
    val status: StateFlow<String> = _status.asStateFlow()

    private val _progress = MutableStateFlow(-1f)
    /** 0..1 while downloading; -1 idle / indeterminate. */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }
    }

    /**
     * Single action: check channel=wear, download if newer, open installer.
     * No-op (status only) when already latest.
     */
    suspend fun updateNow() = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) {
            _status.value = "Update already running…"
            return@withContext
        }
        _running.value = true
        _progress.value = -1f
        try {
            val token = prefs.deviceToken.trim()
            if (token.isEmpty()) {
                _status.value = "No device token — open phone Grokify or paste token in Settings"
                return@withContext
            }

            _status.value = "Checking for update…"
            val check = checkUpdate(token)
            if (!check.updateAvailable) {
                _status.value = "Up to date (v${BuildConfig.VERSION_NAME})"
                _progress.value = -1f
                return@withContext
            }

            val label = check.versionName.ifBlank { "update" }
            _status.value = "Downloading $label…"
            val file = download(
                token = token,
                downloadUrl = check.downloadUrl,
                expectedSha256 = check.sha256,
            ) { p ->
                _progress.value = p
                _status.value = if (p < 0f) {
                    "Downloading $label…"
                } else {
                    "Downloading $label… ${(p * 100).toInt()}%"
                }
            }

            _progress.value = 1f
            _status.value = "Opening installer…"
            withContext(Dispatchers.Main) {
                install(file)
            }
            _status.value = "Approve install → $label"
        } catch (e: Exception) {
            Log.w(TAG, "updateNow failed: ${e.message}", e)
            val msg = e.message.orEmpty()
            if (msg.contains("Install unknown", ignoreCase = true) ||
                msg.contains("Allow", ignoreCase = true)
            ) {
                _status.value = msg
            } else {
                _status.value = "Update failed: $msg"
            }
            _progress.value = -1f
        } finally {
            busy.set(false)
            _running.value = false
        }
    }

    private data class CheckResult(
        val updateAvailable: Boolean,
        val versionName: String,
        val versionCode: Int,
        val sha256: String,
        val downloadUrl: String,
    )

    private fun checkUpdate(token: String): CheckResult {
        val path = "/update.php?version_code=${BuildConfig.VERSION_CODE}" +
            "&version_name=${enc(BuildConfig.VERSION_NAME)}" +
            "&channel=$CHANNEL_WEAR"
        val req = Request.Builder()
            .url(API_BASE + path)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Check failed (HTTP ${resp.code}): ${body.take(160)}")
            }
            val json = JSONObject(body)
            if (!json.optBoolean("update_available")) {
                return CheckResult(false, "", 0, "", "")
            }
            val latest = json.optJSONObject("latest")
            val name = latest?.optString("version_name").orEmpty()
            val code = latest?.optInt("version_code") ?: 0
            val sha = latest?.optString("sha256").orEmpty()
            val url = latest?.optString("download_url").orEmpty().ifBlank {
                defaultDownloadUrl()
            }
            return CheckResult(true, name, code, sha, url)
        }
    }

    private fun download(
        token: String,
        downloadUrl: String?,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
    ): File {
        val url = downloadUrl?.takeIf { it.isNotBlank() } ?: defaultDownloadUrl()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty().take(200)
                throw IllegalStateException("Download failed (HTTP ${resp.code}): $err")
            }
            val body = resp.body ?: throw IllegalStateException("Empty APK body")
            val total = body.contentLength()
            val dir = File(app.cacheDir, "apk").apply { mkdirs() }
            val outFile = File(dir, "grokify-wear-self-update.apk")
            if (outFile.exists()) outFile.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (total > 0) {
                            onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        } else {
                            onProgress(-1f)
                        }
                    }
                    output.flush()
                }
            }

            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = expectedSha256?.trim()?.lowercase().orEmpty()
            if (expected.isNotEmpty() && !expected.equals(sha, ignoreCase = true)) {
                outFile.delete()
                throw IllegalStateException("SHA-256 mismatch — retry")
            }
            if (written < 1024L) {
                outFile.delete()
                throw IllegalStateException("APK too small ($written bytes)")
            }
            onProgress(1f)
            return outFile
        }
    }

    private fun install(apkFile: File) {
        if (!apkFile.exists() || apkFile.length() < 1024L) {
            throw IllegalStateException("APK file missing")
        }
        if (!canInstallPackages()) {
            openInstallPermissionSettings()
            throw IllegalStateException(
                "Allow “Install unknown apps” for Grokify, then tap Update again",
            )
        }
        val authority = "${app.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(app, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }

    companion object {
        private const val TAG = "WearSelfUpdater"
        private const val CHANNEL_WEAR = "wear"
        private val API_BASE: String = BuildConfig.API_BASE.trimEnd('/')

        private fun defaultDownloadUrl(): String =
            "$API_BASE/apk-download.php?channel=$CHANNEL_WEAR"

        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")
    }
}
