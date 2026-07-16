package io.grokify.os.apps

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.grokify.os.BuildConfig
import io.grokify.os.GrokifyApp
import io.grokify.os.data.GrokifyApi
import io.grokify.os.data.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable album/artist art:
 * 1. Local disk cache (device) so widgets never block on Spotify CDN
 * 2. Host [media-cache.php] so we re-serve from grokifyos instead of i.scdn.co
 *
 * Call [mirror] / [mirrorAsync] whenever we learn a Spotify CDN URL. Prefer
 * [preferredUrl] when binding UI so we hit our server after the first fetch.
 */
object SpotifyArtMirror {
    private const val TAG = "SpotifyArtMirror"
    private const val PREFS = "spotify_art_mirror"
    private const val KEY_PREFIX = "srv:" // sourceUrl hash → server public URL

    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    /** sourceUrl → durable public URL (memory). */
    private val memory = ConcurrentHashMap<String, String>()
    private val hydrated = AtomicBoolean(false)

    fun isOurUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return u.contains("/media-cache.php") ||
            u.contains("grokifyos.") && u.contains("media-cache")
    }

    fun isSpotifyCdn(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return u.contains("scdn.co") ||
            u.contains("spotifycdn.com") ||
            u.contains("i.scdn.co")
    }

    /** Best URL to load: already-mirrored host URL, else original. */
    fun preferredUrl(context: Context, sourceUrl: String?): String {
        val src = sourceUrl?.trim().orEmpty()
        if (src.isBlank()) return ""
        if (isOurUrl(src)) return src
        memory[src]?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + sha1(src)
        val stored = prefs.getString(key, null)
        if (!stored.isNullOrBlank()) {
            memory[src] = stored
            return stored
        }
        return src
    }

    fun localFile(context: Context, sourceUrl: String?): File? {
        val src = sourceUrl?.trim().orEmpty()
        if (src.isBlank()) return null
        val f = fileFor(context, src)
        return if (f.isFile && f.length() > 32L) f else null
    }

    /**
     * Ensure [sourceUrl] is on disk and (when signed in) on the host.
     * Safe to call from a background thread. Returns durable URL when known.
     */
    fun mirror(context: Context, sourceUrl: String?): String? {
        val src = sourceUrl?.trim().orEmpty()
        if (src.isBlank()) return null
        if (isOurUrl(src)) return src
        memory[src]?.let { return it }

        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mapKey = KEY_PREFIX + sha1(src)
        prefs.getString(mapKey, null)?.takeIf { it.isNotBlank() }?.let {
            memory[src] = it
            // Still ensure local disk for offline/widget speed.
            ensureLocalFile(appCtx, src, it)
            return it
        }

        if (inFlight.putIfAbsent(src, true) != null) {
            // Another worker is on it — return local/original.
            return localFile(appCtx, src)?.toURI()?.toString() ?: src
        }
        return try {
            // 1) Local bytes (download from CDN once, or from existing preferred).
            val local = ensureLocalFile(appCtx, src, null) ?: return src

            // 2) Push to host so future clients / widgets use our origin.
            val token = deviceToken(appCtx)
            if (token.isNullOrBlank()) {
                Log.d(TAG, "no device token — local only for ${src.take(48)}")
                return "file://${local.absolutePath}"
            }
            val api = GrokifyApi { token }
            val res = try {
                // Prefer server-side fetch (one VPS hit to Spotify, de-duped by URL).
                api.cacheMediaFromUrl(src)
            } catch (e: Exception) {
                Log.w(TAG, "cacheMediaFromUrl: ${e.message}")
                // Fallback: upload local bytes.
                try {
                    api.cacheMediaBytes(local.readBytes(), guessMime(local), sourceKey = src)
                } catch (e2: Exception) {
                    Log.w(TAG, "cacheMediaBytes: ${e2.message}")
                    null
                }
            }
            val durable = res?.optString("url", "")?.trim().orEmpty()
            if (res?.optBoolean("ok", false) == true && durable.isNotBlank()) {
                prefs.edit().putString(mapKey, durable).apply()
                memory[src] = durable
                // Keep a local copy named after the durable URL too.
                ensureLocalFile(appCtx, durable, durable)
                Log.d(TAG, "mirrored ok cached=${res.optBoolean("cached")} → ${durable.take(80)}")
                durable
            } else {
                Log.w(TAG, "mirror failed: ${res?.optString("error")}")
                "file://${local.absolutePath}"
            }
        } finally {
            inFlight.remove(src)
        }
    }

    fun mirrorAsync(context: Context, sourceUrl: String?, onDone: ((String?) -> Unit)? = null) {
        val src = sourceUrl?.trim().orEmpty()
        if (src.isBlank()) {
            onDone?.invoke(null)
            return
        }
        if (isOurUrl(src)) {
            onDone?.invoke(src)
            return
        }
        memory[src]?.let {
            onDone?.invoke(it)
            return
        }
        pool.execute {
            val out = runCatching { mirror(context, src) }.getOrNull()
            onDone?.invoke(out)
        }
    }

    /** Mirror several URLs (album + artist) without blocking the caller. */
    fun mirrorAllAsync(context: Context, urls: Collection<String?>, onAny: (() -> Unit)? = null) {
        val list = urls.mapNotNull { it?.trim()?.takeIf { u -> u.isNotBlank() } }.distinct()
        if (list.isEmpty()) return
        pool.execute {
            var changed = false
            for (u in list) {
                val before = memory[u]
                val after = runCatching { mirror(context, u) }.getOrNull()
                if (!after.isNullOrBlank() && after != before && isOurUrl(after)) changed = true
            }
            if (changed) onAny?.invoke()
        }
    }

    /**
     * Upload a media-session bitmap when Spotify did not expose a CDN URL.
     * [sourceKey] should be stable (e.g. trackUri) so we de-dupe.
     */
    fun mirrorBitmap(context: Context, sourceKey: String, bitmap: Bitmap): String? {
        val key = sourceKey.trim().ifBlank { return null }
        memory[key]?.let { return it }
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mapKey = KEY_PREFIX + sha1(key)
        prefs.getString(mapKey, null)?.takeIf { it.isNotBlank() }?.let {
            memory[key] = it
            return it
        }
        val local = fileFor(appCtx, key)
        if (!local.isFile || local.length() < 32L) {
            local.parentFile?.mkdirs()
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, baos)
            local.writeBytes(baos.toByteArray())
        }
        val token = deviceToken(appCtx) ?: return "file://${local.absolutePath}"
        return try {
            val api = GrokifyApi { token }
            val res = api.cacheMediaBytes(local.readBytes(), "image/jpeg", sourceKey = key)
            val durable = res.optString("url", "").trim()
            if (res.optBoolean("ok", false) && durable.isNotBlank()) {
                prefs.edit().putString(mapKey, durable).apply()
                memory[key] = durable
                durable
            } else {
                "file://${local.absolutePath}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "mirrorBitmap: ${e.message}")
            "file://${local.absolutePath}"
        }
    }

    fun mirrorBitmapAsync(
        context: Context,
        sourceKey: String,
        bitmap: Bitmap,
        onDone: ((String?) -> Unit)? = null,
    ) {
        pool.execute {
            val out = runCatching { mirrorBitmap(context, sourceKey, bitmap) }.getOrNull()
            onDone?.invoke(out)
        }
    }

    /**
     * Rewrite scdn URLs on chat messages to durable host URLs when we already
     * mirrored them. Does not network — only applies known mappings.
     */
    fun rewriteMessages(context: Context, msgs: List<DjChatMessage>): List<DjChatMessage> {
        var any = false
        val out = msgs.map { m ->
            if (m.role != DjChatRole.Track) return@map m
            val album = preferredUrl(context, m.albumArtUrl)
            val artist = preferredUrl(context, m.artistArtUrl)
            val albumChanged = album.isNotBlank() && album != m.albumArtUrl
            val artistChanged = artist.isNotBlank() && artist != m.artistArtUrl
            if (!albumChanged && !artistChanged) return@map m
            any = true
            m.copy(
                albumArtUrl = album.ifBlank { m.albumArtUrl },
                artistArtUrl = artist.ifBlank { m.artistArtUrl },
            )
        }
        return if (any) out else msgs
    }

    private fun ensureLocalFile(context: Context, sourceUrl: String, preferFetchUrl: String?): File? {
        val dest = fileFor(context, sourceUrl)
        if (dest.isFile && dest.length() > 32L) return dest
        // Prefer durable/host URL when downloading to avoid Spotify rate limits.
        val fetch = when {
            !preferFetchUrl.isNullOrBlank() && isOurUrl(preferFetchUrl) -> preferFetchUrl
            isOurUrl(sourceUrl) -> sourceUrl
            memory[sourceUrl]?.let { isOurUrl(it) } == true -> memory[sourceUrl]!!
            else -> sourceUrl
        }
        return try {
            dest.parentFile?.mkdirs()
            val conn = (URL(fetch).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "GrokifyOS-ArtMirror/1.0")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (dest.length() > 32L) dest else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "local fetch ${fetch.take(60)}: ${e.message}")
            null
        }
    }

    private fun fileFor(context: Context, key: String): File {
        val dir = File(context.applicationContext.cacheDir, "spotify-art")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, sha1(key) + ".img")
    }

    private fun guessMime(file: File): String {
        val bytes = file.inputStream().use { it.readNBytes(12) }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
            return "image/png"
        }
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte()) {
            return "image/webp"
        }
        return "image/jpeg"
    }

    private fun deviceToken(context: Context): String? {
        return try {
            val app = context.applicationContext
            val store = if (app is GrokifyApp) app.tokenStore else TokenStore(app)
            runBlocking { store.tokenFlow.first()?.trim()?.takeIf { it.isNotEmpty() } }
        } catch (e: Exception) {
            Log.w(TAG, "token: ${e.message}")
            null
        }
    }

    private fun sha1(s: String): String {
        val dig = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }

    /** Absolute public media-cache base (debug aid). */
    fun siteMediaBase(): String = BuildConfig.SITE_URL.trimEnd('/') + "/api/media-cache.php"
}
