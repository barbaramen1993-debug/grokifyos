package io.grokify.os.apps

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import io.grokify.os.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val AA_TAG = "SpotifyAndroidAuto"

/** Browse root for Android Auto / MediaBrowser clients. */
private const val MEDIA_ROOT_ID = "grokify_live_dj_root"

/** Media id prefixes — distinguish playable history vs info-only banter. */
private const val ID_TRACK = "track:"
private const val ID_BANTER = "banter:"
private const val ID_NOW = "now"
private const val ID_IDLE = "idle"

/** Cap mixed history+banter rows in the AA queue (newest first). */
private const val MAX_AA_QUEUE_ITEMS = 40

/**
 * Process-wide Android Auto session state.
 *
 * Discovery rules (critical for the car media list):
 * - When the master switch is **on**, always accept [MediaBrowserServiceCompat.onGetRoot].
 *   Android Auto enumerates sources by binding; returning null hides the app entirely.
 * - Live DJ on air → full booth (art, transport, banter/history queue).
 * - Live DJ off, new connect → idle “Start Live DJ on phone”.
 * - Live DJ ends while car still connected → thin Spotify mirror until disconnect.
 * - Master switch off → reject new roots (active car session keeps serving until unbind).
 *
 * Sideloaded / debug APKs also need Android Auto **Unknown sources** (developer mode).
 */
object SpotifyAndroidAuto {
    /**
     * True while a MediaBrowser client (Android Auto) has been accepted and the
     * browser service has not yet fully unbound. Mid-session master-switch off is
     * ignored until this clears.
     */
    @Volatile
    var activeSession: Boolean = false
        private set

    /**
     * After Live DJ stops while a car browser is connected, serve a thin Spotify
     * mirror until unbind. Cleared on unbind or next Live DJ start.
     */
    @Volatile
    var handOffMirror: Boolean = false
        private set

    fun markBrowserAccepted() {
        activeSession = true
    }

    fun markBrowserUnbound(context: Context) {
        if (!activeSession && !handOffMirror) return
        activeSession = false
        handOffMirror = false
        Log.i(AA_TAG, "browser unbound · session cleared")
        notifyRefresh(context.applicationContext)
    }

    fun isMasterEnabled(context: Context): Boolean =
        SpotifyControllerStore(context.applicationContext).androidAutoEnabled

    /**
     * Whether we should accept a MediaBrowser root (appear / stay in the car media list).
     * Master switch on is enough — do **not** require Live DJ for discovery.
     */
    fun canAcceptBrowser(context: Context): Boolean {
        if (activeSession) return true
        return isMasterEnabled(context)
    }

    @Deprecated("Use canAcceptBrowser", ReplaceWith("canAcceptBrowser(context)"))
    fun canServe(context: Context): Boolean = canAcceptBrowser(context)

    /** Full booth UI (banter + history + DJ transport). */
    fun isBoothMode(context: Context): Boolean =
        SpotifyDjStore(context.applicationContext).enabled

    /** Thin Spotify mirror after DJ stopped mid-drive. */
    fun isHandOffMode(context: Context): Boolean =
        !isBoothMode(context) && handOffMirror && activeSession

    /** Setting on, DJ off, not mid-drive hand-off — listed but idle. */
    fun isIdleMode(context: Context): Boolean =
        canAcceptBrowser(context) && !isBoothMode(context) && !isHandOffMode(context)

    /** Live DJ just went on air — poke the browser service so AA can connect / refresh. */
    fun onLiveDjStarted(context: Context) {
        val appCtx = context.applicationContext
        handOffMirror = false
        if (!isMasterEnabled(appCtx) && !activeSession) {
            Log.d(AA_TAG, "Live DJ started · Android Auto master switch off")
            return
        }
        Log.i(AA_TAG, "Live DJ started · arm Android Auto media browser")
        ensureService(appCtx)
        notifyRefresh(appCtx)
    }

    /**
     * Live DJ stopped. If the car is still connected, keep serving as a thin
     * Spotify mirror until disconnect; otherwise drop to idle (if setting on).
     */
    fun onLiveDjStopped(context: Context) {
        val appCtx = context.applicationContext
        if (activeSession) {
            handOffMirror = true
            Log.i(AA_TAG, "Live DJ stopped · hand off to Spotify mirror until car disconnect")
            ensureService(appCtx)
            notifyRefresh(appCtx)
        } else {
            handOffMirror = false
            Log.d(AA_TAG, "Live DJ stopped · no active car session")
            notifyRefresh(appCtx)
        }
    }

    /** Master switch flipped — mid-session car keeps working; next connect re-evaluates. */
    fun onSettingChanged(context: Context) {
        val appCtx = context.applicationContext
        val on = isMasterEnabled(appCtx)
        Log.i(AA_TAG, "Android Auto setting → $on (activeSession=$activeSession)")
        if (on) {
            // Always arm the browser when the switch is on so AA can discover us
            // even before Live DJ starts (idle root).
            ensureService(appCtx)
        }
        notifyRefresh(appCtx)
    }

    /** Chat / now-playing / queue changed — push metadata + children to AA. */
    fun onBoothStateChanged(context: Context) {
        if (!canAcceptBrowser(context) && !activeSession) return
        notifyRefresh(context.applicationContext)
    }

    private fun ensureService(context: Context) {
        val i = Intent(context, SpotifyAndroidAutoService::class.java)
            .setAction(SpotifyAndroidAutoService.ACTION_REFRESH)
        runCatching {
            // Not a long-running FGS — start is enough for MediaBrowser binds from AA.
            context.startService(i)
        }.onFailure {
            Log.w(AA_TAG, "ensureService: ${it.message}")
        }
    }

    private fun notifyRefresh(context: Context) {
        val i = Intent(context, SpotifyAndroidAutoService::class.java)
            .setAction(SpotifyAndroidAutoService.ACTION_REFRESH)
        runCatching { context.startService(i) }
            .onFailure { Log.w(AA_TAG, "notifyRefresh: ${it.message}") }
    }
}

/**
 * Android Auto / media-browser surface for **Grokify Live DJ**.
 *
 * Stock AA music template: album art, title/artist, progress, prev / play-pause / next.
 * Queue / browse list is a mixed newest-first history of previous tracks + banter lines.
 * Transport routes through Live DJ when the booth is on; after hand-off it mirrors Spotify.
 */
class SpotifyAndroidAutoService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: MediaSessionCompat? = null
    private var busJob: Job? = null
    private var pollJob: Job? = null
    private val transportBusy = AtomicBoolean(false)
    @Volatile private var lastMetaSig: String = ""
    @Volatile private var lastQueueSig: String = ""

    override fun onCreate() {
        super.onCreate()
        val s = MediaSessionCompat(this, "GrokifyLiveDjAA").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS,
            )
            setCallback(SessionCallback(), mainHandler)
            val openApp = PendingIntent.getActivity(
                this@SpotifyAndroidAutoService,
                47300,
                Intent(this@SpotifyAndroidAutoService, MainActivity::class.java).apply {
                    putExtra("open_app", "spotify_controller")
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setSessionActivity(openApp)
            setQueueTitle(SOURCE_NAME)
            isActive = true
        }
        session = s
        sessionToken = s.sessionToken
        busJob = scope.launch {
            SpotifyDjBus.state.collectLatest {
                pushSession(force = false)
                notifyChildrenChanged(MEDIA_ROOT_ID)
            }
        }
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (SpotifyAndroidAuto.activeSession || SpotifyAndroidAuto.canAcceptBrowser(this@SpotifyAndroidAutoService)) {
                    pushSession(force = false)
                }
                delay(2_000L)
            }
        }
        pushSession(force = true)
        Log.i(AA_TAG, "MediaBrowserService created · master=${SpotifyAndroidAuto.isMasterEnabled(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            pushSession(force = true)
            notifyChildrenChanged(MEDIA_ROOT_ID)
        }
        // No sticky FGS — AA binds the browser service as needed.
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SpotifyAndroidAuto.markBrowserUnbound(this)
        Log.i(AA_TAG, "MediaBrowser unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        busJob?.cancel()
        pollJob?.cancel()
        scope.cancel()
        SpotifyAndroidAuto.markBrowserUnbound(this)
        runCatching {
            session?.apply {
                isActive = false
                setCallback(null)
                release()
            }
        }
        session = null
        lastMetaSig = ""
        lastQueueSig = ""
        Log.i(AA_TAG, "MediaBrowserService destroyed")
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        // Always log — needed to debug car discovery (gearhead / manufacturer HU).
        Log.i(
            AA_TAG,
            "onGetRoot package=$clientPackageName uid=$clientUid " +
                "master=${SpotifyAndroidAuto.isMasterEnabled(this)} " +
                "booth=${SpotifyAndroidAuto.isBoothMode(this)} " +
                "active=${SpotifyAndroidAuto.activeSession}",
        )
        if (!SpotifyAndroidAuto.canAcceptBrowser(this)) {
            Log.w(AA_TAG, "onGetRoot DENY — turn on Settings → Spotify → Android Auto")
            return null
        }
        SpotifyAndroidAuto.markBrowserAccepted()
        val extras = Bundle().apply {
            putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, false)
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        Log.i(
            AA_TAG,
            "onGetRoot OK · booth=${SpotifyAndroidAuto.isBoothMode(this)} " +
                "handoff=${SpotifyAndroidAuto.isHandOffMode(this)}",
        )
        return BrowserRoot(MEDIA_ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        if (parentId != MEDIA_ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        if (!SpotifyAndroidAuto.canAcceptBrowser(this)) {
            result.sendResult(mutableListOf())
            return
        }
        result.detach()
        scope.launch(Dispatchers.IO) {
            val items = buildBrowseItems()
            result.sendResult(items)
        }
    }

    private fun buildBrowseItems(): MutableList<MediaBrowserCompat.MediaItem> {
        val out = ArrayList<MediaBrowserCompat.MediaItem>(MAX_AA_QUEUE_ITEMS)
        when {
            SpotifyAndroidAuto.isBoothMode(this) -> fillBoothBrowse(out)
            SpotifyAndroidAuto.isHandOffMode(this) -> fillHandOffBrowse(out)
            else -> fillIdleBrowse(out)
        }
        return out
    }

    private fun fillIdleBrowse(out: MutableList<MediaBrowserCompat.MediaItem>) {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(ID_IDLE)
            .setTitle("Grokify Live DJ")
            .setSubtitle("Start Live DJ on your phone")
            .setDescription("Android Auto is ready · start the booth to play")
            .build()
        out.add(
            MediaBrowserCompat.MediaItem(
                desc,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            ),
        )
    }

    private fun fillHandOffBrowse(out: MutableList<MediaBrowserCompat.MediaItem>) {
        val now = runCatching { nowPlayingForNotification(this) }.getOrNull()
        val title = now?.title?.takeIf { it.isNotBlank() && it != "Unknown track" } ?: "Spotify"
        val artist = now?.artist.orEmpty().ifBlank { "Live DJ off · mirroring Spotify" }
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(ID_NOW)
            .setTitle(title)
            .setSubtitle(artist)
            .setDescription("Thin Spotify mirror until car disconnects")
            .build()
        out.add(
            MediaBrowserCompat.MediaItem(
                desc,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            ),
        )
    }

    private fun fillBoothBrowse(out: MutableList<MediaBrowserCompat.MediaItem>) {
        val dj = SpotifyDjBus.state.value
        val currentUri = dj.messages
            .lastOrNull { it.role == DjChatRole.Track && it.isNowPlaying }
            ?.trackUri
            .orEmpty()
            .ifBlank {
                runCatching { nowPlayingForNotification(this).trackUri }.getOrNull().orEmpty()
            }
        // Newest first: reverse chronological chat, skip pure system noise.
        val mixed = dj.messages.asReversed().asSequence()
            .filter { m ->
                when (m.role) {
                    DjChatRole.Track ->
                        !m.trackUri.isNullOrBlank() &&
                            m.trackUri != currentUri &&
                            !m.isNowPlaying
                    DjChatRole.Dj ->
                        m.text.isNotBlank() && !m.streaming
                    else -> false
                }
            }
            .take(MAX_AA_QUEUE_ITEMS)
            .toList()
        for (m in mixed) {
            when (m.role) {
                DjChatRole.Track -> {
                    val uri = m.trackUri.orEmpty()
                    val name = m.trackName?.ifBlank { null } ?: m.text.ifBlank { "Track" }
                    val artists = m.trackArtists.orEmpty()
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(ID_TRACK + m.id)
                        .setTitle(name)
                        .setSubtitle(artists.ifBlank { "Previous · tap to play now" })
                        .setDescription(uri)
                        .apply {
                            if (!m.albumArtUrl.isNullOrBlank()) {
                                setIconUri(android.net.Uri.parse(m.albumArtUrl))
                            }
                        }
                        .build()
                    out.add(
                        MediaBrowserCompat.MediaItem(
                            desc,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                        ),
                    )
                }
                DjChatRole.Dj -> {
                    val line = m.text.trim().replace('\n', ' ')
                    if (line.isBlank()) continue
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(ID_BANTER + m.id)
                        .setTitle("DJ")
                        .setSubtitle(line.take(180))
                        .setDescription(line)
                        .build()
                    // Playable flag so AA lists the row; onPlayFromMediaId is a no-op.
                    out.add(
                        MediaBrowserCompat.MediaItem(
                            desc,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    private fun pushSession(force: Boolean) {
        val s = session ?: return
        val booth = SpotifyAndroidAuto.isBoothMode(this)
        val handoff = SpotifyAndroidAuto.isHandOffMode(this)
        val eligible = SpotifyAndroidAuto.canAcceptBrowser(this)
        if (!eligible) {
            // Keep session token valid but mark stopped so we don't look "live" when denied.
            if (s.isActive) {
                runCatching {
                    s.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setActions(0)
                            .setState(
                                PlaybackStateCompat.STATE_NONE,
                                0L,
                                0f,
                                SystemClock.elapsedRealtime(),
                            )
                            .build(),
                    )
                }
            }
            lastMetaSig = ""
            return
        }
        if (!s.isActive) s.isActive = true

        val now = runCatching { nowPlayingForNotification(this) }.getOrNull()
            ?: SpotifyNowPlaying()
        val dj = SpotifyDjBus.state.value
        val curTrack = dj.messages.lastOrNull { it.role == DjChatRole.Track && it.isNowPlaying }

        val title: String
        val artist: String
        val uri: String
        val playing: Boolean
        val pos: Long
        val dur: Long
        val artUrl: String
        val description: String

        when {
            booth -> {
                title = when {
                    now.title.isNotBlank() && now.title != "Unknown track" -> now.title
                    !curTrack?.trackName.isNullOrBlank() -> curTrack!!.trackName!!
                    else -> "Grokify Live DJ"
                }
                artist = when {
                    now.artist.isNotBlank() -> now.artist
                    !curTrack?.trackArtists.isNullOrBlank() -> curTrack!!.trackArtists!!
                    else -> "Live DJ"
                }
                uri = when {
                    now.trackUri.isNotBlank() -> now.trackUri
                    !curTrack?.trackUri.isNullOrBlank() -> curTrack!!.trackUri!!
                    else -> ""
                }
                playing = now.isPlaying || (curTrack?.isPlaying == true)
                pos = (now.positionMs.takeIf { it > 0 } ?: curTrack?.progressMs ?: 0L).coerceAtLeast(0L)
                dur = (now.durationMs.takeIf { it > 0 } ?: curTrack?.durationMs ?: 0L).coerceAtLeast(0L)
                artUrl = now.albumArtUrl.ifBlank { curTrack?.albumArtUrl.orEmpty() }
                description = dj.messages.asReversed().firstOrNull {
                    it.role == DjChatRole.Dj && it.text.isNotBlank() && !it.streaming
                }?.text?.trim()?.replace('\n', ' ')?.take(120).orEmpty()
                    .ifBlank { SOURCE_NAME }
            }
            handoff -> {
                title = when {
                    now.title.isNotBlank() && now.title != "Unknown track" -> now.title
                    else -> "Spotify"
                }
                artist = now.artist.ifBlank { "Live DJ off · mirror" }
                uri = now.trackUri
                playing = now.isPlaying
                pos = now.positionMs.coerceAtLeast(0L)
                dur = now.durationMs.coerceAtLeast(0L)
                artUrl = now.albumArtUrl
                description = "Spotify mirror until car disconnects"
            }
            else -> {
                // Idle: listed in the car, waiting for Live DJ.
                title = "Grokify Live DJ"
                artist = "Start Live DJ on your phone"
                uri = ""
                playing = false
                pos = 0L
                dur = 0L
                artUrl = ""
                description = "Android Auto ready"
            }
        }

        val artPair = if (artUrl.isNotBlank() || uri.isNotBlank()) {
            runCatching {
                SpotifyMediaNotif.resolveArt(
                    this,
                    now.copy(
                        title = title,
                        artist = artist,
                        trackUri = uri,
                        albumArtUrl = artUrl,
                        isPlaying = playing,
                    ),
                    kickNetwork = false,
                )
            }.getOrNull()
        } else {
            null
        }
        val sessionBmp = SpotifyMediaNotif.bestSessionArt(artPair)
        val artPresence = when {
            sessionBmp != null -> "bmp${sessionBmp.width}x${sessionBmp.height}"
            artUrl.isNotBlank() -> "url"
            else -> "noart"
        }
        val mode = when {
            booth -> "booth"
            handoff -> "handoff"
            else -> "idle"
        }
        val sig = "$mode|$uri|$playing|${pos / 2_000L}|$title|$artist|$dur|$artPresence|${description.hashCode()}"
        if (!force && sig == lastMetaSig && s.isActive) {
            pushQueue(s, booth, force = false)
            return
        }
        lastMetaSig = sig
        try {
            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, description)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, uri.ifBlank { if (booth) ID_NOW else ID_IDLE })
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, uri)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            if (artUrl.isNotBlank()) {
                meta.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUrl)
                meta.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUrl)
                meta.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUrl)
            }
            sessionBmp?.let {
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            s.setMetadata(meta.build())

            val actions = if (booth || handoff) {
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_STOP
            } else {
                // Idle: no transport — start the booth on the phone first.
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            }
            val state = when {
                !booth && !handoff -> PlaybackStateCompat.STATE_STOPPED
                playing -> PlaybackStateCompat.STATE_PLAYING
                title.isNotBlank() -> PlaybackStateCompat.STATE_PAUSED
                else -> PlaybackStateCompat.STATE_STOPPED
            }
            s.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(
                        state,
                        pos,
                        if (playing) 1.0f else 0f,
                        SystemClock.elapsedRealtime(),
                    )
                    .setActiveQueueItemId(
                        if (uri.isNotBlank()) uri.hashCode().toLong() and 0x7fff_ffffL
                        else MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
                    )
                    .build(),
            )
            pushQueue(s, booth, force = true)
            Log.d(AA_TAG, "pushSession mode=$mode playing=$playing title=$title")
        } catch (e: Exception) {
            Log.w(AA_TAG, "pushSession: ${e.message}")
        }
    }

    private fun pushQueue(s: MediaSessionCompat, booth: Boolean, force: Boolean) {
        if (!booth) {
            if (force || lastQueueSig != "nobooth") {
                lastQueueSig = "nobooth"
                s.setQueue(emptyList())
            }
            return
        }
        val items = buildBrowseItems()
        val qSig = items.joinToString("|") { it.mediaId.orEmpty() }.hashCode().toString()
        if (!force && qSig == lastQueueSig) return
        lastQueueSig = qSig
        val queue = items.mapIndexedNotNull { index, item ->
            val desc = item.description ?: return@mapIndexedNotNull null
            MediaSessionCompat.QueueItem(desc, index.toLong())
        }
        s.setQueue(queue)
        s.setQueueTitle(SOURCE_NAME)
    }

    private fun runTransport(kind: String) {
        if (!transportBusy.compareAndSet(false, true)) {
            Log.d(AA_TAG, "transport busy, drop $kind")
            return
        }
        val booth = SpotifyAndroidAuto.isBoothMode(this)
        val handoff = SpotifyAndroidAuto.isHandOffMode(this)
        Log.i(AA_TAG, "transport kind=$kind booth=$booth handoff=$handoff")
        if (!booth && !handoff) {
            transportBusy.set(false)
            // Idle: ignore car transport until Live DJ is on.
            mainHandler.post { pushSession(force = true) }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (booth) {
                    when (kind) {
                        "next" -> spotifyLiveDjSkip(this@SpotifyAndroidAutoService, forceTalk = false)
                        "prev" -> spotifyLiveDjPrevious(this@SpotifyAndroidAutoService)
                        "play_pause" -> spotifyLiveDjPauseToggle(this@SpotifyAndroidAutoService)
                        "play" -> {
                            val playing = nowPlayingForNotification(this@SpotifyAndroidAutoService).isPlaying
                            if (!playing) spotifyLiveDjPauseToggle(this@SpotifyAndroidAutoService)
                        }
                        "pause" -> {
                            val playing = nowPlayingForNotification(this@SpotifyAndroidAutoService).isPlaying
                            if (playing) spotifyLiveDjPauseToggle(this@SpotifyAndroidAutoService)
                        }
                    }
                } else {
                    when (kind) {
                        "next" -> dispatchMediaCommand(this@SpotifyAndroidAutoService, ACTION_NEXT)
                        "prev" -> dispatchMediaCommand(this@SpotifyAndroidAutoService, ACTION_PREV)
                        "play_pause", "play", "pause" ->
                            dispatchMediaCommand(this@SpotifyAndroidAutoService, ACTION_PLAY_PAUSE)
                    }
                }
            } finally {
                transportBusy.set(false)
                mainHandler.post { pushSession(force = true) }
            }
        }
    }

    private fun playHistoryMediaId(mediaId: String?) {
        if (mediaId.isNullOrBlank()) return
        if (mediaId == ID_IDLE || mediaId.startsWith(ID_BANTER)) {
            Log.d(AA_TAG, "non-playable row tap ignored id=$mediaId")
            return
        }
        if (!SpotifyAndroidAuto.isBoothMode(this)) {
            // Hand-off / idle: ignore history play; treat as play if hand-off.
            if (SpotifyAndroidAuto.isHandOffMode(this)) runTransport("play")
            return
        }
        val msgId = when {
            mediaId.startsWith(ID_TRACK) -> mediaId.removePrefix(ID_TRACK)
            else -> mediaId
        }
        val msg = SpotifyDjBus.state.value.messages.firstOrNull { it.id == msgId }
            ?: SpotifyDjBus.state.value.messages.firstOrNull {
                it.role == DjChatRole.Track && it.trackUri == mediaId.removePrefix(ID_TRACK)
            }
        val uri = msg?.trackUri?.takeIf { it.isNotBlank() }
            ?: mediaId.removePrefix(ID_TRACK).takeIf { it.startsWith("spotify:") }
        if (uri.isNullOrBlank()) {
            Log.w(AA_TAG, "playHistory: no uri for $mediaId")
            return
        }
        Log.i(AA_TAG, "play now from history uri=$uri")
        spotifyLiveDjPlayUri(
            this,
            trackUri = uri,
            name = msg?.trackName.orEmpty(),
            artists = msg?.trackArtists.orEmpty(),
            albumArtUrl = msg?.albumArtUrl.orEmpty(),
            artistArtUrl = msg?.artistArtUrl.orEmpty(),
            albumUri = msg?.albumUri.orEmpty(),
            artistUri = msg?.artistUri.orEmpty(),
        )
        mainHandler.postDelayed({ pushSession(force = true) }, 400L)
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() = runTransport("play")
        override fun onPause() = runTransport("pause")
        override fun onSkipToNext() = runTransport("next")
        override fun onSkipToPrevious() = runTransport("prev")
        override fun onStop() = runTransport("pause")
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            playHistoryMediaId(mediaId)
        }
        override fun onSkipToQueueItem(id: Long) {
            val queue = session?.controller?.queue ?: return
            val item = queue.firstOrNull { it.queueId == id } ?: return
            playHistoryMediaId(item.description.mediaId)
        }
    }

    companion object {
        const val ACTION_REFRESH = "io.grokify.os.SPOTIFY_AA_REFRESH"
        const val SOURCE_NAME = "Grokify Live DJ"
    }
}
