package io.grokify.os.apps

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.apps.plugin.SpotifyOAuth
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.MainActivity
import io.grokify.os.permission.AppPermissionId
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.service.GrokifyNotificationListener
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun setOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else Handler(Looper.getMainLooper()).post(block)
}

private const val TAG = "SpotifyCtrl"
private const val PREFS = "spotify_controller"
private const val KEY_ENABLED = "enabled"
private const val KEY_PREFERRED_DEVICE = "preferred_device_id"
const val SPOTIFY_CTRL_NOTIF_ID = 47001

private const val ACTION_PREV = "io.grokify.os.SPOTIFY_PREV"
private const val ACTION_PLAY_PAUSE = "io.grokify.os.SPOTIFY_PLAY_PAUSE"
private const val ACTION_NEXT = "io.grokify.os.SPOTIFY_NEXT"
private const val ACTION_STOP = "io.grokify.os.SPOTIFY_STOP"

private val SPOTIFY_PACKAGES = listOf(
    "com.spotify.music",
    "com.spotify.lite",
)

/** Snapshot of whatever media session we can drive. */
data class SpotifyNowPlaying(
    val title: String = "",
    val artist: String = "",
    val appLabel: String = "",
    val packageName: String = "",
    val isPlaying: Boolean = false,
    val hasSession: Boolean = false,
    /** Playback position in ms (extrapolated while playing). */
    val positionMs: Long = 0L,
    /** Track duration in ms, 0 if unknown. */
    val durationMs: Long = 0L,
    /** Album / track cover (media session URI or Spotify CDN). */
    val albumArtUrl: String = "",
    /** Primary artist portrait when known from Web API. */
    val artistArtUrl: String = "",
    val trackUri: String = "",
    val albumUri: String = "",
    val artistUri: String = "",
)

/** Persists lockscreen widget on/off and last-picked playback device. */
class SpotifyControllerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Last device the user selected in Control (used for transfer + Live DJ play). */
    var preferredDeviceId: String
        get() = prefs.getString(KEY_PREFERRED_DEVICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PREFERRED_DEVICE, value).apply()
}

/** Spotify Connect / app playback target from Web API. */
data class SpotifyPlaybackDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean,
    val volumePercent: Int,
)

/**
 * List available Spotify Connect devices. Requires Spotify login.
 * @return devices + optional error message (null on success).
 */
fun fetchSpotifyDevices(context: Context): Pair<List<SpotifyPlaybackDevice>, String?> {
    if (!SpotifyOAuth.isLoggedIn(context)) {
        return emptyList<SpotifyPlaybackDevice>() to "Connect Spotify in the Account tab"
    }
    return try {
        val raw = SpotifyOAuth.api(context, "GET", "/v1/me/player/devices", null)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        if (!o.optBoolean("ok", false) && status !in listOf(200, 201, 202, 204)) {
            val err = o.optString("error", "").ifBlank {
                o.optString("body", "").take(120)
            }
            return emptyList<SpotifyPlaybackDevice>() to
                (err.ifBlank { "Couldn’t load devices (HTTP $status)" })
        }
        val bodyStr = o.optString("body", "")
        if (bodyStr.isBlank()) return emptyList<SpotifyPlaybackDevice>() to null
        val data = runCatching { JSONObject(bodyStr) }.getOrNull()
            ?: return emptyList<SpotifyPlaybackDevice>() to "Bad devices response"
        val arr = data.optJSONArray("devices") ?: JSONArray()
        val out = ArrayList<SpotifyPlaybackDevice>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optString("id", "")
            if (id.isBlank()) continue
            out.add(
                SpotifyPlaybackDevice(
                    id = id,
                    name = d.optString("name", "Device").ifBlank { "Device" },
                    type = d.optString("type", "Unknown").ifBlank { "Unknown" },
                    isActive = d.optBoolean("is_active", false),
                    isRestricted = d.optBoolean("is_restricted", false),
                    volumePercent = d.optInt("volume_percent", -1),
                ),
            )
        }
        // Active first, then name
        out.sortWith(
            compareByDescending<SpotifyPlaybackDevice> { it.isActive }
                .thenBy { it.name.lowercase() },
        )
        out to null
    } catch (e: Exception) {
        Log.w(TAG, "fetchSpotifyDevices", e)
        emptyList<SpotifyPlaybackDevice>() to (e.message ?: "devices_failed")
    }
}

/**
 * Transfer playback to [deviceId]. When [play] is true, start/resume on that device.
 * @return null on success, else error string.
 */
fun transferSpotifyPlayback(
    context: Context,
    deviceId: String,
    play: Boolean = true,
): String? {
    if (deviceId.isBlank()) return "No device id"
    if (!SpotifyOAuth.isLoggedIn(context)) return "Connect Spotify in the Account tab"
    return try {
        val body = JSONObject()
            .put("device_ids", JSONArray().put(deviceId))
            .put("play", play)
            .toString()
        val raw = SpotifyOAuth.api(context, "PUT", "/v1/me/player", body)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        // 204 No Content = success; some clients also 200/202
        if (o.optBoolean("ok", false) || status in listOf(200, 201, 202, 204)) {
            SpotifyControllerStore(context).preferredDeviceId = deviceId
            null
        } else {
            val errBody = o.optString("body", "")
            val parsed = runCatching {
                JSONObject(errBody).optJSONObject("error")?.optString("message")
            }.getOrNull()
            parsed?.takeIf { it.isNotBlank() }
                ?: o.optString("error", "").ifBlank { "Transfer failed (HTTP $status)" }
        }
    } catch (e: Exception) {
        Log.w(TAG, "transferSpotifyPlayback", e)
        e.message ?: "transfer_failed"
    }
}

private fun deviceTypeIcon(type: String): ImageVector {
    return when (type.trim().lowercase()) {
        "computer" -> Icons.Default.Computer
        "tablet" -> Icons.Default.Tablet
        "smartphone" -> Icons.Default.PhoneAndroid
        "speaker" -> Icons.Default.Speaker
        "tv" -> Icons.Default.Tv
        "avr", "stb", "castvideo", "castaudio" -> Icons.Default.Tv
        "audiodongle", "gameconsole" -> Icons.Default.Headphones
        "automobile" -> Icons.Default.DirectionsCar
        else -> Icons.Default.Devices
    }
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ) ?: return false
    val pkg = context.packageName
    return flat.split(':').any { it.contains(pkg) }
}

fun openNotificationListenerSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

fun isSpotifyInstalled(context: Context): Boolean {
    val pm = context.packageManager
    return SPOTIFY_PACKAGES.any { pkg ->
        try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

fun openSpotifyApp(context: Context) {
    val pm = context.packageManager
    for (pkg in SPOTIFY_PACKAGES) {
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return
        }
    }
    val market = Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=com.spotify.music"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }
}

/** Whether our controller notification is currently posted. */
fun isSpotifyControllerNotificationPosted(context: Context): Boolean {
    return try {
        NotificationManagerCompat.from(context).activeNotifications
            .any { it.id == SPOTIFY_CTRL_NOTIF_ID }
    } catch (_: Exception) {
        false
    }
}

/** Start or stop the lockscreen media control notification. */
fun setSpotifyControllerEnabled(context: Context, enabled: Boolean) {
    val appCtx = context.applicationContext
    val store = SpotifyControllerStore(appCtx)
    store.enabled = enabled
    val intent = Intent(appCtx, SpotifyControllerService::class.java)
    if (enabled) {
        // One shared shade entry with Live DJ — drop the legacy DJ-only id.
        runCatching {
            appCtx.getSystemService(NotificationManager::class.java)
                ?.cancel(SPOTIFY_DJ_NOTIF_ID)
        }
        try {
            ContextCompat.startForegroundService(appCtx, intent)
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed: ${e.message}", e)
        }
    } else {
        appCtx.stopService(intent)
        // Keep the shared notification if Live DJ still needs a FGS slot.
        if (!SpotifyDjStore(appCtx).enabled) {
            val nm = appCtx.getSystemService(NotificationManager::class.java)
            nm?.cancel(SPOTIFY_CTRL_NOTIF_ID)
        }
    }
}

/**
 * Finds an active media session, preferring Spotify.
 * Requires Notification Listener access for session list.
 */
fun resolveActiveMediaController(context: Context): MediaController? {
    val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
    if (!isNotificationListenerEnabled(context)) return null
    val listener = ComponentName(context, GrokifyNotificationListener::class.java)
    val sessions = try {
        msm.getActiveSessions(listener)
    } catch (e: SecurityException) {
        Log.w(TAG, "getActiveSessions denied: ${e.message}")
        emptyList()
    }
    if (sessions.isEmpty()) return null
    return sessions.firstOrNull { it.packageName in SPOTIFY_PACKAGES }
        ?: sessions.firstOrNull { ctrl ->
            val st = ctrl.playbackState?.state
            st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        }
        ?: sessions.firstOrNull()
}

/** Extrapolate live position from last PlaybackState update while playing. */
private fun livePositionMs(state: PlaybackState?): Long {
    if (state == null) return 0L
    val base = state.position.coerceAtLeast(0L)
    val st = state.state
    if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_BUFFERING) {
        return base
    }
    val updatedAt = state.lastPositionUpdateTime
    if (updatedAt <= 0L) return base
    val elapsed = SystemClock.elapsedRealtime() - updatedAt
    if (elapsed <= 0L) return base
    val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
    return base + (elapsed * speed).toLong()
}

fun formatTrackTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

fun readNowPlaying(context: Context): SpotifyNowPlaying {
    val ctrl = resolveActiveMediaController(context) ?: return SpotifyNowPlaying()
    val md = ctrl.metadata
    val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty() }
    val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() }
    val pb = ctrl.playbackState
    val state = pb?.state
    val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
    var position = livePositionMs(pb)
    if (duration > 0L) position = position.coerceIn(0L, duration)
    val label = try {
        val ai = context.packageManager.getApplicationInfo(ctrl.packageName, 0)
        context.packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) {
        ctrl.packageName
    }
    val albumArt = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty() }
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty() }
    val mediaUri = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI).orEmpty()
        .ifBlank { md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty() }
    val trackUri = when {
        mediaUri.startsWith("spotify:track:") -> mediaUri
        mediaUri.startsWith("https://open.spotify.com/track/") -> {
            val id = mediaUri.removePrefix("https://open.spotify.com/track/")
                .substringBefore('?').substringBefore('/')
            if (id.isNotBlank()) "spotify:track:$id" else ""
        }
        mediaUri.matches(Regex("^[A-Za-z0-9]{22}$")) -> "spotify:track:$mediaUri"
        else -> ""
    }
    return SpotifyNowPlaying(
        title = title.ifBlank { "Unknown track" },
        artist = artist,
        appLabel = label,
        packageName = ctrl.packageName,
        isPlaying = playing,
        hasSession = true,
        positionMs = position,
        durationMs = duration,
        albumArtUrl = albumArt,
        trackUri = trackUri,
    )
}

/** In-process artist id → portrait URL cache for Control tab enrichment. */
private val controlArtistImageCache = HashMap<String, String>(48)

/**
 * Enrich a media-session snapshot with Spotify Web API art + deep-link URIs
 * (album background, artist thumbnail, clickable track/artist/album).
 */
fun enrichNowPlayingFromApi(context: Context, base: SpotifyNowPlaying): SpotifyNowPlaying {
    if (!SpotifyOAuth.isLoggedIn(context)) return base
    return try {
        val raw = SpotifyOAuth.api(context, "GET", "/v1/me/player/currently-playing", null)
        val o = JSONObject(raw)
        val status = o.optInt("status", 0)
        if (status == 204) return base
        if (!o.optBoolean("ok", false) && status !in listOf(200, 201, 202)) return base
        val bodyStr = o.optString("body", "")
        if (bodyStr.isBlank()) return base
        val data = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return base
        if (!data.has("item") || data.isNull("item")) return base
        val item = data.getJSONObject("item")
        val title = item.optString("name", "").ifBlank { base.title }
        val trackUri = item.optString("uri", "").ifBlank { base.trackUri }
        val album = item.optJSONObject("album")
        val albumUri = album?.optString("uri", "").orEmpty().ifBlank {
            val id = album?.optString("id", "").orEmpty()
            if (id.isNotBlank()) "spotify:album:$id" else base.albumUri
        }
        val images = album?.optJSONArray("images")
        var albumArt = base.albumArtUrl
        if (images != null) {
            var best = ""
            var bestW = -1
            for (i in 0 until images.length()) {
                val im = images.optJSONObject(i) ?: continue
                val url = im.optString("url", "")
                if (url.isBlank()) continue
                val w = im.optInt("width", 0)
                if (w >= bestW) {
                    bestW = w
                    best = url
                }
            }
            if (best.isNotBlank()) albumArt = best
        }
        val artistsArr = item.optJSONArray("artists")
        val artistNames = ArrayList<String>()
        var artistUri = base.artistUri
        var artistId = ""
        if (artistsArr != null) {
            for (i in 0 until artistsArr.length()) {
                val a = artistsArr.optJSONObject(i) ?: continue
                val n = a.optString("name", "")
                if (n.isNotBlank()) artistNames.add(n)
                if (i == 0) {
                    artistUri = a.optString("uri", "").ifBlank {
                        val id = a.optString("id", "")
                        if (id.isNotBlank()) "spotify:artist:$id" else artistUri
                    }
                    artistId = a.optString("id", "")
                }
            }
        }
        val artistArt = if (artistId.isNotBlank()) {
            controlArtistImageCache[artistId] ?: run {
                val ar = SpotifyOAuth.api(context, "GET", "/v1/artists/$artistId", null)
                val ao = JSONObject(ar)
                val ab = ao.optString("body", "")
                val aj = if (ab.isNotBlank()) runCatching { JSONObject(ab) }.getOrNull() else null
                val imgs = aj?.optJSONArray("images")
                var best = ""
                var bestW = -1
                if (imgs != null) {
                    for (i in 0 until imgs.length()) {
                        val im = imgs.optJSONObject(i) ?: continue
                        val url = im.optString("url", "")
                        if (url.isBlank()) continue
                        val w = im.optInt("width", 0)
                        if (best.isBlank() || (w in 160..640 && w >= bestW) || w > bestW) {
                            bestW = w
                            best = url
                        }
                    }
                }
                if (best.isNotBlank()) controlArtistImageCache[artistId] = best
                best
            }
        } else base.artistArtUrl
        val playing = data.optBoolean("is_playing", base.isPlaying)
        val progress = data.optLong("progress_ms", base.positionMs)
        val duration = item.optLong("duration_ms", base.durationMs)
        base.copy(
            title = title,
            artist = artistNames.joinToString(", ").ifBlank { base.artist },
            isPlaying = playing,
            hasSession = true,
            positionMs = progress,
            durationMs = duration,
            albumArtUrl = albumArt,
            artistArtUrl = artistArt.ifBlank { base.artistArtUrl },
            trackUri = trackUri,
            albumUri = albumUri,
            artistUri = artistUri,
        )
    } catch (e: Exception) {
        Log.w(TAG, "enrichNowPlaying: ${e.message}")
        base
    }
}

/** Open a spotify: / open.spotify.com URI in the Spotify app (or store). */
fun openSpotifyContent(context: Context, uri: String?) {
    val u = uri?.trim().orEmpty()
    if (u.isBlank()) {
        openSpotifyApp(context)
        return
    }
    SpotifyOAuth.openContentUri(context, u)
}

/**
 * Prefer session transport controls; fall back to media key events.
 *
 * When Live DJ is on, Next / Prev / Play-Pause go through the DJ service so the
 * radio UP NEXT stays aligned. Media-session skip alone advances Spotify’s stale
 * Up Next (append-only) and leaves the Live DJ queue several songs behind.
 */
fun dispatchMediaCommand(context: Context, action: String) {
    val appCtx = context.applicationContext
    if (SpotifyDjStore(appCtx).enabled) {
        when (action) {
            ACTION_NEXT -> {
                spotifyLiveDjSkip(appCtx, forceTalk = false)
                return
            }
            ACTION_PREV -> {
                spotifyLiveDjPrevious(appCtx)
                return
            }
            ACTION_PLAY_PAUSE -> {
                spotifyLiveDjPauseToggle(appCtx)
                return
            }
        }
    }
    val ctrl = resolveActiveMediaController(context)
    if (ctrl != null) {
        try {
            when (action) {
                ACTION_PREV -> ctrl.transportControls.skipToPrevious()
                ACTION_NEXT -> ctrl.transportControls.skipToNext()
                ACTION_PLAY_PAUSE -> {
                    val st = ctrl.playbackState?.state
                    if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) {
                        ctrl.transportControls.pause()
                    } else {
                        ctrl.transportControls.play()
                    }
                }
            }
            return
        } catch (e: Exception) {
            Log.w(TAG, "transportControls failed: ${e.message}")
        }
    }
    // System-wide media keys — works when Spotify (or other player) holds audio focus
    val key = when (action) {
        ACTION_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ACTION_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        ACTION_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        else -> return
    }
    val am = context.getSystemService(AudioManager::class.java) ?: return
    val now = SystemClock.uptimeMillis()
    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
    am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
}

/**
 * Foreground service that pins a shade/lockscreen notification with
 * Previous / Play-Pause / Next always visible (MediaStyle compact actions).
 * Does NOT attach a MediaSession token — that fights Spotify for the system
 * media player and usually makes our notif disappear. Progress uses the
 * standard notification progress bar + time text from Spotify's session.
 * specialUse FGS: we control other apps' media, we are not a player.
 */
class SpotifyControllerService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val now = refreshNotification()
            // Tick progress bar smoothly while playing; idle slower to save battery
            val delayMs = if (now?.isPlaying == true) 1_000L else 2_500L
            handler.postDelayed(this, delayMs)
        }
    }

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { _ ->
        refreshNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            if (msm != null && isNotificationListenerEnabled(this)) {
                msm.addOnActiveSessionsChangedListener(
                    sessionListener,
                    ComponentName(this, GrokifyNotificationListener::class.java),
                    handler,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "session listener: ${e.message}")
        }
        handler.post(refreshRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SpotifyControllerStore(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification(readNowPlaying(this))
        // specialUse: we control other apps' media — we are not a player.
        // mediaPlayback FGS is killed on Android 14+ when not actually playing.
        val fgsType = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, SPOTIFY_CTRL_NOTIF_ID, n, fgsType)
            Log.i(TAG, "foreground started, notif posted id=$SPOTIFY_CTRL_NOTIF_ID")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed, falling back: ${e.message}", e)
            // Last resort: post as plain notification so controls still appear
            try {
                startForeground(SPOTIFY_CTRL_NOTIF_ID, n)
            } catch (e2: Exception) {
                Log.e(TAG, "plain startForeground failed: ${e2.message}", e2)
                getSystemService(NotificationManager::class.java)
                    ?.notify(SPOTIFY_CTRL_NOTIF_ID, n)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        try {
            getSystemService(MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** @return latest snapshot (for refresh scheduling), or null if stopped. */
    private fun refreshNotification(): SpotifyNowPlaying? {
        if (!SpotifyControllerStore(this).enabled) {
            stopSelf()
            return null
        }
        val now = readNowPlaying(this)
        val nm = getSystemService(NotificationManager::class.java) ?: return now
        nm.notify(SPOTIFY_CTRL_NOTIF_ID, buildNotification(now))
        return now
    }

    private fun pi(action: String, req: Int): PendingIntent {
        val i = Intent(this, SpotifyControllerReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            this,
            req,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(now: SpotifyNowPlaying): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("open_app", "spotify_controller")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            now.hasSession && now.title.isNotBlank() -> now.title
            else -> "Spotify Controller"
        }
        val timePart = when {
            now.hasSession && now.durationMs > 0L ->
                "${formatTrackTime(now.positionMs)} / ${formatTrackTime(now.durationMs)}"
            now.hasSession && now.positionMs > 0L ->
                formatTrackTime(now.positionMs)
            else -> null
        }
        val text = when {
            now.hasSession && now.artist.isNotBlank() && timePart != null ->
                "${now.artist} · $timePart"
            now.hasSession && now.artist.isNotBlank() ->
                "${now.artist} · ${now.appLabel.ifBlank { "media" }}"
            now.hasSession && timePart != null -> timePart
            now.hasSession -> now.appLabel.ifBlank { "Media session active" }
            else -> "Prev · Play/Pause · Next — start Spotify"
        }
        val playIcon = if (now.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playLabel = if (now.isPlaying) "Pause" else "Play"

        // MediaStyle compact actions = buttons visible without expanding.
        // No MediaSession token — attaching one steals/loses the lockscreen
        // media slot to Spotify and our controls vanish.
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, GrokifyApp.CHANNEL_SPOTIFY_CTRL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(mediaStyle)
            .setSubText(if (now.hasSession) now.appLabel.ifBlank { "GrokifyOS" } else "GrokifyOS")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pi(ACTION_PREV, 1))
            .addAction(playIcon, playLabel, pi(ACTION_PLAY_PAUSE, 2))
            .addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT, 3))

        // Determinate progress bar under the text (works without MediaSession).
        if (now.hasSession && now.durationMs > 0L) {
            val max = now.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val prog = now.positionMs.coerceIn(0L, now.durationMs)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            builder.setProgress(max, prog, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }
}

/** Handles notification action taps and boot re-arm. */
class SpotifyControllerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_PREV, ACTION_PLAY_PAUSE, ACTION_NEXT -> {
                dispatchMediaCommand(context, action)
                if (SpotifyControllerStore(context).enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SpotifyControllerService::class.java),
                    )
                }
            }
            ACTION_STOP -> setSpotifyControllerEnabled(context, false)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (SpotifyControllerStore(context).enabled) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, SpotifyControllerService::class.java),
                    )
                }
            }
        }
    }
}

@Composable
fun SpotifyControllerPane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val store = remember { SpotifyControllerStore(appCtx) }
    val djStore = remember { SpotifyDjStore(appCtx) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) } // 0 control, 1 live dj, 2 build, 3 account

    var enabled by remember { mutableStateOf(store.enabled) }
    var now by remember { mutableStateOf(readNowPlaying(appCtx)) }
    var listenerOk by remember { mutableStateOf(isNotificationListenerEnabled(appCtx)) }
    var spotifyOk by remember { mutableStateOf(isSpotifyInstalled(appCtx)) }
    var notifPosted by remember { mutableStateOf(isSpotifyControllerNotificationPosted(appCtx)) }
    val notifOk = PermissionHelper.status(appCtx, AppPermissionId.NOTIFICATIONS).granted ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    val djState by SpotifyDjBus.state.collectAsState()
    var clientId by remember {
        mutableStateOf(HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPOTIFY_CLIENT_ID).orEmpty())
    }
    var authMsg by remember { mutableStateOf(SpotifyOAuth.lastAuthMessage.orEmpty()) }
    var loggedIn by remember { mutableStateOf(SpotifyOAuth.isLoggedIn(appCtx)) }
    var voiceId by remember { mutableStateOf(djStore.voiceId) }
    var useAiRank by remember { mutableStateOf(djStore.useAiRank) }
    var banterMode by remember { mutableStateOf(djStore.banterMode) }
    var banterFixed by remember { mutableStateOf(djStore.banterFixed) }
    var banterMin by remember { mutableStateOf(djStore.banterMin) }
    var banterMax by remember { mutableStateOf(djStore.banterMax) }
    var allowTalkOver by remember { mutableStateOf(djStore.allowTalkOver) }
    var resumeAfterRestart by remember { mutableStateOf(djStore.resumeAfterRestart) }
    var busy by remember { mutableStateOf(false) }
    var voicePreviewMsg by remember { mutableStateOf<String?>(null) }
    var voicePreviewBusy by remember { mutableStateOf(false) }
    var hasXaiKey by remember {
        mutableStateOf(!HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank())
    }
    var djChatDraft by remember { mutableStateOf("") }
    /** 0 = Chat, 1 = Queue, 2 = Settings (inner Live DJ tabs) */
    var djSubTab by remember { mutableStateOf(0) }
    val djChatListState = rememberLazyListState()

    // Restore chat/queue + re-arm service after OTA/process death when resume is on.
    LaunchedEffect(Unit) {
        ensureDjChatHydrated(appCtx)
        maybeResumeLiveDj(appCtx)
        resumeAfterRestart = djStore.resumeAfterRestart
    }

    // Research / build / edit playlist
    var researchPrompt by remember { mutableStateOf("") }
    var lastResearch by remember { mutableStateOf<SpotifyPlaylistAi.ResearchResult?>(null) }
    var researchOut by remember { mutableStateOf("") }
    var workStep by remember { mutableStateOf<String?>(null) }
    var buildMsg by remember { mutableStateOf<String?>(null) }
    var buildOk by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylistAi.PlaylistRef>>(emptyList()) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var editPrompt by remember { mutableStateOf("") }
    var lastEdit by remember { mutableStateOf<SpotifyPlaylistAi.EditPlan?>(null) }
    var editOut by remember { mutableStateOf("") }
    var playlistLoadMsg by remember { mutableStateOf<String?>(null) }

    // Playback devices (Control tab)
    var devices by remember { mutableStateOf<List<SpotifyPlaybackDevice>>(emptyList()) }
    var devicesMsg by remember { mutableStateOf<String?>(null) }
    var devicesLoading by remember { mutableStateOf(false) }
    var devicesTransferringId by remember { mutableStateOf<String?>(null) }
    var preferredDeviceId by remember { mutableStateOf(store.preferredDeviceId) }

    val vibeChips = remember {
        listOf(
            "Sunset rooftop chill: soft R&B, lo-fi edges, warm bass, 80–95 BPM" to "Rooftop",
            "Gym aggression: modern trap + metalcore drops, high energy, clean structure" to "Gym",
            "Focus deep work: instrumental only, minimal lyrics, ambient electronic" to "Focus",
            "Party starter 2020s hits + timeless sing-alongs, upbeat" to "Party",
        )
    }

    LaunchedEffect(enabled) {
        while (true) {
            val snap = readNowPlaying(appCtx)
            now = if (SpotifyOAuth.isLoggedIn(appCtx) && (snap.hasSession || loggedIn)) {
                withContext(Dispatchers.IO) { enrichNowPlayingFromApi(appCtx, snap) }
            } else {
                snap
            }
            listenerOk = isNotificationListenerEnabled(appCtx)
            spotifyOk = isSpotifyInstalled(appCtx)
            notifPosted = isSpotifyControllerNotificationPosted(appCtx)
            if (enabled && store.enabled && !notifPosted) {
                setSpotifyControllerEnabled(appCtx, true)
            }
            delay(1_500L)
        }
    }

    LaunchedEffect(tab) {
        while (true) {
            loggedIn = SpotifyOAuth.isLoggedIn(appCtx)
            authMsg = SpotifyOAuth.lastAuthMessage.orEmpty()
            hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
            delay(1_200L)
        }
    }

    // Refresh device list on Control tab (and when login becomes available)
    LaunchedEffect(tab, loggedIn) {
        if (tab != 0) return@LaunchedEffect
        while (true) {
            if (SpotifyOAuth.isLoggedIn(appCtx)) {
                devicesLoading = devices.isEmpty()
                val (list, err) = withContext(Dispatchers.IO) { fetchSpotifyDevices(appCtx) }
                devices = list
                devicesMsg = err
                preferredDeviceId = store.preferredDeviceId
                devicesLoading = false
            } else {
                devices = emptyList()
                devicesMsg = "Connect Spotify in the Account tab"
                devicesLoading = false
            }
            delay(5_000L)
        }
    }

    DisposableEffect(Unit) {
        enabled = store.enabled
        // Publish baseline DJ UI when pane opens (keep chat history if any)
        if (!djStore.enabled) {
            val prev = SpotifyDjBus.state.value
            SpotifyDjBus.publish(
                SpotifyDjUiState(
                    enabled = false,
                    status = "Off",
                    messages = prev.messages,
                    queue = prev.queue.ifEmpty { djStore.loadQueue() },
                    loggedIn = SpotifyOAuth.isLoggedIn(appCtx),
                    voiceId = djStore.voiceId,
                    useAiRank = djStore.useAiRank,
                    songsSinceBanter = djStore.songsSinceBanter,
                    banterEvery = djStore.banterEvery,
                    tracksUntilTalk = tracksUntilTalk(djStore.songsSinceBanter, djStore.banterEvery),
                    banterMode = djStore.banterMode,
                    banterFixed = djStore.banterFixed,
                    banterMin = djStore.banterMin,
                    banterMax = djStore.banterMax,
                    allowTalkOver = djStore.allowTalkOver,
                    resumeAfterRestart = djStore.resumeAfterRestart,
                ),
            )
        } else {
            // Service may have died while enabled=true (OTA / low memory) — re-arm.
            maybeResumeLiveDj(appCtx)
        }
        onDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.GlowCyan,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Spotify",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Text(
                    "Controller · Live DJ · Build · Account",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = GrokifyColors.GlowMint,
                modifier = Modifier.size(28.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Control", "Live DJ", "Build", "Account").forEachIndexed { i, label ->
                val selected = tab == i
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) GrokifyColors.GlowMint.copy(alpha = 0.18f)
                            else GrokifyColors.Void.copy(alpha = 0f),
                        )
                        .clickable { tab = i }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (tab) {
            0 -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true)) {
                // Master toggle card
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Lockscreen widget",
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                when {
                                    enabled && notifPosted ->
                                        "On — look for “Spotify Controller” in shade / lockscreen"
                                    enabled && !notifPosted ->
                                        "On — starting notification…"
                                    else ->
                                        "Off — enable to pin Prev · Play · Next on lockscreen"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (!notifOk) onRequestPermissions()
                                    setSpotifyControllerEnabled(appCtx, true)
                                    enabled = true
                                } else {
                                    setSpotifyControllerEnabled(appCtx, false)
                                    enabled = false
                                }
                                notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.Void,
                                checkedTrackColor = GrokifyColors.GlowMint,
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                                uncheckedTrackColor = GrokifyColors.PanelSoft,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp)),
                ) {
                    // Album art as full-card background
                    if (now.albumArtUrl.isNotBlank()) {
                        AsyncImage(
                            model = now.albumArtUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                if (now.albumArtUrl.isNotBlank()) {
                                    GrokifyColors.Void.copy(alpha = 0.72f)
                                } else {
                                    GrokifyColors.Panel
                                },
                            ),
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.GlowCyan,
                        )
                        Spacer(Modifier.height(12.dp))
                        // Artist portrait as thumbnail (opens artist in Spotify)
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(GrokifyColors.GlowMint.copy(alpha = 0.12f))
                                .border(2.dp, GrokifyColors.GlowMint.copy(alpha = 0.45f), CircleShape)
                                .clickable(enabled = now.artistUri.isNotBlank() || now.artist.isNotBlank()) {
                                    when {
                                        now.artistUri.isNotBlank() -> openSpotifyContent(context, now.artistUri)
                                        now.albumUri.isNotBlank() -> openSpotifyContent(context, now.albumUri)
                                        now.trackUri.isNotBlank() -> openSpotifyContent(context, now.trackUri)
                                        else -> openSpotifyApp(context)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val thumb = now.artistArtUrl.ifBlank { now.albumArtUrl }
                            if (thumb.isNotBlank()) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = "Artist",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = GrokifyColors.GlowMint,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (now.hasSession) now.title else "Nothing detected",
                            color = GrokifyColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = now.hasSession) {
                                    when {
                                        now.trackUri.isNotBlank() -> openSpotifyContent(context, now.trackUri)
                                        now.albumUri.isNotBlank() -> openSpotifyContent(context, now.albumUri)
                                        else -> openSpotifyApp(context)
                                    }
                                },
                        )
                        Text(
                            when {
                                now.hasSession && now.artist.isNotBlank() -> now.artist
                                now.hasSession -> now.appLabel
                                else -> "Start Spotify, then use controls below or the lockscreen widget"
                            },
                            color = GrokifyColors.TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = now.hasSession && now.artist.isNotBlank()) {
                                    when {
                                        now.artistUri.isNotBlank() -> openSpotifyContent(context, now.artistUri)
                                        now.albumUri.isNotBlank() -> openSpotifyContent(context, now.albumUri)
                                        else -> openSpotifyApp(context)
                                    }
                                },
                        )
                        if (now.hasSession && now.appLabel.isNotBlank() && now.artist.isNotBlank()) {
                            Text(
                                now.appLabel,
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (now.hasSession && now.durationMs > 0L) {
                            Spacer(Modifier.height(12.dp))
                            val frac = (now.positionMs.toFloat() / now.durationMs.toFloat())
                                .coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(GrokifyColors.PanelSoft.copy(alpha = 0.85f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(frac)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(GrokifyColors.GlowMint),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatTrackTime(now.positionMs),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    formatTrackTime(now.durationMs),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TransportButton(
                                icon = Icons.Default.SkipPrevious,
                                label = "Back",
                                onClick = {
                                    dispatchMediaCommand(appCtx, ACTION_PREV)
                                    now = readNowPlaying(appCtx)
                                },
                            )
                            TransportButton(
                                icon = if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                label = if (now.isPlaying) "Pause" else "Play",
                                accent = true,
                                onClick = {
                                    dispatchMediaCommand(appCtx, ACTION_PLAY_PAUSE)
                                    now = readNowPlaying(appCtx)
                                },
                            )
                            TransportButton(
                                icon = Icons.Default.SkipNext,
                                label = "Next",
                                onClick = {
                                    dispatchMediaCommand(appCtx, ACTION_NEXT)
                                    now = readNowPlaying(appCtx)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Playback device picker (Spotify Connect)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "PLAY ON",
                                style = MaterialTheme.typography.labelSmall,
                                color = GrokifyColors.GlowCyan,
                            )
                            Text(
                                "Choose which device Spotify plays on",
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    devicesLoading = true
                                    val (list, err) = withContext(Dispatchers.IO) {
                                        fetchSpotifyDevices(appCtx)
                                    }
                                    devices = list
                                    devicesMsg = err
                                    preferredDeviceId = store.preferredDeviceId
                                    devicesLoading = false
                                }
                            },
                            enabled = !devicesLoading && devicesTransferringId == null,
                        ) {
                            if (devicesLoading && devices.isEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = GrokifyColors.GlowCyan,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Refresh devices",
                                    tint = GrokifyColors.GlowCyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        !loggedIn -> {
                            Text(
                                "Connect Spotify in the Account tab to list devices.",
                                color = GrokifyColors.TextMuted,
                                fontSize = 12.sp,
                            )
                            TextButton(onClick = { tab = 3 }) {
                                Text("Open Account", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                            }
                        }
                        devicesMsg != null && devices.isEmpty() -> {
                            Text(
                                devicesMsg ?: "No devices",
                                color = GrokifyColors.GlowAmber,
                                fontSize = 12.sp,
                            )
                        }
                        devices.isEmpty() && devicesLoading -> {
                            Text(
                                "Loading devices…",
                                color = GrokifyColors.TextDim,
                                fontSize = 12.sp,
                            )
                        }
                        devices.isEmpty() -> {
                            Text(
                                "No active devices. Open Spotify on a phone, speaker, or computer, then refresh.",
                                color = GrokifyColors.TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                        else -> {
                            devices.forEach { dev ->
                                val active = dev.isActive
                                val preferred = preferredDeviceId == dev.id
                                val transferring = devicesTransferringId == dev.id
                                val selected = active || preferred
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                active -> GrokifyColors.GlowMint.copy(alpha = 0.14f)
                                                preferred -> GrokifyColors.GlowCyan.copy(alpha = 0.10f)
                                                else -> GrokifyColors.Void.copy(alpha = 0.35f)
                                            },
                                        )
                                        .border(
                                            1.dp,
                                            when {
                                                active -> GrokifyColors.GlowMint.copy(alpha = 0.45f)
                                                preferred -> GrokifyColors.GlowCyan.copy(alpha = 0.35f)
                                                else -> GrokifyColors.PanelBorder
                                            },
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable(
                                            enabled = !dev.isRestricted &&
                                                devicesTransferringId == null &&
                                                !active,
                                        ) {
                                            scope.launch {
                                                devicesTransferringId = dev.id
                                                devicesMsg = null
                                                val err = withContext(Dispatchers.IO) {
                                                    transferSpotifyPlayback(appCtx, dev.id, play = true)
                                                }
                                                if (err == null) {
                                                    store.preferredDeviceId = dev.id
                                                    preferredDeviceId = dev.id
                                                    // Optimistically mark active until next poll
                                                    devices = devices.map {
                                                        it.copy(isActive = it.id == dev.id)
                                                    }
                                                    delay(600L)
                                                    val (list, listErr) = withContext(Dispatchers.IO) {
                                                        fetchSpotifyDevices(appCtx)
                                                    }
                                                    if (list.isNotEmpty()) devices = list
                                                    devicesMsg = listErr
                                                } else {
                                                    devicesMsg = err
                                                }
                                                devicesTransferringId = null
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        deviceTypeIcon(dev.type),
                                        contentDescription = dev.type,
                                        tint = if (active) GrokifyColors.GlowMint
                                        else GrokifyColors.TextMuted,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            dev.name,
                                            color = GrokifyColors.TextPrimary,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                            else FontWeight.Normal,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                append(dev.type)
                                                if (active) append(" · Playing here")
                                                else if (preferred) append(" · Preferred")
                                                if (dev.isRestricted) append(" · Restricted")
                                                if (dev.volumePercent in 0..100) {
                                                    append(" · ${dev.volumePercent}%")
                                                }
                                            },
                                            color = GrokifyColors.TextDim,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    when {
                                        transferring -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = GrokifyColors.GlowMint,
                                            )
                                        }
                                        active -> {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Active",
                                                tint = GrokifyColors.GlowMint,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (devicesMsg != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    devicesMsg ?: "",
                                    color = GrokifyColors.GlowAmber,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "SETUP",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusLine(
                        ok = notifOk,
                        okText = "Notifications allowed",
                        badText = "Notifications blocked — required for lockscreen widget",
                    )
                    StatusLine(
                        ok = !enabled || notifPosted,
                        okText = if (enabled) "Controller notification is live" else "Widget idle",
                        badText = "Controller notification missing — toggle off/on",
                    )
                    StatusLine(
                        ok = listenerOk,
                        okText = "Notification access on (track title + reliable control)",
                        badText = "Notification access off — enable for Spotify metadata",
                    )
                    StatusLine(
                        ok = spotifyOk,
                        okText = "Spotify installed",
                        badText = "Spotify not found — install Spotify Music",
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!listenerOk) {
                        TextButton(onClick = { openNotificationListenerSettings(context) }) {
                            Text("Open notification access", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                        }
                    }
                    if (!notifOk) {
                        TextButton(onClick = onRequestPermissions) {
                            Text("Allow notifications", color = GrokifyColors.GlowAmber, fontSize = 13.sp)
                        }
                    }
                    if (enabled && !notifPosted) {
                        TextButton(onClick = {
                            setSpotifyControllerEnabled(appCtx, true)
                            notifPosted = isSpotifyControllerNotificationPosted(appCtx)
                        }) {
                            Text("Repost lockscreen widget", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                        }
                    }
                    TextButton(onClick = { openSpotifyApp(context) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = GrokifyColors.GlowMint,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (spotifyOk) "Open Spotify" else "Get Spotify",
                                color = GrokifyColors.GlowMint,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Native built-in — lockscreen controls stay in-process. Live AI DJ runs as a " +
                        "foreground service (no WebView), so it should not crash the host app.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(24.dp))
            }

            1 -> Column(Modifier.weight(1f, fill = true).fillMaxWidth()) {
                // Slim header — on/off + one-line status (settings/queue live in sub-tabs)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Live AI DJ",
                            color = GrokifyColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            if (djState.enabled || djStore.enabled) {
                                when {
                                    djState.transitioning -> "Transition…"
                                    djState.filling -> "Filling queue…"
                                    djState.chatBusy -> "Reading chat…"
                                    else -> {
                                        val base = djState.status.ifBlank { "On" }
                                        // Prefer structured countdown so leave/return stays correct.
                                        val cd = banterCountdownLabel(
                                            djState.songsSinceBanter,
                                            djState.banterEvery,
                                        )
                                        if (base.contains("talk in") || base.contains("banter")) base
                                        else "$base · $cd"
                                    }
                                }
                            } else {
                                "Off"
                            },
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (djState.queue.isNotEmpty()) {
                        Text(
                            "${djState.queue.size} queued",
                            color = GrokifyColors.GlowCyan,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Switch(
                        checked = djState.enabled || djStore.enabled,
                        onCheckedChange = { on ->
                            if (on && !loggedIn) {
                                tab = 3
                                authMsg = "Connect Spotify first (Account tab)"
                                return@Switch
                            }
                            if (on && !notifOk) onRequestPermissions()
                            setSpotifyLiveDjEnabled(appCtx, on)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GrokifyColors.Void,
                            checkedTrackColor = GrokifyColors.GlowMint,
                            uncheckedThumbColor = GrokifyColors.TextMuted,
                            uncheckedTrackColor = GrokifyColors.PanelSoft,
                        ),
                    )
                }
                if (!djState.error.isNullOrBlank()) {
                    Text(
                        djState.error!!,
                        color = GrokifyColors.GlowAmber,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Inner tabs: Chat · Queue · Settings
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GrokifyColors.PanelSoft)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    listOf("Chat", "Queue", "Settings").forEachIndexed { i, label ->
                        val selected = djSubTab == i
                        val badge = when (i) {
                            1 -> if (djState.queue.isNotEmpty()) " ${djState.queue.size}" else ""
                            else -> ""
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) GrokifyColors.Panel
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                .clickable { djSubTab = i }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label + badge,
                                color = if (selected) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                when (djSubTab) {
                    // ── Chat ──────────────────────────────────────────────
                    0 -> {
                        LaunchedEffect(djState.messages.size) {
                            if (djState.messages.isNotEmpty()) {
                                djChatListState.animateScrollToItem(djState.messages.lastIndex)
                            }
                        }
                        LazyColumn(
                            state = djChatListState,
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (djState.messages.isEmpty()) {
                                item {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(GrokifyColors.PanelSoft)
                                            .padding(14.dp),
                                    ) {
                                        Text(
                                            "DJ booth chat",
                                            color = GrokifyColors.TextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Turn on Live AI DJ. Tracks & banter show here. " +
                                                "Chat can new-queue, top-up, drop songs, or pull song/artist info. " +
                                                "Queue tab lists the radio set (not Spotify’s native queue).",
                                            color = GrokifyColors.TextDim,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                            items(djState.messages, key = { it.id }) { msg ->
                                DjChatBubble(
                                    msg = msg,
                                    djOn = djStore.enabled || djState.enabled,
                                    onPrev = { spotifyLiveDjPrevious(appCtx) },
                                    onPauseToggle = { spotifyLiveDjPauseToggle(appCtx) },
                                    onSkip = { spotifyLiveDjSkip(appCtx, forceTalk = false) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(GrokifyColors.Panel)
                                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            OutlinedTextField(
                                value = djChatDraft,
                                onValueChange = { djChatDraft = it },
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                placeholder = {
                                    Text(
                                        if (djStore.enabled || djState.enabled) {
                                            "Vibes, new queue, remove a song, artist info…"
                                        } else {
                                            "Turn on Live DJ to chat"
                                        },
                                        color = GrokifyColors.TextDim,
                                        fontSize = 13.sp,
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = GrokifyColors.TextPrimary,
                                    unfocusedTextColor = GrokifyColors.TextPrimary,
                                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                    cursorColor = GrokifyColors.GlowMint,
                                ),
                            )
                            IconButton(
                                onClick = {
                                    val t = djChatDraft.trim()
                                    if (t.isEmpty() || djState.chatBusy) return@IconButton
                                    djChatDraft = ""
                                    spotifyLiveDjChat(appCtx, t)
                                },
                                enabled = djChatDraft.isNotBlank() && !djState.chatBusy,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send to DJ",
                                    tint = if (djChatDraft.isNotBlank() && !djState.chatBusy) {
                                        GrokifyColors.GlowMint
                                    } else {
                                        GrokifyColors.TextDim
                                    },
                                )
                            }
                        }
                    }

                    // ── Queue ─────────────────────────────────────────────
                    1 -> Column(
                        Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "Live DJ UP NEXT is the set Spotify should play next. We mirror on " +
                                "queue changes, handoffs, and near track end if the Up Next head drifted — " +
                                "not on a mid-song timer (that thrashed Spotify’s queue). " +
                                "Chat adds, removes, refill, jumps, and skips force-mirror. " +
                                "If Spotify drifts to a ghost track, we reclaim the DJ next without wiping. " +
                                "Tap a title or ▶ to jump (drops songs above · no talk). " +
                                "Sync adopts whatever Spotify is on now (keeps UP NEXT). " +
                                "Mirror to Spotify force-aligns. Refill adds · New queue replaces.",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextButton(
                                onClick = { spotifyLiveDjSyncToSpotify(appCtx) },
                                enabled = djStore.enabled || djState.enabled,
                            ) {
                                Text("Sync to Spotify", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { spotifyLiveDjAddToSpotifyQueue(appCtx) },
                                enabled = (djStore.enabled || djState.enabled) &&
                                    djState.queue.isNotEmpty(),
                            ) {
                                Text("Mirror to Spotify", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { spotifyLiveDjNewQueue(appCtx) },
                                enabled = djStore.enabled || djState.enabled,
                            ) {
                                Text("New queue", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { spotifyLiveDjRefill(appCtx) },
                                enabled = djStore.enabled || djState.enabled,
                            ) {
                                Text("Refill", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { spotifyLiveDjSkip(appCtx, forceTalk = true) },
                                enabled = djStore.enabled || djState.enabled,
                            ) {
                                Text("Skip + talk", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (djState.nowLine.isNotBlank()) {
                            Text(
                                "NOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = GrokifyColors.GlowMint,
                            )
                            Text(
                                djState.nowLine,
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            "UP NEXT (${djState.queue.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.GlowCyan,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (djState.queue.isEmpty()) {
                            Text(
                                if (djState.enabled || djStore.enabled) {
                                    "Empty — New queue or Refill builds from liked · top · recent."
                                } else {
                                    "Turn on Live DJ to build a radio set."
                                },
                                color = GrokifyColors.TextMuted,
                                fontSize = 12.sp,
                            )
                        } else {
                            djState.queue.forEachIndexed { i, t ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (t.albumArtUrl.isNotBlank() || t.artistArtUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = t.artistArtUrl.ifBlank { t.albumArtUrl },
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .clickable(enabled = t.artistUri.isNotBlank()) {
                                                    openSpotifyContent(context, t.artistUri)
                                                },
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    } else {
                                        Text(
                                            "${i + 1}.",
                                            color = GrokifyColors.TextDim,
                                            fontSize = 12.sp,
                                            modifier = Modifier.width(28.dp),
                                        )
                                    }
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable(enabled = djStore.enabled || djState.enabled) {
                                                // Tap title row → play this cut (silent jump)
                                                spotifyLiveDjPlayFromQueue(appCtx, t.uri, i)
                                            },
                                    ) {
                                        Text(
                                            t.name.ifBlank { t.uri },
                                            color = GrokifyColors.TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (t.artists.isNotBlank() || t.reason.isNotBlank()) {
                                            Text(
                                                buildString {
                                                    if (t.artists.isNotBlank()) append(t.artists)
                                                    if (t.reason.isNotBlank()) {
                                                        if (isNotEmpty()) append(" · ")
                                                        append(t.reason)
                                                    }
                                                },
                                                color = GrokifyColors.TextDim,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.clickable(enabled = t.artistUri.isNotBlank()) {
                                                    openSpotifyContent(context, t.artistUri)
                                                },
                                            )
                                        }
                                    }
                                    // Open in Spotify (track / album)
                                    IconButton(
                                        onClick = {
                                            when {
                                                t.uri.isNotBlank() -> openSpotifyContent(context, t.uri)
                                                t.albumUri.isNotBlank() -> openSpotifyContent(context, t.albumUri)
                                                t.artistUri.isNotBlank() -> openSpotifyContent(context, t.artistUri)
                                            }
                                        },
                                        enabled = t.uri.isNotBlank() || t.albumUri.isNotBlank() || t.artistUri.isNotBlank(),
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.OpenInNew,
                                            contentDescription = "Open in Spotify",
                                            tint = GrokifyColors.TextDim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { spotifyLiveDjPlayFromQueue(appCtx, t.uri, i) },
                                        enabled = (djStore.enabled || djState.enabled) &&
                                            (t.uri.isNotBlank() || i >= 0),
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Play now (no talk)",
                                            tint = GrokifyColors.GlowMint,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    IconButton(
                                        onClick = { spotifyLiveDjRemoveFromQueue(appCtx, t.uri) },
                                        enabled = (djStore.enabled || djState.enabled) && t.uri.isNotBlank(),
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove from queue",
                                            tint = GrokifyColors.TextDim,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // ── Settings ──────────────────────────────────────────
                    else -> Column(
                        Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "AI rank next tracks",
                                    color = GrokifyColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Host Grok shapes the set (optional)",
                                    color = GrokifyColors.TextDim,
                                    fontSize = 10.sp,
                                )
                            }
                            Switch(
                                checked = useAiRank,
                                onCheckedChange = {
                                    useAiRank = it
                                    djStore.useAiRank = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = GrokifyColors.Void,
                                    checkedTrackColor = GrokifyColors.GlowViolet,
                                    uncheckedThumbColor = GrokifyColors.TextMuted,
                                    uncheckedTrackColor = GrokifyColors.PanelSoft,
                                ),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (hasXaiKey) "Grok Voice · xAI key found" else "Grok Voice · add xAI key or use device TTS",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        val voiceScroll = rememberScrollState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(voiceScroll),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GROK_VOICES.forEach { v ->
                                val selected = voiceId.equals(v.id, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        voiceId = v.id
                                        djStore.voiceId = v.id
                                        voicePreviewMsg = "${v.label} — ${v.tone}"
                                    },
                                    label = { Text(v.label, fontSize = 12.sp, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GrokifyColors.GlowMint.copy(alpha = 0.25f),
                                        selectedLabelColor = GrokifyColors.GlowMint,
                                        containerColor = GrokifyColors.PanelSoft,
                                        labelColor = GrokifyColors.TextPrimary,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = GrokifyColors.PanelBorder,
                                        selectedBorderColor = GrokifyColors.GlowMint,
                                    ),
                                )
                            }
                        }
                        if (!voicePreviewMsg.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(voicePreviewMsg!!, color = GrokifyColors.TextMuted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "BANTER FREQUENCY",
                            style = MaterialTheme.typography.labelSmall,
                            color = GrokifyColors.GlowViolet,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "How often the DJ talks between songs",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = banterMode == BanterFrequencyMode.Fixed,
                                onClick = {
                                    banterMode = BanterFrequencyMode.Fixed
                                    djStore.banterMode = BanterFrequencyMode.Fixed
                                    applyDjBanterSettings(appCtx)
                                },
                                label = { Text("Every N songs", fontSize = 12.sp, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = GrokifyColors.GlowCyan,
                                    containerColor = GrokifyColors.PanelSoft,
                                    labelColor = GrokifyColors.TextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = banterMode == BanterFrequencyMode.Fixed,
                                    borderColor = GrokifyColors.PanelBorder,
                                    selectedBorderColor = GrokifyColors.GlowCyan,
                                ),
                            )
                            FilterChip(
                                selected = banterMode == BanterFrequencyMode.Random,
                                onClick = {
                                    banterMode = BanterFrequencyMode.Random
                                    djStore.banterMode = BanterFrequencyMode.Random
                                    applyDjBanterSettings(appCtx)
                                },
                                label = { Text("Random range", fontSize = 12.sp, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = GrokifyColors.GlowCyan,
                                    containerColor = GrokifyColors.PanelSoft,
                                    labelColor = GrokifyColors.TextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = banterMode == BanterFrequencyMode.Random,
                                    borderColor = GrokifyColors.PanelBorder,
                                    selectedBorderColor = GrokifyColors.GlowCyan,
                                ),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        if (banterMode == BanterFrequencyMode.Fixed) {
                            BanterStepperRow(
                                label = "Talk every",
                                value = banterFixed,
                                suffix = "songs",
                                onDec = {
                                    banterFixed = (banterFixed - 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                    djStore.banterFixed = banterFixed
                                    applyDjBanterSettings(appCtx)
                                },
                                onInc = {
                                    banterFixed = (banterFixed + 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                    djStore.banterFixed = banterFixed
                                    applyDjBanterSettings(appCtx)
                                },
                            )
                        } else {
                            BanterStepperRow(
                                label = "Random min",
                                value = banterMin,
                                suffix = "songs",
                                onDec = {
                                    banterMin = (banterMin - 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                    djStore.banterMin = banterMin
                                    applyDjBanterSettings(appCtx)
                                },
                                onInc = {
                                    banterMin = (banterMin + 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                    if (banterMin > banterMax) {
                                        banterMax = banterMin
                                        djStore.banterMax = banterMax
                                    }
                                    djStore.banterMin = banterMin
                                    applyDjBanterSettings(appCtx)
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            BanterStepperRow(
                                label = "Random max",
                                value = banterMax,
                                suffix = "songs",
                                onDec = {
                                    banterMax = (banterMax - 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                    if (banterMax < banterMin) {
                                        banterMin = banterMax
                                        djStore.banterMin = banterMin
                                    }
                                    djStore.banterMax = banterMax
                                    applyDjBanterSettings(appCtx)
                                },
                                onInc = {
                                    banterMax = (banterMax + 1).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                                    djStore.banterMax = banterMax
                                    applyDjBanterSettings(appCtx)
                                },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Next line: ${
                                banterCountdownLabel(djState.songsSinceBanter, djState.banterEvery)
                            } (target every ${djState.banterEvery})",
                            color = GrokifyColors.TextMuted,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Allow talk over",
                                    color = GrokifyColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (allowTalkOver) {
                                        "Banter can ride the outro under the music"
                                    } else {
                                        "Music pauses so the line is exclusive"
                                    },
                                    color = GrokifyColors.TextDim,
                                    fontSize = 10.sp,
                                )
                            }
                            Switch(
                                checked = allowTalkOver,
                                onCheckedChange = {
                                    allowTalkOver = it
                                    djStore.allowTalkOver = it
                                    applyDjBanterSettings(appCtx)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = GrokifyColors.Void,
                                    checkedTrackColor = GrokifyColors.GlowMint,
                                    uncheckedThumbColor = GrokifyColors.TextMuted,
                                    uncheckedTrackColor = GrokifyColors.PanelSoft,
                                ),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Resume after restart",
                                    color = GrokifyColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (resumeAfterRestart) {
                                        "Keep Live DJ on across OTA, reboot, and process death"
                                    } else {
                                        "Live DJ ends when the app restarts (queue still kept)"
                                    },
                                    color = GrokifyColors.TextDim,
                                    fontSize = 10.sp,
                                )
                            }
                            Switch(
                                checked = resumeAfterRestart,
                                onCheckedChange = {
                                    resumeAfterRestart = it
                                    djStore.resumeAfterRestart = it
                                    SpotifyDjBus.patch { s -> s.copy(resumeAfterRestart = it) }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = GrokifyColors.Void,
                                    checkedTrackColor = GrokifyColors.GlowCyan,
                                    uncheckedThumbColor = GrokifyColors.TextMuted,
                                    uncheckedTrackColor = GrokifyColors.PanelSoft,
                                ),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Radio seeds from liked, top, and recently played (recent cuts are excluded so they are not re-queued). " +
                                "Queue, chat, and settings survive leave/return. " +
                                "With resume on, an active session continues after OTA/restart.",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            2 -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true)) {
                // Research & build new playlist
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "RESEARCH & BUILD",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Describe a set → Grok Build researches tracks → Build writes a private playlist on Spotify. " +
                            "Uses host device token (same as Chat).",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = researchPrompt,
                        onValueChange = { researchPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = {
                            Text(
                                "e.g. Late-night cyberpunk drive: dense synths, no vocals, 110–125 BPM…",
                                color = GrokifyColors.TextDim,
                                fontSize = 13.sp,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowViolet,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowViolet,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    val chipScroll = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        vibeChips.forEach { (fill, label) ->
                            FilterChip(
                                selected = researchPrompt == fill,
                                onClick = { researchPrompt = fill },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = GrokifyColors.GlowViolet.copy(alpha = 0.25f),
                                    selectedLabelColor = GrokifyColors.GlowViolet,
                                    containerColor = GrokifyColors.PanelSoft,
                                    labelColor = GrokifyColors.TextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = researchPrompt == fill,
                                    borderColor = GrokifyColors.PanelBorder,
                                    selectedBorderColor = GrokifyColors.GlowViolet,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && researchPrompt.isNotBlank(),
                            onClick = {
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Starting research…"
                                buildMsg = null
                                scope.launch {
                                    val (result, err) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.research(appCtx, researchPrompt) { step ->
                                            setOnMain { workStep = step }
                                        }
                                    }
                                    if (result != null) {
                                        lastResearch = result
                                        val lines = buildString {
                                            appendLine("▶ ${result.title}")
                                            if (result.description.isNotBlank()) appendLine(result.description)
                                            appendLine()
                                            if (result.rationale.isNotBlank()) appendLine(result.rationale)
                                            if (result.banter.isNotBlank()) {
                                                appendLine()
                                                appendLine("🎙 ${result.banter}")
                                            }
                                            appendLine()
                                            result.tracks.forEachIndexed { i, t ->
                                                appendLine(
                                                    "${i + 1}. ${t.query}" +
                                                        if (t.reason.isNotBlank()) " — ${t.reason}" else "",
                                                )
                                            }
                                        }
                                        researchOut = lines
                                        buildOk = true
                                        buildMsg = "Research ready — ${result.tracks.size} tracks. Tap Build playlist."
                                    } else {
                                        lastResearch = null
                                        researchOut = ""
                                        buildOk = false
                                        buildMsg = err ?: "Research failed"
                                    }
                                    workStep = null
                                    busy = false
                                }
                            },
                        ) {
                            Text("Research set", color = GrokifyColors.GlowViolet, fontSize = 13.sp)
                        }
                        TextButton(
                            enabled = !busy && lastResearch != null && loggedIn,
                            onClick = {
                                val data = lastResearch ?: return@TextButton
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Building playlist…"
                                buildMsg = null
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.build(appCtx, data) { step ->
                                            setOnMain { workStep = step }
                                        }
                                    }
                                    buildOk = outcome.ok
                                    buildMsg = outcome.message
                                    workStep = null
                                    busy = false
                                    if (outcome.ok) {
                                        // Refresh playlist list for edit section
                                        val (pls, _) = withContext(Dispatchers.IO) {
                                            SpotifyPlaylistAi.listPlaylists(appCtx)
                                        }
                                        playlists = pls
                                    }
                                }
                            },
                        ) {
                            Text("Build playlist", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                        }
                    }
                    if (!loggedIn) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Connect Spotify under Account to build playlists (research still works).",
                            color = GrokifyColors.GlowAmber,
                            fontSize = 11.sp,
                        )
                    }
                    if (workStep != null) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = GrokifyColors.GlowViolet,
                            trackColor = GrokifyColors.PanelSoft,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(workStep!!, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                    buildMsg?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            msg,
                            color = if (buildOk) GrokifyColors.GlowMint else GrokifyColors.GlowRose,
                            fontSize = 12.sp,
                        )
                    }
                    if (researchOut.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            researchOut,
                            color = GrokifyColors.TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GrokifyColors.PanelSoft)
                                .padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Edit existing playlist with prompt
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "EDIT PLAYLIST",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowCyan,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Pick a playlist, describe the change (more upbeat, swap ballads, add 5 hip-hop cuts…), " +
                            "then Research edit → Apply.",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && loggedIn,
                            onClick = {
                                if (busy) return@TextButton
                                busy = true
                                playlistLoadMsg = "Loading playlists…"
                                scope.launch {
                                    val (pls, err) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.listPlaylists(appCtx)
                                    }
                                    playlists = pls
                                    playlistLoadMsg = err
                                        ?: if (pls.isEmpty()) "No playlists found"
                                        else "${pls.size} playlists"
                                    if (selectedPlaylistId == null && pls.isNotEmpty()) {
                                        selectedPlaylistId = pls.first().id
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text("Load playlists", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                        }
                    }
                    playlistLoadMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = GrokifyColors.TextMuted, fontSize = 11.sp)
                    }
                    if (playlists.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val plScroll = rememberScrollState()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(plScroll),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            playlists.take(40).forEach { pl ->
                                val selected = selectedPlaylistId == pl.id
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedPlaylistId = pl.id
                                        lastEdit = null
                                        editOut = ""
                                    },
                                    label = {
                                        Text(
                                            pl.name.take(28) + if (pl.trackCount > 0) " (${pl.trackCount})" else "",
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GrokifyColors.GlowCyan.copy(alpha = 0.22f),
                                        selectedLabelColor = GrokifyColors.GlowCyan,
                                        containerColor = GrokifyColors.PanelSoft,
                                        labelColor = GrokifyColors.TextPrimary,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = GrokifyColors.PanelBorder,
                                        selectedBorderColor = GrokifyColors.GlowCyan,
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editPrompt,
                        onValueChange = { editPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = {
                            Text(
                                "e.g. Drop slow tracks, add 6 modern trap bangers, keep the vibe dark",
                                color = GrokifyColors.TextDim,
                                fontSize = 13.sp,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowCyan,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowCyan,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && loggedIn &&
                                !selectedPlaylistId.isNullOrBlank() && editPrompt.isNotBlank(),
                            onClick = {
                                val pid = selectedPlaylistId ?: return@TextButton
                                val pl = playlists.firstOrNull { it.id == pid } ?: return@TextButton
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Loading tracks…"
                                buildMsg = null
                                scope.launch {
                                    val (tracks, tErr) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.loadTracks(appCtx, pid)
                                    }
                                    if (tracks.isEmpty()) {
                                        buildOk = false
                                        buildMsg = tErr ?: "No tracks in playlist"
                                        workStep = null
                                        busy = false
                                        return@launch
                                    }
                                    workStep = "Planning edits…"
                                    val (plan, err) = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.researchEdit(
                                            appCtx,
                                            pl,
                                            tracks,
                                            editPrompt,
                                        ) { step -> setOnMain { workStep = step } }
                                    }
                                    if (plan != null) {
                                        lastEdit = plan
                                        editOut = buildString {
                                            if (plan.notes.isNotBlank()) {
                                                appendLine(plan.notes)
                                                appendLine()
                                            }
                                            if (plan.removeUris.isNotEmpty()) {
                                                appendLine("Remove ${plan.removeUris.size}:")
                                                plan.removeUris.take(12).forEach { appendLine("  − $it") }
                                                appendLine()
                                            }
                                            if (plan.addTracks.isNotEmpty()) {
                                                appendLine("Add ${plan.addTracks.size}:")
                                                plan.addTracks.forEachIndexed { i, t ->
                                                    appendLine(
                                                        "  + ${t.query}" +
                                                            if (t.reason.isNotBlank()) " — ${t.reason}" else "",
                                                    )
                                                }
                                            }
                                            plan.newName?.let { appendLine("\nRename → $it") }
                                            plan.newDescription?.let { appendLine("Desc → $it") }
                                        }
                                        buildOk = true
                                        buildMsg =
                                            "Edit plan ready: −${plan.removeUris.size} · +${plan.addTracks.size}. Tap Apply edit."
                                    } else {
                                        lastEdit = null
                                        editOut = ""
                                        buildOk = false
                                        buildMsg = err ?: "Edit research failed"
                                    }
                                    workStep = null
                                    busy = false
                                }
                            },
                        ) {
                            Text("Research edit", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                        }
                        TextButton(
                            enabled = !busy && lastEdit != null && !selectedPlaylistId.isNullOrBlank(),
                            onClick = {
                                val pid = selectedPlaylistId ?: return@TextButton
                                val plan = lastEdit ?: return@TextButton
                                if (busy) return@TextButton
                                busy = true
                                workStep = "Applying edits…"
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        SpotifyPlaylistAi.applyEdit(appCtx, pid, plan) { step ->
                                            setOnMain { workStep = step }
                                        }
                                    }
                                    buildOk = outcome.ok
                                    buildMsg = outcome.message
                                    if (outcome.ok) {
                                        lastEdit = null
                                        val (pls, _) = withContext(Dispatchers.IO) {
                                            SpotifyPlaylistAi.listPlaylists(appCtx)
                                        }
                                        playlists = pls
                                    }
                                    workStep = null
                                    busy = false
                                }
                            },
                        ) {
                            Text("Apply edit", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                        }
                    }
                    if (editOut.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            editOut,
                            color = GrokifyColors.TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GrokifyColors.PanelSoft)
                                .padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Research uses host Grok Build (Home device token). Build/Edit need Spotify login. " +
                        "Live DJ banter still uses xAI Voice or device TTS under Live DJ.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(24.dp))
            }

            else -> Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = true)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        "SPOTIFY API",
                        style = MaterialTheme.typography.labelSmall,
                        color = GrokifyColors.GlowViolet,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusLine(
                        ok = loggedIn,
                        okText = "Logged in — Live DJ can control playback",
                        badText = "Not logged in — save Client ID + Connect",
                    )
                    if (authMsg.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(authMsg, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Client ID", color = GrokifyColors.TextDim, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it.trim() },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("from developer.spotify.com", color = GrokifyColors.TextDim)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = GrokifyColors.TextPrimary,
                            unfocusedTextColor = GrokifyColors.TextPrimary,
                            focusedBorderColor = GrokifyColors.GlowMint,
                            unfocusedBorderColor = GrokifyColors.PanelBorder,
                            cursorColor = GrokifyColors.GlowMint,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Redirect URI (exact):\n${SpotifyOAuth.REDIRECT_URI}",
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (clientId.isNotBlank()) {
                                            HostApiKeyStore.save(
                                                appCtx,
                                                ApiKeyIds.SPOTIFY_CLIENT_ID,
                                                clientId,
                                                label = "Spotify Client ID",
                                            )
                                        }
                                        val raw = SpotifyOAuth.startLogin(appCtx)
                                        authMsg = runCatching {
                                            org.json.JSONObject(raw).optString("error")
                                                .ifBlank {
                                                    org.json.JSONObject(raw)
                                                        .optString("status", "opened")
                                                }
                                        }.getOrElse { SpotifyOAuth.lastAuthMessage.orEmpty() }
                                        if (authMsg == "opened" || authMsg.isBlank()) {
                                            authMsg = SpotifyOAuth.lastAuthMessage
                                                ?: "Browser opened for Spotify login"
                                        }
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text(
                                if (loggedIn) "Reconnect" else "Connect Spotify",
                                color = GrokifyColors.GlowMint,
                                fontSize = 13.sp,
                            )
                        }
                        if (loggedIn) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    SpotifyOAuth.logout(appCtx)
                                    loggedIn = false
                                    authMsg = "Logged out"
                                    setSpotifyLiveDjEnabled(appCtx, false)
                                },
                            ) {
                                Text("Logout", color = GrokifyColors.GlowAmber, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Create an app at developer.spotify.com → add the Redirect URI above → " +
                        "paste Client ID here → Connect. PKCE does not require Client Secret. " +
                        "Optional xAI key in host Settings for Grok Voice banter.",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DjChatBubble(
    msg: DjChatMessage,
    djOn: Boolean,
    onPrev: () -> Unit,
    onPauseToggle: () -> Unit,
    onSkip: () -> Unit,
) {
    when (msg.role) {
        DjChatRole.User -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Column(
                    Modifier
                        .fillMaxWidth(0.88f)
                        .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                        .background(GrokifyColors.UserBubble)
                        .border(1.dp, GrokifyColors.GlowBlue.copy(alpha = 0.25f), RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                        .padding(12.dp),
                ) {
                    Text("YOU", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowBlue)
                    Spacer(Modifier.height(4.dp))
                    Text(msg.text, color = GrokifyColors.TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        DjChatRole.Dj -> {
            Column(
                Modifier
                    .fillMaxWidth(0.94f)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(GrokifyColors.AssistantBubble)
                    .border(1.dp, GrokifyColors.GlowMint.copy(alpha = 0.22f), RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE DJ", style = MaterialTheme.typography.labelSmall, color = GrokifyColors.GlowMint)
                    if (msg.streaming) {
                        Spacer(Modifier.width(8.dp))
                        Text("…", color = GrokifyColors.TextMuted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (msg.text.isBlank() && msg.streaming) "…" else msg.text,
                    color = GrokifyColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
        DjChatRole.Track -> {
            val context = LocalContext.current
            val accent = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.PanelBorder
            // Smooth progress between Spotify polls (~2.5s) while the track is playing.
            var displayProgress by remember(msg.id, msg.progressMs) {
                mutableLongStateOf(msg.progressMs)
            }
            LaunchedEffect(msg.id, msg.progressMs, msg.isPlaying, msg.isNowPlaying, msg.durationMs) {
                displayProgress = msg.progressMs
                if (!msg.isNowPlaying || !msg.isPlaying || msg.durationMs <= 0L) return@LaunchedEffect
                while (true) {
                    delay(400)
                    displayProgress = (displayProgress + 400L).coerceAtMost(msg.durationMs)
                }
            }
            val progressFrac = if (msg.durationMs > 0L) {
                (displayProgress.toFloat() / msg.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val artists = msg.trackArtists.orEmpty().ifBlank {
                msg.text.lineSequence().drop(1).firstOrNull().orEmpty()
            }
            val trackTitle = msg.trackName ?: msg.text.lineSequence().firstOrNull().orEmpty()
            val thumbUrl = msg.artistArtUrl?.takeIf { it.isNotBlank() }
                ?: msg.albumArtUrl?.takeIf { it.isNotBlank() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        accent.copy(alpha = if (msg.isNowPlaying) 0.55f else 1f),
                        RoundedCornerShape(14.dp),
                    ),
            ) {
                // Song/album art fills the bubble
                if (!msg.albumArtUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = msg.albumArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            when {
                                !msg.albumArtUrl.isNullOrBlank() -> GrokifyColors.Void.copy(alpha = 0.78f)
                                msg.isNowPlaying -> GrokifyColors.GlowCyan.copy(alpha = 0.08f)
                                else -> GrokifyColors.Panel
                            },
                        ),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        // Artist portrait thumbnail (opens artist)
                        Box(
                            Modifier
                                .size(if (msg.isNowPlaying) 64.dp else 48.dp)
                                .clip(CircleShape)
                                .background(GrokifyColors.PanelSoft)
                                .clickable {
                                    when {
                                        !msg.artistUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.artistUri)
                                        !msg.albumUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.albumUri)
                                        !msg.trackUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.trackUri)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!thumbUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = "Artist",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.TextMuted,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (msg.isNowPlaying) {
                                    if (msg.isPlaying) "NOW PLAYING" else "PAUSED"
                                } else {
                                    "PLAYED"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.TextDim,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                trackTitle,
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    when {
                                        !msg.trackUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.trackUri)
                                        !msg.albumUri.isNullOrBlank() ->
                                            openSpotifyContent(context, msg.albumUri)
                                    }
                                },
                            )
                            if (artists.isNotBlank()) {
                                Text(
                                    artists,
                                    color = GrokifyColors.TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable {
                                        when {
                                            !msg.artistUri.isNullOrBlank() ->
                                                openSpotifyContent(context, msg.artistUri)
                                            !msg.albumUri.isNullOrBlank() ->
                                                openSpotifyContent(context, msg.albumUri)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // Song progression on the now-playing (and past tracks with known duration)
                    if (msg.isNowPlaying || msg.durationMs > 0L) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { if (msg.isNowPlaying) progressFrac else 1f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = if (msg.isNowPlaying) GrokifyColors.GlowCyan else GrokifyColors.TextDim,
                            trackColor = GrokifyColors.PanelSoft.copy(alpha = 0.9f),
                        )
                        if (msg.isNowPlaying && msg.durationMs > 0L) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatTrackClock(displayProgress),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    formatTrackClock(msg.durationMs),
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    // Transport on the latest (now-playing) bubble
                    if (msg.isNowPlaying && djOn) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPrev) {
                                Icon(
                                    Icons.Default.SkipPrevious,
                                    contentDescription = "Restart / previous",
                                    tint = GrokifyColors.TextPrimary,
                                )
                            }
                            IconButton(onClick = onPauseToggle) {
                                Icon(
                                    if (msg.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (msg.isPlaying) "Pause" else "Play",
                                    tint = GrokifyColors.GlowMint,
                                )
                            }
                            IconButton(onClick = onSkip) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Skip (countdown −1, no forced talk)",
                                    tint = GrokifyColors.TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
        DjChatRole.System -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    msg.text,
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GrokifyColors.PanelSoft)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BanterStepperRow(
    label: String,
    value: Int,
    suffix: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = GrokifyColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onDec,
                enabled = value > BANTER_EVERY_MIN,
            ) {
                Text("−", color = GrokifyColors.GlowCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "$value $suffix",
                color = GrokifyColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(88.dp),
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onInc,
                enabled = value < BANTER_EVERY_MAX,
            ) {
                Text("+", color = GrokifyColors.GlowCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatTrackClock(ms: Long): String {
    val total = (ms / 1000L).toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) GrokifyColors.GlowMint else GrokifyColors.GlowAmber),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (ok) okText else badText,
            color = if (ok) GrokifyColors.TextMuted else GrokifyColors.GlowAmber,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(if (accent) 64.dp else 52.dp)
                .clip(CircleShape)
                .background(
                    if (accent) GrokifyColors.GlowMint.copy(alpha = 0.18f)
                    else GrokifyColors.PanelSoft,
                )
                .border(
                    1.dp,
                    if (accent) GrokifyColors.GlowMint.copy(alpha = 0.5f)
                    else GrokifyColors.PanelBorder,
                    CircleShape,
                ),
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (accent) GrokifyColors.GlowMint else GrokifyColors.TextPrimary,
                modifier = Modifier.size(if (accent) 32.dp else 26.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = GrokifyColors.TextDim, fontSize = 11.sp)
    }
}
