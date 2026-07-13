package io.grokify.os.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.grokify.os.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Download the latest Grokify APK (authenticated) and hand it to the system package installer.
 */
class ApkUpdater(
    private val context: Context,
    private val tokenProvider: () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class DownloadResult(
        val file: File,
        val sha256: String,
        val bytes: Long,
    )

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Opens system settings so the user can allow installs from this app. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Streams APK from [downloadUrl] (or default update endpoint) into cache/apk/.
     * [onProgress] is 0f..1f when Content-Length is known, else -1f for indeterminate.
     */
    fun download(
        downloadUrl: String? = null,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in — save your device token first")

        val url = downloadUrl?.takeIf { it.isNotBlank() }
            ?: (BuildConfig.API_BASE.trimEnd('/') + "/apk-download.php")

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty().take(200)
                throw IllegalStateException("Download failed (HTTP ${resp.code}): $body")
            }
            val body = resp.body ?: throw IllegalStateException("Empty response body")
            val total = body.contentLength()
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val outFile = File(dir, "grokify-update.apk")
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
                throw IllegalStateException("SHA-256 mismatch — download corrupted or tampered")
            }
            if (written < 1024L) {
                outFile.delete()
                throw IllegalStateException("Downloaded file too small ($written bytes)")
            }
            onProgress(1f)
            return DownloadResult(file = outFile, sha256 = sha, bytes = written)
        }
    }

    fun install(apkFile: File) {
        if (!apkFile.exists() || apkFile.length() < 1024L) {
            throw IllegalStateException("APK file missing")
        }
        if (!canInstallPackages()) {
            openInstallPermissionSettings()
            throw IllegalStateException(
                "Allow “Install unknown apps” for Grokify, then tap Install again",
            )
        }
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
