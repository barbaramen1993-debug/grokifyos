package io.grokify.os.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import io.grokify.os.apps.SpotifyArtMirror
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Cached download + circle-crop helpers for RemoteViews album/artist art.
 *
 * Prefer [loadCachedOnly] on the widget binder thread so list rows never
 * stall on network (that was the "Loading…" forever bug). Use [loadSync]
 * / [prefetch] only from background workers.
 */
object WidgetArt {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val circleCache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    @Volatile private var appContext: Context? = null

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    fun getCached(url: String): Bitmap? {
        if (url.isBlank()) return null
        return synchronized(cache) {
            cache.get("$url|mem") ?: cache.get(url)
        }
    }

    /**
     * Memory + local disk only — never hits the network.
     * Resolves Spotify CDN URLs to our host mirror / on-device file first.
     */
    fun loadCachedOnly(url: String, maxEdge: Int = 512): Bitmap? {
        if (url.isBlank()) return null
        val ctx = appContext
        val resolved = if (ctx != null) SpotifyArtMirror.preferredUrl(ctx, url) else url
        val key = "$resolved|$maxEdge"
        synchronized(cache) {
            cache.get(key)?.let { return it }
            cache.get(resolved)?.let { return it }
            cache.get(url)?.let { return it }
        }
        // Disk from art mirror (by original or resolved key).
        if (ctx != null) {
            val disk = SpotifyArtMirror.localFile(ctx, url)
                ?: SpotifyArtMirror.localFile(ctx, resolved)
            if (disk != null) {
                decodeFile(disk, maxEdge)?.let { bmp ->
                    put(key, resolved, bmp)
                    return bmp
                }
            }
        }
        // file:// already on device
        if (resolved.startsWith("file://", ignoreCase = true)) {
            val path = Uri.parse(resolved).path
            if (!path.isNullOrBlank()) {
                decodeFile(File(path), maxEdge)?.let { bmp ->
                    put(key, resolved, bmp)
                    return bmp
                }
            }
        }
        return null
    }

    fun loadSync(url: String, maxEdge: Int = 512): Bitmap? {
        if (url.isBlank()) return null
        loadCachedOnly(url, maxEdge)?.let { return it }
        val ctx = appContext
        val resolved = if (ctx != null) {
            // Mirror to host + local (idempotent). Prefer durable URL afterwards.
            runCatching { SpotifyArtMirror.mirror(ctx, url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: SpotifyArtMirror.preferredUrl(ctx, url)
        } else {
            url
        }
        loadCachedOnly(resolved, maxEdge)?.let { return it }
        loadCachedOnly(url, maxEdge)?.let { return it }

        val key = "$resolved|$maxEdge"
        val decoded = decode(resolved, maxEdge) ?: decode(url, maxEdge) ?: return null
        put(key, resolved, decoded)
        return decoded
    }

    /** Background fetch; [onDone] may run on a pool thread. */
    fun prefetch(url: String, maxEdge: Int = 512, onDone: ((Bitmap?) -> Unit)? = null) {
        if (url.isBlank()) {
            onDone?.invoke(null)
            return
        }
        loadCachedOnly(url, maxEdge)?.let {
            onDone?.invoke(it)
            return
        }
        pool.execute {
            onDone?.invoke(loadSync(url, maxEdge))
        }
    }

    fun loadAsync(url: String, maxEdge: Int = 512, onDone: (Bitmap?) -> Unit) {
        prefetch(url, maxEdge, onDone)
    }

    private fun put(key: String, url: String, bmp: Bitmap) {
        synchronized(cache) {
            cache.put(key, bmp)
            cache.put(url, bmp)
        }
    }

    private fun decode(url: String, maxEdge: Int): Bitmap? {
        return try {
            when {
                url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true) -> decodeHttp(url, maxEdge)
                url.startsWith("content://", ignoreCase = true) ||
                    url.startsWith("file://", ignoreCase = true) ||
                    url.startsWith("android.resource://", ignoreCase = true) -> decodeUri(url, maxEdge)
                url.startsWith("/") -> decodeFile(File(url), maxEdge)
                else -> decodeHttp(url, maxEdge)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeHttp(url: String, maxEdge: Int): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GrokifyOS-Widget/1.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { stream ->
                scaleToEdge(BitmapFactory.decodeStream(stream) ?: return null, maxEdge)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeUri(url: String, maxEdge: Int): Bitmap? {
        if (url.startsWith("file://", ignoreCase = true)) {
            val path = Uri.parse(url).path ?: return null
            return decodeFile(File(path), maxEdge)
        }
        val ctx = appContext ?: return null
        val uri = Uri.parse(url)
        return ctx.contentResolver.openInputStream(uri)?.use { stream ->
            scaleToEdge(BitmapFactory.decodeStream(stream) ?: return null, maxEdge)
        }
    }

    private fun decodeFile(file: File, maxEdge: Int): Bitmap? {
        if (!file.isFile || file.length() < 32L) return null
        return try {
            // Bounds first so we don't allocate huge bitmaps in the widget process.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            scaleToEdge(raw, maxEdge)
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleSizeFor(w: Int, h: Int, maxEdge: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        val edge = maxOf(w, h)
        while (edge / sample > maxEdge * 2) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun scaleToEdge(raw: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxEdge.coerceAtLeast(64)
        return if (raw.width > edge || raw.height > edge) {
            val scale = edge.toFloat() / maxOf(raw.width, raw.height).toFloat()
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, w, h, true).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }
    }

    fun circleCrop(src: Bitmap, size: Int = 128): Bitmap {
        val cacheKey = "${System.identityHashCode(src)}@$size"
        synchronized(circleCache) {
            circleCache.get(cacheKey)?.let { return it }
        }
        val edge = min(src.width, src.height)
        val squared = if (src.width == edge && src.height == edge) {
            src
        } else {
            val x = (src.width - edge) / 2
            val y = (src.height - edge) / 2
            Bitmap.createBitmap(src, x, y, edge, edge)
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(
            squared,
            Rect(0, 0, squared.width, squared.height),
            Rect(0, 0, size, size),
            paint,
        )
        if (squared !== src) squared.recycle()
        synchronized(circleCache) { circleCache.put(cacheKey, out) }
        return out
    }

    /**
     * Album art + dark scrim clipped to a rounded rect with **transparent** corners.
     * RemoteViews cannot clip children to parent outline, so square ImageViews
     * otherwise paint over the bubble's rounded shape (the "colored box" look).
     */
    fun roundedCover(
        src: Bitmap,
        outW: Int = 480,
        outH: Int = 220,
        radiusPx: Float = 28f,
        scrimColor: Int = Color.argb(0xC8, 0x05, 0x06, 0x0A),
    ): Bitmap {
        val cacheKey = "rr:${System.identityHashCode(src)}:${outW}x${outH}:$radiusPx:$scrimColor"
        synchronized(circleCache) {
            circleCache.get(cacheKey)?.let { return it }
        }
        val w = outW.coerceAtLeast(64)
        val h = outH.coerceAtLeast(64)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dest = RectF(0f, 0f, w.toFloat(), h.toFloat())

        // Destination-in clip: draw round mask, then album art into it.
        canvas.drawRoundRect(dest, radiusPx, radiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        // Center-crop source into dest.
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = src.width * scale
        val sh = src.height * scale
        val left = (w - sw) / 2f
        val top = (h - sh) / 2f
        canvas.drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            RectF(left, top, left + sw, top + sh),
            paint,
        )
        paint.xfermode = null
        paint.color = scrimColor
        canvas.drawRoundRect(dest, radiusPx, radiusPx, paint)

        synchronized(circleCache) { circleCache.put(cacheKey, out) }
        return out
    }
}
