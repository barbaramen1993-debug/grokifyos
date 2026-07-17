package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.MainActivity
import io.grokify.os.R
import io.grokify.os.widgets.WidgetArt
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared Spotify / Live DJ **live notification** (lockscreen + shade).
 *
 * Samsung (One UI) no longer surfaces third-party MediaStyle sessions as a lockscreen
 * player — media is forced into Now bar / AI boards. We intentionally do **not** use
 * MediaStyle + MediaSession on this card so SystemUI treats it as a normal ongoing
 * notification.
 *
 * Transport uses **custom RemoteViews** (compact + expanded) so prev/play/next icon
 * buttons are tappable **without expanding** the notification. Standard
 * [NotificationCompat.addAction] icons remain as a fallback on OEMs that still
 * surface system action rows.
 *
 * On Android 16+ we also request **Live Update** promotion
 * (`android.requestPromotedOngoing`) so the card pins at the top of the shade and
 * lock screen as a Live Notification chip.
 */
object SpotifyMediaNotif {
    private const val TAG = "SpotifyMediaNotif"
    private const val MAX_QUEUE_ROWS = 5
    /** Android 16 Live Update: same key as Notification.EXTRA_REQUEST_PROMOTED_ONGOING. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private val artPool = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastArtKickMs = AtomicLong(0L)
    /** Last track we kicked art for — force re-kick immediately on track change. */
    @Volatile private var lastArtKickUri: String = ""

    /**
     * Invoked on the main thread after album art finishes loading so services
     * can re-post the notification (first paint often has no art yet).
     */
    private val artReadyListeners = AtomicReference<((Context) -> Unit)?>(null)

    fun setArtReadyListener(listener: ((Context) -> Unit)?) {
        artReadyListeners.set(listener)
    }

    /**
     * @param album full-ish cover for RemoteViews / session (~320px)
     * @param largeIcon small square for Notification.setLargeIcon (~128px, binder-safe)
     * @param sessionArt larger cover for MediaSession lockscreen background (~400px)
     */
    data class ArtPair(
        val album: Bitmap?,
        val largeIcon: Bitmap?,
        val sessionArt: Bitmap? = null,
    )

    fun openSpotifyAppPi(context: Context, requestCode: Int = 47010): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            putExtra("open_app", "spotify_controller")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun transportPi(context: Context, action: String, req: Int): PendingIntent {
        val i = Intent(context, SpotifyControllerReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            req,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun queueJumpPi(
        context: Context,
        trackUri: String,
        queueIndex: Int,
        req: Int,
    ): PendingIntent {
        val i = Intent(context, SpotifyLiveDjService::class.java)
            .setAction(SpotifyLiveDjService.ACTION_DJ_PLAY_FROM_QUEUE)
            .putExtra(SpotifyLiveDjService.EXTRA_TRACK_URI, trackUri)
            .putExtra(SpotifyLiveDjService.EXTRA_QUEUE_INDEX, queueIndex)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, req, i, flags)
        } else {
            PendingIntent.getService(context, req, i, flags)
        }
    }

    /**
     * Quiet FGS placeholder — sinks under the fold (MIN channel).
     * Live DJ keep-alive while the controller owns the visible live controls card.
     */
    fun buildHidden(
        context: Context,
        title: String = "Spotify",
        status: String = "Standby",
    ): Notification {
        return NotificationCompat.Builder(context, GrokifyApp.CHANNEL_ASSISTANT)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(openSpotifyAppPi(context, 47011))
            .build()
    }

    /**
     * Full live notification with prev/play/next. Falls back to [buildMinimal]
     * if packaging fails. [sessionToken] is accepted for call-site compatibility
     * but is **not** attached — MediaStyle+session is swallowed into Samsung Now bar.
     */
    fun buildPlaying(
        context: Context,
        now: SpotifyNowPlaying,
        queue: List<DjQueueTrack> = emptyList(),
        subText: String = "GrokifyOS",
        art: ArtPair? = null,
        sessionToken: MediaSessionCompat.Token? = null,
    ): Notification {
        return try {
            buildPlayingInner(context, now, queue, subText, art)
        } catch (e: Exception) {
            Log.e(TAG, "buildPlaying failed, using minimal: ${e.message}", e)
            buildMinimal(context, now, subText)
        }
    }

    /**
     * Bare live card — never throws (used as FGS startForeground guarantee).
     * No custom views, no large bitmaps, no progress edge cases.
     */
    fun buildMinimal(
        context: Context,
        now: SpotifyNowPlaying = SpotifyNowPlaying(
            title = "Spotify Controller",
            artist = "Prev · Play · Next",
        ),
        subText: String = "GrokifyOS",
        sessionToken: MediaSessionCompat.Token? = null,
    ): Notification {
        val title = now.title.ifBlank { "Spotify Controller" }
        val text = now.artist.ifBlank { "Prev · Play · Next" }
        val openApp = openSpotifyAppPi(context)
        val emptyArt = ArtPair(album = null, largeIcon = null, sessionArt = null)
        val builder = applyLiveChrome(
            NotificationCompat.Builder(context, GrokifyApp.CHANNEL_SPOTIFY_CTRL)
                .setSmallIcon(R.drawable.notif_ic_play)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText),
            context,
            isPlaying = now.isPlaying,
        )
        // Always try icon RemoteViews so collapsed buttons work even on the FGS
        // placeholder path.
        runCatching {
            val compact = buildCompactViews(
                context = context,
                title = title,
                artist = text,
                timePart = null,
                art = emptyArt,
                openApp = openApp,
                isPlaying = now.isPlaying,
            )
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(compact)
                .setCustomBigContentView(
                    buildExpandedViews(
                        context = context,
                        title = title,
                        artist = text,
                        timePart = null,
                        art = emptyArt,
                        openApp = openApp,
                        queue = emptyList(),
                        isPlaying = now.isPlaying,
                    ),
                )
        }
        return builder.build()
    }

    /**
     * Shared chrome: ongoing + PUBLIC lockscreen + icon actions + Live Update request.
     * Deliberately **not** MediaStyle (Samsung routes that away from lockscreen notifs).
     * In-layout RemoteViews own the always-visible transport; [addAction] is fallback.
     */
    private fun applyLiveChrome(
        builder: NotificationCompat.Builder,
        context: Context,
        isPlaying: Boolean,
    ): NotificationCompat.Builder {
        val playIcon = if (isPlaying) R.drawable.notif_ic_pause else R.drawable.notif_ic_play
        val playLabel = if (isPlaying) "Pause" else "Play"
        builder
            // Icon-first actions (empty-ish labels preferred by some OEMs for icon row).
            .addAction(
                R.drawable.notif_ic_prev,
                "Previous",
                transportPi(context, ACTION_PREV, 47101),
            )
            .addAction(playIcon, playLabel, transportPi(context, ACTION_PLAY_PAUSE, 47102))
            .addAction(
                R.drawable.notif_ic_next,
                "Next",
                transportPi(context, ACTION_NEXT, 47103),
            )
            .setContentIntent(openSpotifyAppPi(context))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // SERVICE (not TRANSPORT): TRANSPORT + MediaStyle is what OEMs park in
            // the media player surface. SERVICE keeps us in the notification list /
            // Live Notification surfaces on Samsung lock screen.
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSortKey("!")
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColorized(false)
            .setColor(0xFF34D399.toInt())

        // Android 16 / Samsung Live Notifications: ask SystemUI to promote this ongoing
        // card to the top of shade + lock screen (and status-bar chip when supported).
        runCatching {
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        // Reflective setRequestPromotedOngoing if androidx eventually adds it.
        runCatching {
            val m = builder.javaClass.methods.firstOrNull {
                it.name == "setRequestPromotedOngoing" && it.parameterTypes.size == 1
            }
            m?.invoke(builder, true)
        }
        return builder
    }

    private fun buildPlayingInner(
        context: Context,
        now: SpotifyNowPlaying,
        queue: List<DjQueueTrack>,
        subText: String,
        art: ArtPair?,
    ): Notification {
        val resolvedArt = art ?: resolveArt(context, now, kickNetwork = true)
        val title = now.title.ifBlank { "Spotify" }
        val artist = now.artist
        val timePart = when {
            now.durationMs > 0L ->
                "${formatTrackTime(now.positionMs)} / ${formatTrackTime(now.durationMs)}"
            now.positionMs > 0L -> formatTrackTime(now.positionMs)
            else -> null
        }
        val queueHint = when {
            queue.isEmpty() -> null
            queue.size == 1 -> "Up next: ${queue[0].name.ifBlank { "1 track" }}"
            else -> "Up next: ${queue[0].name.ifBlank { "track" }} · +${queue.size - 1}"
        }
        val line2 = when {
            artist.isNotBlank() && timePart != null -> "$artist · $timePart"
            artist.isNotBlank() -> artist
            timePart != null -> timePart
            else -> now.appLabel.ifBlank { "Playing" }
        }
        val contentText = when {
            queueHint != null && artist.isNotBlank() -> "$artist · $queueHint"
            else -> line2
        }
        val whenMs = stableWhenForTrack(now)
        val openApp = openSpotifyAppPi(context)

        val builder = applyLiveChrome(
            NotificationCompat.Builder(context, GrokifyApp.CHANNEL_SPOTIFY_CTRL)
                .setSmallIcon(R.drawable.notif_ic_play)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(subText)
                .setWhen(whenMs)
                .setUsesChronometer(false),
            context,
            isPlaying = now.isPlaying,
        )

        // Custom RemoteViews: icon transport visible while collapsed (no expand needed).
        // DecoratedCustomViewStyle keeps system header alignment (app name / time).
        val compact = buildCompactViews(
            context = context,
            title = title,
            artist = artist.ifBlank { line2 },
            timePart = timePart,
            art = resolvedArt,
            openApp = openApp,
            isPlaying = now.isPlaying,
        )
        val expanded = buildExpandedViews(
            context = context,
            title = title,
            artist = artist.ifBlank { line2 },
            timePart = timePart,
            art = resolvedArt,
            openApp = openApp,
            queue = queue,
            isPlaying = now.isPlaying,
        )
        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            // Heads-up / ambient also use compact so buttons stay reachable.
            .setCustomHeadsUpContentView(compact)

        // Album art lives in the RemoteViews row; skip largeIcon so SystemUI
        // doesn't double-draw a right-side bitmap and throw off alignment.
        // Progress bar under custom views also misaligns on Samsung — omit it.

        return builder.build()
    }

    private fun buildCompactViews(
        context: Context,
        title: String,
        artist: String,
        timePart: String?,
        art: ArtPair,
        openApp: PendingIntent,
        isPlaying: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notif_spotify_compact)
        bindHeader(rv, title, artist, timePart = null, art, openApp)
        // Compact: hide time row for vertical space; artist line carries status.
        rv.setViewVisibility(R.id.notif_spotify_time, View.GONE)
        bindTransport(context, rv, isPlaying)
        return rv
    }

    /**
     * Ranking timestamp: stay near "now" so shade keeps us top-most, but bucket by
     * minute + track so progress-only rebuilds don't reshuffle the list.
     */
    private fun stableWhenForTrack(now: SpotifyNowPlaying): Long {
        val key = now.trackUri.ifBlank { now.title + "|" + now.artist }
        val minute = System.currentTimeMillis() / 60_000L
        if (key.isBlank()) return System.currentTimeMillis()
        val jitter = (key.hashCode().toLong() and 0x3ffL)
        return minute * 60_000L + (60_000L - jitter)
    }

    fun resolveArt(
        context: Context,
        now: SpotifyNowPlaying,
        kickNetwork: Boolean,
    ): ArtPair {
        WidgetArt.bindContext(context)
        val albumUrl = SpotifyArtMirror.preferredUrl(context, now.albumArtUrl)
            .ifBlank { now.albumArtUrl }
        // Prefer URL cache; fall back to media-session bitmap when the cache is cold
        // (first second after a skip often has a URL but no decoded pixels yet).
        var album = if (albumUrl.isNotBlank()) {
            WidgetArt.loadCachedOnly(albumUrl, maxEdge = 400)
                ?: SpotifyArtMirror.localFile(context, now.albumArtUrl)?.let { f ->
                    WidgetArt.loadCachedOnly("file://${f.absolutePath}", maxEdge = 400)
                        ?: runCatching {
                            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                        }.getOrNull()?.let { bmp ->
                            if (maxOf(bmp.width, bmp.height) > 400) {
                                val scale = 400f / maxOf(bmp.width, bmp.height)
                                Bitmap.createScaledBitmap(
                                    bmp,
                                    (bmp.width * scale).toInt().coerceAtLeast(1),
                                    (bmp.height * scale).toInt().coerceAtLeast(1),
                                    true,
                                )
                            } else {
                                bmp
                            }
                        }
                }
        } else {
            null
        }
        if (album == null) {
            album = readSessionAlbumArtBitmap(context, maxEdge = 400)
        }
        if (kickNetwork) {
            maybeKickArtLoad(context, now)
        }
        val large = squareCrop(album, 128)
        // Larger square for MediaSession — SystemUI uses this as lockscreen background.
        val session = squareCrop(album, 400)
        return ArtPair(album = album, largeIcon = large, sessionArt = session)
    }

    /**
     * Best bitmap for MediaSession lockscreen art (prefer large sessionArt, then album).
     * Keeps under ~1MB so OEMs don't drop the metadata.
     */
    fun bestSessionArt(art: ArtPair?): Bitmap? {
        val candidates = listOfNotNull(art?.sessionArt, art?.album, art?.largeIcon)
        for (bmp in candidates) {
            if (bmp.isRecycled) continue
            if (bmp.byteCount in 1..(1024 * 1024)) return bmp
        }
        return null
    }

    private fun maybeKickArtLoad(context: Context, now: SpotifyNowPlaying) {
        val uri = now.trackUri.ifBlank { now.title }
        val nowMs = SystemClock.elapsedRealtime()
        val prev = lastArtKickMs.get()
        val trackChanged = uri.isNotBlank() && uri != lastArtKickUri
        if (!trackChanged && nowMs - prev < 2_500L) return
        if (!lastArtKickMs.compareAndSet(prev, nowMs) && !trackChanged) return
        lastArtKickUri = uri
        val appCtx = context.applicationContext
        val albumUrl = now.albumArtUrl
        val trackUri = now.trackUri
        artPool.execute {
            var loaded = false
            runCatching {
                WidgetArt.bindContext(appCtx)
                SpotifyArtMirror.mirrorAllAsync(
                    appCtx,
                    listOf(albumUrl),
                    onAny = null,
                )
                if (albumUrl.isNotBlank()) {
                    val bmp = WidgetArt.loadSync(albumUrl, maxEdge = 400)
                    loaded = bmp != null
                }
                if (!loaded) {
                    readSessionAlbumArtBitmap(appCtx, maxEdge = 400)?.let { bmp ->
                        loaded = true
                        if (trackUri.isNotBlank()) {
                            SpotifyArtMirror.mirrorBitmap(
                                appCtx,
                                "session:$trackUri",
                                bmp,
                            )
                        }
                    }
                }
            }
            if (loaded) {
                mainHandler.post {
                    artReadyListeners.get()?.invoke(appCtx)
                }
            }
        }
    }

    private fun squareCrop(src: Bitmap?, size: Int): Bitmap? {
        val base = src ?: return null
        if (base.isRecycled) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale = maxOf(size.toFloat() / base.width, size.toFloat() / base.height)
        val sw = base.width * scale
        val sh = base.height * scale
        val left = (size - sw) / 2f
        val top = (size - sh) / 2f
        canvas.drawBitmap(
            base,
            Rect(0, 0, base.width, base.height),
            RectF(left, top, left + sw, top + sh),
            paint,
        )
        return out
    }

    private fun bindHeader(
        rv: RemoteViews,
        title: String,
        artist: String,
        timePart: String?,
        art: ArtPair,
        openApp: PendingIntent,
    ) {
        rv.setTextViewText(R.id.notif_spotify_title, title.ifBlank { "Spotify" })
        rv.setTextViewText(
            R.id.notif_spotify_artist_name,
            artist.ifBlank { " " },
        )
        if (!timePart.isNullOrBlank()) {
            rv.setViewVisibility(R.id.notif_spotify_time, View.VISIBLE)
            rv.setTextViewText(R.id.notif_spotify_time, timePart)
        } else {
            rv.setViewVisibility(R.id.notif_spotify_time, View.GONE)
        }
        if (art.album != null && !art.album.isRecycled) {
            rv.setImageViewBitmap(R.id.notif_spotify_album, art.album)
        } else {
            rv.setImageViewResource(R.id.notif_spotify_album, R.drawable.widget_ic_music)
        }
        listOf(
            R.id.notif_spotify_album,
            R.id.notif_spotify_meta,
            R.id.notif_spotify_title,
            R.id.notif_spotify_artist_name,
        ).forEach { id ->
            runCatching { rv.setOnClickPendingIntent(id, openApp) }
        }
    }

    private fun bindTransport(
        context: Context,
        rv: RemoteViews,
        isPlaying: Boolean,
    ) {
        val playIcon = if (isPlaying) R.drawable.notif_ic_pause else R.drawable.notif_ic_play
        // Explicit resources + color filters — theme attrs / layout tints often
        // fail inside notification RemoteViews (prev/next vanished; only mint play showed).
        rv.setImageViewResource(R.id.notif_spotify_btn_prev, R.drawable.notif_ic_prev)
        rv.setImageViewResource(R.id.notif_spotify_btn_play, playIcon)
        rv.setImageViewResource(R.id.notif_spotify_btn_next, R.drawable.notif_ic_next)
        rv.setViewVisibility(R.id.notif_spotify_btn_prev, View.VISIBLE)
        rv.setViewVisibility(R.id.notif_spotify_btn_play, View.VISIBLE)
        rv.setViewVisibility(R.id.notif_spotify_btn_next, View.VISIBLE)
        rv.setViewVisibility(R.id.notif_spotify_transport, View.VISIBLE)
        // White side controls on dark disc; mint center (readable on light + dark shade).
        runCatching {
            rv.setInt(R.id.notif_spotify_btn_prev, "setColorFilter", 0xFFFFFFFF.toInt())
            rv.setInt(R.id.notif_spotify_btn_play, "setColorFilter", 0xFF34D399.toInt())
            rv.setInt(R.id.notif_spotify_btn_next, "setColorFilter", 0xFFFFFFFF.toInt())
        }
        rv.setOnClickPendingIntent(
            R.id.notif_spotify_btn_prev,
            transportPi(context, ACTION_PREV, 47111),
        )
        rv.setOnClickPendingIntent(
            R.id.notif_spotify_btn_play,
            transportPi(context, ACTION_PLAY_PAUSE, 47112),
        )
        rv.setOnClickPendingIntent(
            R.id.notif_spotify_btn_next,
            transportPi(context, ACTION_NEXT, 47113),
        )
    }

    private fun buildExpandedViews(
        context: Context,
        title: String,
        artist: String,
        timePart: String?,
        art: ArtPair,
        openApp: PendingIntent,
        queue: List<DjQueueTrack>,
        isPlaying: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notif_spotify_expanded)
        bindHeader(rv, title, artist, timePart, art, openApp)
        bindTransport(context, rv, isPlaying)
        rv.removeAllViews(R.id.notif_spotify_queue)
        val rows = queue.take(MAX_QUEUE_ROWS)
        if (rows.isEmpty()) {
            rv.setViewVisibility(R.id.notif_spotify_queue_empty, View.VISIBLE)
            rv.setViewVisibility(R.id.notif_spotify_queue_label, View.VISIBLE)
            rv.setTextViewText(R.id.notif_spotify_queue_label, "UP NEXT")
        } else {
            rv.setViewVisibility(R.id.notif_spotify_queue_empty, View.GONE)
            rv.setViewVisibility(R.id.notif_spotify_queue_label, View.VISIBLE)
            val more = if (queue.size > rows.size) " · +${queue.size - rows.size}" else ""
            rv.setTextViewText(R.id.notif_spotify_queue_label, "UP NEXT (${queue.size})$more")
            rows.forEachIndexed { index, track ->
                val row = RemoteViews(context.packageName, R.layout.notif_spotify_queue_row)
                row.setTextViewText(
                    R.id.notif_queue_title,
                    track.name.ifBlank { "Track" },
                )
                row.setTextViewText(
                    R.id.notif_queue_artist,
                    track.artists.ifBlank { " " },
                )
                row.setTextViewText(R.id.notif_queue_index, "#${index + 1}")
                // Icon only — queue thumbs balloon binder size and can kill the post.
                row.setImageViewResource(R.id.notif_queue_thumb, R.drawable.widget_ic_music)
                val jump = queueJumpPi(
                    context,
                    trackUri = track.uri,
                    queueIndex = index,
                    req = 47200 + index,
                )
                row.setOnClickPendingIntent(R.id.notif_queue_row, jump)
                rv.addView(R.id.notif_spotify_queue, row)
            }
        }
        return rv
    }
}

/**
 * Sticky last-good now-playing. Spotify often ships album art as a session Bitmap
 * without a URL, and Web API enrich is rate-limited — without stickiness the Control
 * tab flashes art for one poll then goes blank.
 */
object SpotifyNowPlayingSticky {
    @Volatile private var last: SpotifyNowPlaying? = null
    @Volatile private var lastUri: String = ""

    fun clear() {
        last = null
        lastUri = ""
    }

    fun remember(snap: SpotifyNowPlaying): SpotifyNowPlaying {
        if (!snap.hasSession && snap.title.isBlank()) {
            return last?.takeIf {
                it.hasSession || it.albumArtUrl.isNotBlank() || it.title.isNotBlank()
            } ?: snap
        }
        val prev = last
        val sameTrack = when {
            snap.trackUri.isNotBlank() && prev?.trackUri == snap.trackUri -> true
            snap.trackUri.isBlank() && prev != null &&
                snap.title.isNotBlank() && snap.title == prev.title &&
                snap.artist == prev.artist -> true
            else -> false
        }
        val merged = if (prev != null && sameTrack) {
            snap.copy(
                title = snap.title.takeUnless { it.isBlank() || it == "Unknown track" }
                    ?: prev.title,
                artist = snap.artist.ifBlank { prev.artist },
                albumArtUrl = snap.albumArtUrl.ifBlank { prev.albumArtUrl },
                artistArtUrl = snap.artistArtUrl.ifBlank { prev.artistArtUrl },
                trackUri = snap.trackUri.ifBlank { prev.trackUri },
                albumUri = snap.albumUri.ifBlank { prev.albumUri },
                artistUri = snap.artistUri.ifBlank { prev.artistUri },
                durationMs = if (snap.durationMs > 0L) snap.durationMs else prev.durationMs,
                positionMs = if (snap.positionMs > 0L) snap.positionMs else prev.positionMs,
                hasSession = snap.hasSession || prev.hasSession,
                appLabel = snap.appLabel.ifBlank { prev.appLabel },
                packageName = snap.packageName.ifBlank { prev.packageName },
            )
        } else {
            // New track: still keep art if the new snap is missing it briefly
            // and we haven't confirmed a different URI yet.
            if (prev != null &&
                snap.albumArtUrl.isBlank() &&
                prev.albumArtUrl.isNotBlank() &&
                (
                    snap.trackUri.isBlank() ||
                        snap.trackUri == lastUri ||
                        (prev.trackUri.isNotBlank() && snap.trackUri == prev.trackUri)
                    )
            ) {
                snap.copy(
                    albumArtUrl = prev.albumArtUrl,
                    artistArtUrl = snap.artistArtUrl.ifBlank { prev.artistArtUrl },
                )
            } else {
                snap
            }
        }
        last = merged
        if (merged.trackUri.isNotBlank()) lastUri = merged.trackUri
        return merged
    }
}

/**
 * Snapshot for the media notification: media-session first, then Live DJ's known
 * now-playing when the session lags after a skip / queue jump. Art URLs stick
 * across blank polls.
 */
fun nowPlayingForNotification(context: Context): SpotifyNowPlaying {
    val session = readNowPlaying(context)
    val djNow = SpotifyDjBus.state.value.messages
        .lastOrNull { it.role == DjChatRole.Track && it.isNowPlaying }

    val merged = if (djNow == null) {
        session
    } else {
        val djUri = djNow.trackUri.orEmpty()
        val sessionUri = session.trackUri
        val djName = djNow.trackName.orEmpty()
        val djArtists = djNow.trackArtists.orEmpty()
        val djAlbum = djNow.albumArtUrl.orEmpty()
        val djArtistArt = djNow.artistArtUrl.orEmpty()

        val sameUri = djUri.isNotBlank() && sessionUri.isNotBlank() && djUri == sessionUri
        val sessionBlank = !session.hasSession ||
            (sessionUri.isBlank() && (session.title.isBlank() || session.title == "Unknown track"))
        val djAhead = djUri.isNotBlank() && sessionUri.isNotBlank() && djUri != sessionUri &&
            (djNow.isPlaying || djNow.isNowPlaying)
        if (!sameUri && !sessionBlank && !djAhead) {
            session
        } else {
            session.copy(
                title = when {
                    djName.isNotBlank() -> djName
                    session.title.isNotBlank() && session.title != "Unknown track" -> session.title
                    else -> djName.ifBlank { session.title }
                },
                artist = djArtists.ifBlank { session.artist },
                albumArtUrl = when {
                    djAhead && djAlbum.isNotBlank() -> djAlbum
                    sameUri -> session.albumArtUrl.ifBlank { djAlbum }
                    else -> djAlbum.ifBlank { session.albumArtUrl }
                },
                artistArtUrl = when {
                    djAhead && djArtistArt.isNotBlank() -> djArtistArt
                    sameUri -> session.artistArtUrl.ifBlank { djArtistArt }
                    else -> djArtistArt.ifBlank { session.artistArtUrl }
                },
                trackUri = when {
                    djAhead || sessionUri.isBlank() -> djUri.ifBlank { sessionUri }
                    else -> sessionUri.ifBlank { djUri }
                },
                positionMs = if (sameUri && session.positionMs > 0L) {
                    session.positionMs
                } else {
                    djNow.progressMs.takeIf { it > 0L } ?: session.positionMs
                },
                durationMs = if (sameUri && session.durationMs > 0L) {
                    session.durationMs
                } else {
                    djNow.durationMs.takeIf { it > 0L } ?: session.durationMs
                },
                isPlaying = when {
                    sameUri -> session.isPlaying || djNow.isPlaying
                    djAhead -> true
                    else -> session.isPlaying || djNow.isPlaying
                },
                hasSession = session.hasSession || djUri.isNotBlank() || djName.isNotBlank(),
            )
        }
    }
    return SpotifyNowPlayingSticky.remember(merged)
}
