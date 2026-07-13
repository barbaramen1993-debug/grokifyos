package io.grokify.os.apps.plugin

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * On-device cache for remote marketplace packages (WebView scripts).
 *
 * Layout: filesDir/plugins/{id}/index.html (+ optional assets)
 * Manifest cache: filesDir/plugins/catalog_cache.json
 */
class PluginPackageStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "plugins").also { it.mkdirs() }
    private val catalogCacheFile = File(root, "catalog_cache.json")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun packageDir(id: String): File = File(root, sanitizeId(id))

    fun entryHtml(id: String): File? {
        val index = File(packageDir(id), "index.html")
        return index.takeIf { it.isFile && it.canRead() }
    }

    fun isDownloaded(id: String): Boolean = entryHtml(id) != null

    fun uninstallPackage(id: String) {
        val dir = packageDir(id)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "removed package files id=$id")
        }
    }

    /**
     * Download package from [url] (Bearer [token]) into [id] directory.
     * Accepts zip (preferred) or raw HTML body.
     */
    fun downloadAndInstall(id: String, url: String, token: String?): File {
        val cleanId = sanitizeId(id)
        require(cleanId.isNotEmpty()) { "invalid plugin id" }
        require(url.isNotBlank()) { "missing package url" }

        val reqBuilder = Request.Builder().url(url).get()
        token?.takeIf { it.isNotBlank() }?.let {
            reqBuilder.header("Authorization", "Bearer $it")
        }
        val req = reqBuilder.build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("download failed HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("empty package body")
            val contentType = (resp.header("Content-Type") ?: "").lowercase()
            val format = (resp.header("X-Grokify-Package-Format") ?: "").lowercase()
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw IllegalStateException("empty package")

            val dir = packageDir(cleanId)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()

            val looksLikeZip = bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() &&
                bytes[1] == 0x4B.toByte()
            val isZip = format == "zip" ||
                contentType.contains("zip") ||
                looksLikeZip

            if (isZip) {
                unzipSafely(bytes, dir)
            } else {
                File(dir, "index.html").writeBytes(bytes)
            }

            val index = File(dir, "index.html")
            if (!index.isFile) {
                dir.deleteRecursively()
                throw IllegalStateException("package missing index.html")
            }
            Log.i(TAG, "installed package id=$cleanId bytes=${bytes.size} zip=$isZip")
            return index
        }
    }

    fun saveCatalogCache(plugins: List<PluginManifest>) {
        try {
            val arr = JSONArray()
            plugins.forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("title", p.title)
                        .put("subtitle", p.subtitle)
                        .put("version", p.version)
                        .put("author", p.author)
                        .put("source", p.source.name.lowercase())
                        .put("kind", p.kind.name.lowercase())
                        .put("host_module_id", p.hostModuleId)
                        .put("capabilities", JSONArray(p.capabilities))
                        .put("accent", p.accent.name.lowercase())
                        .put("icon", p.icon.name.lowercase())
                        .put("package_url", p.packageUrl)
                        .put("featured", p.featured),
                )
            }
            catalogCacheFile.writeText(
                JSONObject()
                    .put("saved_at", System.currentTimeMillis())
                    .put("plugins", arr)
                    .toString(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "catalog cache write: ${e.message}")
        }
    }

    fun loadCatalogCache(): List<PluginManifest> {
        return try {
            if (!catalogCacheFile.isFile) return emptyList()
            val root = JSONObject(catalogCacheFile.readText())
            val arr = root.optJSONArray("plugins") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    RemotePluginCatalog.parsePlugin(o)?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "catalog cache read: ${e.message}")
            emptyList()
        }
    }

    private fun unzipSafely(bytes: ByteArray, destDir: File) {
        val destPath = destDir.canonicalPath
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (name.isEmpty() || name.contains("..") || name.startsWith("/")) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val outFile = File(destDir, name)
                val canonical = outFile.canonicalPath
                if (!canonical.startsWith(destPath + File.separator) && canonical != destPath) {
                    throw IllegalStateException("zip slip blocked: $name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val TAG = "PluginPackageStore"

        fun sanitizeId(id: String): String =
            id.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64)
    }
}
