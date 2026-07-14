package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.MainActivity
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.SpotifyOAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpotifyLiveDj"
const val SPOTIFY_DJ_NOTIF_ID = 47002

/** Process-talk openers the model sometimes leaks into banter TTS. */
private val META_NARRATION_OPENER = Regex(
    """(?i)^\s*(""" +
        """checking(\s+for)?|looking\s+up|searching(\s+for)?|fetching|researching|""" +
        """before\s+(i\s+|we\s+)?writ|let\s+me(\s+\w+){0,3}|i('ll| will)\s+(check|look|search|find|write|verify)|""" +
        """i('m| am)\s+(checking|looking|searching|about\s+to|going\s+to)|""" +
        """as\s+i\s+(check|look|search|write|research)|note\s+to\s+self|""" +
        """writing\s+the\s+(dj|spoken)|here'?s\s+my\s+(plan|process|draft)""" +
        """)\b""",
)

/** Phrases that mark a sentence as off-mic process narration, not radio copy. */
private val META_NARRATION_PHRASE = Regex(
    """(?i)(""" +
        """before\s+writing\s+the\s+(dj|spoken)\s+line|""" +
        """public\s+tidbit|""" +
        """writing\s+the\s+dj\s+line|""" +
        """checking\s+for\s+a\s+(real\s+)?(public\s+)?tidbit|""" +
        """\b(verify|verifying)\s+(that|this|the)\b|""" +
        """\b(tool|web\s*search|browsing)\b""" +
        """)""",
)

private const val PREFS = "spotify_live_dj"
private const val KEY_ENABLED = "enabled"
private const val KEY_VOICE = "voice_id"
private const val KEY_USE_AI = "use_ai_rank"
private const val KEY_CHAT = "chat_messages_v1"
private const val KEY_QUEUE = "queue_v1"
private const val KEY_PLAYED = "played_uris_v1"
private const val KEY_VIBE = "vibe_hint_v1"
private const val KEY_BANTER_EVERY = "banter_every_v1"
private const val KEY_SONGS_SINCE_BANTER = "songs_since_banter_v1"
private const val KEY_CURRENT_URI = "current_uri_v1"
private const val KEY_BANTER_MODE = "banter_mode_v1"
private const val KEY_BANTER_FIXED = "banter_fixed_v1"
private const val KEY_BANTER_MIN = "banter_min_v1"
private const val KEY_BANTER_MAX = "banter_max_v1"
private const val KEY_ALLOW_TALKOVER = "allow_talkover_v1"
/** Master switch: when false, never speak banter (Skip + talk becomes silent skip). */
private const val KEY_BANTER_ENABLED = "banter_enabled_v1"
/** When true, re-start Live DJ after process death / OTA / reboot if it was on. */
private const val KEY_RESUME_AFTER_RESTART = "resume_after_restart_v1"

/** Inclusive bounds for “talk every N songs” settings. */
const val BANTER_EVERY_MIN = 1
const val BANTER_EVERY_MAX = 20

/** How often the Live DJ speaks between tracks. */
enum class BanterFrequencyMode {
    /** Speak every fixed N completed songs. */
    Fixed,
    /** Speak after a random count in [min, max] each cycle. */
    Random,
    ;

    companion object {
        fun fromPref(raw: String?): BanterFrequencyMode =
            when (raw?.lowercase()) {
                "fixed", "manual", "every" -> Fixed
                else -> Random
            }
    }

    fun toPref(): String = when (this) {
        Fixed -> "fixed"
        Random -> "random"
    }
}

/** Max bubbles kept in memory + on disk. */
const val MAX_DJ_CHAT_MESSAGES = 100
/** Max upcoming tracks kept in the radio queue (persisted). */
const val MAX_DJ_QUEUE = 40

/** Built-in Grok Voice IDs for the Live DJ picker (xAI TTS). */
data class GrokVoice(
    val id: String,
    val label: String,
    val tone: String,
)

val GROK_VOICES: List<GrokVoice> = listOf(
    GrokVoice("eve", "Eve", "Energetic & upbeat"),
    GrokVoice("ara", "Ara", "Warm & friendly"),
    GrokVoice("leo", "Leo", "Authoritative & strong"),
    GrokVoice("rex", "Rex", "Confident & clear"),
    GrokVoice("sal", "Sal", "Smooth & balanced"),
    GrokVoice("carina", "Carina", "Soft, empathetic"),
    GrokVoice("helix", "Helix", "Bold, dynamic"),
    GrokVoice("orion", "Orion", "Cinematic, rich"),
    GrokVoice("luna", "Luna", "Gentle, nurturing"),
    GrokVoice("iris", "Iris", "Friendly, upbeat"),
    GrokVoice("sirius", "Sirius", "Playful, clever"),
    GrokVoice("atlas", "Atlas", "Commanding"),
)

/** One queued track for Live AIDJ. */
data class DjQueueTrack(
    val uri: String,
    val name: String = "",
    val artists: String = "",
    val reason: String = "",
    /** Spotify artist IDs (first = primary), for radio expansion. */
    val artistIds: List<String> = emptyList(),
    /** Best album cover URL when known. */
    val albumArtUrl: String = "",
    /** Primary artist image (Spotify CDN) when known. */
    val artistArtUrl: String = "",
    val albumUri: String = "",
    /** Primary artist URI (spotify:artist:…). */
    val artistUri: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        put("artists", artists)
        put("reason", reason)
        if (artistIds.isNotEmpty()) put("artistIds", JSONArray(artistIds))
        if (albumArtUrl.isNotBlank()) put("albumArtUrl", albumArtUrl)
        if (artistArtUrl.isNotBlank()) put("artistArtUrl", artistArtUrl)
        if (albumUri.isNotBlank()) put("albumUri", albumUri)
        if (artistUri.isNotBlank()) put("artistUri", artistUri)
    }

    companion object {
        fun fromJson(o: JSONObject): DjQueueTrack? {
            val uri = o.optString("uri", "").ifBlank { return null }
            val ids = ArrayList<String>()
            val arr = o.optJSONArray("artistIds")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "")
                    if (id.isNotBlank()) ids.add(id)
                }
            }
            return DjQueueTrack(
                uri = uri,
                name = o.optString("name", ""),
                artists = o.optString("artists", ""),
                reason = o.optString("reason", ""),
                artistIds = ids,
                albumArtUrl = o.optString("albumArtUrl", ""),
                artistArtUrl = o.optString("artistArtUrl", ""),
                albumUri = o.optString("albumUri", ""),
                artistUri = o.optString("artistUri", ""),
            )
        }
    }
}

/** Roles in the Live DJ chat timeline. */
enum class DjChatRole {
    /** You → DJ (suggestions, queue edits). */
    User,
    /** Spoken banter or text replies from the Live DJ AI. */
    Dj,
    /** A played / now-playing track (controls live on the latest). */
    Track,
    /** Quiet system notes (queue fill, errors). */
    System,
}

/**
 * One bubble in the Live DJ chat — chronological feed of plays, banter, and chat.
 * Last [MAX_DJ_CHAT_MESSAGES] are persisted across sessions.
 */
data class DjChatMessage(
    val id: String,
    val role: DjChatRole,
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    /** Present for Track bubbles. */
    val trackUri: String? = null,
    val trackName: String? = null,
    val trackArtists: String? = null,
    /** Spotify CDN album cover (largest available when captured). */
    val albumArtUrl: String? = null,
    /** Primary artist portrait for the thumbnail. */
    val artistArtUrl: String? = null,
    val albumUri: String? = null,
    val artistUri: String? = null,
    /** Playback position / length for Track bubbles (ms). */
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    /** True while this track is the active now-playing. */
    val isNowPlaying: Boolean = false,
    val isPlaying: Boolean = false,
    /** True while the DJ is thinking / speaking. */
    val streaming: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role.name)
        put("text", text)
        put("ts", ts)
        if (trackUri != null) put("trackUri", trackUri)
        if (trackName != null) put("trackName", trackName)
        if (trackArtists != null) put("trackArtists", trackArtists)
        if (!albumArtUrl.isNullOrBlank()) put("albumArtUrl", albumArtUrl)
        if (!artistArtUrl.isNullOrBlank()) put("artistArtUrl", artistArtUrl)
        if (!albumUri.isNullOrBlank()) put("albumUri", albumUri)
        if (!artistUri.isNullOrBlank()) put("artistUri", artistUri)
        if (durationMs > 0) put("durationMs", durationMs)
        // Don't persist live progress / playing flags as "now" — restored as history.
        put("isNowPlaying", false)
        put("isPlaying", false)
        put("streaming", false)
    }

    companion object {
        fun fromJson(o: JSONObject): DjChatMessage? {
            val id = o.optString("id", "").ifBlank { return null }
            val role = runCatching {
                DjChatRole.valueOf(o.optString("role", DjChatRole.System.name))
            }.getOrDefault(DjChatRole.System)
            return DjChatMessage(
                id = id,
                role = role,
                text = o.optString("text", ""),
                ts = o.optLong("ts", System.currentTimeMillis()),
                trackUri = o.optString("trackUri", "").ifBlank { null },
                trackName = o.optString("trackName", "").ifBlank { null },
                trackArtists = o.optString("trackArtists", "").ifBlank { null },
                albumArtUrl = o.optString("albumArtUrl", "").ifBlank { null },
                artistArtUrl = o.optString("artistArtUrl", "").ifBlank { null },
                albumUri = o.optString("albumUri", "").ifBlank { null },
                artistUri = o.optString("artistUri", "").ifBlank { null },
                progressMs = 0L,
                durationMs = o.optLong("durationMs", 0L),
                isNowPlaying = false,
                isPlaying = false,
                streaming = false,
            )
        }
    }
}

/** Snapshot shown in the Spotify hub Live DJ tab. */
data class SpotifyDjUiState(
    val enabled: Boolean = false,
    val status: String = "Off",
    val nowLine: String = "Nothing playing",
    val queue: List<DjQueueTrack> = emptyList(),
    val messages: List<DjChatMessage> = emptyList(),
    val chatBusy: Boolean = false,
    val transitioning: Boolean = false,
    val filling: Boolean = false,
    val loggedIn: Boolean = false,
    val error: String? = null,
    val voiceId: String = "eve",
    val useAiRank: Boolean = false,
    /** Songs completed since last spoken banter (persisted). */
    val songsSinceBanter: Int = 0,
    /** Current cycle target: speak after this many songs (persisted). */
    val banterEvery: Int = 4,
    /**
     * Tracks until the next banter line (0 = due / soon).
     * Derived from [songsSinceBanter] + [banterEvery] so leave/return stays correct.
     */
    val tracksUntilTalk: Int = 4,
    val banterMode: BanterFrequencyMode = BanterFrequencyMode.Random,
    val banterFixed: Int = 4,
    val banterMin: Int = 3,
    val banterMax: Int = 5,
    /** When true, banter may ride over the outro; when false, music pauses for the line. */
    val allowTalkOver: Boolean = true,
    /** Master banter switch — off = silent handoffs only (no TTS / banter bubbles). */
    val banterEnabled: Boolean = true,
    /**
     * When true (default), Live DJ restarts after app process death, OTA update, or reboot
     * if it was left on. When false, a restart ends the session (queue/settings still kept).
     */
    val resumeAfterRestart: Boolean = true,
)

/**
 * Persists Live DJ on/off, preferences, chat bubbles, and the radio queue.
 * Service is the source of runtime state; queue + chat survive leave/return & restarts.
 */
class SpotifyDjStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var voiceId: String
        get() = prefs.getString(KEY_VOICE, "eve")?.ifBlank { "eve" } ?: "eve"
        set(value) = prefs.edit().putString(KEY_VOICE, value.ifBlank { "eve" }).apply()

    var useAiRank: Boolean
        get() = prefs.getBoolean(KEY_USE_AI, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_AI, value).apply()

    var vibeHint: String
        get() = prefs.getString(KEY_VIBE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VIBE, value.take(400)).apply()

    var banterEvery: Int
        get() = prefs.getInt(KEY_BANTER_EVERY, 4).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
        set(value) = prefs.edit()
            .putInt(KEY_BANTER_EVERY, value.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX))
            .apply()

    var songsSinceBanter: Int
        get() = prefs.getInt(KEY_SONGS_SINCE_BANTER, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_SONGS_SINCE_BANTER, value.coerceAtLeast(0)).apply()

    var banterMode: BanterFrequencyMode
        get() = BanterFrequencyMode.fromPref(prefs.getString(KEY_BANTER_MODE, "random"))
        set(value) = prefs.edit().putString(KEY_BANTER_MODE, value.toPref()).apply()

    var banterFixed: Int
        get() = prefs.getInt(KEY_BANTER_FIXED, 4).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
        set(value) = prefs.edit()
            .putInt(KEY_BANTER_FIXED, value.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX))
            .apply()

    var banterMin: Int
        get() = prefs.getInt(KEY_BANTER_MIN, 3).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
        set(value) = prefs.edit()
            .putInt(KEY_BANTER_MIN, value.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX))
            .apply()

    var banterMax: Int
        get() = prefs.getInt(KEY_BANTER_MAX, 5).coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
        set(value) = prefs.edit()
            .putInt(KEY_BANTER_MAX, value.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX))
            .apply()

    var allowTalkOver: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_TALKOVER, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_TALKOVER, value).apply()

    /** When false, Live DJ never speaks banter (chat replies still work as text). */
    var banterEnabled: Boolean
        get() = prefs.getBoolean(KEY_BANTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BANTER_ENABLED, value).apply()

    /**
     * Resume Live DJ after process death / OTA / boot when [enabled] was true.
     * Default on so OTA updates don't strand an active set.
     */
    var resumeAfterRestart: Boolean
        get() = prefs.getBoolean(KEY_RESUME_AFTER_RESTART, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_AFTER_RESTART, value).apply()

    var lastCurrentUri: String
        get() = prefs.getString(KEY_CURRENT_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CURRENT_URI, value).apply()

    /** Normalized random range (lo ≤ hi), each clamped to [BANTER_EVERY_MIN, BANTER_EVERY_MAX]. */
    fun banterRange(): IntRange {
        val a = banterMin.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
        val b = banterMax.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return lo..hi
    }

    /**
     * Next cycle target from user settings (fixed N, or random in [min, max]).
     * Writes [banterEvery] and returns it.
     */
    fun rollNextBanterEvery(): Int {
        val next = when (banterMode) {
            BanterFrequencyMode.Fixed -> banterFixed.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
            BanterFrequencyMode.Random -> banterRange().random()
        }
        banterEvery = next
        return next
    }

    /**
     * After the user edits frequency settings: set the current-cycle target without
     * wiping [songsSinceBanter]. Fixed → N; random → keep target if still in range else re-roll.
     */
    fun syncBanterEveryFromSettings(): Int {
        val next = when (banterMode) {
            BanterFrequencyMode.Fixed -> banterFixed.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
            BanterFrequencyMode.Random -> {
                val range = banterRange()
                val cur = banterEvery
                if (cur in range) cur else range.random()
            }
        }
        banterEvery = next
        return next
    }

    fun loadMessages(): List<DjChatMessage> {
        val raw = prefs.getString(KEY_CHAT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    DjChatMessage.fromJson(o)?.let { add(it) }
                }
            }.takeLast(MAX_DJ_CHAT_MESSAGES)
        }.getOrElse {
            Log.w(TAG, "load chat: ${it.message}")
            emptyList()
        }
    }

    fun saveMessages(msgs: List<DjChatMessage>) {
        runCatching {
            val arr = JSONArray()
            msgs.takeLast(MAX_DJ_CHAT_MESSAGES).forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_CHAT, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "save chat: ${it.message}") }
    }

    fun loadQueue(): List<DjQueueTrack> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    DjQueueTrack.fromJson(o)?.let { add(it) }
                }
            }.take(MAX_DJ_QUEUE)
        }.getOrElse {
            Log.w(TAG, "load queue: ${it.message}")
            emptyList()
        }
    }

    fun saveQueue(tracks: List<DjQueueTrack>) {
        runCatching {
            val arr = JSONArray()
            tracks.take(MAX_DJ_QUEUE).forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "save queue: ${it.message}") }
    }

    fun clearQueue() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    fun loadPlayedUris(): Map<String, Long> {
        val raw = prefs.getString(KEY_PLAYED, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uri = o.optString("uri", "")
                    if (uri.isBlank()) continue
                    put(uri, o.optLong("ts", System.currentTimeMillis()))
                }
            }
        }.getOrElse {
            Log.w(TAG, "load played: ${it.message}")
            emptyMap()
        }
    }

    fun savePlayedUris(map: Map<String, Long>) {
        runCatching {
            val arr = JSONArray()
            map.entries.toList().takeLast(200).forEach { (uri, ts) ->
                arr.put(JSONObject().put("uri", uri).put("ts", ts))
            }
            prefs.edit().putString(KEY_PLAYED, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "save played: ${it.message}") }
    }
}

/** Human-readable banter countdown from persisted counters. */
fun banterCountdownLabel(songsSinceBanter: Int, banterEvery: Int): String {
    val left = tracksUntilTalk(songsSinceBanter, banterEvery)
    return if (left <= 1) "banter soon" else "talk in $left tracks"
}

/** Tracks remaining until banter (0 = next handoff talks). */
fun tracksUntilTalk(songsSinceBanter: Int, banterEvery: Int): Int {
    val every = banterEvery.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
    val since = songsSinceBanter.coerceAtLeast(0)
    return (every - since).coerceAtLeast(0)
}

/** Push banter prefs + countdown into the UI bus (and live service when running). */
fun applyDjBanterSettings(context: Context) {
    val appCtx = context.applicationContext
    val store = SpotifyDjStore(appCtx)
    val every = store.syncBanterEveryFromSettings()
    val since = store.songsSinceBanter
    val until = tracksUntilTalk(since, every)
    SpotifyDjBus.patch {
        it.copy(
            banterEvery = every,
            songsSinceBanter = since,
            tracksUntilTalk = until,
            banterMode = store.banterMode,
            banterFixed = store.banterFixed,
            banterMin = store.banterMin,
            banterMax = store.banterMax,
            allowTalkOver = store.allowTalkOver,
            banterEnabled = store.banterEnabled,
            status = when {
                !store.enabled -> it.status
                !store.banterEnabled -> {
                    val base = it.status
                        .substringBefore(" · talk in")
                        .substringBefore(" · banter")
                        .substringBefore(" · banter off")
                    if (base.isBlank() || base == it.status) {
                        "Watching playback · banter off"
                    } else {
                        "$base · banter off"
                    }
                }
                it.status.contains("talk in") || it.status.contains("banter") ||
                    it.status.startsWith("Watching") || it.status.startsWith("Paused") -> {
                    val base = it.status
                        .substringBefore(" · talk in")
                        .substringBefore(" · banter")
                    val cd = banterCountdownLabel(since, every)
                    if (base.isBlank() || base == it.status) {
                        if (store.enabled) "Watching playback · $cd" else it.status
                    } else {
                        "$base · $cd"
                    }
                }
                else -> it.status
            },
        )
    }
    if (store.enabled) {
        val i = Intent(appCtx, SpotifyLiveDjService::class.java)
            .setAction(SpotifyLiveDjService.ACTION_DJ_RELOAD_SETTINGS)
        try {
            ContextCompat.startForegroundService(appCtx, i)
        } catch (e: Exception) {
            Log.w(TAG, "reload banter settings: ${e.message}")
        }
    }
}

/**
 * Load last chat + queue from disk into the UI bus when empty (e.g. app open, DJ off).
 * Always restores banter countdown counters so leave/return keeps “talk in N tracks” honest.
 */
fun ensureDjChatHydrated(context: Context) {
    val store = SpotifyDjStore(context.applicationContext)
    val bus = SpotifyDjBus.state.value
    val msgs = if (bus.messages.isEmpty()) store.loadMessages() else bus.messages
    val q = if (bus.queue.isEmpty()) store.loadQueue() else bus.queue
    val songsSince = store.songsSinceBanter
    val every = store.banterEvery
    val until = tracksUntilTalk(songsSince, every)
    val countdown = banterCountdownLabel(songsSince, every)
    val qLabel = if (q.isNotEmpty()) " · ${q.size} queued" else ""
    val statusFromStore = when {
        !store.enabled -> {
            val base = if (bus.status.isBlank() || bus.status == "Off") {
                "Booth ready$qLabel"
            } else {
                bus.status
            }
            base
        }
        bus.enabled && bus.status.isNotBlank() &&
            (bus.status.contains("talk in") || bus.status.contains("banter")) -> bus.status
        store.enabled && !store.banterEnabled -> "Watching playback · banter off"
        store.enabled -> "Watching playback · $countdown"
        else -> bus.status
    }
    SpotifyDjBus.patch {
        it.copy(
            messages = if (it.messages.isEmpty()) msgs else it.messages,
            queue = if (it.queue.isEmpty()) q else it.queue,
            enabled = store.enabled || it.enabled,
            voiceId = store.voiceId,
            useAiRank = store.useAiRank,
            loggedIn = SpotifyOAuth.isLoggedIn(context.applicationContext),
            status = statusFromStore,
            songsSinceBanter = songsSince,
            banterEvery = every,
            tracksUntilTalk = until,
            banterMode = store.banterMode,
            banterFixed = store.banterFixed,
            banterMin = store.banterMin,
            banterMax = store.banterMax,
            allowTalkOver = store.allowTalkOver,
            banterEnabled = store.banterEnabled,
            resumeAfterRestart = store.resumeAfterRestart,
        )
    }
}

/**
 * Re-arm Live DJ after process start, OTA, or boot.
 *
 * - If [SpotifyDjStore.enabled] and [SpotifyDjStore.resumeAfterRestart]: start the service.
 * - If enabled but resume is off: clear [enabled] (session ended with the process) and keep
 *   queue / chat / settings on disk.
 *
 * Start failures no longer wipe [enabled] when resume is on — the next open can retry.
 */
fun maybeResumeLiveDj(context: Context) {
    val appCtx = context.applicationContext
    val store = SpotifyDjStore(appCtx)
    if (!store.enabled) return
    if (!store.resumeAfterRestart) {
        Log.i(TAG, "resume skipped — resumeAfterRestart=false; clearing enabled flag")
        store.enabled = false
        val prev = SpotifyDjBus.state.value
        val msgs = prev.messages.ifEmpty { store.loadMessages() }
        val q = prev.queue.ifEmpty { store.loadQueue() }
        SpotifyDjBus.publish(
            SpotifyDjUiState(
                enabled = false,
                status = "Off · ended with app restart",
                nowLine = "Live DJ not resumed",
                messages = msgs,
                queue = q,
                loggedIn = SpotifyOAuth.isLoggedIn(appCtx),
                voiceId = store.voiceId,
                useAiRank = store.useAiRank,
                songsSinceBanter = store.songsSinceBanter,
                banterEvery = store.banterEvery,
                tracksUntilTalk = tracksUntilTalk(store.songsSinceBanter, store.banterEvery),
                banterMode = store.banterMode,
                banterFixed = store.banterFixed,
                banterMin = store.banterMin,
                banterMax = store.banterMax,
                allowTalkOver = store.allowTalkOver,
                banterEnabled = store.banterEnabled,
                resumeAfterRestart = false,
            ),
        )
        return
    }
    Log.i(TAG, "resuming Live DJ after restart (queue=${store.loadQueue().size})")
    startLiveDjService(appCtx, fromResume = true)
}

/**
 * Start the Live DJ foreground service without flipping [SpotifyDjStore.enabled] to false
 * on transient FGS failures (common right after OTA / boot).
 */
private fun startLiveDjService(context: Context, fromResume: Boolean = false) {
    val appCtx = context.applicationContext
    val store = SpotifyDjStore(appCtx)
    val intent = Intent(appCtx, SpotifyLiveDjService::class.java)
    try {
        ContextCompat.startForegroundService(appCtx, intent)
        if (fromResume) {
            // Baseline UI until service publishes real state
            SpotifyDjBus.patch {
                it.copy(
                    enabled = true,
                    status = if (it.status.isBlank() || it.status == "Off") {
                        "Resuming after restart…"
                    } else {
                        it.status
                    },
                    resumeAfterRestart = store.resumeAfterRestart,
                    error = null,
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "start Live DJ failed: ${e.message}", e)
        // Keep [enabled] when user wants resume — next process/activity open can retry.
        // Only wipe when they opted out of resume (session mode).
        if (!store.resumeAfterRestart) {
            store.enabled = false
        }
        SpotifyDjBus.patch {
            it.copy(
                enabled = store.enabled,
                status = if (store.enabled) {
                    "Start deferred: ${e.message?.take(80) ?: "blocked"} — will retry"
                } else {
                    "Failed to start: ${e.message}"
                },
                error = e.message,
                resumeAfterRestart = store.resumeAfterRestart,
            )
        }
    }
}

/** Observable Live DJ state for Compose. */
object SpotifyDjBus {
    private val _state = MutableStateFlow(SpotifyDjUiState())
    val state: StateFlow<SpotifyDjUiState> = _state.asStateFlow()

    fun publish(s: SpotifyDjUiState) {
        _state.value = s
    }

    fun patch(block: (SpotifyDjUiState) -> SpotifyDjUiState) {
        _state.value = block(_state.value)
    }
}

fun setSpotifyLiveDjEnabled(context: Context, enabled: Boolean) {
    val appCtx = context.applicationContext
    val store = SpotifyDjStore(appCtx)
    store.enabled = enabled
    if (enabled) {
        startLiveDjService(appCtx, fromResume = false)
    } else {
        appCtx.stopService(Intent(appCtx, SpotifyLiveDjService::class.java))
        val prevMsgs = SpotifyDjBus.state.value.messages
        val finalMsgs = (prevMsgs.map { m ->
            if (m.role == DjChatRole.Track && m.isNowPlaying) {
                m.copy(isNowPlaying = false, isPlaying = false, progressMs = 0L)
            } else m
        } + DjChatMessage(
            id = "sys-stop-${System.currentTimeMillis()}",
            role = DjChatRole.System,
            text = "Auto-handoff off — booth stays open (chat, queue, play still work)",
        )).takeLast(MAX_DJ_CHAT_MESSAGES)
        store.saveMessages(finalMsgs)
        // Keep queue on disk so turning DJ back on resumes the set
        val keptQueue = store.loadQueue().ifEmpty { SpotifyDjBus.state.value.queue }
        if (keptQueue.isNotEmpty()) store.saveQueue(keptQueue)
        val qLabel = if (keptQueue.isNotEmpty()) " · ${keptQueue.size} queued" else ""
        SpotifyDjBus.publish(
            SpotifyDjUiState(
                enabled = false,
                status = "Booth ready$qLabel",
                nowLine = "Live DJ auto-handoff off — booth still works",
                messages = finalMsgs,
                queue = keptQueue,
                loggedIn = SpotifyOAuth.isLoggedIn(appCtx),
                voiceId = store.voiceId,
                useAiRank = store.useAiRank,
                songsSinceBanter = store.songsSinceBanter,
                banterEvery = store.banterEvery,
                tracksUntilTalk = tracksUntilTalk(store.songsSinceBanter, store.banterEvery),
                banterMode = store.banterMode,
                banterFixed = store.banterFixed,
                banterMin = store.banterMin,
                banterMax = store.banterMax,
                allowTalkOver = store.allowTalkOver,
                banterEnabled = store.banterEnabled,
                resumeAfterRestart = store.resumeAfterRestart,
            ),
        )
    }
}

/** Start a one-shot DJ service action (works with Live DJ auto-handoff off). */
private fun startDjServiceAction(context: Context, intent: Intent, tag: String) {
    try {
        ContextCompat.startForegroundService(context.applicationContext, intent)
    } catch (e: Exception) {
        Log.w(TAG, "$tag: ${e.message}")
    }
}


/**
 * Advance to the next Live DJ queue track.
 *
 * @param forceTalk when true (Skip + talk), always speak a banter line first.
 *   When false (plain skip / media next), only talk if the countdown is due
 *   or a line was already prefetched for the next track — otherwise just
 *   skip and decrement the banter countdown by one.
 */
fun spotifyLiveDjSkip(context: Context, forceTalk: Boolean = false) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_SKIP)
        .putExtra(SpotifyLiveDjService.EXTRA_FORCE_TALK, forceTalk)
    startDjServiceAction(appCtx, i, "skip")
}

fun spotifyLiveDjRefill(context: Context) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java).setAction(SpotifyLiveDjService.ACTION_DJ_REFILL)
    startDjServiceAction(appCtx, i, "refill")
}

/** Clear the radio queue and build a fresh set (unlike refill, which appends). */
fun spotifyLiveDjNewQueue(context: Context) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_NEW_QUEUE)
    startDjServiceAction(appCtx, i, "newQueue")
}

/** Remove a track from the Live DJ radio queue by Spotify URI. */
fun spotifyLiveDjRemoveFromQueue(context: Context, trackUri: String) {
    val appCtx = context.applicationContext
    val uri = trackUri.trim()
    if (uri.isBlank()) return
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_REMOVE_TRACK)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_URI, uri)
    startDjServiceAction(appCtx, i, "removeTrack")
}

/**
 * Jump to a queued track: discard everything ahead of it, play it immediately,
 * and skip DJ talk for this handoff.
 *
 * @param queueIndex 0-based index in the current UP NEXT list (preferred when set ≥ 0).
 */
fun spotifyLiveDjPlayFromQueue(context: Context, trackUri: String, queueIndex: Int = -1) {
    val appCtx = context.applicationContext
    val uri = trackUri.trim()
    if (uri.isBlank() && queueIndex < 0) return
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_PLAY_FROM_QUEUE)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_URI, uri)
        .putExtra(SpotifyLiveDjService.EXTRA_QUEUE_INDEX, queueIndex)
    startDjServiceAction(appCtx, i, "playFromQueue")
}

/**
 * Direct-play a track URI from chat history (or anywhere) without requiring Live DJ on.
 * Does not mutate the UP NEXT list.
 */
fun spotifyLiveDjPlayUri(
    context: Context,
    trackUri: String,
    name: String = "",
    artists: String = "",
    albumArtUrl: String = "",
    artistArtUrl: String = "",
    albumUri: String = "",
    artistUri: String = "",
) {
    val appCtx = context.applicationContext
    val uri = trackUri.trim()
    if (uri.isBlank()) return
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_PLAY_URI)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_URI, uri)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_NAME, name)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_ARTISTS, artists)
        .putExtra(SpotifyLiveDjService.EXTRA_ALBUM_ART, albumArtUrl)
        .putExtra(SpotifyLiveDjService.EXTRA_ARTIST_ART, artistArtUrl)
        .putExtra(SpotifyLiveDjService.EXTRA_ALBUM_URI, albumUri)
        .putExtra(SpotifyLiveDjService.EXTRA_ARTIST_URI, artistUri)
    startDjServiceAction(appCtx, i, "playUri")
}

fun spotifyLiveDjPauseToggle(context: Context) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_PAUSE_TOGGLE)
    startDjServiceAction(appCtx, i, "pause")
}

fun spotifyLiveDjPrevious(context: Context) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_PREVIOUS)
    startDjServiceAction(appCtx, i, "previous")
}

/**
 * Pull whatever Spotify is playing right now into Live DJ:
 * - If that cut is already in UP NEXT → drop songs ahead of it and keep the rest
 * - If not → adopt it and rebuild the radio queue from that seed
 * Does not force-play or skip Spotify’s current track.
 */
fun spotifyLiveDjSyncToSpotify(context: Context) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_SYNC_SPOTIFY)
    startDjServiceAction(appCtx, i, "syncToSpotify")
}

/**
 * Legacy “mirror to Spotify queue” entry point.
 * Live DJ is direct-play now — this only posts a status note (no Spotify Up Next writes).
 */
fun spotifyLiveDjAddToSpotifyQueue(context: Context) {
    val appCtx = context.applicationContext
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_ADD_TO_SPOTIFY_QUEUE)
    startDjServiceAction(appCtx, i, "addToSpotifyQueue")
}

/** Send a chat message to the Live DJ AI (queue suggestions / vibe changes). */
fun spotifyLiveDjChat(context: Context, text: String) {
    val appCtx = context.applicationContext
    val body = text.trim()
    if (body.isBlank()) return
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_CHAT)
        .putExtra(SpotifyLiveDjService.EXTRA_CHAT_TEXT, body)
    startDjServiceAction(appCtx, i, "chat")
}

/**
 * Native Live AI DJ — watches Spotify playback, keeps an in-app radio list from
 * liked / top / recent seeds, **direct-plays** each next cut (never writes Spotify’s
 * Up Next), and speaks short banter every few tracks via Grok Voice / device TTS.
 */
class SpotifyLiveDjService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val transitioning = AtomicBoolean(false)
    private val filling = AtomicBoolean(false)

    private val queue = ArrayDeque<DjQueueTrack>()
    private val playedUris = LinkedHashMap<String, Long>(64, 0.75f, true)
    private var current: DjQueueTrack? = null
    private var lastUri: String? = null
    private var nearEndArmed = false
    /**
     * URI we already launched a banter/stuck handoff for — prevents double-firing while
     * the same cut is still on the player (talkover waits for natural end inside transition).
     */
    private var handoffLaunchedForUri: String? = null
    private var wasPlaying = false
    /**
     * When true, Live DJ must not auto-speak or auto-advance.
     * Set on mid-track pause, user pause, or empty player after a pause / non-end drop.
     * Cleared when Spotify is playing again or the user explicitly skips/plays.
     */
    private var autoHandoffHeld = false
    /**
     * Optional one-shot banter from AI queue-shape (fill).
     * **Must** be paired with [pendingBanterForUri] — never speak it for a different next cut.
     */
    private var pendingBanter: String? = null
    private var pendingBanterForUri: String? = null
    /** Consecutive polls with nothing on Spotify player (leave/return / idle). */
    private var idlePolls = 0
    /** Track ended but Spotify still reports the item paused at the end. */
    private var stuckEndPolls = 0
    /** Avoid thrashing play API when device is missing. */
    private var lastPlayAttemptMs = 0L
    /**
     * Last known ms remaining on the current cut — used to speed the poll loop
     * before [nearEndArmed] so background handoffs are not missed.
     */
    private var lastRemainMs = 999_999L
    /**
     * URI we just told Spotify to play (via [playTrack]). External URI changes
     * that do not match this are user/autoplay influence — sync or recalibrate,
     * never force-play the next radio cut over what Spotify is already doing.
     *
     * Important: [playTrack] sets [lastUri] optimistically before Spotify's
     * currently-playing API catches up. For several polls the API still returns
     * the *previous* cut, which looks like an external change (new → old) and
     * used to spam "Spotify changed outside Live DJ" + duplicate track bubbles.
     * While [expectedPlayUri] is live we suppress those lag echoes.
     */
    private var expectedPlayUri: String? = null
    private var expectedPlayUntilMs = 0L
    /** URI that was current when we issued the last [playTrack] (for lag detection). */
    private var expectedPlayFromUri: String? = null
    /** Refresh partial wake lock periodically so long DJ sessions stay alive. */
    private var lastWakeRefreshMs = 0L

    /** Tracks completed since last spoken banter. */
    private var songsSinceBanter = 0
    /** Speak after this many songs (from settings: fixed or rolled random). */
    private var banterEvery = 4
    /** Skip + talk (or explicit chat force) forces a banter line. Plain skip does not. */
    private var forceBanter = false
    /** Soft rotation of radio seed modes (liked / top / recent / artist). */
    private var radioModeIdx = 0
    /** Prefetched spoken line for the upcoming handoff (keyed by next URI). */
    private var prefetchedBanter: String? = null
    private var prefetchedForUri: String? = null
    private val prefetchingBanter = AtomicBoolean(false)
    /** Cached research bullets for banter (keyed by next track URI). */
    private var researchedForUri: String? = null
    private var researchedFacts: List<String> = emptyList()

    /** In-memory chat timeline (also mirrored on SpotifyDjBus). */
    private val chatLog = ArrayList<DjChatMessage>(64)
    private val chatBusy = AtomicBoolean(false)
    /** Soft “vibe” notes from user chat — biased into pool picking. */
    private var vibeHint: String = ""
    private var lastChatTrackUri: String? = null
    /** In-process cache of artist id → portrait URL (Spotify CDN). */
    private val artistImageCache = HashMap<String, String>(64)
    /**
     * Legacy bookkeeping from queue-mirror mode (no longer written to Spotify Up Next).
     * Kept so older call sites compile; always empty in direct-play mode.
     */
    private val syncedToSpotifyUris = LinkedHashSet<String>()
    /** Last time we attempted a play (debounce adjacent calls). */
    private var lastSpotifyQueueSyncMs = 0L
    /** Unused in direct-play (was anti-thrash for hard context replace). */
    private var lastHardMirrorMs = 0L
    /** Unused in direct-play (was near-end Up Next head check). */
    private var lastQueueAlignCheckMs = 0L

    private lateinit var store: SpotifyDjStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SpotifyDjStore(this)
        // Restore chat + radio queue so leave/return and process restarts keep the set.
        synchronized(chatLog) {
            chatLog.clear()
            for (m in store.loadMessages()) {
                chatLog.add(
                    if (m.role == DjChatRole.Track && (m.isNowPlaying || m.isPlaying)) {
                        m.copy(isNowPlaying = false, isPlaying = false, progressMs = 0L)
                    } else m,
                )
            }
        }
        synchronized(queue) {
            queue.clear()
            store.loadQueue().forEach { queue.addLast(it) }
        }
        playedUris.clear()
        playedUris.putAll(store.loadPlayedUris())
        vibeHint = store.vibeHint
        // Ensure current-cycle target matches settings (first run may still have legacy 3–5).
        banterEvery = store.syncBanterEveryFromSettings()
        songsSinceBanter = store.songsSinceBanter
        val restoredUri = store.lastCurrentUri
        if (restoredUri.isNotBlank()) {
            lastUri = restoredUri
            lastChatTrackUri = restoredUri
        }
        publish(persist = false)
        acquireWakeLock()
        startAsForeground(
            if (queue.isNotEmpty()) "Resuming · ${queue.size} in queue…"
            else "Starting Live DJ…",
        )
        Log.i(TAG, "service created · chat=${chatLog.size} queue=${queue.size} played=${playedUris.size}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Session actions work with Live DJ auto-handoff off (booth mode).
        var sessionWork = false
        when (intent?.action) {
            ACTION_DJ_STOP -> {
                store.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DJ_RELOAD_SETTINGS -> {
                banterEvery = store.banterEvery.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
                songsSinceBanter = store.songsSinceBanter
                val status = when {
                    !store.enabled -> SpotifyDjBus.state.value.status.ifBlank { "Booth ready" }
                    !store.banterEnabled -> "Watching playback · banter off"
                    else -> "Watching playback · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
                }
                publish(status = status, persist = false)
            }
            ACTION_DJ_SKIP -> {
                // Only Skip + talk forces banter; plain skip follows the countdown.
                forceBanter = intent.getBooleanExtra(EXTRA_FORCE_TALK, false) && store.banterEnabled
                sessionWork = true
                scope.launch {
                    try {
                        runTransition("skip")
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_REFILL -> {
                sessionWork = true
                scope.launch(Dispatchers.IO) {
                    try {
                        fillQueue(useAi = store.useAiRank, force = true, replace = false)
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_NEW_QUEUE -> {
                sessionWork = true
                scope.launch(Dispatchers.IO) {
                    try {
                        fillQueue(useAi = store.useAiRank, force = true, replace = true)
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_REMOVE_TRACK -> {
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty()
                if (uri.isNotBlank()) {
                    sessionWork = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val n = removeTracksMatching(uri)
                            publish(
                                status = if (n > 0) "Removed $n from queue" else "Track not in queue",
                                clearError = n > 0,
                            )
                        } finally {
                            finishSessionIfNeeded()
                        }
                    }
                }
            }
            ACTION_DJ_PLAY_FROM_QUEUE -> {
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty()
                val index = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)
                if (uri.isNotBlank() || index >= 0) {
                    sessionWork = true
                    scope.launch {
                        try {
                            jumpToQueueTrack(uri, index)
                        } finally {
                            finishSessionIfNeeded()
                        }
                    }
                }
            }
            ACTION_DJ_PLAY_URI -> {
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty().trim()
                if (uri.isNotBlank()) {
                    sessionWork = true
                    val track = DjQueueTrack(
                        uri = uri,
                        name = intent.getStringExtra(EXTRA_TRACK_NAME).orEmpty(),
                        artists = intent.getStringExtra(EXTRA_TRACK_ARTISTS).orEmpty(),
                        albumArtUrl = intent.getStringExtra(EXTRA_ALBUM_ART).orEmpty(),
                        artistArtUrl = intent.getStringExtra(EXTRA_ARTIST_ART).orEmpty(),
                        albumUri = intent.getStringExtra(EXTRA_ALBUM_URI).orEmpty(),
                        artistUri = intent.getStringExtra(EXTRA_ARTIST_URI).orEmpty(),
                        reason = "from chat",
                    )
                    scope.launch(Dispatchers.IO) {
                        try {
                            val ok = playTrack(track)
                            publish(
                                status = if (ok) "Playing from history" else "Play failed — open Spotify on a device",
                                clearError = ok,
                                error = if (ok) null else "play_failed",
                            )
                            if (ok) {
                                appendChat(
                                    DjChatMessage(
                                        id = "sys-hist-${System.currentTimeMillis()}",
                                        role = DjChatRole.System,
                                        text = "Replayed ${track.name.ifBlank { track.uri }}" +
                                            if (track.artists.isNotBlank()) " — ${track.artists}" else "",
                                    ),
                                )
                            }
                        } finally {
                            finishSessionIfNeeded()
                        }
                    }
                }
            }
            ACTION_DJ_PAUSE_TOGGLE -> {
                sessionWork = true
                scope.launch(Dispatchers.IO) {
                    try {
                        togglePause()
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_PREVIOUS -> {
                sessionWork = true
                scope.launch(Dispatchers.IO) {
                    try {
                        restartOrPrevious()
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_SYNC_SPOTIFY -> {
                sessionWork = true
                scope.launch(Dispatchers.IO) {
                    try {
                        forceSyncToSpotify()
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_ADD_TO_SPOTIFY_QUEUE -> {
                sessionWork = true
                scope.launch(Dispatchers.IO) {
                    try {
                        pushQueueToSpotify()
                    } finally {
                        finishSessionIfNeeded()
                    }
                }
            }
            ACTION_DJ_CHAT -> {
                val text = intent.getStringExtra(EXTRA_CHAT_TEXT).orEmpty().trim()
                if (text.isNotBlank()) {
                    sessionWork = true
                    scope.launch {
                        try {
                            handleUserChat(text)
                        } finally {
                            finishSessionIfNeeded()
                        }
                    }
                }
            }
        }
        if (store.enabled) {
            startAsForeground(SpotifyDjBus.state.value.status.ifBlank { "Watching playback…" })
            if (loopJob?.isActive != true) {
                loopJob = scope.launch { runLoop() }
            }
            return START_STICKY
        }
        // Booth session: keep FGS alive only while a one-shot action runs.
        if (sessionWork) {
            startAsForeground(SpotifyDjBus.state.value.status.ifBlank { "DJ booth…" })
            return START_NOT_STICKY
        }
        stopSelf()
        return START_NOT_STICKY
    }

    /** Stop the FGS after a one-shot booth action when auto-handoff is off. */
    private fun finishSessionIfNeeded() {
        if (!store.enabled) {
            persistRuntimeState()
            val qSize = synchronized(queue) { queue.size }
            val label = if (qSize > 0) "Booth ready · $qSize queued" else "Booth ready"
            publish(status = label, transitioning = false, filling = false, chatBusy = false)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Freeze now-playing flags and flush chat + queue to disk.
        synchronized(chatLog) {
            for (i in chatLog.indices) {
                val m = chatLog[i]
                if (m.role == DjChatRole.Track && (m.isNowPlaying || m.isPlaying)) {
                    chatLog[i] = m.copy(isNowPlaying = false, isPlaying = false, progressMs = 0L)
                }
            }
            store.saveMessages(chatLog.toList())
        }
        persistRuntimeState()
        loopJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            // Always clear legacy DJ-only notif id.
            nm?.cancel(SPOTIFY_DJ_NOTIF_ID)
            // Only drop the shared controller slot if lockscreen widget is off.
            if (!SpotifyControllerStore(this).enabled) {
                nm?.cancel(SPOTIFY_CTRL_NOTIF_ID)
            }
        }
        Log.i(TAG, "service destroyed · queue=${queue.size}")
        super.onDestroy()
    }

    private suspend fun runLoop() {
        val restored = synchronized(queue) { queue.size }
        appendChat(
            DjChatMessage(
                id = "sys-start-${System.currentTimeMillis()}",
                role = DjChatRole.System,
                text = if (restored > 0) {
                    "Live DJ on — restored $restored track${if (restored == 1) "" else "s"} in the app list. " +
                        "Direct-play: we start each cut ourselves (Spotify’s Up Next is not used)."
                } else {
                    "Live DJ on — building a radio set from liked · top · recent. " +
                        "UP NEXT stays in the app; each song is played directly when its turn comes."
                },
            ),
        )
        publish(
            status = if (restored > 0) "Resumed · $restored in queue" else "Watching playback…",
            nowLine = "Connecting…",
        )
        // Only seed when the restored set is thin
        withContext(Dispatchers.IO) {
            if (queue.size < 4) {
                fillQueue(useAi = store.useAiRank, force = true)
            }
            // If Spotify is idle, kick the next track immediately (common after leave/return)
            kickIfIdle()
        }
        while (scope.isActive && store.enabled) {
            try {
                maybeRefreshWakeLock()
                if (!transitioning.get()) {
                    withContext(Dispatchers.IO) { pollOnce() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "poll error", e)
                publish(status = "Error: ${e.message}", error = e.message)
            }
            // Poll faster as the cut ends so background / Doze doesn't skip handoffs.
            // Silent near-end used to be only ~1.2s with a 2.5s tick — that missed almost
            // every natural end when the UI was not open, so the talk counter never moved.
            val remain = lastRemainMs
            val delayMs = when {
                transitioning.get() || nearEndArmed -> 700L
                idlePolls > 0 -> 1_000L
                remain in 0L..8_000L -> 700L
                remain in 0L..20_000L -> 1_000L
                remain in 0L..45_000L -> 1_500L
                else -> 2_200L
            }
            delay(delayMs)
        }
        stopSelf()
    }

    private fun holdAutoHandoff(reason: String) {
        if (!autoHandoffHeld) {
            Log.i(TAG, "auto-handoff held ($reason)")
        }
        autoHandoffHeld = true
        idlePolls = 0
        stuckEndPolls = 0
        nearEndArmed = false
    }

    private fun releaseAutoHandoff(reason: String) {
        if (autoHandoffHeld) {
            Log.i(TAG, "auto-handoff released ($reason)")
        }
        autoHandoffHeld = false
    }

    /**
     * On service start: only nudge a cut that is stuck finished (paused at ~0 remain).
     * Mid-track pause and empty player must not auto-speak / auto-play — that used to
     * fire after a long pause when Spotify cleared currently-playing.
     */
    private fun kickIfIdle() {
        if (transitioning.get() || queue.isEmpty() || autoHandoffHeld) return
        val res = spotifyGet("/v1/me/player/currently-playing")
        val data = res.json
        val hasItem = data != null && data.has("item") && !data.isNull("item")
        val playing = data?.optBoolean("is_playing", false) == true
        if (hasItem && playing) {
            releaseAutoHandoff("kick_playing")
            return
        }
        if (hasItem && !playing) {
            val item = data!!.optJSONObject("item")
            val duration = item?.optLong("duration_ms", 0L) ?: 0L
            val progress = data.optLong("progress_ms", 0L)
            val remain = if (duration > 0) duration - progress else 0L
            // Truly stuck at end — safe to advance once.
            if (duration > 0 && remain <= 2_500L) {
                scope.launch { runTransition("kick_idle") }
                return
            }
            // Mid-track / unknown pause — wait for the user to press play.
            holdAutoHandoff("kick_paused remain=${remain}ms")
            return
        }
        // Nothing on the player — do not invent a next track.
        holdAutoHandoff("kick_empty")
    }

    private fun pollOnce() {
        if (!SpotifyOAuth.isLoggedIn(this)) {
            publish(
                status = "Connect Spotify in the Account tab",
                error = "not_logged_in",
                loggedIn = false,
            )
            return
        }
        val res = spotifyGet("/v1/me/player/currently-playing")
        // 204 = nothing playing
        if (!res.ok && res.status != 204) {
            // 401/403 etc. — never auto-play through API errors while held / paused.
            publish(status = res.error ?: "Player poll failed", error = res.error)
            idlePolls++
            val mayAdvanceOnError = !autoHandoffHeld &&
                wasPlaying &&
                lastRemainMs <= 8_000L &&
                queue.isNotEmpty() &&
                !transitioning.get()
            if (mayAdvanceOnError && idlePolls >= 3) {
                idlePolls = 0
                scope.launch { runTransition("poll_error_advance") }
            }
            return
        }
        val data = res.json
        if (data == null || !data.has("item") || data.isNull("item")) {
            // Natural end: we were playing and remaining time was already low.
            // Long pause / session drop: Spotify clears currently-playing while mid-cut —
            // that must NOT banter + play next (was mis-read as idle_advance).
            val remainBeforeEmpty = lastRemainMs
            val likelyNaturalEnd = wasPlaying && remainBeforeEmpty <= 8_000L && !autoHandoffHeld
            if (!likelyNaturalEnd) {
                holdAutoHandoff(
                    if (autoHandoffHeld) "empty_while_held"
                    else "empty_not_near_end remainWas=${remainBeforeEmpty}ms",
                )
                wasPlaying = false
                idlePolls = 0
                lastRemainMs = 0L
                val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
                store.songsSinceBanter = songsSinceBanter
                publish(
                    nowLine = "Nothing playing — Live DJ waiting for play…",
                    status = if (queue.isEmpty()) {
                        "Paused · queue empty$banterHint"
                    } else {
                        "Paused · ${queue.size} queued$banterHint"
                    },
                )
                if (queue.isEmpty() && !filling.get()) {
                    scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
                }
                return
            }
            idlePolls++
            lastRemainMs = 0L
            // Between tracks currently-playing can flash empty briefly after a natural end.
            // Direct-play: only a short grace, then we start the next URI ourselves.
            val hadBeenPlaying = wasPlaying
            val banterDueNow = store.banterEnabled &&
                (forceBanter || songsSinceBanter + 1 >= banterEvery)
            val gracePolls = when {
                banterDueNow -> 1
                hadBeenPlaying -> 2
                else -> 2
            }
            publish(
                nowLine = if (idlePolls < gracePolls) {
                    "Track ended — starting next from app list…"
                } else {
                    "Track ended — next from app list…"
                },
            )
            val shouldAdvance = queue.isNotEmpty() &&
                !transitioning.get() &&
                idlePolls >= gracePolls
            if (shouldAdvance) {
                wasPlaying = false
                idlePolls = 0
                scope.launch {
                    runTransition(
                        when {
                            banterDueNow && hadBeenPlaying -> "ended"
                            else -> "stuck_end"
                        },
                    )
                }
                return
            }
            if (queue.isEmpty() && !filling.get()) {
                scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
            }
            val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
            store.songsSinceBanter = songsSinceBanter
            publish(
                status = if (queue.isEmpty()) {
                    "Queue empty — filling…$banterHint"
                } else {
                    "Idle · ${queue.size} queued$banterHint"
                },
            )
            return
        }

        idlePolls = 0
        val item = data.getJSONObject("item")
        val uri = item.optString("uri", "")
        val name = item.optString("name", "")
        val artists = artistsOf(item)
        val artistIds = artistIdsOf(item)
        val albumArt = albumArtUrlOf(item)
        val albumUri = albumUriOf(item)
        val artistUri = artistUriOf(item)
        val artistArt = artistArtUrlOf(artistIds.firstOrNull().orEmpty())
        val progress = data.optLong("progress_ms", 0L)
        val duration = item.optLong("duration_ms", 0L)
        val playing = data.optBoolean("is_playing", false)
        val remain = if (duration > 0) (duration - progress).coerceAtLeast(0L) else 999_999L
        val prevRemain = lastRemainMs

        // Resume / hold based on live transport state.
        if (playing) {
            releaseAutoHandoff("playing")
        } else if (duration > 0 && remain > 5_000L) {
            // User (or Spotify) paused mid-cut — freeze auto handoff + banter.
            holdAutoHandoff("mid_track_pause remain=${remain}ms")
        }

        val track = DjQueueTrack(
            uri = uri,
            name = name,
            artists = artists,
            artistIds = artistIds,
            albumArtUrl = albumArt,
            artistArtUrl = artistArt,
            albumUri = albumUri,
            artistUri = artistUri,
        )

        // Spotify changed tracks without our playTrack (user skip/pick, autoplay, native queue).
        // Old behavior always "reclaimed" by playing queue[0] — that skipped the expected cut
        // or overwrote a manual Spotify choice with something unrelated. Now: sync if the
        // new URI is already in UP NEXT, otherwise adopt + rebuild the radio queue.
        //
        // Race: playTrack sets lastUri to the *new* cut immediately, but currently-playing
        // still reports the previous cut for a poll or two. That must not look "external".
        if (uri.isNotBlank() && lastUri != null && uri != lastUri && !transitioning.get()) {
            val now = System.currentTimeMillis()
            val expecting = expectedPlayUri
            val withinExpect = expecting != null && now <= expectedPlayUntilMs
            when {
                withinExpect && uri == expecting -> {
                    expectedPlayUri = null
                    expectedPlayFromUri = null
                    Log.i(TAG, "ack our play → $uri")
                    // fall through — normal now-playing update
                }
                withinExpect -> {
                    // Still waiting for our commanded play to land.
                    // Typical: API echoes the previous cut (or a brief interstitial).
                    // Only treat as a real override if the unexpected track is clearly
                    // mid-song (user scrubbed / picked something else in Spotify).
                    val fromPrev = expectedPlayFromUri != null && uri == expectedPlayFromUri
                    val midTrackOverride =
                        !fromPrev &&
                            progress >= 8_000L &&
                            remain > 20_000L &&
                            duration > 45_000L
                    if (!midTrackOverride) {
                        Log.i(
                            TAG,
                            "await our play expected=$expecting saw=$uri " +
                                "fromPrev=$fromPrev progress=${progress}ms — suppress external",
                        )
                        // Do not rewrite lastUri / chat / queue while Spotify lags.
                        return
                    }
                    Log.i(
                        TAG,
                        "override during expected play $expecting → $uri (progress=${progress}ms)",
                    )
                    expectedPlayUri = null
                    expectedPlayFromUri = null
                    handleExternalTrackChange(track, playing, progress, duration, prevRemain)
                    return
                }
                else -> {
                    if (expecting != null) {
                        // Window expired without seeing our URI — clear and treat as external.
                        expectedPlayUri = null
                        expectedPlayFromUri = null
                    }
                    Log.i(
                        TAG,
                        "external track change $lastUri → $uri (prevRemain=${prevRemain}ms) — sync/recalibrate",
                    )
                    handleExternalTrackChange(track, playing, progress, duration, prevRemain)
                    return
                }
            }
        }
        // playTrack already set lastUri; when Spotify catches up uri == lastUri and we
        // never entered the branch above — still clear ownership so it cannot linger.
        if (expectedPlayUri != null && uri == expectedPlayUri) {
            expectedPlayUri = null
            expectedPlayFromUri = null
            Log.i(TAG, "ack our play (caught up) → $uri")
        }
        lastRemainMs = remain

        current = track
        if (uri.isNotBlank()) {
            markPlayed(uri)
            store.lastCurrentUri = uri
        }

        val line = buildString {
            append(if (playing) "▶ " else "⏸ ")
            append(name.ifBlank { uri })
            if (artists.isNotBlank()) append(" — ").append(artists)
            if (duration > 0) append(" · ").append(formatClock(progress)).append(" / ").append(formatClock(duration))
        }

        // New track → new chat bubble; same track → update now-playing + progress
        if (uri.isNotBlank() && uri != lastChatTrackUri) {
            lastChatTrackUri = uri
            postTrackMessage(track, playing = playing, progressMs = progress, durationMs = duration)
        } else if (uri.isNotBlank()) {
            updateNowPlayingPlayback(
                uri, playing, progress, duration, albumArt,
                artistArtUrl = artistArt,
                albumUri = albumUri,
                artistUri = artistUri,
            )
        }
        lastUri = uri
        publish(nowLine = line, clearError = true, loggedIn = true, persist = false)

        // Direct-play mode: we start the next cut ourselves (no Spotify Up Next).
        // Banter needs headroom for talkover; silent cuts fire in the last ~1.2s so
        // Spotify autoplay never steals the booth.
        val banterDue = store.banterEnabled &&
            (forceBanter || songsSinceBanter + 1 >= banterEvery)
        val peekUri = synchronized(queue) { queue.firstOrNull()?.uri }
        val banterPrefetched = banterDue &&
            peekUri != null &&
            prefetchedForUri == peekUri &&
            !prefetchedBanter.isNullOrBlank()
        // Fast poll near the end so background / Doze still catches handoffs.
        if (playing && duration > 15_000L && remain <= 8_000L) {
            nearEndArmed = true
        }
        // Banter: enter handoff with enough headroom to land speech on the true outro.
        val banterThreshold = when {
            banterDue && banterPrefetched -> 12_000L
            banterDue -> 14_000L
            else -> 0L
        }
        if (
            !autoHandoffHeld &&
            banterDue &&
            banterThreshold > 0L &&
            playing &&
            duration > 15_000L &&
            remain <= banterThreshold &&
            handoffLaunchedForUri != uri &&
            !transitioning.get()
        ) {
            handoffLaunchedForUri = uri
            nearEndArmed = true
            Log.i(
                TAG,
                "banter near_end armed remain=${remain}ms prefetched=$banterPrefetched " +
                    "since=$songsSinceBanter every=$banterEvery",
            )
            scope.launch { runTransition("near_end") }
            return
        }
        // Silent: direct-play the next cut in the last second (Spotify has no queue from us).
        if (
            !autoHandoffHeld &&
            !banterDue &&
            playing &&
            duration > 15_000L &&
            remain <= 1_200L &&
            queue.isNotEmpty() &&
            handoffLaunchedForUri != uri &&
            !transitioning.get()
        ) {
            handoffLaunchedForUri = uri
            nearEndArmed = true
            Log.i(TAG, "silent near_end direct-play remain=${remain}ms")
            scope.launch { runTransition("near_end_direct") }
            return
        }
        // Stuck at end: same URI paused near 0 — start next ourselves.
        // Never while auto-handoff is held (user pause / empty session).
        if (!autoHandoffHeld && !playing && duration > 0 && remain <= 2_500L && queue.isNotEmpty()) {
            stuckEndPolls++
            val canNudge = handoffLaunchedForUri != uri && !transitioning.get()
            if (canNudge && !banterDue && (wasPlaying || stuckEndPolls >= 2)) {
                stuckEndPolls = 0
                handoffLaunchedForUri = uri
                nearEndArmed = true
                scope.launch { runTransition("stuck_end") }
                return
            }
            if (canNudge && banterDue && stuckEndPolls >= 3) {
                stuckEndPolls = 0
                handoffLaunchedForUri = uri
                nearEndArmed = true
                scope.launch { runTransition("stopped_at_end") }
                return
            }
        } else if (playing || remain > 5_000L) {
            stuckEndPolls = 0
        }

        wasPlaying = playing
        if (queue.size < 3 && !filling.get()) {
            scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
        }
        // Prefetch AI banter early — research (news/shows/facts) needs tool time.
        // Start while plenty of track remains so handoff never blocks on live research.
        // Skip while paused so we don't burn AI after a long hold then suddenly speak.
        if (!autoHandoffHeld && playing && banterDue && remain in 18_000L..240_000L) {
            val peek = synchronized(queue) { queue.firstOrNull() }
            if (peek != null && prefetchedForUri != peek.uri && !prefetchingBanter.get()) {
                val prevSnap = current
                scope.launch(Dispatchers.IO) { prefetchBanter(prevSnap, peek) }
            }
        }
        // Flush banter counters every poll so leave/return always sees the true countdown.
        store.songsSinceBanter = songsSinceBanter
        store.banterEvery = banterEvery
        val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
        publish(
            status = when {
                playing -> "Watching playback$banterHint"
                autoHandoffHeld -> "Paused · waiting for play$banterHint · ${queue.size} queued"
                else -> "Paused$banterHint · ${queue.size} queued"
            },
        )
        updateNotif(line)
    }

    /** Drop prefetched / pending banter when it no longer matches the queue head. */
    private fun invalidateStaleBanterCaches(expectedNextUri: String?) {
        val next = expectedNextUri?.takeIf { it.isNotBlank() }
        if (prefetchedForUri != null && prefetchedForUri != next) {
            Log.i(TAG, "drop prefetched banter (was for ${prefetchedForUri}, head=$next)")
            prefetchedBanter = null
            prefetchedForUri = null
        }
        if (pendingBanterForUri != null && pendingBanterForUri != next) {
            Log.i(TAG, "drop pending banter (was for $pendingBanterForUri, head=$next)")
            pendingBanter = null
            pendingBanterForUri = null
        }
        if (researchedForUri != null && researchedForUri != next) {
            researchedForUri = null
            researchedFacts = emptyList()
        }
    }

    /**
     * Direct-play mode: Spotify’s Up Next is never written.
     * Kept as a no-op so older call sites stay harmless.
     */
    private fun ensureDjQueueMirroredOnSpotify(force: Boolean, quiet: Boolean = true): Boolean = true

    private fun prefetchBanter(prev: DjQueueTrack?, next: DjQueueTrack) {
        if (!prefetchingBanter.compareAndSet(false, true)) return
        try {
            if (prefetchedForUri == next.uri && !prefetchedBanter.isNullOrBlank()) return
            publish(status = "🔍 Researching ${primaryArtist(next.artists).ifBlank { next.name }}…")
            // Research is allowed only here — never block the near-end handoff on tools.
            val line = generateBanter(prev, next, allowResearch = true)
            if (line.isNotBlank()) {
                prefetchedBanter = line
                prefetchedForUri = next.uri
                Log.i(TAG, "prefetched banter for ${next.uri}: ${line.take(80)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "prefetch banter: ${e.message}")
        } finally {
            prefetchingBanter.set(false)
        }
    }

    /** Rough on-air duration for a banter line (~150 wpm + padding). */
    private fun estimateSpeechMs(line: String): Long {
        val words = line.trim().split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
        return (words * 400L + 500L).coerceIn(2_500L, 18_000L)
    }

    private data class PlaybackSnap(
        val uri: String,
        val playing: Boolean,
        val progressMs: Long,
        val durationMs: Long,
    ) {
        val remainMs: Long
            get() = if (durationMs > 0L) (durationMs - progressMs).coerceAtLeast(0L) else 0L
    }

    private fun readPlaybackSnap(): PlaybackSnap? {
        val res = spotifyGet("/v1/me/player/currently-playing")
        val data = res.json ?: return null
        if (!data.has("item") || data.isNull("item")) return null
        val item = data.optJSONObject("item") ?: return null
        return PlaybackSnap(
            uri = item.optString("uri", ""),
            playing = data.optBoolean("is_playing", false),
            progressMs = data.optLong("progress_ms", 0L),
            durationMs = item.optLong("duration_ms", 0L),
        )
    }

    /**
     * Wait until the current cut has ≤ [targetRemainMs] left (or already ended / changed).
     * Used so talkover banter lands on the real outro instead of mid-song after fast prep.
     */
    private suspend fun waitUntilRemainAbout(
        expectedUri: String?,
        targetRemainMs: Long,
        maxWaitMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val snap = withContext(Dispatchers.IO) { readPlaybackSnap() }
            if (snap == null) return // nothing playing — handoff now
            if (expectedUri != null && snap.uri.isNotBlank() && snap.uri != expectedUri) {
                return // already moved on
            }
            // Paused / stopped (any remain) — do not spin; speech + next track must proceed.
            if (!snap.playing) return
            if (snap.durationMs > 0L && snap.remainMs <= targetRemainMs) return
            lastRemainMs = snap.remainMs
            delay(450L)
        }
    }

    /**
     * Wait for Spotify to leave [fromUri] on its own (native Up Next), or time out.
     * On success, adopts the new cut into Live DJ state and pops matching UP NEXT rows.
     */
    private suspend fun awaitNativeAdvance(
        fromUri: String?,
        expectedNext: DjQueueTrack?,
        maxWaitMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val snap = withContext(Dispatchers.IO) { readPlaybackSnap() }
            if (snap != null && snap.uri.isNotBlank()) {
                val moved = fromUri.isNullOrBlank() || snap.uri != fromUri
                if (moved) {
                    adoptNativeAdvance(snap.uri, expectedNext)
                    return true
                }
                // Still on same cut but essentially finished — keep waiting a bit
                if (!snap.playing && snap.remainMs <= 1_500L) {
                    delay(400L)
                    continue
                }
            } else {
                // Empty player mid-handoff is common; keep waiting for Up Next to land
                delay(400L)
                continue
            }
            delay(450L)
        }
        return false
    }

    /**
     * After Spotify advanced (or we skipped), align AI queue + now-playing with [newUri].
     */
    private fun adoptNativeAdvance(newUri: String, expectedNext: DjQueueTrack?) {
        val res = spotifyGet("/v1/me/player/currently-playing")
        val data = res.json
        val item = data?.optJSONObject("item")
        val uri = item?.optString("uri", "").orEmpty().ifBlank { newUri }
        val progress = data?.optLong("progress_ms", 0L) ?: 0L
        val duration = item?.optLong("duration_ms", 0L) ?: 0L
        val playing = data?.optBoolean("is_playing", false) == true
        val artistIds = if (item != null) artistIdsOf(item) else emptyList()
        val track = if (item != null) {
            DjQueueTrack(
                uri = uri,
                name = item.optString("name", expectedNext?.name.orEmpty()),
                artists = artistsOf(item).ifBlank { expectedNext?.artists.orEmpty() },
                artistIds = artistIds,
                albumArtUrl = albumArtUrlOf(item).ifBlank { expectedNext?.albumArtUrl.orEmpty() },
                artistArtUrl = artistArtUrlOf(artistIds.firstOrNull().orEmpty())
                    .ifBlank { expectedNext?.artistArtUrl.orEmpty() },
                albumUri = albumUriOf(item).ifBlank { expectedNext?.albumUri.orEmpty() },
                artistUri = artistUriOf(item).ifBlank { expectedNext?.artistUri.orEmpty() },
                reason = expectedNext?.reason.orEmpty(),
            )
        } else {
            expectedNext?.copy(uri = uri) ?: DjQueueTrack(uri = uri, name = uri)
        }

        // Drop matching row (and anything ahead) from AI UP NEXT; mark skipped-ahead as played
        // so fillQueue will not re-queue songs the user already jumped past.
        synchronized(queue) {
            val idx = queue.indexOfFirst { it.uri == uri }
            if (idx >= 0) {
                repeat(idx) {
                    if (queue.isNotEmpty()) markPlayed(queue.removeFirst().uri)
                }
                if (queue.isNotEmpty() && queue.first().uri == uri) {
                    markPlayed(queue.removeFirst().uri)
                }
            } else if (expectedNext != null && queue.firstOrNull()?.uri == expectedNext.uri) {
                // Spotify played our expected next under a laggy URI read — still consume it
                if (uri == expectedNext.uri || uri.isBlank()) {
                    markPlayed(queue.removeFirst().uri)
                }
            }
        }
        syncedToSpotifyUris.remove(uri)
        expectedPlayUri = null
        expectedPlayFromUri = null
        handoffLaunchedForUri = null
        nearEndArmed = false
        stuckEndPolls = 0
        idlePolls = 0
        lastRemainMs = if (duration > 0) (duration - progress).coerceAtLeast(0L) else 999_999L
        adoptExternalCurrent(track, playing, progress, duration)
        persistRuntimeState()
        Log.i(TAG, "native advance adopted uri=$uri q=${queue.size}")
    }

    /** Pop [next] (and anything ahead of it) from the AI queue; mark discarded as played. */
    private fun takeNextFromQueue(next: DjQueueTrack): DjQueueTrack? {
        return synchronized(queue) {
            when {
                queue.firstOrNull()?.uri == next.uri -> queue.removeFirst()
                else -> {
                    val idx = queue.indexOfFirst { it.uri == next.uri }
                    if (idx >= 0) {
                        repeat(idx) {
                            if (queue.isNotEmpty()) {
                                markPlayed(queue.removeFirst().uri)
                            }
                        }
                        queue.removeFirstOrNull()
                    } else null
                }
            }
        }
    }

    /**
     * Advance to [next] by **direct play** (single URI).
     * Spotify’s Up Next / skip / queue APIs are not used.
     *
     * [preferSkip] is ignored (kept for call-site compatibility).
     */
    private suspend fun advanceToNext(
        next: DjQueueTrack?,
        prevUri: String?,
        preferSkip: Boolean,
        allowPlayFallback: Boolean,
    ): Boolean {
        if (next == null) return false

        // Already on our intended next (rare autoplay coincidence / lag after our play)?
        val early = withContext(Dispatchers.IO) { readPlaybackSnap() }
        if (early != null && early.uri.isNotBlank() && early.uri == next.uri) {
            adoptNativeAdvance(early.uri, next)
            return true
        }

        if (!allowPlayFallback) return false

        val taken = takeNextFromQueue(next)
        val play = taken ?: next
        return playTrack(play)
    }

    /** No-op — direct-play never writes Spotify Up Next. */
    private fun ensureTrackInSpotifyQueue(uri: String) = Unit

    /** No-op — direct-play never replaces multi-URI context. */
    private fun mirrorContextToSpotify(
        headUri: String?,
        upcoming: List<DjQueueTrack>,
        positionMs: Long? = null,
        quiet: Boolean = true,
    ): Boolean {
        if (!quiet) {
            publish(
                status = "Direct-play mode — Spotify Up Next is not used",
                clearError = true,
            )
        }
        return true
    }

    /** No-op — app UP NEXT is display/planning only. */
    private fun syncQueueToSpotify(quiet: Boolean = false, forceMirror: Boolean = false) {
        if (!quiet) {
            val n = synchronized(queue) { queue.size }
            publish(
                status = "Direct-play · $n in app list (Spotify queue unused)",
                clearError = true,
            )
        }
    }

    /**
     * User tapped **Sync to Spotify** on the Queue tab.
     * Reads currently-playing and aligns Live DJ (queue + now line) without forcing a play.
     */
    private fun forceSyncToSpotify() {
        if (transitioning.get()) {
            publish(status = "Busy transitioning — try Sync again in a moment")
            return
        }
        if (!SpotifyOAuth.isLoggedIn(this)) {
            publish(
                status = "Connect Spotify in the Account tab",
                error = "not_logged_in",
                loggedIn = false,
            )
            return
        }
        publish(status = "Syncing to Spotify…", clearError = true, loggedIn = true)
        val res = spotifyGet("/v1/me/player/currently-playing")
        if (!res.ok && res.status != 204) {
            publish(status = res.error ?: "Sync failed", error = res.error)
            return
        }
        val data = res.json
        if (data == null || !data.has("item") || data.isNull("item")) {
            publish(status = "Nothing playing on Spotify — start a song, then Sync")
            appendChat(
                DjChatMessage(
                    id = "sys-sync-empty-${System.currentTimeMillis()}",
                    role = DjChatRole.System,
                    text = "Sync: nothing active on Spotify.",
                ),
            )
            return
        }
        val item = data.getJSONObject("item")
        val uri = item.optString("uri", "")
        if (uri.isBlank()) {
            publish(status = "Spotify item has no track URI")
            return
        }
        val progress = data.optLong("progress_ms", 0L)
        val duration = item.optLong("duration_ms", 0L)
        val playing = data.optBoolean("is_playing", false)
        val artistIds = artistIdsOf(item)
        val track = DjQueueTrack(
            uri = uri,
            name = item.optString("name", ""),
            artists = artistsOf(item),
            artistIds = artistIds,
            albumArtUrl = albumArtUrlOf(item),
            artistArtUrl = artistArtUrlOf(artistIds.firstOrNull().orEmpty()),
            albumUri = albumUriOf(item),
            artistUri = artistUriOf(item),
        )
        val alreadyOn = lastUri != null && uri == lastUri
        handleExternalTrackChange(
            track = track,
            playing = playing,
            progressMs = progress,
            durationMs = duration,
            prevRemainMs = lastRemainMs,
            countTowardBanter = !alreadyOn,
            userInitiated = true,
        )
    }

    /**
     * Spotify is on a track we did not start via [playTrack] (or user tapped Sync).
     *
     * - URI already in the radio queue → adopt it (drop everything ahead + itself).
     * - URI not in the queue (autoplay / user pick) → reclaim by **direct-playing**
     *   our next cut (never wipe the app set).
     * - Manual Sync on a foreign cut → adopt as now-playing, keep app UP NEXT.
     * - Same URI already adopted → re-align pointer only.
     */
    private fun handleExternalTrackChange(
        track: DjQueueTrack,
        playing: Boolean,
        progressMs: Long,
        durationMs: Long,
        prevRemainMs: Long,
        countTowardBanter: Boolean = true,
        userInitiated: Boolean = false,
    ) {
        if (transitioning.get()) return
        val uri = track.uri
        if (uri.isBlank()) return

        nearEndArmed = false
        handoffLaunchedForUri = null
        prefetchedBanter = null
        prefetchedForUri = null
        pendingBanter = null
        pendingBanterForUri = null
        researchedForUri = null
        researchedFacts = emptyList()
        expectedPlayUri = null
        expectedPlayFromUri = null
        stuckEndPolls = 0
        idlePolls = 0
        lastRemainMs = if (durationMs > 0) {
            (durationMs - progressMs).coerceAtLeast(0L)
        } else {
            999_999L
        }
        syncedToSpotifyUris.remove(uri)

        val idxInQueue = synchronized(queue) { queue.indexOfFirst { it.uri == uri } }
        val alreadyOn = lastUri != null && uri == lastUri

        if (idxInQueue >= 0) {
            // In our set — sync pointer, keep the tail
            var droppedAhead = 0
            synchronized(queue) {
                // Drop tracks before the match (user skipped past them — don't re-queue)
                repeat(idxInQueue) {
                    if (queue.isNotEmpty()) {
                        markPlayed(queue.removeFirst().uri)
                        droppedAhead++
                    }
                }
                // Drop the match itself (it's now playing, not UP NEXT)
                if (queue.isNotEmpty() && queue.first().uri == uri) {
                    markPlayed(queue.removeFirst().uri)
                }
            }
            invalidateStaleBanterCaches(synchronized(queue) { queue.firstOrNull()?.uri })
            adoptExternalCurrent(track, playing, progressMs, durationMs)
            if (countTowardBanter) {
                songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                store.songsSinceBanter = songsSinceBanter
            }
            persistRuntimeState()
            // Only chat-spam on explicit Sync or when we actually dropped ahead tracks.
            // Quiet in-queue advances (Spotify landed on our next cut) just adopt.
            if (userInitiated || droppedAhead > 0) {
                val note = buildString {
                    append(if (userInitiated) "Synced to Spotify · " else "Caught up · ")
                    append(track.name.ifBlank { uri })
                    if (track.artists.isNotBlank()) append(" — ").append(track.artists)
                    if (droppedAhead > 0) append(" · dropped $droppedAhead ahead")
                    append(" · kept ${synchronized(queue) { queue.size }} up next")
                }
                appendChat(
                    DjChatMessage(
                        id = "sys-sync-${System.currentTimeMillis()}",
                        role = DjChatRole.System,
                        text = note,
                    ),
                )
            }
            val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
            publish(
                nowLine = buildExternalNowLine(track, playing, progressMs, durationMs),
                status = "Synced to Spotify$banterHint",
                clearError = true,
                loggedIn = true,
            )
            updateNotif(buildExternalNowLine(track, playing, progressMs, durationMs))
            Log.i(
                TAG,
                "external sync uri=$uri droppedAhead=$droppedAhead remainWas=${prevRemainMs}ms q=${queue.size} user=$userInitiated",
            )
            if (synchronized(queue) { queue.size } < 4 && !filling.get()) {
                scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
            }
            return
        }

        // Already adopted this cut and it's not in UP NEXT — re-affirm only (don't wipe queue)
        if (alreadyOn) {
            adoptExternalCurrent(track, playing, progressMs, durationMs)
            persistRuntimeState()
            val qSize = synchronized(queue) { queue.size }
            val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
            val note = buildString {
                append("Already on ")
                append(track.name.ifBlank { uri })
                if (track.artists.isNotBlank()) append(" — ").append(track.artists)
                append(" · $qSize up next")
            }
            if (userInitiated) {
                appendChat(
                    DjChatMessage(
                        id = "sys-sync-same-${System.currentTimeMillis()}",
                        role = DjChatRole.System,
                        text = note,
                    ),
                )
            }
            publish(
                nowLine = buildExternalNowLine(track, playing, progressMs, durationMs),
                status = "Synced · $qSize queued$banterHint",
                clearError = true,
                loggedIn = true,
            )
            updateNotif(buildExternalNowLine(track, playing, progressMs, durationMs))
            if (qSize < 4 && !filling.get()) {
                scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
            }
            return
        }

        val label = buildString {
            append(track.name.ifBlank { uri })
            if (track.artists.isNotBlank()) append(" — ").append(track.artists)
        }
        val qSize = synchronized(queue) { queue.size }

        // Manual Sync: adopt what Spotify is on; keep app UP NEXT (direct-play next when due).
        if (userInitiated) {
            adoptExternalCurrent(track, playing, progressMs, durationMs)
            if (countTowardBanter) {
                songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                store.songsSinceBanter = songsSinceBanter
            }
            persistRuntimeState()
            appendChat(
                DjChatMessage(
                    id = "sys-sync-keep-${System.currentTimeMillis()}",
                    role = DjChatRole.System,
                    text = "Sync → on $label · kept $qSize in app list (direct-play next)",
                ),
            )
            publish(
                nowLine = buildExternalNowLine(track, playing, progressMs, durationMs),
                status = "Synced · $qSize in app list",
                clearError = true,
                loggedIn = true,
            )
            updateNotif(buildExternalNowLine(track, playing, progressMs, durationMs))
            if (qSize < 4 && !filling.get()) {
                scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
            }
            return
        }

        // Auto drift (autoplay / user pick) — reclaim our next cut by direct play.
        Log.i(
            TAG,
            "external drift uri=$uri remainWas=${prevRemainMs}ms q=$qSize — reclaim DJ next (direct-play)",
        )
        appendChat(
            DjChatMessage(
                id = "sys-reclaim-${System.currentTimeMillis()}",
                role = DjChatRole.System,
                text = "Spotify drifted to $label — direct-playing Live DJ next ($qSize kept)",
            ),
        )
        publish(
            status = "Reclaiming Live DJ next…",
            clearError = true,
            loggedIn = true,
        )
        scope.launch {
            if (!transitioning.compareAndSet(false, true)) return@launch
            try {
                withContext(Dispatchers.IO) {
                    val next = synchronized(queue) { queue.firstOrNull() }
                    if (next == null) {
                        // Empty set — adopt foreign cut only so we have a seed, then fill.
                        adoptExternalCurrent(track, playing, progressMs, durationMs)
                        persistRuntimeState()
                        fillQueue(useAi = store.useAiRank, force = true, replace = false)
                        return@withContext
                    }
                    if (countTowardBanter) {
                        songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                        store.songsSinceBanter = songsSinceBanter
                    }
                    val taken = takeNextFromQueue(next) ?: next
                    val ok = playTrack(taken)
                    if (!ok) {
                        requeueFront(taken)
                        adoptExternalCurrent(track, playing, progressMs, durationMs)
                        publish(
                            status = "Reclaim play failed — kept queue · open Spotify",
                            error = "reclaim_failed",
                        )
                    } else {
                        val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
                        publish(
                            status = "Reclaimed · ${taken.name.ifBlank { taken.uri }}$banterHint",
                            clearError = true,
                        )
                    }
                    persistRuntimeState()
                    if (synchronized(queue) { queue.size } < 4 && !filling.get()) {
                        fillQueue(useAi = store.useAiRank)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reclaim failed", e)
                publish(status = "Reclaim error: ${e.message}", error = e.message)
            } finally {
                transitioning.set(false)
                publish(transitioning = false)
            }
        }
    }

    private fun adoptExternalCurrent(
        track: DjQueueTrack,
        playing: Boolean,
        progressMs: Long,
        durationMs: Long,
    ) {
        current = track
        lastUri = track.uri
        store.lastCurrentUri = track.uri
        markPlayed(track.uri)
        wasPlaying = playing
        if (track.uri != lastChatTrackUri) {
            lastChatTrackUri = track.uri
            postTrackMessage(
                track,
                playing = playing,
                progressMs = progressMs,
                durationMs = durationMs,
            )
        } else {
            updateNowPlayingPlayback(
                track.uri,
                playing,
                progressMs,
                durationMs,
                track.albumArtUrl,
                artistArtUrl = track.artistArtUrl,
                albumUri = track.albumUri,
                artistUri = track.artistUri,
            )
        }
    }

    private fun buildExternalNowLine(
        track: DjQueueTrack,
        playing: Boolean,
        progressMs: Long,
        durationMs: Long,
    ): String = buildString {
        append(if (playing) "▶ " else "⏸ ")
        append(track.name.ifBlank { track.uri })
        if (track.artists.isNotBlank()) append(" — ").append(track.artists)
        if (durationMs > 0) {
            append(" · ").append(formatClock(progressMs)).append(" / ").append(formatClock(durationMs))
        }
    }

    private suspend fun runTransition(reason: String) {
        // Works in booth mode (Live DJ auto-handoff off) for skip/chat; auto poll only when enabled.
        if (!transitioning.compareAndSet(false, true)) return
        // Manual user actions always run; automatic idle kicks respect pause hold.
        val isSkipReason = reason == "skip" || reason == "chat_skip"
        val isIdleKick = reason == "kick_idle" || reason == "idle_advance" || reason == "poll_error_advance"
        val isUserForced = isSkipReason ||
            reason.startsWith("chat_") ||
            reason == "play_from_queue" ||
            reason == "play_uri"
        if (autoHandoffHeld && isIdleKick && !isUserForced) {
            Log.i(TAG, "skip transition $reason — auto-handoff held (paused / no now playing)")
            transitioning.set(false)
            return
        }
        if (isUserForced || reason == "near_end" || reason == "near_end_direct" ||
            reason == "stuck_end" || reason == "stopped_at_end" || reason == "ended"
        ) {
            // User skipped/played or natural handoff — leave pause-hold.
            releaseAutoHandoff("transition:$reason")
        }
        // Capture before clear — Skip + talk sets this; plain skip / natural end do not.
        // Master banter switch kills all spoken lines (including forced talk).
        val forcedTalk = forceBanter && store.banterEnabled
        forceBanter = false
        // Talk-over: when the user allows it, always ride the track (duck volume) —
        // never hard-pause mid-outro. When allowTalkOver is off, pause at the last second.
        val allowTalk = store.allowTalkOver && store.banterEnabled
        // Manual skip jumps now; natural ends may hold for banter outro then direct-play.
        val isStuckEnd = reason == "stuck_end" || reason == "stopped_at_end" || reason == "ended" ||
            reason == "near_end_direct"
        // Provisional status; refined after we know next + prefetch below.
        val banterDueProvisional = store.banterEnabled &&
            (forcedTalk || songsSinceBanter + 1 >= banterEvery) // countdown or forced
        publish(
            status = when {
                banterDueProvisional && allowTalk -> "🎙 Prep talkover ($reason)…"
                banterDueProvisional -> "🎙 Prep banter ($reason)…"
                isStuckEnd -> "Playing next ($reason)…"
                else -> "Next track ($reason)…"
            },
            transitioning = true,
        )
        try {
            withContext(Dispatchers.IO) {
                if (queue.size < 2) {
                    fillQueue(useAi = store.useAiRank, force = true)
                }
                // Peek only — do not remove until we actually advance via playTrack.
                var next = synchronized(queue) { queue.firstOrNull() }
                var prev = current
                // Prefer live currently-playing metadata for "just played" (not a stale current).
                val liveSnap = readPlaybackSnap()
                if (liveSnap != null && liveSnap.uri.isNotBlank()) {
                    if (prev == null || prev.uri != liveSnap.uri) {
                        val res = spotifyGet("/v1/me/player/currently-playing")
                        val item = res.json?.optJSONObject("item")
                        if (item != null) {
                            val ids = artistIdsOf(item)
                            prev = DjQueueTrack(
                                uri = liveSnap.uri,
                                name = item.optString("name", prev?.name.orEmpty()),
                                artists = artistsOf(item).ifBlank { prev?.artists.orEmpty() },
                                artistIds = ids,
                                albumArtUrl = albumArtUrlOf(item),
                                artistArtUrl = artistArtUrlOf(ids.firstOrNull().orEmpty()),
                                albumUri = albumUriOf(item),
                                artistUri = artistUriOf(item),
                                reason = prev?.reason.orEmpty(),
                            )
                        }
                    }
                }
                var prevUri = prev?.uri ?: lastUri
                invalidateStaleBanterCaches(next?.uri)

                // Plain skip: countdown −1 only. Banter only when due, already
                // prefetched for next, or Skip + talk forced it. Master switch can mute all.
                val banterDueCountdown = songsSinceBanter + 1 >= banterEvery
                val banterDue = store.banterEnabled && (forcedTalk || banterDueCountdown)
                // If research/prefetch is still in flight, wait briefly so we don't
                // start a second tool-backed research pass mid-outro.
                if (next != null && (forcedTalk || banterDue) && prefetchingBanter.get()) {
                    publish(status = "🎙 Waiting on research…", transitioning = true)
                    val waitDeadline = System.currentTimeMillis() + 14_000L
                    while (
                        prefetchingBanter.get() &&
                        System.currentTimeMillis() < waitDeadline
                    ) {
                        if (
                            prefetchedForUri == next.uri &&
                            !prefetchedBanter.isNullOrBlank()
                        ) {
                            break
                        }
                        delay(200L)
                    }
                }
                // Re-resolve after wait — queue or Spotify may have shifted.
                next = synchronized(queue) { queue.firstOrNull() }
                invalidateStaleBanterCaches(next?.uri)
                val banterReady = store.banterEnabled &&
                    next != null &&
                    prefetchedForUri == next?.uri &&
                    !prefetchedBanter.isNullOrBlank()
                val wantBanter = store.banterEnabled && (forcedTalk || banterDueCountdown || banterReady)
                val talkover = wantBanter && allowTalk

                if (wantBanter) {
                    // Keep Spotify playing while we write + fetch TTS so the cut isn't
                    // dead air. Skip always stays live; natural ends talk over / pause at tail.
                    publish(
                        status = if (talkover) {
                            "🎙 Writing line over the track…"
                        } else {
                            "🎙 Writing line (music keeps playing)…"
                        },
                        transitioning = true,
                    )

                    // Final targets for the spoken line — must match what we will play next.
                    val banterNext = next
                    val banterPrev = prev
                    val line = when {
                        banterNext != null &&
                            prefetchedForUri == banterNext.uri &&
                            !prefetchedBanter.isNullOrBlank() -> {
                            val p = prefetchedBanter!!
                            prefetchedBanter = null
                            prefetchedForUri = null
                            p
                        }
                        // Handoff must stay fast: use cached facts / local templates only.
                        else -> generateBanter(banterPrev, banterNext, allowResearch = false)
                    }
                    prefetchedBanter = null
                    prefetchedForUri = null

                    appendChat(
                        DjChatMessage(
                            id = "banter-${System.currentTimeMillis()}",
                            role = DjChatRole.Dj,
                            text = line,
                        ),
                    )
                    publish(
                        status = if (talkover) "🎙 Talkover: $line" else "🎙 $line",
                        transitioning = true,
                    )

                    val speechMs = estimateSpeechMs(line)

                    // Natural ends: land speech on the true outro — never start the next
                    // cut early via playTrack.
                    if (!isSkipReason && !isIdleKick) {
                        if (talkover) {
                            publish(
                                status = "🎙 Holding for outro (${speechMs / 1000}s line)…",
                                transitioning = true,
                            )
                            waitUntilRemainAbout(
                                expectedUri = prevUri,
                                targetRemainMs = speechMs + 900L,
                                maxWaitMs = 120_000L,
                            )
                        } else {
                            // Clean mic: pause only in the last ~1.2s of the cut.
                            publish(
                                status = "🎙 Holding for last second…",
                                transitioning = true,
                            )
                            waitUntilRemainAbout(
                                expectedUri = prevUri,
                                targetRemainMs = 1_200L,
                                maxWaitMs = 120_000L,
                            )
                        }
                    }

                    // If Spotify already advanced to our next during the wait, adopt and
                    // still speak (line is about that cut) — do not introduce a different song.
                    val preSpeakSnap = readPlaybackSnap()
                    if (
                        preSpeakSnap != null &&
                        banterNext != null &&
                        preSpeakSnap.uri == banterNext.uri
                    ) {
                        adoptNativeAdvance(preSpeakSnap.uri, banterNext)
                        next = synchronized(queue) { queue.firstOrNull() }
                        prevUri = banterNext.uri
                    }

                    // Clean handoff only: pause right before speech (after wait).
                    // Talkover / skip: leave the track running under the mic.
                    if (!talkover && !isSkipReason) {
                        val stillOnPrev = preSpeakSnap == null ||
                            prevUri == null ||
                            preSpeakSnap.uri == prevUri
                        if (stillOnPrev) {
                            spotifyPut("/v1/me/player/pause", "{}")
                            delay(200L)
                        }
                    }

                    var duckedFrom: Int? = null
                    if (talkover) {
                        duckedFrom = duckSpotifyVolume(targetPercent = 32)
                    }

                    val voice = store.voiceId
                    val speakJson = JSONObject()
                        .put("wait", true)
                        .put("voice_id", voice)
                        .put("language", "en")
                        .put("talkover", talkover)
                        .toString()
                    val speakRaw = HostAiClient.speak(applicationContext, line, speakJson)
                    val speak = runCatching { JSONObject(speakRaw) }.getOrElse { JSONObject() }
                    val mode = speak.optString("mode", "")
                    if (!speak.optBoolean("ok")) {
                        val err = speak.optString("error", "tts_failed")
                        val hint = speak.optString("hint", "")
                        val xaiErr = speak.optString("xai_error", "")
                        Log.w(TAG, "banter speak failed: $err body=${speak.optString("body")} xai=$xaiErr")
                        publish(
                            status = "TTS failed: $err" + if (hint.isNotBlank()) " — $hint" else "",
                            error = err,
                        )
                    } else {
                        publish(
                            status = "🎙 spoke ($mode${if (talkover) " · talkover" else ""})…",
                            clearError = true,
                            transitioning = true,
                        )
                    }

                    if (duckedFrom != null) {
                        restoreSpotifyVolume(duckedFrom)
                    }

                    songsSinceBanter = 0
                    banterEvery = store.rollNextBanterEvery()
                    store.songsSinceBanter = songsSinceBanter

                    val style = when {
                        !speak.optBoolean("ok") -> " · (banter silent)"
                        talkover -> " · talkover $mode"
                        else -> " · banter $mode"
                    }

                    // After banter: always direct-play our next cut.
                    next = synchronized(queue) { queue.firstOrNull() } ?: next
                    val alreadyOnBanterNext = lastUri != null &&
                        banterNext != null &&
                        lastUri == banterNext.uri
                    val advanced = when {
                        alreadyOnBanterNext -> true
                        talkover && !isSkipReason && !isIdleKick -> {
                            // Let the cut finish under/after the line, then start next.
                            publish(
                                status = "🎙 Waiting for track end$style…",
                                transitioning = true,
                            )
                            val natural = awaitNativeAdvance(
                                prevUri,
                                banterNext ?: next,
                                maxWaitMs = 12_000L,
                            )
                            if (natural) {
                                true
                            } else {
                                advanceToNext(
                                    next = banterNext ?: next,
                                    prevUri = prevUri,
                                    preferSkip = false,
                                    allowPlayFallback = true,
                                )
                            }
                        }
                        else -> {
                            delay(100L)
                            advanceToNext(
                                next = banterNext ?: next,
                                prevUri = prevUri,
                                preferSkip = false,
                                allowPlayFallback = true,
                            )
                        }
                    }
                    if (advanced) {
                        publish(
                            status = "Playing next$style · ${banterCountdownLabel(songsSinceBanter, banterEvery)}",
                            clearError = true,
                        )
                    } else if (next == null) {
                        publish(status = "No next track — refill queue", error = "empty_queue")
                        fillQueue(useAi = store.useAiRank, force = true)
                    } else {
                        publish(
                            status = "Play failed — open Spotify on a device",
                            error = "play_failed",
                        )
                    }
                } else {
                    // Silent handoff — direct-play the next URI (no Spotify queue).
                    songsSinceBanter += 1
                    store.songsSinceBanter = songsSinceBanter
                    publish(
                        status = "Playing next from app list…",
                        transitioning = true,
                    )
                    val advanced = advanceToNext(
                        next = next,
                        prevUri = prevUri,
                        preferSkip = false,
                        allowPlayFallback = true,
                    )
                    if (advanced) {
                        publish(
                            status = "Playing next · ${banterCountdownLabel(songsSinceBanter, banterEvery)}",
                            clearError = true,
                        )
                    } else if (next == null) {
                        publish(status = "No next track — refill queue", error = "empty_queue")
                        fillQueue(useAi = store.useAiRank, force = true)
                    } else {
                        publish(
                            status = "Play failed — open Spotify on a device",
                            error = "play_failed",
                        )
                    }
                }
                persistRuntimeState()
                if (queue.size < 4) {
                    fillQueue(useAi = store.useAiRank)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "transition failed", e)
            publish(status = "Transition error: ${e.message}", error = e.message)
        } finally {
            nearEndArmed = false
            lastRemainMs = 999_999L
            transitioning.set(false)
            // Always flush countdown after a handoff so UI + leave/return stay honest.
            store.songsSinceBanter = songsSinceBanter
            store.banterEvery = banterEvery
            persistRuntimeState()
            publish(
                transitioning = false,
                status = SpotifyDjBus.state.value.status.let { s ->
                    if (s.contains("talk in") || s.contains("banter") || s.startsWith("Playing")) {
                        s
                    } else {
                        "Watching playback · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
                    }
                },
            )
        }
    }

    /** Put a track back at the front of the radio queue (failed play / soft retry). */
    private fun requeueFront(track: DjQueueTrack) {
        synchronized(queue) {
            if (queue.none { it.uri == track.uri }) {
                queue.addFirst(track)
            }
            while (queue.size > MAX_DJ_QUEUE) queue.removeLast()
        }
        persistRuntimeState()
    }

    /**
     * User picked a song from UP NEXT: drop everything ahead of it, play the pick,
     * no banter / talk for this change.
     */
    private suspend fun jumpToQueueTrack(trackUri: String, queueIndex: Int) {
        if (!transitioning.compareAndSet(false, true)) {
            publish(status = "Busy — try again in a moment", persist = false)
            return
        }
        // Never force talk on a manual queue jump
        forceBanter = false
        prefetchedBanter = null
        prefetchedForUri = null
        pendingBanter = null
        pendingBanterForUri = null
        researchedForUri = null
        researchedFacts = emptyList()
        publish(status = "Jumping in queue…", transitioning = true)
        try {
            withContext(Dispatchers.IO) {
                var discarded = 0
                val selected = synchronized(queue) {
                    val idx = when {
                        queueIndex in queue.indices -> {
                            val at = queue.elementAt(queueIndex)
                            // Prefer the tapped row; fall back to URI if the list shifted
                            if (trackUri.isBlank() || at.uri == trackUri) {
                                queueIndex
                            } else {
                                val byUri = queue.indexOfFirst { it.uri == trackUri }
                                if (byUri >= 0) byUri else queueIndex
                            }
                        }
                        trackUri.isNotBlank() -> queue.indexOfFirst { it.uri == trackUri }
                        else -> -1
                    }
                    if (idx < 0 || idx >= queue.size) return@synchronized null
                    discarded = idx
                    // Discard tracks ahead of the selection — mark heard so we don't re-queue
                    repeat(idx) {
                        if (queue.isNotEmpty()) markPlayed(queue.removeFirst().uri)
                    }
                    queue.removeFirstOrNull()
                }
                if (selected == null) {
                    publish(status = "Track not in queue")
                    return@withContext
                }
                // Silent handoff only — count toward next natural banter, don't speak now
                songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                store.songsSinceBanter = songsSinceBanter
                val discardedNote = if (discarded > 0) " · skipped $discarded ahead" else ""
                val ok = playTrack(selected)
                if (ok) {
                    publish(
                        status = "Playing selected$discardedNote · ${banterCountdownLabel(songsSinceBanter, banterEvery)}",
                        clearError = true,
                    )
                    val label = buildString {
                        append("Jumped to ")
                        append(selected.name.ifBlank { selected.uri })
                        if (selected.artists.isNotBlank()) append(" — ").append(selected.artists)
                        append(" (no talk)")
                        if (discarded > 0) append(" · dropped $discarded ahead")
                    }
                    appendChat(
                        DjChatMessage(
                            id = "sys-jump-${System.currentTimeMillis()}",
                            role = DjChatRole.System,
                            text = label,
                        ),
                    )
                } else {
                    requeueFront(selected)
                    publish(
                        status = "Play failed — open Spotify on a device",
                        error = "play_failed",
                    )
                }
                persistRuntimeState()
                if (queue.size < 4) {
                    fillQueue(useAi = store.useAiRank)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "jumpToQueueTrack failed", e)
            publish(status = "Jump failed: ${e.message}", error = e.message)
        } finally {
            nearEndArmed = false
            transitioning.set(false)
            persistRuntimeState()
            publish(transitioning = false)
        }
    }

    /**
     * Soft-duck Spotify so DJ talkover rides over the outro without a hard mute.
     * @return previous volume percent, or null if duck was skipped / failed.
     */
    private fun duckSpotifyVolume(targetPercent: Int): Int? {
        return try {
            val player = spotifyGet("/v1/me/player")
            val device = player.json?.optJSONObject("device")
            val current = device?.optInt("volume_percent", -1) ?: -1
            if (current < 0) return null
            if (current <= targetPercent) return null // already soft enough
            val path = "/v1/me/player/volume?volume_percent=$targetPercent"
            val res = spotifyPut(path, null)
            if (res.ok || res.status in listOf(202, 204)) {
                Log.i(TAG, "ducked volume $current → $targetPercent")
                current
            } else {
                Log.w(TAG, "duck volume failed: ${res.error} status=${res.status}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "duck volume: ${e.message}")
            null
        }
    }

    private fun restoreSpotifyVolume(percent: Int) {
        try {
            val clamped = percent.coerceIn(0, 100)
            val path = "/v1/me/player/volume?volume_percent=$clamped"
            val res = spotifyPut(path, null)
            if (!(res.ok || res.status in listOf(202, 204))) {
                Log.w(TAG, "restore volume failed: ${res.error}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore volume: ${e.message}")
        }
    }

    /**
     * Direct-play a single track URI. Does **not** touch Spotify’s Up Next —
     * remaining app-list songs stay only in Grokify until their turn.
     */
    private fun playTrack(item: DjQueueTrack): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPlayAttemptMs < 800L) {
            // Tiny debounce so retry storms don't trip Spotify rate limits
            try { Thread.sleep(400L) } catch (_: InterruptedException) {}
        }
        lastPlayAttemptMs = System.currentTimeMillis()
        markPlayed(item.uri)
        val upcomingCount = synchronized(queue) {
            queue.count { it.uri.isNotBlank() && it.uri != item.uri }
        }
        // Single URI only — never pack the app queue into Spotify context.
        val body = JSONObject()
            .put("uris", JSONArray().put(item.uri))
            .put("offset", JSONObject().put("position", 0))
            .toString()
        val device = pickDeviceId()
        var path = if (device != null) {
            "/v1/me/player/play?device_id=${java.net.URLEncoder.encode(device, "UTF-8")}"
        } else {
            "/v1/me/player/play"
        }
        var res = spotifyPut(path, body)
        // Retry without device id, then transfer-play, then deep-link Spotify
        if (!res.ok && res.status !in listOf(202, 204)) {
            if (device != null) {
                res = spotifyPut("/v1/me/player/play", body)
            }
            if (!res.ok && res.status !in listOf(202, 204) && device != null) {
                // Force transfer to known device then play
                spotifyPut(
                    "/v1/me/player",
                    JSONObject().put("device_ids", JSONArray().put(device)).put("play", true).toString(),
                )
                try { Thread.sleep(350L) } catch (_: InterruptedException) {}
                res = spotifyPut(path, body)
            }
            if (!res.ok && res.status == 404) {
                openSpotifyUri(item.uri)
                // Deep-link often starts playback even when Web API has no active device
                try { Thread.sleep(900L) } catch (_: InterruptedException) {}
                res = spotifyPut("/v1/me/player/play", body)
            }
        }
        val ok = res.ok || res.status in listOf(202, 204)
        // Remember what Spotify was on so lagging currently-playing polls (still the
        // previous cut) are not treated as "Spotify changed outside Live DJ".
        expectedPlayFromUri = lastUri
        current = item
        // Optimistic: UI + ownership point at the track we commanded. Polls may still
        // report the previous URI until Spotify applies the play — see tickPlayback.
        lastUri = item.uri
        store.lastCurrentUri = item.uri
        nearEndArmed = false
        handoffLaunchedForUri = null
        syncedToSpotifyUris.clear()
        val playAt = System.currentTimeMillis()
        lastSpotifyQueueSyncMs = playAt
        lastHardMirrorMs = playAt
        // Mark ownership so the next poll doesn't treat lag / our handoff as external.
        expectedPlayUri = item.uri
        expectedPlayUntilMs = playAt + 25_000L
        lastRemainMs = 999_999L
        // Only claim "playing" when Spotify accepted the play (or deep-link path may still recover)
        wasPlaying = ok || res.status == 404
        idlePolls = 0
        stuckEndPolls = 0
        if (ok || res.status == 404) {
            releaseAutoHandoff("play_track")
        }
        if (item.uri != lastChatTrackUri) {
            lastChatTrackUri = item.uri
            postTrackMessage(item, playing = ok)
        } else {
            updateNowPlayingFlags(item.uri, playing = ok)
        }
        persistRuntimeState()
        publish(
            nowLine = (if (ok) "▶ " else "⚠ ") +
                "${item.name.ifBlank { item.uri }}${if (item.artists.isNotBlank()) " — ${item.artists}" else ""}",
            status = if (ok) {
                "Playing · $upcomingCount in app list"
            } else {
                "Play rejected · ${res.error ?: res.status}"
            },
            clearError = ok,
            error = if (ok) null else (res.error ?: "play_${res.status}"),
        )
        Log.i(TAG, "direct-play ${item.uri.takeLast(22)} ok=$ok upcomingApp=$upcomingCount")
        return ok
    }

    private fun togglePause() {
        val curPlaying = wasPlaying
        val path = if (curPlaying) "/v1/me/player/pause" else "/v1/me/player/play"
        val res = spotifyPut(path, if (curPlaying) "{}" else "{}")
        if (res.ok || res.status in listOf(202, 204)) {
            wasPlaying = !curPlaying
            if (curPlaying) {
                // User paused from the booth — freeze auto banter / next-track.
                holdAutoHandoff("user_pause_toggle")
            } else {
                releaseAutoHandoff("user_play_toggle")
            }
            current?.uri?.let { updateNowPlayingFlags(it, playing = !curPlaying) }
            publish(
                status = if (!curPlaying) "Playing" else "Paused · auto-handoff waiting",
                nowLine = current?.let { t ->
                    val mark = if (!curPlaying) "▶ " else "⏸ "
                    "$mark${t.name.ifBlank { t.uri }}${if (t.artists.isNotBlank()) " — ${t.artists}" else ""}"
                },
            )
        } else {
            publish(status = "Pause/play failed", error = res.error)
        }
    }

    private fun restartOrPrevious() {
        // Restart current from 0 when available; Spotify seek is simpler than full previous history
        val uri = current?.uri
        if (uri.isNullOrBlank()) {
            publish(status = "Nothing to restart")
            return
        }
        val seek = spotifyPut("/v1/me/player/seek?position_ms=0", null)
        if (seek.ok || seek.status in listOf(202, 204)) {
            spotifyPut("/v1/me/player/play", "{}")
            wasPlaying = true
            updateNowPlayingFlags(uri, playing = true)
            publish(status = "Restarted track")
        } else {
            // Fallback: re-fire play on current URI
            current?.let { playTrack(it) }
            publish(status = "Replayed track")
        }
    }

    private suspend fun handleUserChat(text: String) {
        if (!chatBusy.compareAndSet(false, true)) {
            appendChat(
                DjChatMessage(
                    id = "sys-busy-${System.currentTimeMillis()}",
                    role = DjChatRole.System,
                    text = "Still working on your last request…",
                ),
            )
            return
        }
        publish(chatBusy = true)
        appendChat(
            DjChatMessage(
                id = "user-${System.currentTimeMillis()}",
                role = DjChatRole.User,
                text = text,
            ),
        )
        val thinkingId = "dj-think-${System.currentTimeMillis()}"
        appendChat(
            DjChatMessage(
                id = thinkingId,
                role = DjChatRole.Dj,
                text = "…",
                streaming = true,
            ),
        )
        try {
            withContext(Dispatchers.IO) {
                val lower = text.lowercase()
                // Fast local intents
                when {
                    lower.contains("skip") || lower.contains("next song") ||
                        lower.contains("pass") || lower.contains("hate this") ||
                        lower.contains("don't like this") || lower.contains("dont like this") -> {
                        // Chat skip follows countdown like the Skip button — not Skip + talk.
                        val forceTalk = lower.contains("talk") || lower.contains("banter")
                        replaceStreaming(
                            thinkingId,
                            if (forceTalk) "Skip + talk — one sec." else "Skipping ahead — one sec.",
                        )
                        forceBanter = forceTalk
                        runTransition("chat_skip")
                        return@withContext
                    }
                    (lower == "pause" || lower.startsWith("pause ") ||
                        lower == "stop" || lower.startsWith("stop music") ||
                        lower == "hold up" || lower == "hold on") &&
                        !lower.contains("don't pause") -> {
                        val was = wasPlaying
                        togglePause()
                        replaceStreaming(thinkingId, if (was) "Paused." else "Playing.")
                        return@withContext
                    }
                    (lower == "play" || lower == "resume" || lower == "unpause" ||
                        lower.startsWith("play ") && lower.length < 20) -> {
                        if (!wasPlaying) togglePause()
                        replaceStreaming(thinkingId, "Back on.")
                        return@withContext
                    }
                    lower.contains("restart") || lower.contains("from the start") ||
                        lower.contains("from the top") || lower.contains("replay") ||
                        lower.contains("previous") || lower.contains("go back") -> {
                        restartOrPrevious()
                        replaceStreaming(thinkingId, "From the top.")
                        return@withContext
                    }
                    // New queue = wipe upcoming + rebuild. Refill = append more.
                    lower.contains("new queue") || lower.contains("fresh set") ||
                        lower.contains("fresh queue") || lower.contains("new set") ||
                        lower.contains("start a new queue") || lower.contains("rebuild the queue") ||
                        lower.contains("rebuild queue") || lower.contains("different queue") ||
                        lower.contains("something different") && lower.contains("queue") ||
                        (lower.contains("clear") && lower.contains("queue") &&
                            (lower.contains("new") || lower.contains("rebuild") || lower.contains("refill"))) -> {
                        fillQueue(useAi = store.useAiRank, force = true, replace = true)
                        replaceStreaming(
                            thinkingId,
                            "Scratched the upcoming list — building a fresh set from library + radio.",
                        )
                        return@withContext
                    }
                    lower.contains("refill") || lower.contains("add more") ||
                        lower.contains("more songs") || lower.contains("more tracks") ||
                        lower.contains("fill the queue") || lower.contains("top up") ||
                        lower.contains("top-up") || lower.contains("shuffle the queue") -> {
                        fillQueue(useAi = store.useAiRank, force = true, replace = false)
                        replaceStreaming(thinkingId, "Topped up the set from your library + radio.")
                        return@withContext
                    }
                    // "remove/drop <song> from the queue"
                    (lower.startsWith("remove ") || lower.startsWith("drop ") ||
                        lower.startsWith("delete ") || lower.contains(" take out ") ||
                        lower.contains("remove from queue") || lower.contains("drop from queue")) &&
                        !lower.contains("drop the artist") -> {
                        val match = extractQueueMatchQuery(text)
                        val n = if (match.isNotBlank()) removeTracksMatching(match) else 0
                        replaceStreaming(
                            thinkingId,
                            if (n > 0) "Pulled $n off the upcoming list."
                            else "Couldn't find that in the queue — name a song or artist on the list.",
                        )
                        return@withContext
                    }
                    // "tell me about / info on / who is / what is this song"
                    lower.startsWith("tell me about") || lower.startsWith("what about") ||
                        lower.startsWith("info on") || lower.startsWith("info about") ||
                        lower.startsWith("who is") || lower.startsWith("who's ") ||
                        lower.startsWith("who are") || lower.contains("more info") ||
                        lower.contains("information about") ||
                        (lower.startsWith("what is ") || lower.startsWith("what's ")) &&
                        (lower.contains("song") || lower.contains("track") || lower.contains("artist") ||
                            lower.contains("this")) -> {
                        val reply = chatInfoLookup(text)
                        replaceStreaming(thinkingId, reply)
                        return@withContext
                    }
                }

                // AI / heuristic queue steering
                vibeHint = text.take(200)
                val reply = chatSteerQueue(text)
                replaceStreaming(thinkingId, reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "chat failed", e)
            replaceStreaming(thinkingId, "Couldn't handle that: ${e.message}")
        } finally {
            chatBusy.set(false)
            publish(chatBusy = false)
        }
    }

    /**
     * Interpret free-form booth chat: bias vibe, search + enqueue, drop artists,
     * rebuild/refill queue, remove tracks, or answer song/artist questions.
     */
    private fun chatSteerQueue(userText: String): String {
        val cur = current
        val qSnap = synchronized(queue) { queue.toList() }
        val qSummary = qSnap.take(12).mapIndexed { i, t ->
            "${i + 1}. ${t.name} — ${t.artists}"
        }.joinToString("\n").ifBlank { "(empty)" }
        val nowLine = cur?.let { "${it.name} — ${it.artists}" } ?: "nothing"
        val lower = userText.lowercase()

        // Info-only questions: answer without mutating the queue.
        if (looksLikeInfoQuestion(lower)) {
            return chatInfoLookup(userText)
        }

        val system =
            "You are the Live AI DJ booth chat (not general Grok chat). User is steering live Spotify radio. " +
                "Reply with JSON only, no markdown fences: " +
                "{\"reply\":\"1-3 short casual sentences\",\"vibe\":\"optional short vibe tag\"," +
                "\"actions\":[" +
                "{\"op\":\"enqueue_search\",\"q\":\"spotify search query\",\"n\":3}," +
                "{\"op\":\"new_queue\"},{\"op\":\"refill\"},{\"op\":\"clear_queue\"}," +
                "{\"op\":\"remove_track\",\"match\":\"song or artist substring in upcoming queue\"}," +
                "{\"op\":\"drop_artist\",\"artist\":\"name\"}," +
                "{\"op\":\"track_info\",\"q\":\"song name\"},{\"op\":\"artist_info\",\"q\":\"artist name\"}," +
                "{\"op\":\"skip\"},{\"op\":\"pause\"},{\"op\":\"play\"}" +
                "]} " +
                "Rules: new_queue = wipe upcoming + build a different set. refill = append more tracks. " +
                "remove_track / drop_artist prune the upcoming list. " +
                "track_info / artist_info answer questions (still put a short reply; the client also fetches Spotify facts). " +
                "Prefer enqueue_search for “play more X / queue some Y”. Keep reply under 50 words."

        val prompt = buildString {
            append("Now playing: $nowLine\n")
            append("Upcoming queue:\n$qSummary\n")
            if (vibeHint.isNotBlank()) append("Current vibe hint: $vibeHint\n")
            append("User said: $userText")
        }

        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify Live DJ Chat")
            .toString()

        val raw = HostAiClient.complete(applicationContext, prompt, opts)
        val envelope = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!envelope.optBoolean("ok", false)) {
            return localChatSteer(userText)
        }
        val text = envelope.optString("text", envelope.optString("reply", "")).ifBlank {
            envelope.optString("message", "")
        }
        val json = extractJson(text) ?: extractJson(envelope.toString())
        if (json == null) {
            if (looksLikeInfoQuestion(lower)) return chatInfoLookup(userText)
            localEnqueueFromQuery(userText, 4)
            return sanitizeSpoken(text).ifBlank { "Got it — pulling a few related cuts." }
        }

        var reply = json.optString("reply", "On it.").ifBlank { "On it." }
        val vibe = json.optString("vibe", "").trim()
        if (vibe.isNotBlank()) vibeHint = vibe

        val infoBits = ArrayList<String>()
        val actions = json.optJSONArray("actions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val a = actions.optJSONObject(i) ?: continue
                when (a.optString("op", "").lowercase()) {
                    "enqueue_search" -> {
                        val q = a.optString("q", userText).ifBlank { userText }
                        val n = a.optInt("n", 4).coerceIn(1, 8)
                        localEnqueueFromQuery(q, n)
                    }
                    "new_queue" -> fillQueue(useAi = store.useAiRank, force = true, replace = true)
                    "refill" -> fillQueue(useAi = store.useAiRank, force = true, replace = false)
                    "clear_queue" -> {
                        synchronized(queue) { queue.clear() }
                        syncedToSpotifyUris.clear()
                        invalidateStaleBanterCaches(null)
                        persistRuntimeState()
                        publish(status = "App list cleared · current track keeps playing", clearError = true)
                    }
                    "remove_track" -> {
                        val match = a.optString("match", a.optString("q", "")).ifBlank {
                            extractQueueMatchQuery(userText)
                        }
                        if (match.isNotBlank()) removeTracksMatching(match)
                    }
                    "drop_artist" -> {
                        val artist = a.optString("artist", "").ifBlank {
                            a.optString("match", "")
                        }
                        if (artist.isNotBlank()) removeTracksMatching(artist)
                    }
                    "track_info" -> {
                        val q = a.optString("q", "").ifBlank { userText }
                        infoBits.add(lookupTrackInfo(q))
                    }
                    "artist_info" -> {
                        val q = a.optString("q", "").ifBlank { userText }
                        infoBits.add(lookupArtistInfo(q))
                    }
                    "skip" -> {
                        // Tool skip is plain next (countdown −1); use talk/banter ops for force.
                        forceBanter = false
                        scope.launch { runTransition("chat_skip") }
                    }
                    "pause" -> {
                        if (wasPlaying) togglePause()
                    }
                    "play" -> {
                        if (!wasPlaying) togglePause()
                    }
                }
            }
        } else {
            localEnqueueFromQuery(userText, 4)
        }
        if (infoBits.isNotEmpty()) {
            val facts = infoBits.filter { it.isNotBlank() }.joinToString("\n")
            if (facts.isNotBlank()) {
                val head = sanitizeSpoken(reply)
                reply = listOf(head, facts)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                publish(status = "Chat: ${head.take(60)}")
                return reply
            }
        }
        publish(status = "Chat: ${reply.take(60)}")
        return sanitizeSpoken(reply).ifBlank { reply }
    }

    private fun localChatSteer(userText: String): String {
        val lower = userText.lowercase()
        if (looksLikeInfoQuestion(lower)) return chatInfoLookup(userText)
        if (lower.contains("new queue") || lower.contains("fresh set") || lower.contains("new set")) {
            fillQueue(useAi = store.useAiRank, force = true, replace = true)
            return "Building a brand-new upcoming set."
        }
        if (lower.contains("refill") || lower.contains("add more") || lower.contains("more songs")) {
            fillQueue(useAi = store.useAiRank, force = true, replace = false)
            return "Topping up the queue."
        }
        if (lower.startsWith("remove ") || lower.startsWith("drop ") || lower.startsWith("delete ")) {
            val match = extractQueueMatchQuery(userText)
            val n = if (match.isNotBlank()) removeTracksMatching(match) else 0
            return if (n > 0) "Removed $n from the queue." else "Didn't find that in the upcoming list."
        }
        val n = localEnqueueFromQuery(userText, 5)
        return if (n > 0) {
            "Queued $n from “${userText.take(40)}”. Coming up after this one."
        } else {
            "Couldn't find matches for that — try an artist or song name."
        }
    }

    private fun looksLikeInfoQuestion(lower: String): Boolean {
        if (lower.startsWith("tell me about") || lower.startsWith("what about") ||
            lower.startsWith("info on") || lower.startsWith("info about") ||
            lower.startsWith("who is") || lower.startsWith("who's ") ||
            lower.startsWith("who are") || lower.contains("more info") ||
            lower.contains("information about")
        ) return true
        if ((lower.startsWith("what is ") || lower.startsWith("what's ")) &&
            (lower.contains("song") || lower.contains("track") || lower.contains("artist") ||
                lower.contains("this") || lower.contains("album"))
        ) return true
        return false
    }

    /** Pull a song/artist name out of “remove X from the queue” style requests. */
    private fun extractQueueMatchQuery(userText: String): String {
        var s = userText.trim()
        val prefixes = listOf(
            Regex("(?i)^(please\\s+)?(can you\\s+)?(remove|drop|delete|take out)\\s+"),
            Regex("(?i)^(please\\s+)?(can you\\s+)?"),
        )
        for (p in prefixes) {
            s = s.replace(p, "")
        }
        s = s
            .replace(Regex("(?i)\\s+(from\\s+(the\\s+)?)?(queue|upcoming|list|set)\\s*$"), "")
            .replace(Regex("(?i)^(the\\s+song\\s+|the\\s+track\\s+|song\\s+|track\\s+)"), "")
            .trim()
        return s.take(80)
    }

    /**
     * Remove upcoming tracks whose name, artists, or URI match [match] (case-insensitive).
     * Returns how many were removed.
     */
    private fun removeTracksMatching(match: String): Int {
        val m = match.trim().lowercase()
        if (m.isBlank()) return 0
        var removed = 0
        val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
        synchronized(queue) {
            val kept = queue.filterNot { t ->
                val hit = t.uri.equals(match.trim(), ignoreCase = true) ||
                    t.uri.lowercase().contains(m) ||
                    t.name.lowercase().contains(m) ||
                    t.artists.lowercase().contains(m) ||
                    "${t.name} ${t.artists}".lowercase().contains(m)
                if (hit) removed++
                hit
            }
            if (removed > 0) {
                queue.clear()
                kept.forEach { queue.addLast(it) }
            }
        }
        if (removed > 0) {
            val newHead = synchronized(queue) { queue.firstOrNull()?.uri }
            if (prevHead != newHead) invalidateStaleBanterCaches(newHead)
            persistRuntimeState()
            publish(status = "Removed $removed · ${queue.size} left in app list", clearError = true)
        }
        return removed
    }

    private fun chatInfoLookup(userText: String): String {
        val lower = userText.lowercase()
        val q = userText
            .replace(
                Regex(
                    "(?i)^(please\\s+)?(can you\\s+)?(tell me about|what about|info on|info about|" +
                        "who is|who's|who are|what is|what's|more info on|information about)\\s+",
                ),
                "",
            )
            .replace(Regex("(?i)\\?+$"), "")
            .replace(Regex("(?i)^(the\\s+song\\s+|the\\s+artist\\s+|song\\s+|artist\\s+|this\\s+)"), "")
            .trim()
            .ifBlank {
                // “tell me about this song / this artist”
                if (lower.contains("artist")) {
                    current?.artists?.split(",")?.firstOrNull()?.trim().orEmpty()
                } else {
                    current?.name.orEmpty().ifBlank { current?.artists.orEmpty() }
                }
            }
            .take(80)

        if (q.isBlank()) {
            return "Nothing spinning right now — name a song or artist."
        }

        val wantArtist = lower.contains("artist") || lower.startsWith("who is") ||
            lower.startsWith("who's") || lower.startsWith("who are")
        val wantTrack = lower.contains("song") || lower.contains("track") || lower.contains("album")

        val facts = when {
            wantArtist && !wantTrack -> lookupArtistInfo(q)
            wantTrack && !wantArtist -> lookupTrackInfo(q)
            else -> {
                // Prefer track match when ambiguous; fall back to artist.
                val track = lookupTrackInfo(q)
                if (track.isNotBlank() && !track.startsWith("Couldn't")) track
                else lookupArtistInfo(q)
            }
        }
        if (facts.isBlank()) return "Couldn't pull Spotify info for “$q”."

        // Live research: recent news, upcoming shows, song/artist trivia (host Grok tools).
        val artistForResearch = when {
            wantArtist && !wantTrack -> q
            else -> {
                val fromLocal = current?.artists?.split(",")?.firstOrNull()?.trim().orEmpty()
                when {
                    fromLocal.isNotBlank() &&
                        (q.contains(fromLocal, ignoreCase = true) ||
                            fromLocal.contains(q, ignoreCase = true) ||
                            wantTrack) -> fromLocal
                    wantArtist -> q
                    else -> fromLocal.ifBlank { q }
                }
            }
        }
        val songForResearch = if (wantArtist && !wantTrack) "" else q
        val researched = runCatching {
            researchMusicFacts(artistForResearch, songForResearch, null)
        }.getOrDefault(emptyList())
        val newsBlock = if (researched.isNotEmpty()) {
            researched.joinToString("\n") { "• $it" }
        } else {
            ""
        }

        return if (newsBlock.isNotBlank()) "$facts\n\nRecent / notes:\n$newsBlock" else facts
    }

    private fun lookupTrackInfo(query: String): String {
        val q = query.trim().take(80)
        if (q.isBlank()) return ""
        // Prefer a match already on the queue / now playing.
        val local = current?.takeIf {
            it.name.contains(q, ignoreCase = true) ||
                it.artists.contains(q, ignoreCase = true) ||
                q.contains(it.name, ignoreCase = true)
        } ?: synchronized(queue) {
            queue.firstOrNull {
                it.name.contains(q, ignoreCase = true) ||
                    it.artists.contains(q, ignoreCase = true)
            }
        }

        val trackJson = (
            if (local != null && local.uri.startsWith("spotify:track:")) {
                val id = local.uri.removePrefix("spotify:track:")
                spotifyGet("/v1/tracks/$id").json
            } else {
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                val res = spotifyGet("/v1/search?type=track&limit=1&q=$enc")
                res.json?.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)
            }
            ) ?: return "Couldn't find a track matching “$q”."

        val name = trackJson.optString("name", q)
        val artists = artistsOf(trackJson).ifBlank { "Unknown artist" }
        val album = trackJson.optJSONObject("album")
        val albumName = album?.optString("name", "").orEmpty()
        val year = album?.optString("release_date", "")?.take(4).orEmpty()
        val pop = trackJson.optInt("popularity", -1)
        val ms = trackJson.optLong("duration_ms", 0L)
        val explicit = trackJson.optBoolean("explicit", false)
        return buildString {
            append("🎵 $name — $artists")
            if (albumName.isNotBlank()) {
                append("\nAlbum: $albumName")
                if (year.isNotBlank()) append(" ($year)")
            }
            if (ms > 0) append("\nLength: ${formatClock(ms)}")
            if (pop >= 0) append("\nSpotify popularity: $pop/100")
            if (explicit) append("\nExplicit")
        }
    }

    private fun lookupArtistInfo(query: String): String {
        val q = query.trim().take(80)
        if (q.isBlank()) return ""

        // Resolve from current / queue artist ids when possible.
        val fromLocal = run {
            val cur = current
            if (cur != null && cur.artists.contains(q, ignoreCase = true) && cur.artistIds.isNotEmpty()) {
                cur.artistIds.first()
            } else {
                synchronized(queue) {
                    queue.firstOrNull {
                        it.artists.contains(q, ignoreCase = true) && it.artistIds.isNotEmpty()
                    }?.artistIds?.firstOrNull()
                }
            }
        }

        val artistJson = (
            if (!fromLocal.isNullOrBlank()) {
                spotifyGet("/v1/artists/$fromLocal").json
            } else {
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                val res = spotifyGet("/v1/search?type=artist&limit=1&q=$enc")
                res.json?.optJSONObject("artists")?.optJSONArray("items")?.optJSONObject(0)
            }
            ) ?: return "Couldn't find an artist matching “$q”."

        val name = artistJson.optString("name", q)
        val genres = artistJson.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").takeIf { it.isNotBlank() }
            }.take(4).joinToString(", ")
        }.orEmpty()
        val followers = artistJson.optJSONObject("followers")?.optLong("total", 0L) ?: 0L
        val pop = artistJson.optInt("popularity", -1)
        val id = artistJson.optString("id", "")

        // Top tracks for a little extra color
        val tops = if (id.isNotBlank()) {
            val topsRes = spotifyGet("/v1/artists/$id/top-tracks?market=US")
            val arr = topsRes.json?.optJSONArray("tracks")
            if (arr != null) {
                (0 until minOf(3, arr.length())).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("name", "")?.takeIf { it.isNotBlank() }
                }
            } else emptyList()
        } else emptyList()

        return buildString {
            append("🎤 $name")
            if (genres.isNotBlank()) append("\nGenres: $genres")
            if (followers > 0) append("\nFollowers: ${formatCount(followers)}")
            if (pop >= 0) append("\nSpotify popularity: $pop/100")
            if (tops.isNotEmpty()) append("\nTop tracks: ${tops.joinToString(", ")}")
        }
    }

    private fun formatCount(n: Long): String {
        return when {
            n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fK".format(n / 1_000.0)
            else -> n.toString()
        }
    }

    private fun localEnqueueFromQuery(query: String, n: Int): Int {
        val q = query.trim().take(80)
        if (q.isBlank()) return 0
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val res = spotifyGet("/v1/search?type=track&limit=${n.coerceIn(1, 10)}&q=$enc")
        val items = res.json
            ?.optJSONObject("tracks")
            ?.optJSONArray("items")
            ?: return 0
        var added = 0
        val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
        synchronized(queue) {
            for (i in 0 until items.length()) {
                val t = items.optJSONObject(i) ?: continue
                val uri = t.optString("uri", "")
                if (uri.isBlank() || isPlayed(uri) || queue.any { it.uri == uri }) continue
                val ids = artistIdsOf(t)
                queue.addLast(
                    DjQueueTrack(
                        uri = uri,
                        name = t.optString("name", ""),
                        artists = artistsOf(t),
                        reason = "chat: $q",
                        artistIds = ids,
                        albumArtUrl = albumArtUrlOf(t),
                        artistArtUrl = artistArtUrlOf(ids.firstOrNull().orEmpty()),
                        albumUri = albumUriOf(t),
                        artistUri = artistUriOf(t),
                    ),
                )
                added++
                if (added >= n) break
            }
        }
        if (added > 0) {
            val newHead = synchronized(queue) { queue.firstOrNull()?.uri }
            if (prevHead != newHead) invalidateStaleBanterCaches(newHead)
            persistRuntimeState()
            publish(status = "Chat queued $added · total ${queue.size} in app list", clearError = true)
        }
        return added
    }

    /** Persist radio queue + played set + banter counters (leave/return safe). */
    private fun persistRuntimeState() {
        try {
            val q = synchronized(queue) { queue.toList() }
            store.saveQueue(q)
            store.savePlayedUris(playedUris.toMap())
            store.vibeHint = vibeHint
            store.banterEvery = banterEvery
            store.songsSinceBanter = songsSinceBanter
            current?.uri?.let { if (it.isNotBlank()) store.lastCurrentUri = it }
        } catch (e: Exception) {
            Log.w(TAG, "persistRuntime: ${e.message}")
        }
    }

    private fun postTrackMessage(
        track: DjQueueTrack,
        playing: Boolean,
        progressMs: Long = 0L,
        durationMs: Long = 0L,
    ) {
        val title = track.name.ifBlank { track.uri }
        val body = buildString {
            append(title)
            if (track.artists.isNotBlank()) append("\n").append(track.artists)
            if (track.reason.isNotBlank()) append("\n").append(track.reason)
        }
        synchronized(chatLog) {
            // Clear previous now-playing flags
            for (i in chatLog.indices) {
                val m = chatLog[i]
                if (m.role == DjChatRole.Track && m.isNowPlaying) {
                    chatLog[i] = m.copy(isNowPlaying = false, isPlaying = false, progressMs = 0L)
                }
            }
            chatLog.add(
                DjChatMessage(
                    id = "track-${track.uri}-${System.currentTimeMillis()}",
                    role = DjChatRole.Track,
                    text = body,
                    trackUri = track.uri,
                    trackName = title,
                    trackArtists = track.artists,
                    albumArtUrl = track.albumArtUrl.ifBlank { null },
                    artistArtUrl = track.artistArtUrl.ifBlank { null },
                    albumUri = track.albumUri.ifBlank { null },
                    artistUri = track.artistUri.ifBlank { null },
                    progressMs = progressMs,
                    durationMs = durationMs,
                    isNowPlaying = true,
                    isPlaying = playing,
                ),
            )
            trimChatLocked()
        }
        publish(persist = true)
    }

    private fun updateNowPlayingFlags(uri: String, playing: Boolean) {
        updateNowPlayingPlayback(uri, playing, progressMs = null, durationMs = null, albumArtUrl = null)
    }

    /**
     * Keep the latest Track bubble in sync with Spotify position, play state, and cover art.
     * Progress ticks do not hit disk — only structural chat changes are persisted.
     */
    private fun updateNowPlayingPlayback(
        uri: String,
        playing: Boolean,
        progressMs: Long?,
        durationMs: Long?,
        albumArtUrl: String?,
        artistArtUrl: String? = null,
        albumUri: String? = null,
        artistUri: String? = null,
    ) {
        var changed = false
        synchronized(chatLog) {
            for (i in chatLog.indices) {
                val m = chatLog[i]
                if (m.role != DjChatRole.Track) continue
                val now = m.trackUri == uri
                if (now) {
                    val art = albumArtUrl?.takeIf { it.isNotBlank() } ?: m.albumArtUrl
                    val aArt = artistArtUrl?.takeIf { it.isNotBlank() } ?: m.artistArtUrl
                    val aUri = albumUri?.takeIf { it.isNotBlank() } ?: m.albumUri
                    val arUri = artistUri?.takeIf { it.isNotBlank() } ?: m.artistUri
                    val prog = progressMs ?: m.progressMs
                    val dur = durationMs ?: m.durationMs
                    if (
                        !m.isNowPlaying ||
                        m.isPlaying != playing ||
                        m.progressMs != prog ||
                        m.durationMs != dur ||
                        m.albumArtUrl != art ||
                        m.artistArtUrl != aArt ||
                        m.albumUri != aUri ||
                        m.artistUri != arUri
                    ) {
                        chatLog[i] = m.copy(
                            isNowPlaying = true,
                            isPlaying = playing,
                            progressMs = prog,
                            durationMs = dur,
                            albumArtUrl = art,
                            artistArtUrl = aArt,
                            albumUri = aUri,
                            artistUri = arUri,
                        )
                        changed = true
                    }
                } else if (m.isNowPlaying || m.isPlaying) {
                    chatLog[i] = m.copy(isNowPlaying = false, isPlaying = false, progressMs = 0L)
                    changed = true
                }
            }
        }
        // Progress updates every poll — skip disk I/O.
        if (changed) publish(persist = false)
    }

    private fun appendChat(msg: DjChatMessage) {
        val cleaned = when (msg.role) {
            DjChatRole.Dj -> msg.copy(text = sanitizeSpoken(msg.text))
            else -> msg
        }
        synchronized(chatLog) {
            chatLog.add(cleaned)
            trimChatLocked()
        }
        publish(persist = true)
    }

    private fun replaceStreaming(id: String, text: String) {
        val cleaned = sanitizeSpoken(text)
        synchronized(chatLog) {
            val idx = chatLog.indexOfLast { it.id == id }
            if (idx >= 0) {
                chatLog[idx] = chatLog[idx].copy(text = cleaned, streaming = false)
            } else {
                chatLog.add(
                    DjChatMessage(
                        id = id,
                        role = DjChatRole.Dj,
                        text = cleaned,
                        streaming = false,
                    ),
                )
            }
            trimChatLocked()
        }
        publish(persist = true)
    }

    private fun trimChatLocked() {
        while (chatLog.size > MAX_DJ_CHAT_MESSAGES) {
            chatLog.removeAt(0)
        }
    }

    /**
     * @param replace when true, clears the upcoming queue first and rotates seed mode
     * so the next batch feels like a *new* set (vs refill, which only appends).
     */
    private fun fillQueue(useAi: Boolean, force: Boolean = false, replace: Boolean = false) {
        if (!store.enabled && !force) return
        if (!filling.compareAndSet(false, true)) return
        val statusStart = if (replace) {
            "New queue — clearing upcoming, rebuilding from liked · top · recent…"
        } else {
            "Building radio from liked · top · recent…"
        }
        publish(filling = true, status = statusStart)
        try {
            if (replace) {
                synchronized(queue) { queue.clear() }
                // Spotify cannot clear Up Next via API — forget our sync bookkeeping so the
                // new set is re-POSTed; stale Up Next items may still play first once.
                syncedToSpotifyUris.clear()
                radioModeIdx = (radioModeIdx + 1) % 4
                // Soft-forget oldest half of played so a “new” set has room to breathe.
                if (playedUris.size > 40) {
                    val drop = playedUris.size / 2
                    val keys = playedUris.keys.take(drop)
                    keys.forEach { playedUris.remove(it) }
                    store.savePlayedUris(playedUris.toMap())
                }
                persistRuntimeState()
                publish(status = "Queue cleared — gathering a fresh pool…", clearError = true)
            }

            val curForPool = current
            // Drop any UP NEXT rows already heard (recently played / skipped past)
            pruneQueueOfPlayed()
            var pool = gatherRadioPool(curForPool)
            // If replace still yields nothing (everything marked played), forget more —
            // but keep the freshest recently-played exclusions (re-fetch will re-mark).
            if (replace && pool.size < 8 && playedUris.isNotEmpty()) {
                val keys = playedUris.keys.take(playedUris.size.coerceAtLeast(1) / 2)
                keys.forEach { playedUris.remove(it) }
                store.savePlayedUris(playedUris.toMap())
                pool = gatherRadioPool(curForPool)
            }
            publish(status = "Radio pool ${pool.size} — picking…")

            var batch: List<DjQueueTrack> = emptyList()
            var aiBanterLine: String? = null
            if (useAi && pool.size >= 8) {
                publish(status = "Asking host Grok Build to shape the set…")
                val ai = aiPickFromPool(pool, curForPool, if (replace) 8 else 6)
                if (ai != null) {
                    batch = ai.first
                    if (ai.second.isNotBlank()) aiBanterLine = ai.second
                }
            }
            if (batch.isEmpty()) {
                batch = pickFromPool(pool, curForPool, (if (replace) 8 else 6) + (0..3).random())
                aiBanterLine = null
            }
            // Never re-add recently played / already heard
            batch = batch.filter { !isPlayed(it.uri) && it.uri != curForPool?.uri }
            val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
            synchronized(queue) {
                batch.forEach { t ->
                    if (queue.none { it.uri == t.uri } && !isPlayed(t.uri)) queue.addLast(t)
                }
                while (queue.size > MAX_DJ_QUEUE) queue.removeLast()
            }
            val newHead = synchronized(queue) { queue.firstOrNull()?.uri }
            // Drop caches keyed to the old head when the set changes.
            if (prevHead != newHead || replace) {
                invalidateStaleBanterCaches(newHead)
            }
            // Key fill-banter to the actual head of the queue after merge, not a stale pick.
            if (!aiBanterLine.isNullOrBlank() && newHead != null && batch.any { it.uri == newHead }) {
                pendingBanter = aiBanterLine
                pendingBanterForUri = newHead
            }
            persistRuntimeState()
            if (batch.isNotEmpty()) {
                publish(
                    status = if (replace) {
                        "New list · ${batch.size} tracks (direct-play)"
                    } else {
                        "Queued ${batch.size} · total ${queue.size} in app list"
                    },
                    clearError = true,
                )
            } else {
                publish(
                    status = "Could not find more tracks — play something or check Spotify auth",
                    error = "empty_pool",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "fillQueue", e)
            publish(status = "Queue fill error: ${e.message}", error = e.message)
        } finally {
            filling.set(false)
            publish(filling = false)
        }
    }

    /**
     * Spotify-AI-DJ-style pool: seed from liked, top tracks/artists, recently played,
     * then expand via artist radio (top tracks + related artists) and a few playlists.
     */
    private fun gatherRadioPool(current: DjQueueTrack?): List<DjQueueTrack> {
        val pool = ArrayList<DjQueueTrack>(120)
        val seen = HashSet<String>()
        val seedTracks = ArrayList<DjQueueTrack>(40)
        val seedArtistIds = LinkedHashSet<String>()

        fun add(
            uri: String,
            name: String,
            artists: String,
            reason: String,
            artistIds: List<String> = emptyList(),
            albumArtUrl: String = "",
            artistArtUrl: String = "",
            albumUri: String = "",
            artistUri: String = "",
            asSeed: Boolean = false,
        ) {
            if (uri.isBlank() || uri.contains("local:")) return
            if (!seen.add(uri)) return
            if (isPlayed(uri)) return
            if (current?.uri == uri) return
            val t = DjQueueTrack(
                uri = uri,
                name = name,
                artists = artists,
                reason = reason,
                artistIds = artistIds,
                albumArtUrl = albumArtUrl,
                artistArtUrl = artistArtUrl,
                albumUri = albumUri,
                artistUri = artistUri,
            )
            pool.add(t)
            if (asSeed) seedTracks.add(t)
            artistIds.forEach { if (it.isNotBlank()) seedArtistIds.add(it) }
        }

        fun addFromTrackObj(t: JSONObject?, reason: String, asSeed: Boolean = false) {
            if (t == null || t.optBoolean("is_local", false)) return
            val ids = artistIdsOf(t)
            add(
                uri = t.optString("uri", ""),
                name = t.optString("name", ""),
                artists = artistsOf(t),
                reason = reason,
                artistIds = ids,
                albumArtUrl = albumArtUrlOf(t),
                // Artist portraits fetched only for now-playing / chat bubbles (API rate limits).
                artistArtUrl = "",
                albumUri = albumUriOf(t),
                artistUri = artistUriOf(t),
                asSeed = asSeed,
            )
        }

        // 1) Recently played — EXCLUDE from the radio queue (already listened), but still
        // harvest artists / seed tracks for radio expansion so the set stays in the vibe.
        val recent = spotifyGet("/v1/me/player/recently-played?limit=50")
        if (recent.ok) {
            val items = recent.json?.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val t = it.optJSONObject("track") ?: continue
                    if (t.optBoolean("is_local", false)) continue
                    val uri = t.optString("uri", "")
                    if (uri.isNotBlank()) {
                        // Durable exclude so refill / AI pick won't re-queue this cut
                        markPlayed(uri)
                        seen.add(uri) // keep out of pool even if mark is flushed later mid-fill
                    }
                    val ids = artistIdsOf(t)
                    ids.forEach { if (it.isNotBlank()) seedArtistIds.add(it) }
                    // Seed for song_radio expansion only — not a queue candidate
                    if (uri.isNotBlank()) {
                        seedTracks.add(
                            DjQueueTrack(
                                uri = uri,
                                name = t.optString("name", ""),
                                artists = artistsOf(t),
                                reason = "recently played (exclude)",
                                artistIds = ids,
                                albumArtUrl = albumArtUrlOf(t),
                                albumUri = albumUriOf(t),
                                artistUri = artistUriOf(t),
                            ),
                        )
                    }
                }
                Log.i(TAG, "recently-played exclude seeds=${seedTracks.size} artists=${seedArtistIds.size}")
            }
        }

        // 2) Top tracks (short + medium term — what Spotify DJ leans on)
        for (range in listOf("short_term", "medium_term")) {
            val top = spotifyGet("/v1/me/top/tracks?time_range=$range&limit=20")
            if (top.ok) {
                val items = top.json?.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        addFromTrackObj(items.optJSONObject(i), "top tracks ($range)", asSeed = true)
                    }
                }
            }
        }

        // 3) Top artists → later expand as artist radio
        for (range in listOf("short_term", "medium_term")) {
            val topA = spotifyGet("/v1/me/top/artists?time_range=$range&limit=15")
            if (topA.ok) {
                val items = topA.json?.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val a = items.optJSONObject(i) ?: continue
                        val id = a.optString("id", "")
                        if (id.isNotBlank()) seedArtistIds.add(id)
                    }
                }
            }
        }

        // 4) Liked / saved tracks
        val liked = spotifyGet("/v1/me/tracks?limit=40")
        if (liked.ok) {
            val items = liked.json?.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    addFromTrackObj(it.optJSONObject("track"), "liked songs", asSeed = true)
                }
            }
        }

        // Current track's artists are strong radio seeds
        current?.artistIds?.forEach { if (it.isNotBlank()) seedArtistIds.add(it) }
        if (current != null && current.artistIds.isEmpty()) {
            val hint = primaryArtist(current.artists)
            if (hint.isNotBlank()) {
                val q = java.net.URLEncoder.encode(hint, "UTF-8")
                val s = spotifyGet("/v1/search?type=artist&limit=1&q=$q")
                val id = s.json?.optJSONObject("artists")
                    ?.optJSONArray("items")
                    ?.optJSONObject(0)
                    ?.optString("id", "")
                if (!id.isNullOrBlank()) seedArtistIds.add(id)
            }
        }

        // Rotate radio modes like Spotify's DJ (artist radio vs song-adjacent vs liked)
        val modes = listOf("artist_radio", "song_radio", "liked_blend", "top_blend")
        val mode = modes[radioModeIdx % modes.size]
        radioModeIdx++

        // 5) Artist radio: top tracks + related artists for a handful of seeds
        val artistPick = seedArtistIds.shuffled().take(
            when (mode) {
                "artist_radio" -> 6
                "song_radio" -> 3
                else -> 4
            },
        )
        for (aid in artistPick) {
            val tops = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/top-tracks?market=US",
            )
            val tracks = tops.json?.optJSONArray("tracks")
            if (tracks != null) {
                val pick = (0 until tracks.length()).shuffled().take(4)
                for (j in pick) {
                    addFromTrackObj(tracks.optJSONObject(j), "artist radio")
                }
            }
            if (mode == "artist_radio" || mode == "liked_blend") {
                val rel = spotifyGet(
                    "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/related-artists",
                )
                val related = rel.json?.optJSONArray("artists")
                if (related != null) {
                    val rPick = (0 until related.length()).shuffled().take(2)
                    for (j in rPick) {
                        val ra = related.optJSONObject(j) ?: continue
                        val rid = ra.optString("id", "")
                        if (rid.isBlank()) continue
                        val rt = spotifyGet(
                            "/v1/artists/${java.net.URLEncoder.encode(rid, "UTF-8")}/top-tracks?market=US",
                        )
                        val rTracks = rt.json?.optJSONArray("tracks") ?: continue
                        val k = (0 until rTracks.length()).shuffled().take(2)
                        for (m in k) {
                            addFromTrackObj(rTracks.optJSONObject(m), "related artist radio")
                        }
                    }
                }
            }
        }

        // 6) Song radio: more from primary artists of seed tracks (when mode wants it)
        if (mode == "song_radio" || mode == "top_blend") {
            val seeds = seedTracks.shuffled().take(5)
            for (s in seeds) {
                val aid = s.artistIds.firstOrNull().orEmpty()
                if (aid.isBlank()) continue
                val tops = spotifyGet(
                    "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/top-tracks?market=US",
                )
                val tracks = tops.json?.optJSONArray("tracks") ?: continue
                val pick = (0 until tracks.length()).shuffled().take(3)
                for (j in pick) {
                    addFromTrackObj(tracks.optJSONObject(j), "song radio")
                }
            }
        }

        // 7) A couple of user playlists for variety
        val plsRes = spotifyGet("/v1/me/playlists?limit=40")
        val playlists = plsRes.json?.optJSONArray("items")
        if (playlists != null && playlists.length() > 0) {
            val indices = (0 until playlists.length()).shuffled().take(3)
            for (idx in indices) {
                val pl = playlists.optJSONObject(idx) ?: continue
                val id = pl.optString("id", "")
                if (id.isBlank()) continue
                val plName = pl.optString("name", "set")
                val tr = spotifyGet(
                    "/v1/playlists/${java.net.URLEncoder.encode(id, "UTF-8")}/tracks?limit=30",
                )
                val items = tr.json?.optJSONArray("items") ?: continue
                val pick = (0 until items.length()).shuffled().take(8)
                for (j in pick) {
                    val it = items.optJSONObject(j) ?: continue
                    addFromTrackObj(it.optJSONObject("track"), "playlist: $plName")
                }
            }
        }

        return pool
    }

    private fun pickFromPool(
        pool: List<DjQueueTrack>,
        current: DjQueueTrack?,
        n: Int,
    ): List<DjQueueTrack> {
        if (pool.isEmpty()) return emptyList()
        val curArtists = current?.artists.orEmpty().lowercase()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        // Avoid stacking the same primary artist back-to-back in the batch
        val batchArtists = mutableSetOf<String>()
        val scored = pool.map { t ->
            var score = Math.random() * 2.5
            val arts = t.artists.lowercase()
            for (a in curArtists) {
                if (arts.contains(a)) score += 3.5
            }
            when {
                t.reason.contains("liked") -> score += 2.0
                t.reason.contains("top tracks") -> score += 1.8
                // recently played are excluded from pool (used only as radio seeds)
                t.reason.contains("artist radio") -> score += 1.6
                t.reason.contains("related") -> score += 1.3
                t.reason.contains("song radio") -> score += 1.5
                t.reason.startsWith("playlist") -> score += 0.7
            }
            t to score
        }.sortedByDescending { it.second }

        val out = ArrayList<DjQueueTrack>(n)
        for ((t, _) in scored) {
            if (out.size >= n) break
            val primary = primaryArtist(t.artists).lowercase()
            if (primary.isNotBlank() && primary in batchArtists && out.size < n - 1) {
                // soft skip — leave room, try later if needed
                continue
            }
            if (primary.isNotBlank()) batchArtists.add(primary)
            out.add(t)
        }
        // Fill remaining if artist diversity filter was too strict
        if (out.size < n) {
            for ((t, _) in scored) {
                if (out.size >= n) break
                if (out.any { it.uri == t.uri }) continue
                out.add(t)
            }
        }
        return out.shuffled()
    }

    private fun aiPickFromPool(
        pool: List<DjQueueTrack>,
        current: DjQueueTrack?,
        n: Int,
    ): Pair<List<DjQueueTrack>, String>? {
        if (pool.isEmpty()) return null
        val sample = pool.shuffled().take(45)
        val curLine = current?.let {
            "${cleanTitle(it.name)} — ${primaryArtist(it.artists)}"
        } ?: "nothing specific"
        val list = sample.mapIndexed { i, t ->
            "${i + 1}. ${cleanTitle(t.name)} — ${primaryArtist(t.artists)} [${t.uri}] (${t.reason})"
        }.joinToString("\n")
        val system =
            "You are a radio DJ music director (Spotify DJ style). Reply ONLY with valid JSON: " +
                "{\"picks\":[{\"uri\":\"spotify:track:...\",\"banter_note\":\"short why\"}],\"banter\":\"\"}. " +
                "Pick exactly $n tracks from the CANDIDATES list only (use their uris). " +
                "Blend liked/top seeds with artist-radio variety. Candidates already exclude " +
                "recently played and already-heard tracks — never re-pick those. Avoid stacking the same " +
                "primary artist twice in a row. Leave banter empty (spoken lines are generated separately). " +
                "No markdown."
        val prompt =
            "CURRENT: $curLine\n\nCANDIDATES (not recently played):\n$list\n\n" +
                "Pick $n next tracks for a continuous live DJ set. Do not repeat recently heard songs."
        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify Live DJ")
            .toString()
        val raw = HostAiClient.complete(applicationContext, prompt, opts)
        val res = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!res.optBoolean("ok")) return null
        val text = res.optString("text", "")
        val json = extractJson(text) ?: return null
        val picks = json.optJSONArray("picks") ?: return null
        val byUri = sample.associateBy { it.uri }
        val out = ArrayList<DjQueueTrack>()
        for (i in 0 until picks.length()) {
            val p = picks.optJSONObject(i) ?: continue
            val uri = p.optString("uri", "")
            val hit = byUri[uri] ?: continue
            if (isPlayed(hit.uri)) continue
            out.add(
                hit.copy(reason = p.optString("banter_note", hit.reason).ifBlank { hit.reason }),
            )
            if (out.size >= n) break
        }
        val banter = json.optString("banter", "")
        return if (out.isNotEmpty()) out to banter else null
    }

    /**
     * Spoken banter: research recent news/shows/song facts via host Grok tools,
     * then write casual radio phrasing. Falls back to local templates.
     *
     * @param allowResearch when false (live handoff), only reuse cached research bullets —
     *   never start a new tool-backed lookup mid-outro (that raced and cut songs short).
     */
    private fun generateBanter(
        prev: DjQueueTrack?,
        next: DjQueueTrack?,
        allowResearch: Boolean = true,
    ): String {
        // One-shot line from AI queue-shape — only if it was written for this exact next URI.
        val pending = pendingBanter
        val pendingUri = pendingBanterForUri
        if (!pending.isNullOrBlank()) {
            if (next != null && pendingUri == next.uri) {
                pendingBanter = null
                pendingBanterForUri = null
                val cleaned = sanitizeSpoken(pending)
                if (cleaned.isNotBlank() && looksLikeSpokenLine(cleaned)) {
                    return cleaned.take(320)
                }
            } else {
                // Stale or unkeyed — never speak a line that names the wrong cut.
                Log.i(
                    TAG,
                    "skip pending banter (for=$pendingUri next=${next?.uri})",
                )
                pendingBanter = null
                pendingBanterForUri = null
            }
        }
        val ai = aiBanterLine(prev, next, allowResearch = allowResearch)
        if (!ai.isNullOrBlank()) return sanitizeSpoken(ai).take(320)
        return localBanterLine(prev, next)
    }

    /**
     * Tool-backed research for on-air color: recent news, upcoming shows, song/artist facts.
     * Returns short bullets (empty if nothing solid). Cached per next-track URI.
     */
    private fun researchMusicFacts(
        artist: String,
        song: String,
        prevArtist: String?,
    ): List<String> {
        val a = artist.trim()
        val s = song.trim()
        if (a.isBlank() && s.isBlank()) return emptyList()

        val system =
            "You are a music fact researcher for a live radio AI DJ. " +
                "You HAVE tools/web search — USE them to look up real, recent information. " +
                "Research the artist and/or song below for:\n" +
                "1) Recent news, viral moments, releases, collabs (prefer last ~18 months)\n" +
                "2) Upcoming tours, festivals, residencies, or announced shows (only if verified)\n" +
                "3) Interesting song facts (writers, samples, chart peaks, meaning, awards)\n" +
                "4) One short career tidbit listeners would enjoy\n" +
                "Reply with JSON ONLY (no markdown fences):\n" +
                "{\"facts\":[\"short bullet ≤22 words\"],\"shows\":[\"city/date or tour name if real\"]}\n" +
                "Rules: max 4 facts + 2 shows; prefer verifiable/recent; if nothing solid, use empty arrays; " +
                "NEVER invent tour dates, chart numbers, or news. No commentary outside JSON."

        val prompt = buildString {
            appendLine("Primary artist: ${a.ifBlank { "(unknown)" }}")
            if (s.isNotBlank()) appendLine("Song / track: $s")
            if (!prevArtist.isNullOrBlank() && !prevArtist.equals(a, ignoreCase = true)) {
                appendLine("Previous artist (optional color only): $prevArtist")
            }
            appendLine()
            appendLine(
                "Use web search / tools now. Return JSON with real bullets the DJ can speak " +
                    "(news, upcoming shows, song facts).",
            )
        }

        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify Live DJ Research")
            .toString()

        return try {
            val raw = HostAiClient.complete(applicationContext, prompt, opts)
            val env = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
            if (!env.optBoolean("ok")) {
                Log.w(TAG, "research facts failed: ${env.optString("error")}")
                return emptyList()
            }
            val text = env.optString("text", "").trim()
            if (text.isBlank()) return emptyList()
            val json = extractJson(text) ?: return emptyList()
            val out = ArrayList<String>(6)
            val facts = json.optJSONArray("facts")
            if (facts != null) {
                for (i in 0 until facts.length()) {
                    val f = facts.optString(i, "").trim()
                    if (f.isNotBlank()) out.add(f.take(160))
                    if (out.size >= 4) break
                }
            }
            val shows = json.optJSONArray("shows")
            if (shows != null) {
                for (i in 0 until shows.length()) {
                    val sh = shows.optString(i, "").trim()
                    if (sh.isNotBlank()) out.add("Upcoming: ${sh.take(140)}")
                    if (out.size >= 6) break
                }
            }
            // Also accept a flat "bullets" array if the model drifts.
            if (out.isEmpty()) {
                val bullets = json.optJSONArray("bullets")
                if (bullets != null) {
                    for (i in 0 until minOf(4, bullets.length())) {
                        val b = bullets.optString(i, "").trim()
                        if (b.isNotBlank()) out.add(b.take(160))
                    }
                }
            }
            Log.i(TAG, "research facts for “$a” / “$s”: ${out.size} bullets")
            out
        } catch (e: Exception) {
            Log.w(TAG, "researchMusicFacts: ${e.message}")
            emptyList()
        }
    }

    private fun factsForTrack(
        prev: DjQueueTrack?,
        next: DjQueueTrack?,
        allowResearch: Boolean,
    ): List<String> {
        val uri = next?.uri.orEmpty()
        if (uri.isNotBlank() && researchedForUri == uri) {
            return researchedFacts
        }
        if (!allowResearch) {
            // Handoff path: never block on tools; empty facts → pure handoff line.
            return emptyList()
        }
        val nextArtist = next?.let { primaryArtist(it.artists) }.orEmpty()
        val nextTitle = next?.let { cleanTitle(it.name) }.orEmpty()
        val prevArtist = prev?.let { primaryArtist(it.artists) }
        val facts = researchMusicFacts(nextArtist, nextTitle, prevArtist)
        if (uri.isNotBlank()) {
            researchedForUri = uri
            researchedFacts = facts
        }
        return facts
    }

    private fun aiBanterLine(
        prev: DjQueueTrack?,
        next: DjQueueTrack?,
        allowResearch: Boolean = true,
    ): String? {
        val prevTitle = prev?.let { cleanTitle(it.name) }.orEmpty()
        val prevArtist = prev?.let { primaryArtist(it.artists) }.orEmpty()
        val nextTitle = next?.let { cleanTitle(it.name) }.orEmpty()
        val nextArtist = next?.let { primaryArtist(it.artists) }.orEmpty()
        val nextAllArtists = next?.artists.orEmpty()
        val reason = next?.reason.orEmpty()

        // Step 1: tool-backed research only during prefetch; handoff reuses cache.
        val research = factsForTrack(prev, next, allowResearch = allowResearch)

        val system =
            "You are a live radio AI DJ speaking aloud ON AIR. Write 1–3 short sentences (max 52 words). Rules:\n" +
                "• Casual, warm, varied — never robotic credits.\n" +
                "• Prefer vibe intros over full titles: e.g. \"finishing up with some Morgan Wallen\" " +
                "or \"sliding into a little [short title]\" — not \"That was Song Name by Artist Name\".\n" +
                "• Song titles often include (feat. X) / (with X) / - feat. X. NEVER read parentheses " +
                "featuring credits. NEVER say the artist name twice because it appears in the title " +
                "and the artist field. Use the CLEAN title and PRIMARY artist only.\n" +
                "• RESEARCHED FACTS (when provided): weave ONE short fact, news bit, or upcoming-show " +
                "note into the banter as natural radio color — then hand off with " +
                "\"here's [clean title] by [primary artist]\" or similar. Prefer the most recent/interesting bullet.\n" +
                "• If RESEARCHED FACTS is empty, still do a solid handoff; do not invent tour dates or news.\n" +
                "• CRITICAL: Output ONLY words a listener would hear on the radio. " +
                "NEVER narrate process, research, tools, planning, or drafting. Forbidden phrases include: " +
                "\"Checking for…\", \"Looking up…\", \"Searching…\", \"Before writing…\", \"Let me…\", " +
                "\"I'll check…\", \"public tidbit\", \"writing the DJ line\", \"as I research\", " +
                "\"according to my research\". " +
                "Do not mention that you looked anything up.\n" +
                "• No markdown, no hashtags, no emoji, no quotation marks wrapping the whole line.\n" +
                "• Always put a space after periods, commas, question marks, and exclamation points " +
                "(e.g. \"…vibe. Here's…\" not \"…vibe.Here's…\").\n" +
                "• Reply with ONLY the on-air spoken line — nothing before or after it."

        val prompt = buildString {
            appendLine("Just played:")
            if (prev != null) {
                appendLine("  raw title: ${prev.name}")
                appendLine("  clean title: $prevTitle")
                appendLine("  artists field: ${prev.artists}")
                appendLine("  primary artist: $prevArtist")
            } else {
                appendLine("  (cold open / nothing specific)")
            }
            appendLine("Up next:")
            if (next != null) {
                appendLine("  raw title: ${next.name}")
                appendLine("  clean title: $nextTitle")
                appendLine("  artists field: $nextAllArtists")
                appendLine("  primary artist: $nextArtist")
                appendLine("  how we picked it: $reason")
            } else {
                appendLine("  (still digging in the library)")
            }
            appendLine()
            if (research.isNotEmpty()) {
                appendLine("RESEARCHED FACTS (verified for this handoff — use one if it fits naturally):")
                research.forEachIndexed { i, f -> appendLine("  ${i + 1}. $f") }
            } else {
                appendLine("RESEARCHED FACTS: (none solid — pure handoff, no invented news)")
            }
            appendLine()
            appendLine(
                "Write the on-air line: close out the previous vibe, optionally drop one researched fact " +
                    "about $nextArtist / $nextTitle (or a real upcoming show), then introduce the next track.",
            )
            appendLine("On-air DJ line only:")
        }

        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify Live DJ Banter")
            .toString()
        return try {
            val raw = HostAiClient.complete(applicationContext, prompt, opts)
            val res = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (!res.optBoolean("ok")) return null
            val text = res.optString("text", "").trim()
            if (text.isBlank()) return null
            // Strip accidental JSON / fences, then drop process-narration sentences.
            val cleaned = stripMetaNarration(
                text
                    .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("```\\s*$"), "")
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'"),
            )
            if (!looksLikeSpokenLine(cleaned)) return null
            cleaned
        } catch (e: Exception) {
            Log.w(TAG, "ai banter failed: ${e.message}")
            null
        }
    }

    private fun localBanterLine(prev: DjQueueTrack?, next: DjQueueTrack?): String {
        val prevArt = prev?.let { primaryArtist(it.artists) }.orEmpty()
        val nextArt = next?.let { primaryArtist(it.artists) }.orEmpty()
        val nextTitle = next?.let { cleanTitle(it.name) }.orEmpty()

        val close = when {
            prevArt.isNotBlank() -> listOf(
                "Finishing up with some $prevArt.",
                "That $prevArt cut was a vibe.",
                "Rolling out of $prevArt.",
                "Alright, parking that $prevArt one.",
            ).random()
            else -> listOf(
                "Alright, keeping the set moving.",
                "Stay with me — next one's loaded.",
            ).random()
        }

        val open = when {
            nextArt.isNotBlank() && nextTitle.isNotBlank() -> listOf(
                "Up next, $nextArt — here's $nextTitle.",
                "This next one's $nextArt with $nextTitle.",
                "Sliding into $nextTitle by $nextArt.",
                "Coming up: a little $nextArt — $nextTitle.",
            ).random()
            nextArt.isNotBlank() -> listOf(
                "Up next, some $nextArt.",
                "Here's a little $nextArt for you.",
            ).random()
            else -> "Give me a second while I dig through your library."
        }

        return sanitizeSpoken("$close $open").take(280)
    }

    private fun looksLikeSpokenLine(s: String): Boolean {
        // Evaluate the on-air remainder after dropping process-narration.
        val body = stripMetaNarration(s).ifBlank { s.trim() }
        if (body.length < 8 || body.length > 400) return false
        if (body.startsWith("{") || body.startsWith("[")) return false
        if (body.contains("```")) return false
        // Reject if nothing usable remains but the raw text was pure meta.
        if (isMetaNarrationSentence(body)) return false
        return true
    }

    /**
     * Drop model "thinking out loud" / research-process sentences that leak into
     * TTS (e.g. "Checking for a real public tidbit… before writing the DJ line.").
     */
    private fun stripMetaNarration(s: String): String {
        val raw = s.trim()
        if (raw.isEmpty()) return raw
        // Prefer sentence splits; also split on newlines the model sometimes leaves.
        val parts = raw
            .replace(Regex("[\\r\\n]+"), " ")
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val kept = parts.filterNot { isMetaNarrationSentence(it) }
        return kept.joinToString(" ").trim()
    }

    private fun isMetaNarrationSentence(sentence: String): Boolean {
        val t = sentence.trim()
        if (t.isEmpty()) return false
        // Whole-line / sentence openers that are process talk, not radio copy.
        if (
            META_NARRATION_OPENER.containsMatchIn(t) ||
            META_NARRATION_PHRASE.containsMatchIn(t)
        ) {
            return true
        }
        return false
    }

    /**
     * Normalize DJ talk for chat + TTS: collapse whitespace and fix common
     * model glitches like "word.Next" (missing space after punctuation) or
     * "word ." (space before punctuation). Also strips process-narration leaks.
     */
    private fun sanitizeSpoken(s: String): String {
        var t = stripMetaNarration(s)
            .replace('\u00A0', ' ')
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Strip wrapping quotes the model sometimes adds around the whole line.
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length - 1).trim()
        }
        // No space before sentence / clause punctuation.
        t = t.replace(Regex("\\s+([,.;:!?…])"), "$1")
        // Space after sentence-ending punctuation when the next token starts a word.
        // Avoid digits so we don't break "3.14" / track numbers.
        t = t.replace(Regex("([.!?…])([A-Za-z\"'“‘])"), "$1 $2")
        // Space after commas / semicolons / colons when missing (letters only).
        t = t.replace(Regex("([,;:])([A-Za-z\"'“‘])"), "$1 $2")
        // Collapse runs of spaces introduced above.
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    /** Drop (feat./ft./with/…) and trailing - feat tags so TTS doesn't double-credit. */
    private fun cleanTitle(name: String): String {
        if (name.isBlank()) return name
        var t = name
        t = t.replace(
            Regex(
                """\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring|with|prod\.?|produced by)[^\)\]]*[\)\]]""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        t = t.replace(
            Regex(
                """\s*-\s*(?:feat\.?|ft\.?|featuring|with)\s+.+$""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        // Common remaster/version noise for speech
        t = t.replace(
            Regex(
                """\s*[\(\[]\s*(?:remaster(?:ed)?(?:\s+\d{4})?|radio edit|album version|single version|explicit|clean version)\s*[\)\]]""",
                RegexOption.IGNORE_CASE,
            ),
            "",
        )
        return t.replace(Regex("\\s+"), " ").trim().ifBlank { name }
    }

    private fun primaryArtist(artists: String): String =
        artists.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun markPlayed(uri: String) {
        if (uri.isBlank()) return
        playedUris[uri] = System.currentTimeMillis()
        while (playedUris.size > 250) {
            val first = playedUris.entries.firstOrNull()?.key ?: break
            playedUris.remove(first)
        }
        // Cheap debounce: only flush played set every ~8 marks via size check
        if (playedUris.size % 4 == 0) {
            store.savePlayedUris(playedUris.toMap())
        }
    }

    private fun isPlayed(uri: String): Boolean = playedUris.containsKey(uri)

    /**
     * Remove UP NEXT rows that are already in the played / recently-heard set so the
     * Live DJ list cannot stay “ahead” of songs Spotify already finished or skipped.
     */
    private fun pruneQueueOfPlayed() {
        val before = synchronized(queue) { queue.size }
        val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
        synchronized(queue) {
            val keep = queue.filterNot { isPlayed(it.uri) || it.uri == current?.uri }
            if (keep.size == queue.size) return
            queue.clear()
            keep.forEach { queue.addLast(it) }
        }
        val after = synchronized(queue) { queue.size }
        if (before != after) {
            val newHead = synchronized(queue) { queue.firstOrNull()?.uri }
            if (prevHead != newHead) invalidateStaleBanterCaches(newHead)
            Log.i(TAG, "pruneQueueOfPlayed $before → $after")
            persistRuntimeState()
        }
    }

    private fun pickDeviceId(): String? {
        val preferred = SpotifyControllerStore(this).preferredDeviceId.trim()
        val res = spotifyGet("/v1/me/player/devices")
        val devices = res.json?.optJSONArray("devices") ?: return preferred.ifBlank { null }
        var active: String? = null
        var preferredFound: String? = null
        var any: String? = null
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val id = d.optString("id", "")
            if (id.isBlank()) continue
            if (any == null) any = id
            if (d.optBoolean("is_active", false)) active = id
            if (preferred.isNotBlank() && id == preferred) preferredFound = id
        }
        // Prefer last device the user picked in Control, then active Connect device, then any.
        return preferredFound ?: active ?: any ?: preferred.ifBlank { null }
    }

    private fun openSpotifyUri(uri: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.spotify.music")
            }
            startActivity(intent)
        } catch (_: Exception) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Largest album image URL from a Spotify track object. */
    private fun albumArtUrlOf(track: JSONObject): String {
        val images = track.optJSONObject("album")?.optJSONArray("images") ?: return ""
        // Spotify returns images largest-first; fall back to any.
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
        return best
    }

    private fun albumUriOf(track: JSONObject): String {
        val album = track.optJSONObject("album") ?: return ""
        val uri = album.optString("uri", "")
        if (uri.isNotBlank()) return uri
        val id = album.optString("id", "")
        return if (id.isNotBlank()) "spotify:album:$id" else ""
    }

    private fun artistUriOf(track: JSONObject): String {
        val arr = track.optJSONArray("artists") ?: return ""
        val first = arr.optJSONObject(0) ?: return ""
        val uri = first.optString("uri", "")
        if (uri.isNotBlank()) return uri
        val id = first.optString("id", "")
        return if (id.isNotBlank()) "spotify:artist:$id" else ""
    }

    /** Primary artist portrait (cached). */
    private fun artistArtUrlOf(artistId: String): String {
        val id = artistId.trim()
        if (id.isBlank()) return ""
        artistImageCache[id]?.let { return it }
        return try {
            val res = spotifyGet("/v1/artists/$id")
            val images = res.json?.optJSONArray("images")
            var best = ""
            var bestW = -1
            if (images != null) {
                for (i in 0 until images.length()) {
                    val im = images.optJSONObject(i) ?: continue
                    val url = im.optString("url", "")
                    if (url.isBlank()) continue
                    val w = im.optInt("width", 0)
                    // Prefer a mid-size portrait for thumbnails when available.
                    if (best.isBlank() || (w in 160..640 && w > bestW) || (bestW < 0)) {
                        bestW = w
                        best = url
                    }
                }
            }
            if (best.isNotBlank()) artistImageCache[id] = best
            best
        } catch (e: Exception) {
            Log.w(TAG, "artist image $id: ${e.message}")
            ""
        }
    }

    private fun formatClock(ms: Long): String {
        val total = (ms / 1000L).toInt().coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    private fun artistsOf(track: JSONObject): String {
        val arr = track.optJSONArray("artists") ?: return ""
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name", "") ?: ""
            if (n.isNotBlank()) names.add(n)
        }
        return names.joinToString(", ")
    }

    private fun artistIdsOf(track: JSONObject): List<String> {
        val arr = track.optJSONArray("artists") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
            if (id.isNotBlank()) ids.add(id)
        }
        return ids
    }

    private fun extractJson(text: String): JSONObject? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        // fenced block
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(trimmed)
        val candidate = fence?.groupValues?.getOrNull(1)?.trim() ?: trimmed
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(candidate.substring(start, end + 1)) }.getOrNull()
    }

    private data class ApiResult(
        val ok: Boolean,
        val status: Int,
        val json: JSONObject?,
        val error: String?,
    )

    private fun spotifyGet(path: String): ApiResult = spotifyCall("GET", path, null)

    private fun spotifyPut(path: String, body: String?): ApiResult = spotifyCall("PUT", path, body)

    private fun spotifyPost(path: String, body: String?): ApiResult = spotifyCall("POST", path, body)

    /**
     * Legacy “Mirror to Spotify” — direct-play mode never writes Spotify Up Next.
     */
    private fun pushQueueToSpotify() {
        val n = synchronized(queue) { queue.size }
        publish(
            status = "Direct-play mode · $n in app list (Spotify Up Next unused)",
            clearError = true,
        )
        appendChat(
            DjChatMessage(
                id = "sys-direct-${System.currentTimeMillis()}",
                role = DjChatRole.System,
                text = "Live DJ plays each cut directly — Spotify’s queue is not used. " +
                    "UP NEXT in the app is the set we’ll play next.",
            ),
        )
    }

    private fun spotifyCall(method: String, path: String, body: String?): ApiResult {
        val raw = SpotifyOAuth.api(this, method, path, body)
        return try {
            val o = JSONObject(raw)
            val status = o.optInt("status", 0)
            val ok = o.optBoolean("ok", false) || status in listOf(200, 201, 202, 204)
            val bodyStr = o.optString("body", "")
            val json = if (bodyStr.isNotBlank()) {
                runCatching { JSONObject(bodyStr) }.getOrNull()
            } else null
            val err = if (o.isNull("error")) null else o.optString("error").ifBlank { null }
            ApiResult(ok = ok, status = status, json = json, error = err)
        } catch (e: Exception) {
            ApiResult(false, 0, null, e.message)
        }
    }

    private fun publish(
        status: String? = null,
        nowLine: String? = null,
        error: String? = null,
        clearError: Boolean = false,
        transitioning: Boolean? = null,
        filling: Boolean? = null,
        loggedIn: Boolean? = null,
        chatBusy: Boolean? = null,
        /** Write chatLog to SharedPreferences (skip on high-frequency progress ticks). */
        persist: Boolean = false,
    ) {
        val q = synchronized(queue) { queue.toList() }
        val msgs = synchronized(chatLog) { chatLog.toList() }
        if (persist) {
            store.saveMessages(msgs)
            // Queue also saved when chat structure changes (cheap + keeps leave/return in sync)
            store.saveQueue(q)
        }
        val prev = SpotifyDjBus.state.value
        val nextError = when {
            clearError -> null
            error != null -> error
            else -> prev.error
        }
        val until = tracksUntilTalk(songsSinceBanter, banterEvery)
        SpotifyDjBus.publish(
            SpotifyDjUiState(
                enabled = store.enabled,
                status = status ?: prev.status,
                nowLine = nowLine ?: prev.nowLine,
                queue = q,
                messages = msgs,
                chatBusy = chatBusy ?: this.chatBusy.get(),
                transitioning = transitioning ?: this.transitioning.get(),
                filling = filling ?: this.filling.get(),
                loggedIn = loggedIn ?: SpotifyOAuth.isLoggedIn(this),
                error = nextError,
                voiceId = store.voiceId,
                useAiRank = store.useAiRank,
                songsSinceBanter = songsSinceBanter,
                banterEvery = banterEvery,
                tracksUntilTalk = until,
                banterMode = store.banterMode,
                banterFixed = store.banterFixed,
                banterMin = store.banterMin,
                banterMax = store.banterMax,
                allowTalkOver = store.allowTalkOver,
                banterEnabled = store.banterEnabled,
                resumeAfterRestart = store.resumeAfterRestart,
            ),
        )
    }

    /**
     * One shade entry only: share [SPOTIFY_CTRL_NOTIF_ID] with the lockscreen controller.
     * When the controller is on it owns the visible MediaStyle notif; Live DJ only
     * needs startForeground and will not keep a second “Live AI DJ” card.
     */
    private fun startAsForeground(text: String) {
        // Drop the legacy separate Live DJ notification if it still exists.
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(SPOTIFY_DJ_NOTIF_ID)
        }
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    SPOTIFY_CTRL_NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(SPOTIFY_CTRL_NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            runCatching {
                getSystemService(android.app.NotificationManager::class.java)
                    ?.notify(SPOTIFY_CTRL_NOTIF_ID, n)
            }
        }
    }

    private fun updateNotif(text: String) {
        // Controller service refreshes the shared media notification; don't fight it.
        if (SpotifyControllerStore(this).enabled) return
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(SPOTIFY_CTRL_NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("open_app", "spotify_controller")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, SpotifyLiveDjReceiver::class.java).setAction(ACTION_DJ_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Same channel + ID as controller so only one Spotify notification appears.
        val title = current?.name?.takeIf { it.isNotBlank() } ?: "Spotify Controller"
        val body = buildString {
            val arts = current?.artists.orEmpty()
            if (arts.isNotBlank()) append(arts).append(" · ")
            append(text.take(72))
        }
        return NotificationCompat.Builder(this, GrokifyApp.CHANNEL_SPOTIFY_CTRL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("Live DJ")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop DJ", stop)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grokify:spotify_live_dj").apply {
                setReferenceCounted(false)
                // 30 min chunks; [maybeRefreshWakeLock] renews while the loop runs.
                acquire(30 * 60 * 1000L)
            }
            lastWakeRefreshMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock: ${e.message}")
        }
    }

    /** Renew the partial wake lock so multi-hour background DJ sessions keep polling. */
    private fun maybeRefreshWakeLock() {
        val now = System.currentTimeMillis()
        if (now - lastWakeRefreshMs < 10 * 60 * 1000L) return
        lastWakeRefreshMs = now
        try {
            val wl = wakeLock
            if (wl == null || !wl.isHeld) {
                acquireWakeLock()
                return
            }
            // Re-acquire with a fresh timeout (reference-counted off → replaces hold).
            wl.acquire(30 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock refresh: ${e.message}")
            acquireWakeLock()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_DJ_STOP = "io.grokify.os.SPOTIFY_DJ_STOP"
        const val ACTION_DJ_SKIP = "io.grokify.os.SPOTIFY_DJ_SKIP"
        /** Intent extra for [ACTION_DJ_SKIP]: true = Skip + talk (force banter). */
        const val EXTRA_FORCE_TALK = "force_talk"
        const val ACTION_DJ_REFILL = "io.grokify.os.SPOTIFY_DJ_REFILL"
        const val ACTION_DJ_NEW_QUEUE = "io.grokify.os.SPOTIFY_DJ_NEW_QUEUE"
        const val ACTION_DJ_REMOVE_TRACK = "io.grokify.os.SPOTIFY_DJ_REMOVE_TRACK"
        const val ACTION_DJ_PLAY_FROM_QUEUE = "io.grokify.os.SPOTIFY_DJ_PLAY_FROM_QUEUE"
        /** Direct-play a URI from chat history (no queue membership required). */
        const val ACTION_DJ_PLAY_URI = "io.grokify.os.SPOTIFY_DJ_PLAY_URI"
        const val ACTION_DJ_PAUSE_TOGGLE = "io.grokify.os.SPOTIFY_DJ_PAUSE_TOGGLE"
        const val ACTION_DJ_PREVIOUS = "io.grokify.os.SPOTIFY_DJ_PREVIOUS"
        const val ACTION_DJ_SYNC_SPOTIFY = "io.grokify.os.SPOTIFY_DJ_SYNC_SPOTIFY"
        const val ACTION_DJ_ADD_TO_SPOTIFY_QUEUE = "io.grokify.os.SPOTIFY_DJ_ADD_TO_SPOTIFY_QUEUE"
        const val ACTION_DJ_CHAT = "io.grokify.os.SPOTIFY_DJ_CHAT"
        const val ACTION_DJ_RELOAD_SETTINGS = "io.grokify.os.SPOTIFY_DJ_RELOAD_SETTINGS"
        const val EXTRA_CHAT_TEXT = "chat_text"
        const val EXTRA_TRACK_URI = "track_uri"
        const val EXTRA_QUEUE_INDEX = "queue_index"
        const val EXTRA_TRACK_NAME = "track_name"
        const val EXTRA_TRACK_ARTISTS = "track_artists"
        const val EXTRA_ALBUM_ART = "album_art"
        const val EXTRA_ARTIST_ART = "artist_art"
        const val EXTRA_ALBUM_URI = "album_uri"
        const val EXTRA_ARTIST_URI = "artist_uri"
    }
}

class SpotifyLiveDjReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            SpotifyLiveDjService.ACTION_DJ_STOP -> setSpotifyLiveDjEnabled(context, false)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // OTA / reboot: honor resume-after-restart (keeps queue + settings)
                maybeResumeLiveDj(context)
            }
        }
    }
}
