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
 * Download a published Grokify APK (authenticated) by release channel.
 *
 * - [CHANNEL_PHONE] — host self-update (system package installer)
 * - [CHANNEL_WEAR] — watch OTA hop (phone caches APK for wireless adb install)
 *
 * Cache files are channel-separated so phone and wear downloads never clobber each other.
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
        val channel: String = CHANNEL_PHONE,
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
     * Streams APK from [downloadUrl] (or channel default update endpoint) into cache/apk/.
     * [onProgress] is 0f..1f when Content-Length is known, else -1f for indeterminate.
     *
     * @param channel `phone` (default) or `wear` — selects cache filename and default URL
     */
    fun download(
        downloadUrl: String? = null,
        expectedSha256: String? = null,
        channel: String = CHANNEL_PHONE,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Not signed in — save your device token first")

        val ch = normalizeChannel(channel)
        val url = downloadUrl?.takeIf { it.isNotBlank() }
            ?: defaultDownloadUrl(ch)

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
            val outFile = File(dir, cacheFileName(ch))
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
            return DownloadResult(
                file = outFile,
                sha256 = sha,
                bytes = written,
                channel = ch,
            )
        }
    }

    /** Cached APK for [channel] if still on disk from a prior download. */
    fun cachedApk(channel: String = CHANNEL_PHONE): File? {
        val f = File(File(context.cacheDir, "apk"), cacheFileName(normalizeChannel(channel)))
        return f.takeIf { it.isFile && it.length() >= 1024L }
    }

    /**
     * Install APK on **this** device via the system package installer.
     * Wear channel APKs should be pushed to the watch via ADB instead — do not call this for wear.
     */
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

    companion object {
        const val CHANNEL_PHONE = "phone"
        const val CHANNEL_WEAR = "wear"

        fun normalizeChannel(channel: String?): String {
            val c = channel?.trim()?.lowercase().orEmpty()
            return if (c == CHANNEL_WEAR) CHANNEL_WEAR else CHANNEL_PHONE
        }

        /** Phone keeps legacy filename; wear (and future channels) are isolated. */
        fun cacheFileName(channel: String): String {
            return when (normalizeChannel(channel)) {
                CHANNEL_WEAR -> "grokify-wear-update.apk"
                else -> "grokify-update.apk"
            }
        }

        fun defaultDownloadUrl(channel: String = CHANNEL_PHONE): String {
            val ch = normalizeChannel(channel)
            return BuildConfig.API_BASE.trimEnd('/') +
                "/apk-download.php?channel=" +
                java.net.URLEncoder.encode(ch, "UTF-8")
        }
    }
}
