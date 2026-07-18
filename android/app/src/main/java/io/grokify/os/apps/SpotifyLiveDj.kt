package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpotifyLiveDj"
const val SPOTIFY_DJ_NOTIF_ID = 47002

/**
 * Exposes the Live DJ [MediaSessionCompat] token so the lockscreen / shade
 * MediaStyle card can attach it (SystemUI media carousel requires a session token).
 */
object LiveDjMediaSessionHolder {
    @Volatile private var session: MediaSessionCompat? = null

    fun publish(s: MediaSessionCompat?) {
        session = s
    }

    fun token(): MediaSessionCompat.Token? {
        return try {
            session?.sessionToken
        } catch (_: Exception) {
            null
        }
    }

    fun isActive(): Boolean = try {
        session?.isActive == true
    } catch (_: Exception) {
        false
    }
}

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
/** Permanent track URI blocks from dislike (song / lyrics). */
private const val KEY_DISLIKE_TRACKS = "dislike_tracks_v1"
/** Permanent artist id blocks from dislike. */
private const val KEY_DISLIKE_ARTISTS = "dislike_artists_v1"
/** Temporary "tired of hearing it" cooldowns: uri → untilMs. */
private const val KEY_DISLIKE_TIRED = "dislike_tired_v1"
/** How long "tired for now" keeps a cut out of the radio set. */
const val DJ_TIRED_COOLDOWN_MS = 14L * 24 * 60 * 60 * 1000
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
/** Multi-select genre board (optional) — influences radio pool / AI pick. */
private const val KEY_GENRES = "genre_board_v1"
/** Cached genre chips discovered from the listener's top artists. */
private const val KEY_GENRE_BOARD = "genre_board_options_v1"
/** DJ speaking personality after research / queue work. */
private const val KEY_BEHAVIOR = "behavior_mode_v1"
/** City / metro for local show research + discovery. */
private const val KEY_CITY = "listener_city_v1"
/** Listener first name / nickname for on-air address (not a place). */
private const val KEY_NAME = "listener_name_v1"
/** Editable prompt templates (research / behavior / system cores). */
private const val KEY_PROMPTS = "prompt_templates_v1"
/** Active behavior template id (built-in or custom). */
private const val KEY_ACTIVE_BEHAVIOR_ID = "active_behavior_prompt_id_v1"

/** Inclusive bounds for “talk every N songs” settings. */
const val BANTER_EVERY_MIN = 1
const val BANTER_EVERY_MAX = 20

/**
 * Multi-select reasons for the Dislike control / chat-bubble modal.
 * Stored as string tags so prefs stay forward-compatible.
 */
object DjDislikeReason {
    const val ARTIST = "artist"
    const val SONG = "song"
    const val LYRICS = "lyrics"
    /** Temporary cool-down — not permanent. */
    const val TIRED = "tired"

    fun label(reason: String): String = when (reason) {
        ARTIST -> "The artist"
        SONG -> "This song"
        LYRICS -> "The lyrics"
        TIRED -> "Tired of hearing it for now"
        else -> reason
    }
}

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

/**
 * How the Live DJ *sounds* on mic — applied after research and queue decisions.
 * Default keeps the current warm radio vibe (with richer research).
 */
enum class DjBehaviorMode {
    Default,
    Hype,
    HypeUnhinged,
    Comedy,
    Soothing,
    Unhinged,
    ;

    val label: String
        get() = when (this) {
            Default -> "Default"
            Hype -> "Hype"
            HypeUnhinged -> "Hype Unhinged"
            Comedy -> "Comedy"
            Soothing -> "Soothing"
            Unhinged -> "Unhinged"
        }

    val blurb: String
        get() = when (this) {
            Default -> "Warm radio host — natural handoffs"
            Hype -> "High energy hypeman — big energy, still clean"
            HypeUnhinged -> "Savage party chaos — loud, explicit, roast-heavy hype"
            Comedy -> "Witty comic — jokes, bits, playful roasts"
            Soothing -> "Soft late-night — calm, intimate, gentle"
            Unhinged -> "Mean goblin energy — crude, insulting, unfiltered"
        }

    /** Injected into banter / chat system prompts. */
    fun systemStyleBlock(): String = when (this) {
        Default ->
            "PERSONALITY: Default radio host — warm, curious, natural. " +
                "Sound like a good friend on the air, not a hype reel."
        Hype ->
            "PERSONALITY: HYPE MODE — high energy, hypeman energy, punchy short sentences, " +
                "celebrate the drop/cut. Keep it clean enough for a car ride. Use words like " +
                "\"let's go\", \"this one hits\", \"turn it up\" sparingly (not every line)."
        HypeUnhinged ->
            "PERSONALITY: HYPE UNHINGED — absolute party chaos with TEETH. Loud, explicit, " +
                "over-the-top hype PLUS hard roasts: insult the listener's music taste, the last " +
                "track, and the next one with love-hate energy (\"this ass-kicker\", \"your trash " +
                "taste is somehow elite\", \"shut up and turn it up\"). Use RESEARCH as roast fuel " +
                "when present. Address by LISTENER NAME when set — NEVER treat city as a name. " +
                "Swear freely (fuck/shit/damn/ass ok). Be cocky, crude, a little mean — still " +
                "land the handoff clearly. NO hate speech, slurs targeting protected groups, or " +
                "real threats. Roast songs/vibe/listener playfully savage, not bigoted."
        Comedy ->
            "PERSONALITY: COMEDY MODE — witty stand-up radio. Light jokes, wordplay, gentle " +
                "roasts of the vibe (not cruel). Punchlines welcome. Still hand off the song " +
                "cleanly. Prefer clever over cringe."
        Soothing ->
            "PERSONALITY: SOOTHING MODE — soft late-night host. Slow, intimate, gentle, " +
                "comforting. Lower intensity language. No yelling energy. Think quiet drive " +
                "or winding down."
        Unhinged ->
            "PERSONALITY: UNHINGED — chaotic mean goblin DJ. Weird tangents, absurdist takes, " +
                "AND explicit insults: dunk on the track, the artist, the album, AND the " +
                "listener's music taste (\"your trash ears\", \"this is why friends skip your " +
                "aux\"). Only say \"you queued this\" when SOURCE is LISTENER; for DJ picks " +
                "roast the cut without claiming they requested it. Use RESEARCH as roast fuel " +
                "when present. Address them by LISTENER NAME when set — NEVER treat city/location " +
                "as a name. Crude/sexual-adjacent jokes ok if brief. Swear freely. " +
                "Sound unfiltered and a little unhinged, not corporate-safe. Still hand off " +
                "so the listener knows what's next. Don't invent facts; weirdness + meanness " +
                "is in delivery. NO hate speech / protected-class slurs / real threats."
    }

    companion object {
        fun fromPref(raw: String?): DjBehaviorMode =
            when (raw?.lowercase()?.replace(' ', '_')?.replace('-', '_')) {
                "hype" -> Hype
                "hype_unhinged", "hypeunhinged" -> HypeUnhinged
                "comedy", "funny" -> Comedy
                "soothing", "chill", "calm" -> Soothing
                "unhinged", "chaos" -> Unhinged
                else -> Default
            }
    }

    fun toPref(): String = when (this) {
        Default -> "default"
        Hype -> "hype"
        HypeUnhinged -> "hype_unhinged"
        Comedy -> "comedy"
        Soothing -> "soothing"
        Unhinged -> "unhinged"
    }
}

/**
 * Research angles the Live DJ can pull before banter.
 * Each banter cycle randomly picks 1–2 so facts / lyrics / shows / X / radio color rotate.
 */
enum class DjResearchAngle {
    LyricsThemes,
    AlbumSongFacts,
    ArtistFacts,
    ShowsTours,
    RecentXSocial,
    RadioHostColor,
    ;

    val label: String
        get() = when (this) {
            LyricsThemes -> "lyrics & meaning"
            AlbumSongFacts -> "album / song facts"
            ArtistFacts -> "artist facts"
            ShowsTours -> "shows & tours"
            RecentXSocial -> "recent X / social"
            RadioHostColor -> "radio host color"
        }

    /** Focus instructions for the research agent. */
    fun researchBrief(city: String): String = when (this) {
        LyricsThemes ->
            "LYRICS & MEANING: Look up what the CURRENT and NEXT songs are about — themes, " +
                "story, vibe of the lyrics. Paraphrase only (≤28 words each). Never paste long " +
                "lyric blocks or copyrighted lines."
        AlbumSongFacts ->
            "ALBUM / SONG FACTS: Album name, release year, writers, samples, chart peaks, " +
                "awards, collabs, notable production notes. Prefer verified + recent when news."
        ArtistFacts ->
            "ARTIST FACTS: Career color, recent milestones, side projects, beefs (tasteful), " +
                "band lineup notes, fun verified trivia — not Wikipedia dump."
        ShowsTours ->
            "SHOWS & TOURS: Real upcoming concerts / tour legs for these artists " +
                "(city, date, venue when known). " +
                if (city.isNotBlank()) {
                    "Check near $city AND flag major national/international dates if more notable. " +
                        "Also note if familiar artists are coming to $city."
                } else {
                    "National/global tour dates are fine — no local city set."
                }
        RecentXSocial ->
            "RECENT X / SOCIAL: Search recent posts or headlines about these artists/songs " +
                "on X (Twitter) or breaking music social buzz in the last ~2 weeks. " +
                "Short paraphrase only — no full post quotes, no invented viral moments."
        RadioHostColor ->
            "RADIO HOST COLOR: Classic on-air spice — origin stories, sample credits, " +
                "genre context, \"did you know\" moments, cultural placement, funny true " +
                "anecdotes that a good radio host would drop. Verified only."
    }

    companion object {
        private val ALL = entries.toList()

        /**
         * Pick [count] distinct angles (default 1–2 weighted). Always returns at least one.
         */
        fun pickRandom(rng: kotlin.random.Random = kotlin.random.Random.Default): List<DjResearchAngle> {
            val count = when (rng.nextInt(10)) {
                in 0..5 -> 1 // 60% single focus
                in 6..8 -> 2 // 30% dual
                else -> 3 // 10% rich pack
            }
            return ALL.shuffled(rng).take(count.coerceIn(1, ALL.size))
        }
    }
}

/** Max selected genres on the optional board. */
const val MAX_DJ_GENRES = 8

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
    /** Optional multi-select genres biasing the radio pool (empty = no genre filter). */
    val selectedGenres: List<String> = emptyList(),
    /** Genre chips discovered from the listener's Spotify top artists. */
    val genreBoard: List<String> = emptyList(),
    /** On-mic personality (Default / Hype / Comedy / …). */
    val behaviorMode: DjBehaviorMode = DjBehaviorMode.Default,
    /** City / metro for local show research (optional). */
    val listenerCity: String = "",
    /** Listener name / nickname for on-air address (optional; not a place). */
    val listenerName: String = "",
)

/**
 * Persists Live DJ on/off, preferences, chat bubbles, and the radio queue.
 * Service is the source of runtime state; queue + chat survive leave/return & restarts.
 */
class SpotifyDjStore(context: Context) {
    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    var behaviorMode: DjBehaviorMode
        get() = DjBehaviorMode.fromPref(prefs.getString(KEY_BEHAVIOR, "default"))
        set(value) {
            prefs.edit()
                .putString(KEY_BEHAVIOR, value.toPref())
                .putString(KEY_ACTIVE_BEHAVIOR_ID, value.toPref())
                .apply()
        }

    /**
     * Active behavior template id. Prefer over [behaviorMode] when custom templates exist.
     * Keeps [behaviorMode] in sync for built-in ids.
     */
    var activeBehaviorId: String
        get() {
            val raw = prefs.getString(KEY_ACTIVE_BEHAVIOR_ID, null)?.trim().orEmpty()
            if (raw.isNotBlank()) return raw
            return behaviorMode.toPref()
        }
        set(value) {
            val id = value.trim().ifBlank { "default" }
            val edit = prefs.edit().putString(KEY_ACTIVE_BEHAVIOR_ID, id)
            // Mirror built-in enum when possible (word-cap / legacy UI).
            val mode = DjBehaviorMode.fromPref(id)
            if (mode.toPref() == id || id in listOf(
                    "default", "hype", "hype_unhinged", "comedy", "soothing", "unhinged",
                )
            ) {
                edit.putString(KEY_BEHAVIOR, mode.toPref())
            }
            edit.apply()
        }

    var listenerCity: String
        get() = prefs.getString(KEY_CITY, "")?.trim()?.take(80).orEmpty()
        set(value) = prefs.edit().putString(KEY_CITY, value.trim().take(80)).apply()

    /**
     * How the DJ addresses the listener on mic (first name / nickname).
     * Never confused with [listenerCity] — city is location only.
     */
    var listenerName: String
        get() = prefs.getString(KEY_NAME, "")?.trim()?.take(40).orEmpty()
        set(value) = prefs.edit().putString(KEY_NAME, value.trim().take(40)).apply()

    /** Active genre filters (optional multi-select). */
    var selectedGenres: List<String>
        get() = decodeStringList(prefs.getString(KEY_GENRES, null))
        set(value) = prefs.edit()
            .putString(KEY_GENRES, encodeStringList(value.distinct().take(MAX_DJ_GENRES)))
            .apply()

    /** Cached board options from top-artist genres (refreshed in settings). */
    var genreBoard: List<String>
        get() = decodeStringList(prefs.getString(KEY_GENRE_BOARD, null))
        set(value) = prefs.edit()
            .putString(KEY_GENRE_BOARD, encodeStringList(value.distinct().take(40)))
            .apply()

    var lastCurrentUri: String
        get() = prefs.getString(KEY_CURRENT_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CURRENT_URI, value).apply()

    // ── Prompt templates (research / behavior / system cores) ──────────────

    /** All templates (merged with built-ins). Always non-empty after read. */
    fun loadPromptTemplates(): List<DjPromptTemplate> {
        val saved = decodePromptTemplates(prefs.getString(KEY_PROMPTS, null))
        val merged = mergePromptTemplates(saved)
        // Persist merge so new built-ins appear once without wiping edits.
        if (saved.size != merged.size || saved.isEmpty()) {
            savePromptTemplates(merged)
        }
        return merged
    }

    fun savePromptTemplates(list: List<DjPromptTemplate>) {
        prefs.edit().putString(KEY_PROMPTS, encodePromptTemplates(list)).apply()
    }

    fun templatesOf(kind: DjPromptKind): List<DjPromptTemplate> =
        loadPromptTemplates().filter { it.kind == kind }

    fun templateById(id: String): DjPromptTemplate? =
        loadPromptTemplates().firstOrNull { it.id == id }

    fun activeBehaviorTemplate(): DjPromptTemplate {
        val all = loadPromptTemplates().filter { it.kind == DjPromptKind.Behavior }
        val id = activeBehaviorId
        return all.firstOrNull { it.id == id }
            ?: all.firstOrNull { it.id == behaviorMode.toPref() }
            ?: all.firstOrNull { it.enabled }
            ?: DjPromptDefaults.behaviors().first()
    }

    fun systemTemplate(kind: DjPromptKind): DjPromptTemplate {
        require(
            kind == DjPromptKind.BanterSystem ||
                kind == DjPromptKind.ResearchSystem ||
                kind == DjPromptKind.ChatSystem,
        )
        return loadPromptTemplates().firstOrNull { it.kind == kind }
            ?: when (kind) {
                DjPromptKind.BanterSystem -> DjPromptDefaults.banterSystem()
                DjPromptKind.ResearchSystem -> DjPromptDefaults.researchSystem()
                DjPromptKind.ChatSystem -> DjPromptDefaults.chatSystem()
                else -> DjPromptDefaults.banterSystem()
            }
    }

    fun upsertPromptTemplate(template: DjPromptTemplate) {
        val list = loadPromptTemplates().toMutableList()
        val idx = list.indexOfFirst { it.id == template.id }
        if (idx >= 0) list[idx] = template else list.add(template)
        savePromptTemplates(mergePromptTemplates(list))
    }

    fun deletePromptTemplate(id: String): Boolean {
        val list = loadPromptTemplates()
        val t = list.firstOrNull { it.id == id } ?: return false
        // System cores cannot be deleted — reset instead.
        if (t.kind == DjPromptKind.BanterSystem ||
            t.kind == DjPromptKind.ResearchSystem ||
            t.kind == DjPromptKind.ChatSystem
        ) {
            val def = DjPromptDefaults.defaultFor(id) ?: return false
            upsertPromptTemplate(def)
            return true
        }
        if (t.builtIn) {
            // Built-in research/behavior: reset body, keep enabled state.
            val def = DjPromptDefaults.defaultFor(id) ?: return false
            upsertPromptTemplate(t.copy(label = def.label, blurb = def.blurb, body = def.body, flags = def.flags))
            return true
        }
        val next = list.filterNot { it.id == id }
        savePromptTemplates(mergePromptTemplates(next))
        if (activeBehaviorId == id) {
            activeBehaviorId = "default"
        }
        return true
    }

    fun resetPromptTemplate(id: String): Boolean {
        val def = DjPromptDefaults.defaultFor(id) ?: return false
        val cur = templateById(id)
        upsertPromptTemplate(
            def.copy(enabled = cur?.enabled ?: true),
        )
        return true
    }

    fun setTemplateEnabled(id: String, enabled: Boolean) {
        val t = templateById(id) ?: return
        upsertPromptTemplate(t.copy(enabled = enabled))
    }

    private fun encodeStringList(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { s ->
            val t = s.trim()
            if (t.isNotBlank()) arr.put(t)
        }
        return arr.toString()
    }

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        }.getOrElse { emptyList() }
    }

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
            // Persist durable host URLs when we already mirrored covers.
            val rewritten = SpotifyArtMirror.rewriteMessages(appCtx, msgs)
            val arr = JSONArray()
            rewritten.takeLast(MAX_DJ_CHAT_MESSAGES).forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_CHAT, arr.toString()).apply()
            // Background: push any remaining Spotify CDN URLs to our media-cache.
            val urls = rewritten.asSequence()
                .filter { it.role == DjChatRole.Track }
                .flatMap { sequenceOf(it.albumArtUrl, it.artistArtUrl) }
                .filterNotNull()
                .filter { SpotifyArtMirror.isSpotifyCdn(it) }
                .distinct()
                .toList()
            if (urls.isNotEmpty()) {
                SpotifyArtMirror.mirrorAllAsync(appCtx, urls)
            }
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

    // ── Dislike filters (durable; not cleared by “new queue” played soft-forget) ──

    /** uri → reasons (song / lyrics). */
    private val blockedTracksCache = LinkedHashMap<String, MutableSet<String>>()
    /** artistId or `name:<lower>` → display name. */
    private val blockedArtistsCache = LinkedHashMap<String, String>()
    /** uri → until epoch ms. */
    private val tiredTracksCache = LinkedHashMap<String, Long>()
    private var dislikesLoaded = false

    private fun ensureDislikesLoaded() {
        if (dislikesLoaded) return
        synchronized(this) {
            if (dislikesLoaded) return
            blockedTracksCache.clear()
            blockedArtistsCache.clear()
            tiredTracksCache.clear()
            // Tracks
            runCatching {
                val raw = prefs.getString(KEY_DISLIKE_TRACKS, null)
                if (raw != null) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val uri = o.optString("uri", "")
                        if (uri.isBlank()) continue
                        val reasons = LinkedHashSet<String>()
                        val r = o.optJSONArray("reasons")
                        if (r != null) {
                            for (j in 0 until r.length()) {
                                val s = r.optString(j, "")
                                if (s.isNotBlank()) reasons.add(s)
                            }
                        }
                        if (reasons.isEmpty()) reasons.add(DjDislikeReason.SONG)
                        blockedTracksCache[uri] = reasons
                    }
                }
            }.onFailure { Log.w(TAG, "load dislike tracks: ${it.message}") }
            // Artists
            runCatching {
                val raw = prefs.getString(KEY_DISLIKE_ARTISTS, null)
                if (raw != null) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("id", "")
                        val name = o.optString("name", "")
                        val key = id.ifBlank {
                            name.trim().lowercase().takeIf { it.isNotBlank() }
                                ?.let { "name:$it" }.orEmpty()
                        }
                        if (key.isBlank()) continue
                        blockedArtistsCache[key] = name.ifBlank { id }
                    }
                }
            }.onFailure { Log.w(TAG, "load dislike artists: ${it.message}") }
            // Tired (drop expired)
            runCatching {
                val raw = prefs.getString(KEY_DISLIKE_TIRED, null)
                val now = System.currentTimeMillis()
                if (raw != null) {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val uri = o.optString("uri", "")
                        val until = o.optLong("until", 0L)
                        if (uri.isBlank() || until <= now) continue
                        tiredTracksCache[uri] = until
                    }
                }
            }.onFailure { Log.w(TAG, "load dislike tired: ${it.message}") }
            dislikesLoaded = true
        }
    }

    private fun persistBlockedTracks() {
        runCatching {
            val arr = JSONArray()
            blockedTracksCache.entries.toList().takeLast(400).forEach { e ->
                val uri = e.key
                val reasons = e.value
                if (uri.isBlank()) return@forEach
                val r = JSONArray()
                reasons.forEach { reason -> r.put(reason) }
                arr.put(
                    JSONObject()
                        .put("uri", uri)
                        .put("reasons", r)
                        .put("ts", System.currentTimeMillis()),
                )
            }
            prefs.edit().putString(KEY_DISLIKE_TRACKS, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "save dislike tracks: ${it.message}") }
    }

    private fun persistBlockedArtists() {
        runCatching {
            val arr = JSONArray()
            blockedArtistsCache.entries.toList().takeLast(200).forEach { e ->
                val key = e.key
                val name = e.value
                if (key.isBlank()) return@forEach
                val id = if (key.startsWith("name:")) "" else key
                val n = name.ifBlank {
                    if (key.startsWith("name:")) key.removePrefix("name:") else key
                }
                arr.put(JSONObject().put("id", id).put("name", n))
            }
            prefs.edit().putString(KEY_DISLIKE_ARTISTS, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "save dislike artists: ${it.message}") }
    }

    private fun persistTiredTracks() {
        runCatching {
            val now = System.currentTimeMillis()
            val arr = JSONArray()
            tiredTracksCache.entries.toList()
                .filter { it.key.isNotBlank() && it.value > now }
                .takeLast(300)
                .forEach { e ->
                    arr.put(JSONObject().put("uri", e.key).put("until", e.value))
                }
            prefs.edit().putString(KEY_DISLIKE_TIRED, arr.toString()).apply()
        }.onFailure { Log.w(TAG, "save dislike tired: ${it.message}") }
    }

    fun isArtistIdBlocked(artistId: String): Boolean {
        ensureDislikesLoaded()
        val id = artistId.trim()
        return id.isNotBlank() && blockedArtistsCache.containsKey(id)
    }

    fun isArtistNameBlocked(artists: String): Boolean {
        ensureDislikesLoaded()
        if (blockedArtistsCache.isEmpty()) return false
        val parts = artists.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.any { blockedArtistsCache.containsKey("name:$it") }
    }

    fun isTrackBlocked(uri: String): Boolean {
        ensureDislikesLoaded()
        val u = uri.trim()
        return u.isNotBlank() && blockedTracksCache.containsKey(u)
    }

    fun isTired(uri: String): Boolean {
        ensureDislikesLoaded()
        val u = uri.trim()
        if (u.isBlank()) return false
        val until = tiredTracksCache[u] ?: return false
        if (until <= System.currentTimeMillis()) {
            tiredTracksCache.remove(u)
            return false
        }
        return true
    }

    /**
     * True when this cut (or its artists) should not enter / stay in the Live DJ queue.
     */
    fun isDisliked(
        uri: String,
        artistIds: List<String> = emptyList(),
        artists: String = "",
        artistUri: String = "",
    ): Boolean {
        ensureDislikesLoaded()
        val u = uri.trim()
        if (u.isNotBlank() && (isTrackBlocked(u) || isTired(u))) return true
        val idFromUri = artistUri.trim().let { raw ->
            when {
                raw.startsWith("spotify:artist:") -> raw.removePrefix("spotify:artist:")
                raw.contains("/artist/") -> raw.substringAfterLast('/').substringBefore('?')
                else -> raw
            }.trim().takeIf { it.isNotBlank() && !it.contains(':') && !it.contains('/') }
        }
        if (!idFromUri.isNullOrBlank() && isArtistIdBlocked(idFromUri)) return true
        if (artistIds.any { isArtistIdBlocked(it) }) return true
        if (isArtistNameBlocked(artists)) return true
        return false
    }

    /**
     * Apply multi-select dislike reasons for a track. Returns a short human summary.
     */
    fun applyDislike(
        trackUri: String,
        trackName: String = "",
        artists: String = "",
        artistUri: String = "",
        artistIds: List<String> = emptyList(),
        reasons: Set<String>,
    ): String {
        ensureDislikesLoaded()
        val uri = trackUri.trim()
        val picked = reasons.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (picked.isEmpty()) return "Pick at least one reason"

        val permanentTrack = DjDislikeReason.SONG in picked || DjDislikeReason.LYRICS in picked
        val blockArtist = DjDislikeReason.ARTIST in picked
        val tired = DjDislikeReason.TIRED in picked

        if (permanentTrack && uri.isNotBlank()) {
            val set = blockedTracksCache.getOrPut(uri) { LinkedHashSet() }
            if (DjDislikeReason.SONG in picked) set.add(DjDislikeReason.SONG)
            if (DjDislikeReason.LYRICS in picked) set.add(DjDislikeReason.LYRICS)
            persistBlockedTracks()
        }
        if (blockArtist) {
            val primaryName = artists.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val ids = LinkedHashSet<String>()
            artistIds.forEach { if (it.isNotBlank()) ids.add(it.trim()) }
            artistUri.trim().let { raw ->
                val id = when {
                    raw.startsWith("spotify:artist:") -> raw.removePrefix("spotify:artist:")
                    raw.contains("/artist/") -> raw.substringAfterLast('/').substringBefore('?')
                    else -> ""
                }.trim()
                if (id.isNotBlank() && !id.contains(':') && !id.contains('/')) ids.add(id)
            }
            if (ids.isEmpty() && primaryName.isNotBlank()) {
                blockedArtistsCache["name:${primaryName.lowercase()}"] = primaryName
            } else {
                ids.forEach { id ->
                    blockedArtistsCache[id] = primaryName.ifBlank { id }
                }
                // Also name-key so seeds without ids still match.
                if (primaryName.isNotBlank()) {
                    blockedArtistsCache["name:${primaryName.lowercase()}"] = primaryName
                }
            }
            persistBlockedArtists()
        }
        if (tired && uri.isNotBlank()) {
            tiredTracksCache[uri] = System.currentTimeMillis() + DJ_TIRED_COOLDOWN_MS
            // Cap map size
            while (tiredTracksCache.size > 300) {
                val first = tiredTracksCache.keys.firstOrNull() ?: break
                tiredTracksCache.remove(first)
            }
            persistTiredTracks()
        }

        val bits = ArrayList<String>(4)
        if (blockArtist) {
            val who = artists.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: "artist"
            bits.add("artist “${who.take(40)}” blocked")
        }
        if (DjDislikeReason.SONG in picked) bits.add("song blocked")
        if (DjDislikeReason.LYRICS in picked) bits.add("lyrics blocked")
        if (tired) bits.add("cooled ~14 days")
        val title = trackName.ifBlank { uri.takeLast(18) }.take(48)
        return if (bits.isEmpty()) {
            "Disliked “$title”"
        } else {
            "Disliked “$title” · ${bits.joinToString(" · ")}"
        }
    }
}

/** Human-readable banter countdown from persisted counters. */
fun banterCountdownLabel(songsSinceBanter: Int, banterEvery: Int): String {
    val left = tracksUntilTalk(songsSinceBanter, banterEvery)
    return if (left <= 1) "banter soon" else "talk in $left tracks"
}

/**
 * Countdown label that also reflects fixed vs random frequency.
 * Random cycles use the rolled [banterEvery] for this set of songs; range is shown for clarity.
 */
fun banterCountdownLabel(
    songsSinceBanter: Int,
    banterEvery: Int,
    mode: BanterFrequencyMode,
    banterMin: Int = BANTER_EVERY_MIN,
    banterMax: Int = BANTER_EVERY_MAX,
): String {
    val left = tracksUntilTalk(songsSinceBanter, banterEvery)
    val base = if (left <= 1) "banter soon" else "talk in $left tracks"
    if (mode != BanterFrequencyMode.Random) return base
    val a = banterMin.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
    val b = banterMax.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    if (lo == hi) return base
    // e.g. "talk in 3 tracks · roll 2–5" so random range is visible mid-cycle
    return "$base · roll $lo–$hi"
}

/** Tracks remaining until banter (0 = next handoff talks). */
fun tracksUntilTalk(songsSinceBanter: Int, banterEvery: Int): Int {
    val every = banterEvery.coerceIn(BANTER_EVERY_MIN, BANTER_EVERY_MAX)
    val since = songsSinceBanter.coerceAtLeast(0)
    return (every - since).coerceAtLeast(0)
}

/**
 * True when this UP NEXT cut came from the listener (chat / explicit request),
 * not from Live DJ radio fill. Banter must never say "you queued this" for DJ picks.
 */
fun isListenerQueuedReason(reason: String): Boolean {
    val r = reason.trim().lowercase()
    if (r.isBlank()) return false
    return r.startsWith("chat") ||
        r.startsWith("from chat") ||
        r.contains("from chat") ||
        r.startsWith("user:") ||
        r.startsWith("listener") ||
        r.startsWith("request") ||
        r.contains("you asked") ||
        r.contains("listener request") ||
        r.contains("more like")
}

/** On-air attribution for the model — who put this cut in the set. */
fun queueSourceLabel(reason: String): String {
    return if (isListenerQueuedReason(reason)) {
        "LISTENER — they requested/queued this (chat or explicit ask). " +
            "You may credit them (\"as you asked\", \"from your request\")."
    } else {
        "LIVE DJ — you (the AI DJ) put this in the set from radio/liked/top/genre/etc. " +
            "NEVER say the listener queued, requested, or picked this. " +
            "Say \"up next\", \"I queued\", \"on my list\", \"coming up\" — not \"you queued\"."
    }
}

/**
 * Discover genre chips from the listener's Spotify top artists (short + medium term).
 * Persists the board options and returns them. Selected genres are left untouched
 * except that invalid selections no longer on the board stay selected (still useful).
 */
fun refreshDjGenreBoard(context: Context): Pair<List<String>, String?> {
    val appCtx = context.applicationContext
    if (!SpotifyOAuth.isLoggedIn(appCtx)) {
        return emptyList<String>() to "Sign in to Spotify first"
    }
    val counts = LinkedHashMap<String, Int>()
    fun ingestArtists(body: String?) {
        val o = runCatching { JSONObject(body ?: return) }.getOrNull() ?: return
        // SpotifyOAuth.api wraps status/body — accept raw or wrapped
        val items = when {
            o.has("items") -> o.optJSONArray("items")
            o.has("body") -> runCatching {
                JSONObject(o.optString("body")).optJSONArray("items")
            }.getOrNull()
            else -> null
        } ?: return
        for (i in 0 until items.length()) {
            val a = items.optJSONObject(i) ?: continue
            val genres = a.optJSONArray("genres") ?: continue
            for (g in 0 until genres.length()) {
                val name = genres.optString(g, "").trim()
                if (name.isBlank()) continue
                // Title-case-ish for chips
                val label = name.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.uppercaseChar() else c
                }
                counts[label] = (counts[label] ?: 0) + 1
            }
        }
    }
    for (range in listOf("short_term", "medium_term", "long_term")) {
        val raw = SpotifyOAuth.api(
            appCtx,
            "GET",
            "/v1/me/top/artists?time_range=$range&limit=50",
            null,
        )
        ingestArtists(raw)
    }
    if (counts.isEmpty()) {
        return emptyList<String>() to "No genres found on your top artists yet"
    }
    val board = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
        .take(36)
    val store = SpotifyDjStore(appCtx)
    store.genreBoard = board
    // Keep selections that still make sense; drop blanks only
    store.selectedGenres = store.selectedGenres.filter { it.isNotBlank() }.take(MAX_DJ_GENRES)
    SpotifyDjBus.patch {
        it.copy(
            genreBoard = board,
            selectedGenres = store.selectedGenres,
        )
    }
    return board to null
}

/**
 * Spotify profile display name for on-air address.
 * @return display_name (or null) and error message (or null).
 */
fun fetchSpotifyDisplayName(context: Context): Pair<String?, String?> {
    if (!SpotifyOAuth.isLoggedIn(context)) {
        return null to "Sign in on Account tab first"
    }
    return try {
        val raw = SpotifyOAuth.api(context.applicationContext, "GET", "/v1/me", null)
        val env = runCatching { JSONObject(raw) }.getOrNull()
            ?: return null to "Bad profile response"
        if (!env.optBoolean("ok", env.optInt("status", 0) in 200..299)) {
            val err = env.optString("error", "").ifBlank {
                env.optString("body", "").take(120)
            }
            return null to (err.ifBlank { "Could not load Spotify profile" })
        }
        val body = env.optString("body", "")
        val json = when {
            body.isNotBlank() -> runCatching { JSONObject(body) }.getOrNull()
            else -> env.optJSONObject("json") ?: env
        } ?: return null to "Empty profile"
        val name = json.optString("display_name", "").trim()
            .ifBlank { json.optString("id", "").trim() }
        if (name.isBlank()) null to "No display name on Spotify profile"
        else name.take(40) to null
    } catch (e: Exception) {
        Log.w(TAG, "fetchSpotifyDisplayName: ${e.message}")
        null to (e.message ?: "Profile fetch failed")
    }
}

/**
 * Prefer stored [SpotifyDjStore.listenerName]; if blank, pull Spotify display_name once
 * and persist it so banter can address the listener (never uses city as a name).
 */
fun resolveListenerName(context: Context, store: SpotifyDjStore = SpotifyDjStore(context)): String {
    val saved = store.listenerName.trim()
    if (saved.isNotBlank()) return saved
    val (pulled, _) = fetchSpotifyDisplayName(context)
    val name = pulled?.trim().orEmpty()
    if (name.isNotBlank()) {
        store.listenerName = name
        SpotifyDjBus.patch { it.copy(listenerName = name) }
    }
    return name
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
            selectedGenres = store.selectedGenres,
            genreBoard = store.genreBoard,
            behaviorMode = store.behaviorMode,
            listenerCity = store.listenerCity,
            listenerName = store.listenerName,
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
                        .substringBefore(" · roll ")
                    val cd = banterCountdownLabel(
                        since,
                        every,
                        store.banterMode,
                        store.banterMin,
                        store.banterMax,
                    )
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
    val appCtx = context.applicationContext
    val store = SpotifyDjStore(appCtx)
    val bus = SpotifyDjBus.state.value
    val loaded = if (bus.messages.isEmpty()) store.loadMessages() else bus.messages
    val msgs = SpotifyArtMirror.rewriteMessages(appCtx, loaded)
    // Fire-and-forget: cache any leftover Spotify CDN covers on our host.
    SpotifyArtMirror.mirrorAllAsync(
        appCtx,
        msgs.asSequence()
            .filter { it.role == DjChatRole.Track }
            .flatMap { sequenceOf(it.albumArtUrl, it.artistArtUrl) }
            .filterNotNull()
            .toList(),
    )
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
            messages = if (it.messages.isEmpty()) msgs else SpotifyArtMirror.rewriteMessages(appCtx, it.messages),
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
            selectedGenres = store.selectedGenres,
            genreBoard = store.genreBoard,
            behaviorMode = store.behaviorMode,
            listenerCity = store.listenerCity,
            listenerName = store.listenerName,
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
                selectedGenres = store.selectedGenres,
                genreBoard = store.genreBoard,
                behaviorMode = store.behaviorMode,
                listenerCity = store.listenerCity,
                listenerName = store.listenerName,
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
 *
 * Transient start blocks (background restriction right after OTA) are silent — no
 * "Start deferred" status/error in the UI or notification; we retry shortly.
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
                    status = if (
                        it.status.isBlank() ||
                        it.status == "Off" ||
                        it.status.startsWith("Start deferred", ignoreCase = true)
                    ) {
                        "Resuming after restart…"
                    } else {
                        it.status
                    },
                    resumeAfterRestart = store.resumeAfterRestart,
                    error = null,
                )
            }
        } else {
            // Clear any stale deferred noise from a previous failed start.
            SpotifyDjBus.patch {
                if (
                    it.error.isNullOrBlank() &&
                    !it.status.startsWith("Start deferred", ignoreCase = true)
                ) {
                    it
                } else {
                    it.copy(
                        error = null,
                        status = if (it.status.startsWith("Start deferred", ignoreCase = true)) {
                            "Starting…"
                        } else {
                            it.status
                        },
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "start Live DJ failed (silent retry): ${e.message}", e)
        // Keep [enabled] when user wants resume — next process/activity open can retry.
        // Only wipe when they opted out of resume (session mode).
        if (!store.resumeAfterRestart) {
            store.enabled = false
        }
        // Do not surface "Start deferred" in status/error — it clears when the app
        // opens / service attaches, and just looks like a broken notification.
        SpotifyDjBus.patch {
            it.copy(
                enabled = store.enabled,
                status = when {
                    !store.enabled -> "Off"
                    it.status.isBlank() ||
                        it.status.startsWith("Start deferred", ignoreCase = true) ||
                        it.status == "Off" -> "Starting…"
                    else -> it.status
                },
                error = null,
                resumeAfterRestart = store.resumeAfterRestart,
            )
        }
        if (store.enabled) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (SpotifyDjStore(appCtx).enabled) {
                    runCatching { startLiveDjService(appCtx, fromResume = true) }
                }
            }, 2_500L)
        }
    }
}

/** Observable Live DJ state for Compose. */
object SpotifyDjBus {
    private val _state = MutableStateFlow(SpotifyDjUiState())
    val state: StateFlow<SpotifyDjUiState> = _state.asStateFlow()

    fun publish(s: SpotifyDjUiState) {
        _state.value = s
        notifyWidgets()
    }

    fun patch(block: (SpotifyDjUiState) -> SpotifyDjUiState) {
        _state.value = block(_state.value)
        notifyWidgets()
    }

    private fun notifyWidgets() {
        val app = GrokifyApp.instanceOrNull() ?: return
        io.grokify.os.widgets.GrokifyWidgets.refreshSpotify(app)
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
                selectedGenres = store.selectedGenres,
                genreBoard = store.genreBoard,
                behaviorMode = store.behaviorMode,
                listenerCity = store.listenerCity,
                listenerName = store.listenerName,
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

/**
 * Seed a mixed "more like this" batch (same-artist deep cuts + related / genre-adjacent
 * similars), and **prepend** them to UP NEXT. Works in booth mode too.
 */
fun spotifyLiveDjMoreLikeThis(
    context: Context,
    trackUri: String,
    name: String = "",
    artists: String = "",
    artistUri: String = "",
    albumArtUrl: String = "",
) {
    val appCtx = context.applicationContext
    val uri = trackUri.trim()
    if (uri.isBlank() && name.isBlank() && artists.isBlank()) return
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_MORE_LIKE_THIS)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_URI, uri)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_NAME, name)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_ARTISTS, artists)
        .putExtra(SpotifyLiveDjService.EXTRA_ARTIST_URI, artistUri)
        .putExtra(SpotifyLiveDjService.EXTRA_ALBUM_ART, albumArtUrl)
    startDjServiceAction(appCtx, i, "moreLikeThis")
}

/**
 * Apply dislike reasons for a cut so Live DJ won’t re-queue it (and optionally skips
 * if it’s playing now). Reasons: [DjDislikeReason.ARTIST], [DjDislikeReason.SONG],
 * [DjDislikeReason.LYRICS], [DjDislikeReason.TIRED].
 */
fun spotifyLiveDjDislike(
    context: Context,
    trackUri: String,
    name: String = "",
    artists: String = "",
    artistUri: String = "",
    artistIds: List<String> = emptyList(),
    reasons: Collection<String>,
    skipIfPlaying: Boolean = true,
) {
    val appCtx = context.applicationContext
    val uri = trackUri.trim()
    val reasonArr = reasons.map { it.trim() }.filter { it.isNotBlank() }.distinct().toTypedArray()
    if (reasonArr.isEmpty()) return
    if (uri.isBlank() && name.isBlank() && artists.isBlank() && artistUri.isBlank()) return
    val i = Intent(appCtx, SpotifyLiveDjService::class.java)
        .setAction(SpotifyLiveDjService.ACTION_DJ_DISLIKE)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_URI, uri)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_NAME, name)
        .putExtra(SpotifyLiveDjService.EXTRA_TRACK_ARTISTS, artists)
        .putExtra(SpotifyLiveDjService.EXTRA_ARTIST_URI, artistUri)
        .putExtra(SpotifyLiveDjService.EXTRA_ARTIST_IDS, artistIds.toTypedArray())
        .putExtra(SpotifyLiveDjService.EXTRA_DISLIKE_REASONS, reasonArr)
        .putExtra(SpotifyLiveDjService.EXTRA_SKIP_IF_PLAYING, skipIfPlaying)
    startDjServiceAction(appCtx, i, "dislike")
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

    /**
     * Claims Bluetooth / headset media buttons while Live DJ is armed so Next/Prev/Play-Pause
     * hit our radio queue instead of Spotify’s empty Up Next. Does not take audio focus —
     * Spotify keeps playing; we only own transport.
     */
    private var mediaSession: MediaSessionCompat? = null
    /** Dedup metadata/playback-state pushes (position bucketed). */
    @Volatile private var lastMediaSessionSig: String = ""
    /** Ignore echoed system media keys we ourselves dispatched as a pause/play fallback. */
    @Volatile private var ignoreMediaButtonsUntilMs: Long = 0L
    /** Serialize BT/session transport so double-taps / key down+up don’t double-skip. */
    private val mediaSessionBusy = AtomicBoolean(false)

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
    /**
     * Debounce "external track" reclaim — a single poll with a foreign URI (ad blip,
     * laggy metadata) used to direct-play the next cut mid-song.
     */
    private var externalCandidateUri: String? = null
    private var externalCandidateSinceMs: Long = 0L
    /** Consecutive media-session polls with remain in the near-end window. */
    private var sessionNearEndStreak = 0
    /**
     * Sustained mid-track pause detection. Spotify often flickers is_playing=false
     * between cuts / while buffering — never hold on a single poll.
     */
    private var midPauseSinceMs = 0L
    /**
     * After a handoff or our playTrack, ignore empty/paused flickers so we don't
     * freeze auto-handoff and leave the booth dead between songs.
     */
    private var interTrackGraceUntilMs = 0L
    /**
     * After we commanded a play, verify Spotify actually started. API 204 often lies
     * (no active device / lag) and then mid-pause hold freezes the booth forever.
     */
    private var pendingPlayVerifyUri: String? = null
    private var pendingPlayVerifyUntilMs = 0L
    private var pendingPlayRetries = 0
    /** Avoid thrashing play API when device is missing. */
    private var lastPlayAttemptMs = 0L
    /**
     * Spotify Web API rate-limit cool-down. While active we skip most API polls
     * and lean on the media session so we stop spamming `http_429` in status.
     */
    private var rateLimitedUntilMs = 0L
    /** Last backoff length (grows on repeated 429s, resets after a clean poll). */
    private var rateLimitBackoffMs = 0L
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
    /**
     * URI of the cut we already counted toward banter on this handoff.
     * Stops double +1 when playTrack lands and a late external sync also matches the queue.
     */
    private var lastBanterCountedPlayUri: String? = null
    /** Soft rotation of radio seed modes (liked / top / recent / artist). */
    private var radioModeIdx = 0
    /** Prefetched spoken line for the upcoming handoff (keyed by next URI). */
    private var prefetchedBanter: String? = null
    private var prefetchedForUri: String? = null
    /** Pre-baked TTS mp3 for seamless handoff (especially when talk-over is off). */
    private var prefetchedTtsPath: String? = null
    private var prefetchedTtsDurationMs: Long = 0L
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
        ensureMediaSession()
        startAsForeground(
            if (queue.isNotEmpty()) "Resuming · ${queue.size} in queue…"
            else "Starting Live DJ…",
        )
        syncMediaSession(force = true)
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
                // Re-sync fixed N or keep/re-roll random target from settings.
                banterEvery = store.syncBanterEveryFromSettings()
                songsSinceBanter = store.songsSinceBanter
                val status = when {
                    !store.enabled -> SpotifyDjBus.state.value.status.ifBlank { "Booth ready" }
                    !store.banterEnabled -> "Watching playback · banter off"
                    else -> "Watching playback · " +
                        banterCountdownLabel(
                            songsSinceBanter,
                            banterEvery,
                            store.banterMode,
                            store.banterMin,
                            store.banterMax,
                        )
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
            ACTION_DJ_MORE_LIKE_THIS -> {
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty().trim()
                val name = intent.getStringExtra(EXTRA_TRACK_NAME).orEmpty()
                val artists = intent.getStringExtra(EXTRA_TRACK_ARTISTS).orEmpty()
                val artistUri = intent.getStringExtra(EXTRA_ARTIST_URI).orEmpty()
                if (uri.isNotBlank() || name.isNotBlank() || artists.isNotBlank()) {
                    sessionWork = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            moreLikeThis(
                                seedUri = uri,
                                seedName = name,
                                seedArtists = artists,
                                seedArtistUri = artistUri,
                            )
                        } finally {
                            finishSessionIfNeeded()
                        }
                    }
                }
            }
            ACTION_DJ_DISLIKE -> {
                val uri = intent.getStringExtra(EXTRA_TRACK_URI).orEmpty().trim()
                val name = intent.getStringExtra(EXTRA_TRACK_NAME).orEmpty()
                val artists = intent.getStringExtra(EXTRA_TRACK_ARTISTS).orEmpty()
                val artistUri = intent.getStringExtra(EXTRA_ARTIST_URI).orEmpty()
                val artistIds = intent.getStringArrayExtra(EXTRA_ARTIST_IDS)
                    ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val reasons = intent.getStringArrayExtra(EXTRA_DISLIKE_REASONS)
                    ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                val skipIfPlaying = intent.getBooleanExtra(EXTRA_SKIP_IF_PLAYING, true)
                if (reasons.isNotEmpty() &&
                    (uri.isNotBlank() || name.isNotBlank() || artists.isNotBlank() || artistUri.isNotBlank())
                ) {
                    sessionWork = true
                    scope.launch {
                        try {
                            val shouldSkip = withContext(Dispatchers.IO) {
                                applyDislike(
                                    trackUri = uri,
                                    trackName = name,
                                    artists = artists,
                                    artistUri = artistUri,
                                    artistIds = artistIds,
                                    reasons = reasons.toSet(),
                                    skipIfPlaying = skipIfPlaying,
                                )
                            }
                            if (shouldSkip) {
                                forceBanter = false
                                runTransition("skip")
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
            ensureMediaSession()
            syncMediaSession(force = false)
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
        releaseMediaSession()
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
            // Mid-track / idle we stay much slower to avoid Spotify 429 storms that
            // used to last for hours when Control UI + widgets also polled.
            val now = System.currentTimeMillis()
            val rateWait = SpotifyOAuth.rateLimitRemainingMs(now)
                .coerceAtLeast((rateLimitedUntilMs - now).coerceAtLeast(0L))
            val remain = lastRemainMs
            val delayMs = when {
                rateWait > 0L -> rateWait.coerceIn(2_000L, 180_000L)
                transitioning.get() || nearEndArmed -> 800L
                // Real pause / empty booth: almost idle — only watch for the user
                // pressing play again (session changes still wake us via status).
                autoHandoffHeld -> 60_000L
                idlePolls >= 3 -> 20_000L
                idlePolls > 0 -> 8_000L
                remain in 0L..8_000L -> 900L
                remain in 0L..20_000L -> 1_500L
                remain in 0L..45_000L -> 2_500L
                else -> 5_000L
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
        midPauseSinceMs = 0L
        nearEndArmed = false
        // Drop in-flight auto handoff bookkeeping so a long pause cannot
        // "finish" a stale near-end arm hours later.
        handoffLaunchedForUri = null
        externalCandidateUri = null
        externalCandidateSinceMs = 0L
        sessionNearEndStreak = 0
        wasPlaying = false
        // Do not clear pendingPlayVerify here — a verify retry may still recover.
    }

    private fun releaseAutoHandoff(reason: String) {
        if (autoHandoffHeld) {
            Log.i(TAG, "auto-handoff released ($reason)")
        }
        autoHandoffHeld = false
        midPauseSinceMs = 0L
    }

    private fun clearPendingPlayVerify() {
        pendingPlayVerifyUri = null
        pendingPlayVerifyUntilMs = 0L
        pendingPlayRetries = 0
    }

    /**
     * True when we recently owned a handoff / play and should not freeze the booth
     * as a user pause (empty player or lagging transport).
     */
    private fun recentlyOwnedPlayback(now: Long = System.currentTimeMillis()): Boolean {
        // While auto-handoff is held (user pause / empty booth), never claim ownership
        // of playback — that was re-arming stuck_end hours after a pause.
        if (autoHandoffHeld) return false
        return inInterTrackGrace(now) ||
            (pendingPlayVerifyUri != null && now <= pendingPlayVerifyUntilMs + 8_000L) ||
            (expectedPlayUri != null && now <= expectedPlayUntilMs) ||
            // Handoff arm is only "ours" for a short window, not forever.
            (handoffLaunchedForUri != null && nearEndArmed) ||
            nearEndArmed ||
            // Only treat low remain as "ours" while we still believe the set was playing —
            // avoids re-starting after a real mid-track pause once wasPlaying cleared.
            (wasPlaying && lastRemainMs <= 12_000L)
    }

    /** True during the fragile window after a cut ends / we commanded play. */
    private fun inInterTrackGrace(now: Long = System.currentTimeMillis()): Boolean {
        return now <= interTrackGraceUntilMs ||
            now <= expectedPlayUntilMs ||
            transitioning.get() ||
            nearEndArmed
    }

    private fun armInterTrackGrace(ms: Long = 12_000L) {
        val until = System.currentTimeMillis() + ms
        if (until > interTrackGraceUntilMs) interTrackGraceUntilMs = until
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
        // Cool down after 429 — use media session so we keep handoffs without API spam.
        if (isRateLimited()) {
            if (pollFromMediaSession(rateLimited = true)) return
            val waitSec = ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(1L)
            publish(
                status = "Spotify rate limit — cooling ${waitSec}s · session fallback",
                error = "rate_limited",
                persist = false,
            )
            return
        }
        val res = spotifyGet("/v1/me/player/currently-playing")
        // 204 = nothing playing
        if (!res.ok && res.status != 204) {
            val friendly = friendlySpotifyError(res.status, res.error)
            val rateHit = isRateLimitResult(res)
            if (rateHit) {
                // Prefer session immediately so the booth doesn't look broken.
                if (pollFromMediaSession(rateLimited = true)) return
                val waitSec = ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000L)
                    .coerceAtLeast(1L)
                publish(
                    status = "Spotify rate limit — cooling ${waitSec}s",
                    error = "rate_limited",
                    persist = false,
                )
                return
            }
            // 401/403 etc. — never auto-play through API errors while held / paused.
            // Try session before treating as a hard poll failure.
            if (pollFromMediaSession(rateLimited = false)) return
            publish(status = friendly, error = res.error)
            idlePolls++
            // During inter-track grace, API blips are normal — don't freeze, and
            // allow a slightly more patient advance if we were at the outro.
            if (inInterTrackGrace()) {
                return
            }
            // Only force-next on API errors when we were already in the true outro.
            // A sticky/low lastRemainMs used to advance mid-song after a few 5xx blips.
            val mayAdvanceOnError = !autoHandoffHeld &&
                wasPlaying &&
                nearEndArmed &&
                lastRemainMs <= 6_000L &&
                queue.isNotEmpty() &&
                !transitioning.get()
            if (mayAdvanceOnError && idlePolls >= 4) {
                idlePolls = 0
                armInterTrackGrace(12_000L)
                scope.launch { runTransition("poll_error_advance") }
            }
            return
        }
        // Clean poll clears sticky rate-limit backoff growth.
        clearRateLimitSoft()
        val data = res.json
        if (data == null || !data.has("item") || data.isNull("item")) {
            // Natural end: we were playing and remaining time was already low.
            // Long pause / session drop: Spotify clears currently-playing while mid-cut —
            // that must NOT banter + play next (was mis-read as idle_advance).
            val now = System.currentTimeMillis()
            val remainBeforeEmpty = lastRemainMs
            // Between cuts Spotify often returns 204/empty for several seconds —
            // never freeze the booth during inter-track grace or right after our play.
            // Already paused / empty booth: never invent a next track.
            if (autoHandoffHeld) {
                wasPlaying = false
                idlePolls = 0
                lastRemainMs = 0L
                val banterHint = " · ${banterCountdownLabel(songsSinceBanter, banterEvery)}"
                store.songsSinceBanter = songsSinceBanter
                publish(
                    nowLine = "Nothing playing — Live DJ idle…",
                    status = if (queue.isEmpty()) {
                        "Idle · queue empty$banterHint"
                    } else {
                        "Idle · ${queue.size} queued$banterHint"
                    },
                    persist = false,
                )
                return
            }
            if (inInterTrackGrace(now) || recentlyOwnedPlayback(now)) {
                idlePolls++
                // Keep a low remain so later empty polls still look like a natural end
                // (wiping to 999999 after transitions used to freeze the booth as "Paused").
                if (remainBeforeEmpty > 15_000L) lastRemainMs = 0L
                else lastRemainMs = remainBeforeEmpty.coerceAtMost(3_000L)
                publish(
                    nowLine = "Between tracks — keeping the set moving…",
                    status = "Handoff buffer · ${queue.size} queued",
                    persist = false,
                )
                // If empty persists and we still have a queue, nudge the next cut
                // (play may have failed silently or Spotify never started the URI).
                // Bound the grace window — never nudge forever after a failed handoff.
                val graceStillLive = inInterTrackGrace(now) ||
                    (expectedPlayUri != null && now <= expectedPlayUntilMs + 5_000L) ||
                    (pendingPlayVerifyUri != null && now <= pendingPlayVerifyUntilMs + 5_000L)
                val shouldNudge = graceStillLive &&
                    queue.isNotEmpty() &&
                    !transitioning.get() &&
                    idlePolls >= 4 &&
                    idlePolls <= 12 &&
                    (now > expectedPlayUntilMs || idlePolls >= 8)
                if (shouldNudge) {
                    idlePolls = 0
                    armInterTrackGrace(12_000L)
                    scope.launch { runTransition("stuck_end") }
                } else if (!graceStillLive && idlePolls >= 6) {
                    // Grace expired with empty player — freeze, do not keep advancing.
                    holdAutoHandoff("empty_after_grace remainWas=${remainBeforeEmpty}ms")
                    publish(
                        nowLine = "Nothing playing — Live DJ idle…",
                        status = "Idle · ${queue.size} queued",
                    )
                }
                return
            }
            // Empty player only counts as natural end when we were already near the
            // outro (or mid-handoff). remain≤12s alone was too loose after sticky
            // lastRemainMs from a prior near-end arm.
            val likelyNaturalEnd = !autoHandoffHeld &&
                wasPlaying &&
                (
                    (nearEndArmed && remainBeforeEmpty <= 10_000L) ||
                        (remainBeforeEmpty <= 5_000L) ||
                        (handoffLaunchedForUri != null && nearEndArmed) ||
                        (pendingPlayVerifyUri != null && remainBeforeEmpty <= 15_000L)
                    )
            if (!likelyNaturalEnd) {
                // Require a few empty polls before treating as a real session pause —
                // single empty flashes mid-set used to freeze auto-handoff.
                idlePolls++
                if (idlePolls < 4) {
                    publish(
                        nowLine = "Spotify blip — waiting…",
                        status = "Buffer · empty player ($idlePolls/4)",
                        persist = false,
                    )
                    return
                }
                // Real pause / session drop mid-cut: freeze. Never force-next just
                // because wasPlaying was sticky — that fired hours after pause.
                holdAutoHandoff(
                    "empty_not_near_end remainWas=${remainBeforeEmpty}ms wasPlaying=$wasPlaying",
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
            // Between tracks currently-playing can flash empty for a few seconds.
            // Buffer several polls, then start the next URI ourselves.
            val hadBeenPlaying = wasPlaying
            val banterDueNow = store.banterEnabled &&
                (forceBanter || songsSinceBanter + 1 >= banterEvery)
            // ~2–4s buffer at 1s idle poll rate before we force next (avoids double-fire
            // while Spotify is still settling, without leaving dead air forever).
            val gracePolls = when {
                banterDueNow -> 3
                hadBeenPlaying -> 4
                else -> 3
            }
            publish(
                nowLine = if (idlePolls < gracePolls) {
                    "Track ended — buffer before next…"
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
                armInterTrackGrace(12_000L)
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
                    "Between songs · ${queue.size} queued$banterHint"
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
        val nowMs = System.currentTimeMillis()

        // Resume / hold based on live transport state.
        // Spotify flickers is_playing=false between cuts and while buffering the next
        // track (full remain, not playing). Require a sustained mid-track pause before
        // freezing auto-handoff — otherwise the booth "pauses itself" between songs.
        if (playing) {
            midPauseSinceMs = 0L
            releaseAutoHandoff("playing")
            if (pendingPlayVerifyUri != null &&
                (uri == pendingPlayVerifyUri || expectedPlayUri == null || uri == expectedPlayUri)
            ) {
                clearPendingPlayVerify()
            }
        } else if (duration > 0 && remain > 8_000L) {
            val inGrace = inInterTrackGrace(nowMs) ||
                handoffLaunchedForUri == uri ||
                recentlyOwnedPlayback(nowMs) ||
                (expectedPlayUri != null && nowMs <= expectedPlayUntilMs)
            if (inGrace) {
                midPauseSinceMs = 0L
                // Soft UI only — do not hold. If our play never actually started, retry.
                maybeRetryPendingPlay(uri, playing, remain)
            } else {
                if (midPauseSinceMs == 0L) midPauseSinceMs = nowMs
                val pausedFor = nowMs - midPauseSinceMs
                // Give Spotify more time after our own play before freezing as "Paused".
                val holdAfterMs = if (pendingPlayVerifyUri != null) 9_000L else 4_500L
                if (pausedFor >= holdAfterMs) {
                    // If we still owe a play verify, retry instead of freezing the set.
                    if (maybeRetryPendingPlay(uri, playing, remain)) {
                        midPauseSinceMs = 0L
                    } else {
                        // User (or Spotify) paused mid-cut for real — freeze auto handoff.
                        holdAutoHandoff(
                            "mid_track_pause remain=${remain}ms for=${pausedFor}ms",
                        )
                    }
                } else {
                    Log.d(
                        TAG,
                        "mid-pause debounce ${pausedFor}ms remain=${remain}ms (need $holdAfterMs)",
                    )
                }
            }
        } else {
            // At/near end while not playing — end-of-track, not a user pause.
            midPauseSinceMs = 0L
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
                    // Debounce foreign URI: one flaky poll mid-cut must not reclaim/skip.
                    // Require the same unexpected URI for ~2.2s (or a clear mid-track
                    // override on a long cut) before treating it as real drift.
                    val nowExt = System.currentTimeMillis()
                    if (externalCandidateUri != uri) {
                        externalCandidateUri = uri
                        externalCandidateSinceMs = nowExt
                        Log.i(
                            TAG,
                            "external candidate $lastUri → $uri (prevRemain=${prevRemain}ms) — debounce",
                        )
                        // Keep watching the previous cut; don't rewrite lastUri yet.
                        return
                    }
                    val heldMs = nowExt - externalCandidateSinceMs
                    val solidMidTrack =
                        progress >= 10_000L && remain > 25_000L && duration > 45_000L
                    if (heldMs < 2_200L && !solidMidTrack) {
                        Log.d(
                            TAG,
                            "external debounce $uri held=${heldMs}ms — suppress",
                        )
                        return
                    }
                    externalCandidateUri = null
                    externalCandidateSinceMs = 0L
                    Log.i(
                        TAG,
                        "external track change $lastUri → $uri (prevRemain=${prevRemain}ms " +
                            "held=${heldMs}ms) — sync/recalibrate",
                    )
                    handleExternalTrackChange(track, playing, progress, duration, prevRemain)
                    return
                }
            }
        }
        // Same URI as last poll — clear external debounce.
        if (uri.isNotBlank() && uri == lastUri) {
            externalCandidateUri = null
            externalCandidateSinceMs = 0L
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
        val banterTextReady = banterDue &&
            peekUri != null &&
            prefetchedForUri == peekUri &&
            !prefetchedBanter.isNullOrBlank()
        val banterAudioReady = banterTextReady &&
            !prefetchedTtsPath.isNullOrBlank() &&
            File(prefetchedTtsPath!!).isFile &&
            prefetchedTtsDurationMs > 0L
        val banterPrefetched = banterTextReady
        val allowTalkSnap = store.allowTalkOver
        // Fast poll near the end so background / Doze still catches handoffs.
        if (playing && duration > 15_000L && remain <= 8_000L) {
            nearEndArmed = true
        }
        // Banter: enter handoff with enough headroom for the wait loop — NOT to skip.
        // Talk-over ON: speech duration + pad so the line lands on the outro.
        // Talk-over OFF: enter late; waitUntilRemainAbout holds until the last second.
        // Caps stay tight so a long/unknown TTS bake never arms "mid song".
        val banterThreshold = when {
            !banterDue -> 0L
            allowTalkSnap && banterAudioReady ->
                (prefetchedTtsDurationMs + 1_200L).coerceIn(6_000L, 18_000L)
            allowTalkSnap && banterPrefetched -> 10_000L
            banterAudioReady -> 5_000L // clean mic: short headroom; audio already baked
            banterPrefetched -> 7_000L
            else -> 9_000L // still researching / baking — modest early arm only
        }
        // Never arm near-end in the first half of a long cut (stale progress_ms glitches).
        val pastIntro = duration <= 0L || progress >= 12_000L || remain <= duration / 2
        if (
            !autoHandoffHeld &&
            banterDue &&
            banterThreshold > 0L &&
            playing &&
            duration > 15_000L &&
            pastIntro &&
            remain <= banterThreshold &&
            handoffLaunchedForUri != uri &&
            !transitioning.get()
        ) {
            handoffLaunchedForUri = uri
            nearEndArmed = true
            armInterTrackGrace(20_000L)
            Log.i(
                TAG,
                "banter near_end armed remain=${remain}ms prefetched=$banterPrefetched " +
                    "since=$songsSinceBanter every=$banterEvery",
            )
            scope.launch { runTransition("near_end") }
            return
        }
        // Silent: arm a few seconds early so background polls don't miss the end, then
        // waitUntilRemainAbout inside the transition holds until ~0.8s remain.
        if (
            !autoHandoffHeld &&
            !banterDue &&
            playing &&
            duration > 15_000L &&
            pastIntro &&
            remain <= 4_000L &&
            queue.isNotEmpty() &&
            handoffLaunchedForUri != uri &&
            !transitioning.get()
        ) {
            handoffLaunchedForUri = uri
            nearEndArmed = true
            armInterTrackGrace(18_000L)
            Log.i(TAG, "silent near_end direct-play remain=${remain}ms")
            scope.launch { runTransition("near_end_direct") }
            return
        }
        // Stuck at end: same URI paused near 0 — start next ourselves.
        // Hard stop while auto-handoff is held (user pause / empty session).
        // Do not use sticky wasPlaying to override the hold — that resumed sets hours later.
        if (
            !autoHandoffHeld &&
            !playing &&
            duration > 0 &&
            remain <= 5_000L &&
            queue.isNotEmpty()
        ) {
            stuckEndPolls++
            val priorHandoff = handoffLaunchedForUri == uri
            val canNudge = !transitioning.get() &&
                (handoffLaunchedForUri != uri || stuckEndPolls >= 4)
            if (canNudge && !banterDue && (wasPlaying || stuckEndPolls >= 2 || priorHandoff)) {
                stuckEndPolls = 0
                handoffLaunchedForUri = uri
                nearEndArmed = true
                armInterTrackGrace(18_000L)
                scope.launch { runTransition("stuck_end") }
                return
            }
            if (canNudge && banterDue && stuckEndPolls >= 3) {
                stuckEndPolls = 0
                handoffLaunchedForUri = uri
                nearEndArmed = true
                armInterTrackGrace(20_000L)
                scope.launch { runTransition("stopped_at_end") }
                return
            }
        } else if (playing || remain > 5_000L || autoHandoffHeld) {
            stuckEndPolls = 0
        }

        // Sticky wasPlaying: brief not-playing flickers near the end / after our play
        // must not clear the flag or the next empty poll freezes the booth.
        // Held pause always clears it — never stay "wasPlaying" for hours at 0 remain.
        wasPlaying = when {
            autoHandoffHeld -> false
            playing -> true
            inInterTrackGrace(nowMs) -> true
            midPauseSinceMs > 0L && nowMs - midPauseSinceMs >= 4_500L -> false
            // Only stick near-end while we still saw play recently (not a long pause).
            remain <= 12_000L && wasPlaying && midPauseSinceMs == 0L -> true
            else -> wasPlaying // keep last known while debounce runs
        }
        if (queue.size < 3 && !filling.get()) {
            scope.launch(Dispatchers.IO) { fillQueue(useAi = store.useAiRank) }
        }
        // Prefetch AI banter + TTS early — research + bake need wall-clock time.
        // Start as soon as banter is due on this cut (or within ~1 track) so talk-over OFF
        // still has audio ready before the last second. Skip while paused.
        val untilTalkNow = tracksUntilTalk(songsSinceBanter, banterEvery)
        val shouldPrefetch = store.banterEnabled && !autoHandoffHeld && playing &&
            remain > 12_000L &&
            (banterDue || untilTalkNow <= 1)
        if (shouldPrefetch) {
            val peek = synchronized(queue) { queue.firstOrNull() }
            val needLine = peek != null && (
                prefetchedForUri != peek.uri ||
                    prefetchedBanter.isNullOrBlank()
                )
            val needAudio = peek != null && (
                prefetchedForUri == peek.uri &&
                    !prefetchedBanter.isNullOrBlank() &&
                    (prefetchedTtsPath.isNullOrBlank() || !File(prefetchedTtsPath!!).isFile)
                )
            if (peek != null && (needLine || needAudio) && !prefetchingBanter.get()) {
                val prevSnap = current
                scope.launch(Dispatchers.IO) { prefetchBanter(prevSnap, peek) }
            }
        }
        // Flush banter counters every poll so leave/return always sees the true countdown.
        // Random mode: banterEvery is the rolled target for *this* cycle (not re-rolled each poll).
        store.songsSinceBanter = songsSinceBanter
        store.banterEvery = banterEvery
        val banterHint = " · " + banterCountdownLabel(
            songsSinceBanter,
            banterEvery,
            store.banterMode,
            store.banterMin,
            store.banterMax,
        )
        publish(
            status = when {
                playing -> "Watching playback$banterHint"
                remain <= 5_000L && duration > 0L ->
                    "Track ended — starting next$banterHint · ${queue.size} queued"
                pendingPlayVerifyUri != null ->
                    "Starting next track…$banterHint · ${queue.size} queued"
                autoHandoffHeld -> "Paused · waiting for play$banterHint · ${queue.size} queued"
                inInterTrackGrace(nowMs) ->
                    "Between tracks$banterHint · ${queue.size} queued"
                else -> "Paused$banterHint · ${queue.size} queued"
            },
        )
        updateNotif(line)
    }

    /**
     * If [playTrack] claimed success but Spotify never left pause / empty, re-fire once
     * (or twice) instead of freezing as mid-track pause.
     * @return true if a retry was launched.
     */
    private fun maybeRetryPendingPlay(uri: String, playing: Boolean, remain: Long): Boolean {
        if (playing || transitioning.get()) return false
        val pending = pendingPlayVerifyUri ?: return false
        val now = System.currentTimeMillis()
        // Still early in the verify window — wait for Spotify to catch up.
        if (now < pendingPlayVerifyUntilMs - 6_000L) return false
        // Only retry when we're still on the commanded cut (or empty was handled elsewhere)
        // or transport is stuck not-playing with lots of remain.
        val onPending = uri.isBlank() || uri == pending
        if (!onPending && remain > 20_000L) {
            // Foreign track mid-song — abandon verify.
            clearPendingPlayVerify()
            return false
        }
        if (pendingPlayRetries >= 2) {
            Log.w(TAG, "play verify exhausted for ${pending.takeLast(22)}")
            clearPendingPlayVerify()
            return false
        }
        pendingPlayRetries++
        val track = current?.takeIf { it.uri == pending }
            ?: synchronized(queue) { queue.firstOrNull { it.uri == pending } }
            ?: DjQueueTrack(uri = pending)
        Log.i(
            TAG,
            "play verify retry #$pendingPlayRetries uri=${pending.takeLast(22)} " +
                "remain=${remain}ms",
        )
        armInterTrackGrace(16_000L)
        pendingPlayVerifyUntilMs = now + 14_000L
        releaseAutoHandoff("play_verify_retry")
        scope.launch(Dispatchers.IO) {
            val ok = playTrack(track)
            if (!ok) {
                Log.w(TAG, "play verify retry failed")
            }
        }
        return true
    }

    /** Drop prefetched / pending banter when it no longer matches the queue head. */
    private fun invalidateStaleBanterCaches(expectedNextUri: String?) {
        val next = expectedNextUri?.takeIf { it.isNotBlank() }
        if (prefetchedForUri != null && prefetchedForUri != next) {
            Log.i(TAG, "drop prefetched banter (was for ${prefetchedForUri}, head=$next)")
            clearPrefetchedBanterAudio()
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

    /** Delete pre-baked TTS file and clear duration. */
    private fun clearPrefetchedBanterAudio() {
        prefetchedTtsPath?.let { p ->
            runCatching { File(p).delete() }
        }
        prefetchedTtsPath = null
        prefetchedTtsDurationMs = 0L
    }

    /** Snapshot of the next few radio cuts for DJ look-ahead (does not mutate queue). */
    private fun peekUpcoming(limit: Int = 5): List<DjQueueTrack> {
        if (limit <= 0) return emptyList()
        return synchronized(queue) { queue.take(limit.coerceAtMost(queue.size)) }
    }

    /**
     * Direct-play mode: Spotify’s Up Next is never written.
     * Kept as a no-op so older call sites stay harmless.
     */
    private fun ensureDjQueueMirroredOnSpotify(force: Boolean, quiet: Boolean = true): Boolean = true

    private fun prefetchBanter(prev: DjQueueTrack?, next: DjQueueTrack) {
        if (!prefetchingBanter.compareAndSet(false, true)) return
        try {
            val ttsReady = prefetchedForUri == next.uri &&
                !prefetchedBanter.isNullOrBlank() &&
                !prefetchedTtsPath.isNullOrBlank() &&
                File(prefetchedTtsPath!!).isFile
            if (ttsReady) return
            // Text ready but audio missing — re-bake TTS only.
            val lineOnlyReady = prefetchedForUri == next.uri && !prefetchedBanter.isNullOrBlank()
            val untilTalk = tracksUntilTalk(songsSinceBanter, banterEvery)
            // Look ahead: songs until banter (min 1 for next, up to 6 for radio teases).
            val lookCount = (untilTalk.coerceAtLeast(1) + 2).coerceIn(1, 6)
            val upcoming = peekUpcoming(lookCount)
            if (!lineOnlyReady) {
                publish(
                    status = "🔍 Researching ${primaryArtist(next.artists).ifBlank { next.name }}…",
                )
                // Research only here — never block near-end handoff on tools.
                val line = generateBanter(
                    prev,
                    next,
                    allowResearch = true,
                    upcoming = upcoming,
                    tracksUntilTalk = untilTalk,
                )
                if (line.isBlank()) return
                clearPrefetchedBanterAudio()
                prefetchedBanter = line
                prefetchedForUri = next.uri
                Log.i(TAG, "prefetched banter for ${next.uri}: ${line.take(80)}")
            }
            val line = prefetchedBanter ?: return
            publish(status = "🎙 Baking banter audio…")
            val voice = store.voiceId
            val bakeJson = JSONObject()
                .put("synthesize_only", true)
                .put("voice_id", voice)
                .put("language", "en")
                .toString()
            val bakeRaw = HostAiClient.speak(applicationContext, line, bakeJson)
            val bake = runCatching { JSONObject(bakeRaw) }.getOrElse { JSONObject() }
            if (bake.optBoolean("ok")) {
                val p = bake.optString("path", "")
                val dur = bake.optLong("duration_ms", 0L)
                if (p.isNotBlank() && File(p).isFile) {
                    // Drop any prior bake for a different path
                    if (prefetchedTtsPath != null && prefetchedTtsPath != p) {
                        runCatching { File(prefetchedTtsPath!!).delete() }
                    }
                    prefetchedTtsPath = p
                    prefetchedTtsDurationMs = if (dur > 0L) dur else estimateSpeechMs(line)
                    Log.i(
                        TAG,
                        "baked banter TTS path=$p durationMs=$prefetchedTtsDurationMs " +
                            "for ${next.uri}",
                    )
                    publish(
                        status = "🎙 Banter ready (${prefetchedTtsDurationMs / 1000}s audio) · " +
                            banterCountdownLabel(songsSinceBanter, banterEvery),
                    )
                } else {
                    Log.w(TAG, "bake ok but missing file: $bakeRaw")
                    prefetchedTtsDurationMs = estimateSpeechMs(line)
                }
            } else {
                Log.w(TAG, "banter TTS bake failed: ${bake.optString("error")}")
                // Still keep the text — handoff can live-speak as fallback.
                prefetchedTtsDurationMs = estimateSpeechMs(line)
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

    /**
     * Prefer measured TTS duration when we pre-baked audio; else word-count estimate.
     * Used to land talkover on the outro and to know silence budget when talk-over is off.
     */
    private fun speechDurationMs(line: String, bakedMs: Long = prefetchedTtsDurationMs): Long {
        if (bakedMs in 1_200L..45_000L) return bakedMs
        return estimateSpeechMs(line)
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
     *
     * Returns true when it is safe to hand off (near end / track changed / empty at end).
     * Returns false only for a clear *mid-track* user pause — caller must NOT skip, but
     * must also NOT freeze the whole booth forever (poll will re-arm when play resumes).
     *
     * Spotify quirks this absorbs:
     * - single empty / is_playing=false polls mid-song (API lag / buffering)
     * - is_playing=false with a few seconds "remain" on the true outro (file silence /
     *   sticky progress_ms) — 0.1.146 aborted + holdAutoHandoff and left dead air forever
     */
    private suspend fun waitUntilRemainAbout(
        expectedUri: String?,
        targetRemainMs: Long,
        maxWaitMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var emptyStreak = 0
        var pausedStreak = 0
        // Lowest remain observed while still on this cut (playing or not).
        var minRemainSeen = lastRemainMs.coerceAtLeast(0L).let {
            if (it in 1L..600_000L) it else 600_000L
        }
        // Immediate handoff when paused/empty inside this window (true outro / silence).
        val softEndMs = (targetRemainMs + 3_500L).coerceIn(3_500L, 8_000L)
        // After a sustained pause inside this window, treat as ended (sticky progress).
        val stuckEndMs = 14_000L
        val hardMidMs = 25_000L
        while (System.currentTimeMillis() < deadline) {
            val snap = withContext(Dispatchers.IO) { readPlaybackSnap() }
            if (snap == null) {
                emptyStreak++
                pausedStreak = 0
                // Empty after we already saw the outro → natural end (Spotify 204 between cuts).
                if (emptyStreak >= 2 && minRemainSeen <= stuckEndMs) return true
                if (emptyStreak >= 3 && lastRemainMs <= stuckEndMs) return true
                // Long empty with no live item — treat as ended so we never stall the set.
                if (emptyStreak >= 8) return true
                delay(400L)
                continue
            }
            emptyStreak = 0
            if (expectedUri != null && snap.uri.isNotBlank() && snap.uri != expectedUri) {
                return true // already moved on
            }
            lastRemainMs = snap.remainMs
            if (snap.remainMs in 0L..600_000L) {
                minRemainSeen = minOf(minRemainSeen, snap.remainMs)
            }
            if (!snap.playing) {
                pausedStreak++
                val remain = snap.remainMs
                // True outro (or we already ticked into it) while stopped → hand off now.
                if (snap.durationMs > 0L &&
                    (remain <= softEndMs || minRemainSeen <= softEndMs)
                ) {
                    return true
                }
                // Brief not-playing flicker — keep waiting (do not abort the set).
                if (pausedStreak < 5) {
                    delay(400L)
                    continue
                }
                // Sustained pause clearly mid-song → abort this wait; poll re-arms later.
                // Callers must not holdAutoHandoff or songs never advance again.
                if (remain > hardMidMs && minRemainSeen > hardMidMs) {
                    Log.i(
                        TAG,
                        "waitUntilRemainAbout mid-pause remain=${remain}ms " +
                            "minSeen=${minRemainSeen}ms target=${targetRemainMs}ms — abort",
                    )
                    return false
                }
                // Stuck paused in the last ~14s (outro silence / laggy progress) → hand off.
                if (remain <= stuckEndMs || minRemainSeen <= stuckEndMs) {
                    if (pausedStreak >= 7) {
                        Log.i(
                            TAG,
                            "waitUntilRemainAbout stuck-paused remain=${remain}ms " +
                                "minSeen=${minRemainSeen}ms — proceed",
                        )
                        return true
                    }
                    delay(400L)
                    continue
                }
                // Ambiguous (15–25s remain, paused): keep waiting a bit longer.
                delay(400L)
                continue
            }
            pausedStreak = 0
            if (snap.durationMs > 0L && snap.remainMs <= targetRemainMs) return true
            delay(400L)
        }
        // Timed out: proceed in the outro; mid-track timeout re-arms (no freeze).
        val closeEnough =
            lastRemainMs <= stuckEndMs ||
                minRemainSeen <= stuckEndMs ||
                lastRemainMs <= targetRemainMs + 5_000L
        if (!closeEnough) {
            Log.w(
                TAG,
                "waitUntilRemainAbout timeout remain=${lastRemainMs}ms " +
                    "minSeen=${minRemainSeen}ms target=${targetRemainMs}ms — abort (re-arm later)",
            )
        } else {
            Log.i(
                TAG,
                "waitUntilRemainAbout timeout near end remain=${lastRemainMs}ms — proceed",
            )
        }
        return closeEnough
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
        val ok = playTrack(play)
        if (!ok) {
            // Keep the cut at the head so stuck-end / empty recovery can retry.
            requeueFront(play)
            Log.w(TAG, "advanceToNext play failed — requeued ${play.uri.takeLast(22)}")
        }
        return ok
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
            publish(
                status = friendlySpotifyError(res.status, res.error),
                error = res.error,
            )
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
        clearPrefetchedBanterAudio()
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
            // Only count when this wasn't already tallied by runTransition (silent/banter handoff).
            // Late polls after our playTrack used to +1 again → banter every song.
            val alreadyCounted = lastBanterCountedPlayUri != null && lastBanterCountedPlayUri == uri
            if (countTowardBanter && !alreadyCounted && (userInitiated || droppedAhead > 0)) {
                songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                store.songsSinceBanter = songsSinceBanter
                lastBanterCountedPlayUri = uri
            } else if (alreadyCounted) {
                lastBanterCountedPlayUri = null
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
            if (countTowardBanter && lastBanterCountedPlayUri != uri) {
                songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                store.songsSinceBanter = songsSinceBanter
                lastBanterCountedPlayUri = uri
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
                    if (countTowardBanter && lastBanterCountedPlayUri != next.uri) {
                        songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                        store.songsSinceBanter = songsSinceBanter
                        lastBanterCountedPlayUri = next.uri
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
        // Manual user actions always run; automatic handoffs respect pause hold.
        val isSkipReason = reason == "skip" || reason == "chat_skip"
        val isIdleKick = reason == "kick_idle" || reason == "idle_advance" || reason == "poll_error_advance"
        val isUserForced = isSkipReason ||
            reason.startsWith("chat_") ||
            reason == "play_from_queue" ||
            reason == "play_uri"
        // Any automatic advance while held must stop — stuck_end used to be treated as
        // "recovery" and released the hold, so a long pause still bantered + played next.
        if (autoHandoffHeld && !isUserForced) {
            Log.i(TAG, "skip transition $reason — auto-handoff held (paused / idle booth)")
            transitioning.set(false)
            handoffLaunchedForUri = null
            nearEndArmed = false
            return
        }
        if (isUserForced) {
            // User skipped/played — leave pause-hold so the set can run again.
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
            reason == "near_end_direct" || reason == "session_stuck_end" || reason == "session_near_end"
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
                // Wait for research + TTS bake so talk-over OFF isn't dead air after pause.
                if (next != null && (forcedTalk || banterDue)) {
                    val needWait = prefetchingBanter.get() ||
                        prefetchedForUri != next.uri ||
                        prefetchedBanter.isNullOrBlank() ||
                        prefetchedTtsPath.isNullOrBlank() ||
                        !File(prefetchedTtsPath!!).isFile
                    if (needWait) {
                        publish(status = "🎙 Waiting on banter prep…", transitioning = true)
                        // Kick prep if nothing is running (short cuts / cold start).
                        if (!prefetchingBanter.get() &&
                            (prefetchedForUri != next.uri || prefetchedBanter.isNullOrBlank() ||
                                prefetchedTtsPath.isNullOrBlank())
                        ) {
                            val prevSnap = prev
                            val nextSnap = next
                            scope.launch(Dispatchers.IO) { prefetchBanter(prevSnap, nextSnap) }
                        }
                        // Longer budget when talk-over is off (must have audio before pause).
                        val waitBudget = if (allowTalk) 16_000L else 28_000L
                        val waitDeadline = System.currentTimeMillis() + waitBudget
                        while (System.currentTimeMillis() < waitDeadline) {
                            val textOk = prefetchedForUri == next.uri &&
                                !prefetchedBanter.isNullOrBlank()
                            val audioOk = textOk &&
                                !prefetchedTtsPath.isNullOrBlank() &&
                                File(prefetchedTtsPath!!).isFile
                            if (audioOk) break
                            // Text without audio is enough to stop waiting only if bake failed
                            // and prefetch finished.
                            if (textOk && !prefetchingBanter.get() &&
                                (prefetchedTtsPath.isNullOrBlank() ||
                                    !File(prefetchedTtsPath!!).isFile)
                            ) {
                                // Give bake one more chance
                                if (prefetchedTtsDurationMs <= 0L) break
                                break
                            }
                            delay(200L)
                        }
                    }
                }
                // Re-resolve after wait — queue or Spotify may have shifted.
                next = synchronized(queue) { queue.firstOrNull() }
                invalidateStaleBanterCaches(next?.uri)
                // Prefetch is only a speedup — never a reason to talk early.
                // Talking every transition was caused by treating banterReady as due.
                val banterReady = store.banterEnabled &&
                    next != null &&
                    prefetchedForUri == next?.uri &&
                    !prefetchedBanter.isNullOrBlank()
                val wantBanter = store.banterEnabled && (forcedTalk || banterDueCountdown)
                if (!wantBanter && banterReady) {
                    // Drop stale bake so the next due cycle re-researches for the real next cut.
                    Log.i(TAG, "silent handoff — discard prefetched banter (not due)")
                    clearPrefetchedBanterAudio()
                    prefetchedBanter = null
                    prefetchedForUri = null
                }
                val talkover = wantBanter && allowTalk

                if (wantBanter) {
                    // Keep Spotify playing while we finalize — cut shouldn't go silent early.
                    // Prefer pre-baked audio so pause → speak is instant when talk-over is off.
                    publish(
                        status = if (talkover) {
                            "🎙 Prep talkover…"
                        } else {
                            "🎙 Prep clean banter…"
                        },
                        transitioning = true,
                    )

                    // Final targets for the spoken line — must match what we will play next.
                    val banterNext = next
                    val banterPrev = prev
                    var bakedPath: String? = null
                    var bakedMs = 0L
                    val line = when {
                        banterNext != null &&
                            prefetchedForUri == banterNext.uri &&
                            !prefetchedBanter.isNullOrBlank() -> {
                            val p = prefetchedBanter!!
                            if (prefetchedTtsPath != null && File(prefetchedTtsPath!!).isFile) {
                                bakedPath = prefetchedTtsPath
                                bakedMs = prefetchedTtsDurationMs
                            }
                            // Detach text ownership; keep audio path until after speak.
                            prefetchedBanter = null
                            prefetchedForUri = null
                            p
                        }
                        // Handoff must stay fast: use cached facts / local templates only.
                        else -> {
                            val until = tracksUntilTalk(songsSinceBanter, banterEvery)
                            val look = peekUpcoming((until.coerceAtLeast(1) + 2).coerceIn(1, 6))
                            generateBanter(
                                banterPrev,
                                banterNext,
                                allowResearch = false,
                                upcoming = look,
                                tracksUntilTalk = until,
                            )
                        }
                    }
                    prefetchedBanter = null
                    prefetchedForUri = null

                    val speechMs = speechDurationMs(line, bakedMs)

                    // Natural ends: land speech on the true outro BEFORE posting the bubble.
                    // Early wait aborts (mid-track pause / API blip) must not leave a
                    // banter chat line that never got spoken, and must not skip forward.
                    if (!isSkipReason && !isIdleKick) {
                        if (talkover) {
                            publish(
                                status = "🎙 Holding for outro (${speechMs / 1000}s line)…",
                                transitioning = true,
                            )
                            val ready = waitUntilRemainAbout(
                                expectedUri = prevUri,
                                // Cap headroom so a bad TTS duration never starts mid-song.
                                targetRemainMs = (speechMs + 900L).coerceIn(4_000L, 18_000L),
                                maxWaitMs = 120_000L,
                            )
                            if (!ready) {
                                Log.i(TAG, "banter talkover wait aborted — re-arm (no freeze)")
                                // Restore bake so the next near-end arm can reuse it.
                                if (banterNext != null && !line.isBlank()) {
                                    prefetchedBanter = line
                                    prefetchedForUri = banterNext.uri
                                    if (!bakedPath.isNullOrBlank()) {
                                        prefetchedTtsPath = bakedPath
                                        prefetchedTtsDurationMs = bakedMs
                                    }
                                }
                                // Do NOT holdAutoHandoff — that froze the whole set after
                                // Spotify paused flickers and songs never advanced.
                                publish(status = "Wait aborted — watching for outro again")
                                return@withContext
                            }
                        } else {
                            // Clean mic: pause only in the last ~1.2s of the cut.
                            publish(
                                status = "🎙 Holding for last second (${speechMs / 1000}s ready)…",
                                transitioning = true,
                            )
                            val ready = waitUntilRemainAbout(
                                expectedUri = prevUri,
                                targetRemainMs = 1_200L,
                                maxWaitMs = 120_000L,
                            )
                            if (!ready) {
                                Log.i(TAG, "banter clean-mic wait aborted — re-arm (no freeze)")
                                if (banterNext != null && !line.isBlank()) {
                                    prefetchedBanter = line
                                    prefetchedForUri = banterNext.uri
                                    if (!bakedPath.isNullOrBlank()) {
                                        prefetchedTtsPath = bakedPath
                                        prefetchedTtsDurationMs = bakedMs
                                    }
                                }
                                publish(status = "Wait aborted — watching for outro again")
                                return@withContext
                            }
                        }
                    }

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
                            // Clean mic pause — arm grace so poll never freezes us mid-banter
                            // after transition ends, and so empty player between pause→play
                            // is treated as handoff buffer not "user paused".
                            armInterTrackGrace(45_000L)
                            wasPlaying = true // sticky: we own this pause for banter
                            spotifyPut("/v1/me/player/pause", "{}")
                            delay(150L)
                        }
                    }

                    var duckedFrom: Int? = null
                    if (talkover) {
                        duckedFrom = duckSpotifyVolume(targetPercent = 32)
                    }

                    val voice = store.voiceId
                    val speakJson = JSONObject().apply {
                        put("wait", true)
                        put("voice_id", voice)
                        put("language", "en")
                        put("talkover", talkover)
                        if (!bakedPath.isNullOrBlank() && File(bakedPath!!).isFile) {
                            put("audio_path", bakedPath)
                            // Consume the bake — delete after play.
                            put("keep_file", false)
                        }
                    }.toString()
                    val speakRaw = HostAiClient.speak(
                        applicationContext,
                        // When audio_path is set, text is unused; keep for device fallback.
                        line,
                        speakJson,
                    )
                    var speak = runCatching { JSONObject(speakRaw) }.getOrElse { JSONObject() }
                    // Clear bake bookkeeping regardless of play outcome.
                    if (bakedPath != null && prefetchedTtsPath == bakedPath) {
                        prefetchedTtsPath = null
                        prefetchedTtsDurationMs = 0L
                    } else {
                        clearPrefetchedBanterAudio()
                    }
                    // Orphan bake file if cached play failed (live path re-synthesizes).
                    if (!speak.optBoolean("ok") && !bakedPath.isNullOrBlank()) {
                        runCatching { File(bakedPath!!).delete() }
                    }
                    var mode = speak.optString("mode", "")
                    var spokeOk = speak.optBoolean("ok")
                    if (!spokeOk) {
                        val err = speak.optString("error", "tts_failed")
                        val hint = speak.optString("hint", "")
                        val xaiErr = speak.optString("xai_error", "")
                        Log.w(TAG, "banter speak failed: $err body=${speak.optString("body")} xai=$xaiErr")
                        // Live fallback if cached play failed
                        if (!bakedPath.isNullOrBlank()) {
                            val fallbackJson = JSONObject()
                                .put("wait", true)
                                .put("voice_id", voice)
                                .put("language", "en")
                                .put("talkover", talkover)
                                .toString()
                            val fbRaw = HostAiClient.speak(applicationContext, line, fallbackJson)
                            speak = runCatching { JSONObject(fbRaw) }.getOrElse { JSONObject() }
                            mode = speak.optString("mode", "")
                            spokeOk = speak.optBoolean("ok")
                            if (spokeOk) {
                                publish(
                                    status = "🎙 spoke ($mode${if (talkover) " · talkover" else ""} · live)…",
                                    clearError = true,
                                    transitioning = true,
                                )
                            } else {
                                publish(
                                    status = "TTS failed: $err" +
                                        if (hint.isNotBlank()) " — $hint" else "",
                                    error = err,
                                )
                            }
                        } else {
                            publish(
                                status = "TTS failed: $err" +
                                    if (hint.isNotBlank()) " — $hint" else "",
                                error = err,
                            )
                        }
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
                    // Fixed → same N; Random → new target in [min, max] for the next cycle.
                    banterEvery = store.rollNextBanterEvery()
                    store.songsSinceBanter = songsSinceBanter
                    // Spoken handoff already "used" this cycle — don't +1 when next lands.
                    lastBanterCountedPlayUri = banterNext?.uri ?: next?.uri

                    val style = when {
                        !spokeOk -> " · (banter silent)"
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
                            // Budget scales with real remaining time — a fixed 12s used to
                            // force-next while ~15–25s of outro was still playing.
                            publish(
                                status = "🎙 Waiting for track end$style…",
                                transitioning = true,
                            )
                            val endBudget = (lastRemainMs + 10_000L).coerceIn(10_000L, 50_000L)
                            val natural = awaitNativeAdvance(
                                prevUri,
                                banterNext ?: next,
                                maxWaitMs = endBudget,
                            )
                            if (natural) {
                                true
                            } else {
                                // Still only force if we're actually near the end.
                                val snap = readPlaybackSnap()
                                val stillMid = snap != null &&
                                    snap.uri == prevUri &&
                                    snap.playing &&
                                    snap.remainMs > 6_000L
                                if (stillMid) {
                                    Log.i(
                                        TAG,
                                        "talkover end-wait: still mid remain=${snap!!.remainMs}ms — " +
                                            "keep waiting once more",
                                    )
                                    val natural2 = awaitNativeAdvance(
                                        prevUri,
                                        banterNext ?: next,
                                        maxWaitMs = (snap.remainMs + 5_000L).coerceIn(8_000L, 40_000L),
                                    )
                                    if (natural2) {
                                        true
                                    } else {
                                        advanceToNext(
                                            next = banterNext ?: next,
                                            prevUri = prevUri,
                                            preferSkip = false,
                                            allowPlayFallback = true,
                                        )
                                    }
                                } else {
                                    advanceToNext(
                                        next = banterNext ?: next,
                                        prevUri = prevUri,
                                        preferSkip = false,
                                        allowPlayFallback = true,
                                    )
                                }
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
                        val nextCd = banterCountdownLabel(
                            songsSinceBanter,
                            banterEvery,
                            store.banterMode,
                            store.banterMin,
                            store.banterMax,
                        )
                        publish(
                            status = "Playing next$style · $nextCd",
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
                    // Count toward random/fixed cycle; do not re-roll until banter fires.
                    // Cap so external sync races cannot push us past "due" forever.
                    //
                    // near_end_direct used to fire at ~3.5s remain and immediately
                    // playTrack the next cut — that chopped the last few seconds every
                    // silent handoff. Wait until the true outro (or natural end).
                    if (!isSkipReason && !isIdleKick) {
                        publish(
                            status = "Holding for track end…",
                            transitioning = true,
                        )
                        val ready = waitUntilRemainAbout(
                            expectedUri = prevUri,
                            targetRemainMs = 800L,
                            maxWaitMs = 90_000L,
                        )
                        if (!ready) {
                            Log.i(TAG, "silent handoff wait aborted — re-arm (no freeze)")
                            // Leave songsSinceBanter alone (count is below). finally clears
                            // handoffLaunchedForUri so near_end can fire again.
                            publish(status = "Wait aborted — watching for outro again")
                            return@withContext
                        }
                    }
                    songsSinceBanter = (songsSinceBanter + 1).coerceAtMost(banterEvery)
                    store.songsSinceBanter = songsSinceBanter
                    store.banterEvery = banterEvery
                    // Mark this handoff so an external "queue match" poll doesn't +1 again.
                    lastBanterCountedPlayUri = next?.uri
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
                        val nextCd = banterCountdownLabel(
                            songsSinceBanter,
                            banterEvery,
                            store.banterMode,
                            store.banterMin,
                            store.banterMax,
                        )
                        publish(
                            status = "Playing next · $nextCd",
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
            // Always clear so a failed handoff can re-arm stuck_end on the same URI.
            handoffLaunchedForUri = null
            midPauseSinceMs = 0L
            // Do NOT wipe lastRemainMs to 999999 — that made empty-player polls look like
            // a mid-set pause and froze auto-handoff ("DJ thinks it's paused").
            if (lastRemainMs > 20_000L && pendingPlayVerifyUri == null) {
                // Successful playTrack already set a high remain for the new cut.
            } else if (pendingPlayVerifyUri == null && lastRemainMs > 8_000L) {
                lastRemainMs = 2_000L
            }
            // Keep treating empty/paused flickers as between-song buffer, not user pause.
            armInterTrackGrace(14_000L)
            transitioning.set(false)
            // Always flush countdown after a handoff so UI + leave/return stay honest.
            store.songsSinceBanter = songsSinceBanter
            store.banterEvery = banterEvery
            persistRuntimeState()
            publish(
                transitioning = false,
                status = SpotifyDjBus.state.value.status.let { s ->
                    if (s.contains("talk in") || s.contains("banter") || s.startsWith("Playing") ||
                        s.contains("Starting next") || s.contains("Track ended")
                    ) {
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
        clearPrefetchedBanterAudio()
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
                lastBanterCountedPlayUri = selected.uri
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
        midPauseSinceMs = 0L
        // Buffering the new cut often reports paused + full remain — grace covers it.
        armInterTrackGrace(if (ok) 16_000L else 10_000L)
        if (ok || res.status == 404) {
            releaseAutoHandoff("play_track")
            // API 204 often lies — verify the transport actually starts, else retry.
            val samePending = pendingPlayVerifyUri == item.uri
            if (!samePending) pendingPlayRetries = 0
            pendingPlayVerifyUri = item.uri
            pendingPlayVerifyUntilMs = playAt + 14_000L
        } else {
            clearPendingPlayVerify()
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
                "Play rejected · ${friendlySpotifyError(res.status, res.error)}"
            },
            clearError = ok,
            error = if (ok) null else (res.error ?: "play_${res.status}"),
        )
        Log.i(TAG, "direct-play ${item.uri.takeLast(22)} ok=$ok upcomingApp=$upcomingCount")
        return ok
    }

    /**
     * Local media-session playing state when Notification Listener can see Spotify.
     * Avoids Web API poll + sticky [wasPlaying] desync (common after long pause).
     */
    private fun sessionIsPlaying(): Boolean? {
        return try {
            val ctrl = resolveActiveMediaController(this) ?: return null
            when (ctrl.playbackState?.state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                -> true
                PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_STOPPED,
                PlaybackState.STATE_NONE,
                -> false
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "sessionIsPlaying: ${e.message}")
            null
        }
    }

    /** Pause/play via Spotify's media session — no API quota. */
    private fun trySessionTransport(wantPlay: Boolean): Boolean {
        return try {
            val ctrl = resolveActiveMediaController(this) ?: return false
            if (wantPlay) ctrl.transportControls.play()
            else ctrl.transportControls.pause()
            true
        } catch (e: Exception) {
            Log.w(TAG, "session transport: ${e.message}")
            false
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            // Our session may own media buttons — swallow the echo so we don't recurse.
            ignoreMediaButtonsUntilMs = SystemClock.elapsedRealtime() + 900L
            val am = getSystemService(AudioManager::class.java) ?: return
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Exception) {
            Log.w(TAG, "media key $keyCode: ${e.message}")
        }
    }

    /** Best track to resume when empty-body /me/player/play fails. */
    private fun resumeTrackCandidate(): DjQueueTrack? {
        current?.takeIf { it.uri.isNotBlank() }?.let { return it }
        val uri = lastUri?.takeIf { it.isNotBlank() }
            ?: store.lastCurrentUri.takeIf { it.isNotBlank() }
            ?: return null
        return DjQueueTrack(uri = uri)
    }

    private fun applyPausedUi() {
        wasPlaying = false
        holdAutoHandoff("user_pause_toggle")
        current?.uri?.let { updateNowPlayingFlags(it, playing = false) }
        publish(
            status = "Paused · auto-handoff waiting",
            nowLine = current?.let { t ->
                "⏸ ${t.name.ifBlank { t.uri }}" +
                    if (t.artists.isNotBlank()) " — ${t.artists}" else ""
            },
            clearError = true,
        )
        syncMediaSession(force = true)
    }

    private fun applyPlayingUi(source: String) {
        wasPlaying = true
        releaseAutoHandoff(source)
        current?.uri?.let { updateNowPlayingFlags(it, playing = true) }
        publish(
            status = "Playing",
            nowLine = current?.let { t ->
                "▶ ${t.name.ifBlank { t.uri }}" +
                    if (t.artists.isNotBlank()) " — ${t.artists}" else ""
            },
            clearError = true,
        )
        syncMediaSession(force = true)
    }

    /**
     * Pause / resume.
     *
     * Prefer media-session transport (no rate limit). Empty-body Web API resume often
     * fails after a long pause or under 429 while song-pick (full URI) still works —
     * so resume falls back to re-playing the current track via [playTrack].
     */
    private fun togglePause() {
        val curPlaying = sessionIsPlaying() ?: wasPlaying
        if (curPlaying) {
            if (trySessionTransport(wantPlay = false)) {
                applyPausedUi()
                return
            }
            val res = spotifyPut("/v1/me/player/pause", "{}")
            if (res.ok || res.status in listOf(202, 204)) {
                applyPausedUi()
                return
            }
            // Last resort — system media key (still may land if Spotify holds focus).
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            applyPausedUi()
            if (isRateLimitResult(res)) {
                publish(
                    status = "Paused (local) · ${friendlySpotifyError(res.status, res.error)}",
                    error = "rate_limited",
                )
            } else if (!(res.ok || res.status in listOf(202, 204))) {
                Log.w(TAG, "pause API failed status=${res.status} err=${res.error} — used media key")
            }
            return
        }

        // ── Resume ──────────────────────────────────────────────────────────
        if (trySessionTransport(wantPlay = true)) {
            applyPlayingUi("user_play_session")
            return
        }
        val emptyPlay = spotifyPut("/v1/me/player/play", "{}")
        if (emptyPlay.ok || emptyPlay.status in listOf(202, 204)) {
            applyPlayingUi("user_play_toggle")
            return
        }
        // Same path as tapping a track — device + full URI + deep-link retries.
        // This is what still works when empty resume is rate-limited or device-less.
        val item = resumeTrackCandidate()
        if (item != null) {
            Log.i(
                TAG,
                "resume empty-play failed status=${emptyPlay.status} err=${emptyPlay.error} " +
                    "— replaying ${item.uri.takeLast(22)}",
            )
            if (playTrack(item)) {
                // playTrack already published + released handoff
                return
            }
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        // Optimistic UI — media key may still recover without API
        applyPlayingUi("user_play_media_key")
        val hint = emptyPlay.error ?: "play_${emptyPlay.status}"
        publish(
            status = "Play attempted · if silent, pick the track or open Spotify",
            error = hint,
        )
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
                    // Save / heart current cut to Liked Songs (before generic "don't like")
                    (lower.contains("like this") || lower.contains("love this") ||
                        lower.contains("heart this") || lower.contains("save this") ||
                        lower == "like" || lower == "heart it" || lower == "save it" ||
                        lower.contains("add to liked") || lower.contains("save to library") ||
                        lower.contains("add to my liked")) &&
                        !lower.contains("don't like") && !lower.contains("dont like") &&
                        !lower.contains("hate") && !lower.contains("unlike") -> {
                        val uri = current?.uri ?: lastUri.orEmpty()
                        val err = setSpotifyTrackLiked(applicationContext, uri, liked = true)
                        replaceStreaming(
                            thinkingId,
                            if (err == null) {
                                val name = current?.name?.ifBlank { null } ?: "that cut"
                                "Hearted — $name is in your Liked Songs."
                            } else {
                                "Couldn't like it: $err"
                            },
                        )
                        return@withContext
                    }
                    lower.contains("unlike this") || lower.contains("remove like") ||
                        lower.contains("unheart") || lower.contains("remove from liked") -> {
                        val uri = current?.uri ?: lastUri.orEmpty()
                        val err = setSpotifyTrackLiked(applicationContext, uri, liked = false)
                        replaceStreaming(
                            thinkingId,
                            if (err == null) "Removed from Liked Songs."
                            else "Couldn't unlike: $err",
                        )
                        return@withContext
                    }
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

        val behaviorTpl = store.activeBehaviorTemplate()
        val behaviorStyle = behaviorTpl.body.ifBlank {
            store.behaviorMode.systemStyleBlock()
        }
        val system = applyPromptPlaceholders(
            store.systemTemplate(DjPromptKind.ChatSystem).body
                .ifBlank { DjPromptDefaults.chatSystem().body },
            mapOf("BEHAVIOR_STYLE" to behaviorStyle),
        )

        val prompt = buildString {
            append("Now playing: $nowLine\n")
            append("Upcoming queue:\n$qSummary\n")
            append("Behavior mode: ${behaviorTpl.label}\n")
            if (store.selectedGenres.isNotEmpty()) {
                append("Genre board: ${store.selectedGenres.joinToString(", ")}\n")
            }
            val lName = store.listenerName
            if (lName.isNotBlank()) {
                append("Listener NAME (person — not a place): $lName\n")
            }
            if (store.listenerCity.isNotBlank()) {
                append(
                    "Listener CITY (location only — never address them as this): " +
                        "${store.listenerCity}\n",
                )
            }
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
            // Reuse the handoff researcher with synthetic track shells for chat Q&A.
            val nextShell = DjQueueTrack(
                uri = "",
                name = songForResearch,
                artists = artistForResearch,
            )
            researchMusicFacts(
                prev = current,
                next = nextShell,
                city = store.listenerCity,
                genres = store.selectedGenres,
            )
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
                val ids = artistIdsOf(t)
                val arts = artistsOf(t)
                val aUri = artistUriOf(t)
                if (uri.isBlank() || isPlayed(uri) || isDisliked(uri, ids, arts, aUri) ||
                    queue.any { it.uri == uri }
                ) {
                    continue
                }
                queue.addLast(
                    DjQueueTrack(
                        uri = uri,
                        name = t.optString("name", ""),
                        artists = arts,
                        reason = "chat: $q",
                        artistIds = ids,
                        albumArtUrl = albumArtUrlOf(t),
                        artistArtUrl = artistArtUrlOf(ids.firstOrNull().orEmpty()),
                        albumUri = albumUriOf(t),
                        artistUri = aUri,
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

    /**
     * "More like this" from a now-playing or past chat cut.
     *
     * Builds a **mixed** batch (not same-artist radio):
     * - a couple same-artist deep cuts
     * - majority related-artist / similar cuts
     * - genre-adjacent + playlist-radio spice
     *
     * Prepended so they play next (after current finishes / on skip).
     */
    private fun moreLikeThis(
        seedUri: String,
        seedName: String,
        seedArtists: String,
        seedArtistUri: String = "",
        want: Int = 8,
    ): Int {
        publish(status = "Finding more like this…", persist = false)
        val label = when {
            seedName.isNotBlank() && seedArtists.isNotBlank() ->
                "“${seedName.take(40)}” — ${seedArtists.take(40)}"
            seedName.isNotBlank() -> "“${seedName.take(48)}”"
            seedArtists.isNotBlank() -> seedArtists.take(48)
            else -> seedUri.takeLast(22)
        }
        val seedArtistIds = LinkedHashSet<String>()
        // Prefer artist URI from the bubble, then live current match, then track lookup.
        artistIdFromUri(seedArtistUri)?.let { seedArtistIds.add(it) }
        val cur = current
        if (seedUri.isNotBlank() && cur?.uri == seedUri) {
            cur.artistIds.forEach { if (it.isNotBlank()) seedArtistIds.add(it) }
            artistIdFromUri(cur.artistUri)?.let { seedArtistIds.add(it) }
        }
        if (seedArtistIds.isEmpty() && seedUri.isNotBlank()) {
            val id = seedUri.substringAfter("spotify:track:", "")
                .ifBlank { seedUri.substringAfterLast(':') }
            if (id.isNotBlank() && !id.contains(':')) {
                val tr = spotifyGet("/v1/tracks/${java.net.URLEncoder.encode(id, "UTF-8")}")
                artistIdsOf(tr.json ?: JSONObject()).forEach { seedArtistIds.add(it) }
            }
        }
        if (seedArtistIds.isEmpty()) {
            val hint = primaryArtist(seedArtists).ifBlank { seedName }
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
        if (seedArtistIds.isEmpty()) {
            publish(status = "More like this failed", clearError = false)
            appendChat(
                DjChatMessage(
                    id = "sys-mlt-fail-${System.currentTimeMillis()}",
                    role = DjChatRole.System,
                    text = "More like this failed — couldn't find artists for $label",
                ),
            )
            return 0
        }

        val target = want.coerceIn(4, 12)
        // Mix targets — same-artist is the *minority*, similars dominate.
        val wantSame = (target * 0.25).toInt().coerceIn(1, 3)
        val wantRelated = (target * 0.50).toInt().coerceIn(2, 6)
        val wantAdjacent = (target - wantSame - wantRelated).coerceAtLeast(1)

        val seen = HashSet<String>()
        seen.add(seedUri)
        cur?.uri?.takeIf { it.isNotBlank() }?.let { seen.add(it) }
        synchronized(queue) { queue.forEach { seen.add(it.uri) } }

        val samePool = ArrayList<DjQueueTrack>(24)
        val relatedPool = ArrayList<DjQueueTrack>(48)
        val adjacentPool = ArrayList<DjQueueTrack>(48)

        fun consider(t: JSONObject?, reason: String, into: MutableList<DjQueueTrack>) {
            if (t == null || t.optBoolean("is_local", false)) return
            val uri = t.optString("uri", "")
            if (uri.isBlank() || !seen.add(uri)) return
            if (isPlayed(uri)) return
            val ids = artistIdsOf(t)
            val arts = artistsOf(t)
            val aUri = artistUriOf(t)
            if (isDisliked(uri, ids, arts, aUri)) return
            into.add(
                DjQueueTrack(
                    uri = uri,
                    name = t.optString("name", ""),
                    artists = arts,
                    reason = reason,
                    artistIds = ids,
                    albumArtUrl = albumArtUrlOf(t),
                    artistArtUrl = "",
                    albumUri = albumUriOf(t),
                    artistUri = aUri,
                ),
            )
        }

        fun isSeedArtist(ids: List<String>): Boolean =
            ids.any { it in seedArtistIds }

        val primaryIds = seedArtistIds.toList().take(3)
        val primary = primaryIds.firstOrNull().orEmpty()

        // ── 1) Same-artist deep cuts (capped) — tops + album B-sides ─────────
        for (aid in primaryIds) {
            val tops = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/top-tracks?market=US",
            )
            val tracks = tops.json?.optJSONArray("tracks")
            if (tracks != null) {
                val pick = (0 until tracks.length()).shuffled().take(4)
                for (j in pick) {
                    consider(tracks.optJSONObject(j), "more like: same artist", samePool)
                }
            }
            // Album deep cuts for variety beyond the hits
            val albs = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/albums" +
                    "?include_groups=album,single&market=US&limit=8",
            )
            val albItems = albs.json?.optJSONArray("items")
            if (albItems != null && albItems.length() > 0) {
                val aPick = (0 until albItems.length()).shuffled().take(2)
                for (j in aPick) {
                    val alb = albItems.optJSONObject(j) ?: continue
                    val albId = alb.optString("id", "")
                    if (albId.isBlank()) continue
                    val tr = spotifyGet(
                        "/v1/albums/${java.net.URLEncoder.encode(albId, "UTF-8")}/tracks?limit=20&market=US",
                    )
                    val aTracks = tr.json?.optJSONArray("items") ?: continue
                    val k = (0 until aTracks.length()).shuffled().take(2)
                    for (m in k) {
                        // Album track objects lack full artist payloads — hydrate via id if needed.
                        val raw = aTracks.optJSONObject(m) ?: continue
                        val tid = raw.optString("id", "")
                        if (tid.isBlank()) {
                            consider(raw, "more like: same artist album", samePool)
                            continue
                        }
                        val full = spotifyGet(
                            "/v1/tracks/${java.net.URLEncoder.encode(tid, "UTF-8")}",
                        )
                        consider(
                            full.json ?: raw,
                            "more like: same artist album",
                            samePool,
                        )
                    }
                }
            }
        }

        // ── 2) Related artists (majority of the vibe) ────────────────────────
        val relatedArtistIds = LinkedHashSet<String>()
        for (aid in primaryIds) {
            val rel = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/related-artists",
            )
            val related = rel.json?.optJSONArray("artists") ?: continue
            val rPick = (0 until related.length()).shuffled().take(8)
            for (j in rPick) {
                val ra = related.optJSONObject(j) ?: continue
                val rid = ra.optString("id", "")
                if (rid.isNotBlank() && rid !in seedArtistIds) relatedArtistIds.add(rid)
            }
        }
        // Shuffle related artists so each press explores a different neighborhood.
        for (rid in relatedArtistIds.shuffled().take(10)) {
            val rt = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(rid, "UTF-8")}/top-tracks?market=US",
            )
            val rTracks = rt.json?.optJSONArray("tracks") ?: continue
            // 1–3 tracks per related artist, randomized depth
            val depth = (1..3).random()
            val k = (0 until rTracks.length()).shuffled().take(depth)
            for (m in k) {
                consider(rTracks.optJSONObject(m), "more like: related artist", relatedPool)
            }
        }

        // ── 3) Genre-adjacent + playlist radio + listener taste spice ───────
        val seedGenres = LinkedHashSet<String>()
        if (primary.isNotBlank()) {
            val aRes = spotifyGet("/v1/artists/${java.net.URLEncoder.encode(primary, "UTF-8")}")
            val gArr = aRes.json?.optJSONArray("genres")
            if (gArr != null) {
                for (i in 0 until gArr.length()) {
                    val g = gArr.optString(i, "").trim()
                    if (g.isNotBlank()) seedGenres.add(g)
                }
            }
        }
        // Also pull genres from a couple related artists for broader vibe.
        for (rid in relatedArtistIds.shuffled().take(2)) {
            val aRes = spotifyGet("/v1/artists/${java.net.URLEncoder.encode(rid, "UTF-8")}")
            val gArr = aRes.json?.optJSONArray("genres") ?: continue
            for (i in 0 until gArr.length()) {
                val g = gArr.optString(i, "").trim()
                if (g.isNotBlank()) seedGenres.add(g)
            }
        }
        for (g in seedGenres.shuffled().take(3)) {
            val q = java.net.URLEncoder.encode("genre:\"$g\"", "UTF-8")
            val search = spotifyGet("/v1/search?type=track&limit=15&q=$q")
            val items = search.json?.optJSONObject("tracks")?.optJSONArray("items")
            if (items != null && items.length() > 0) {
                val pick = (0 until items.length()).shuffled().take(6)
                for (j in pick) {
                    val t = items.optJSONObject(j) ?: continue
                    // Prefer non-seed artists so this bucket stays "similar", not "more of them"
                    if (isSeedArtist(artistIdsOf(t))) continue
                    consider(t, "more like: genre · $g", adjacentPool)
                }
            } else {
                val q2 = java.net.URLEncoder.encode(g, "UTF-8")
                val s2 = spotifyGet("/v1/search?type=track&limit=12&q=$q2")
                val t2 = s2.json?.optJSONObject("tracks")?.optJSONArray("items")
                if (t2 != null) {
                    val pick = (0 until t2.length()).shuffled().take(4)
                    for (j in pick) {
                        val t = t2.optJSONObject(j) ?: continue
                        if (isSeedArtist(artistIdsOf(t))) continue
                        consider(t, "more like: genre · $g", adjacentPool)
                    }
                }
            }
        }

        // Playlist radio: search public playlists for the seed artist / track vibe
        val plQueries = buildList {
            val pa = primaryArtist(seedArtists)
            if (pa.isNotBlank()) {
                add("$pa radio")
                add("$pa mix")
            }
            if (seedName.isNotBlank() && pa.isNotBlank()) add("$seedName $pa")
            seedGenres.shuffled().take(1).forEach { add("$it playlist") }
        }.distinct().shuffled().take(2)
        for (pq in plQueries) {
            val q = java.net.URLEncoder.encode(pq, "UTF-8")
            val pls = spotifyGet("/v1/search?type=playlist&limit=4&q=$q")
            val items = pls.json?.optJSONObject("playlists")?.optJSONArray("items") ?: continue
            val pPick = (0 until items.length()).shuffled().take(2)
            for (j in pPick) {
                val pl = items.optJSONObject(j) ?: continue
                val pid = pl.optString("id", "")
                if (pid.isBlank()) continue
                val plName = pl.optString("name", "mix").take(28)
                val tr = spotifyGet(
                    "/v1/playlists/${java.net.URLEncoder.encode(pid, "UTF-8")}/tracks?limit=40",
                )
                val trItems = tr.json?.optJSONArray("items") ?: continue
                val k = (0 until trItems.length()).shuffled().take(6)
                for (m in k) {
                    val t = trItems.optJSONObject(m)?.optJSONObject("track") ?: continue
                    if (isSeedArtist(artistIdsOf(t))) continue
                    consider(t, "more like: playlist · $plName", adjacentPool)
                }
            }
        }

        // Listener taste blend: liked / short-term top that aren't the seed artist
        val liked = spotifyGet("/v1/me/tracks?limit=30")
        if (liked.ok) {
            val items = liked.json?.optJSONArray("items")
            if (items != null) {
                val idx = (0 until items.length()).shuffled().take(12)
                for (i in idx) {
                    val t = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                    if (isSeedArtist(artistIdsOf(t))) continue
                    // Soft filter: share a genre token or related-artist id when possible
                    val ids = artistIdsOf(t)
                    val relatedHit = ids.any { it in relatedArtistIds }
                    consider(
                        t,
                        if (relatedHit) "more like: liked · related" else "more like: liked blend",
                        if (relatedHit) relatedPool else adjacentPool,
                    )
                }
            }
        }
        val top = spotifyGet("/v1/me/top/tracks?time_range=short_term&limit=20")
        if (top.ok) {
            val items = top.json?.optJSONArray("items")
            if (items != null) {
                val idx = (0 until items.length()).shuffled().take(8)
                for (i in idx) {
                    val t = items.optJSONObject(i) ?: continue
                    if (isSeedArtist(artistIdsOf(t))) continue
                    consider(t, "more like: your tops blend", adjacentPool)
                }
            }
        }

        // Thin-pool fallbacks: expand related further, then same-artist last resort
        if (relatedPool.size < wantRelated && primary.isNotBlank()) {
            val rel2 = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(primary, "UTF-8")}/related-artists",
            )
            val related = rel2.json?.optJSONArray("artists")
            if (related != null) {
                for (j in 0 until related.length()) {
                    if (relatedPool.size >= wantRelated * 3) break
                    val rid = related.optJSONObject(j)?.optString("id", "").orEmpty()
                    if (rid.isBlank() || rid in seedArtistIds) continue
                    val rt = spotifyGet(
                        "/v1/artists/${java.net.URLEncoder.encode(rid, "UTF-8")}/top-tracks?market=US",
                    )
                    val rTracks = rt.json?.optJSONArray("tracks") ?: continue
                    val k = (0 until rTracks.length()).shuffled().take(2)
                    for (m in k) {
                        consider(rTracks.optJSONObject(m), "more like: related artist", relatedPool)
                    }
                }
            }
        }
        if (samePool.size < wantSame) {
            val artistHint = primaryArtist(seedArtists)
            if (artistHint.isNotBlank()) {
                val q = java.net.URLEncoder.encode("artist:\"$artistHint\"", "UTF-8")
                val s = spotifyGet("/v1/search?type=track&limit=12&q=$q")
                val items = s.json?.optJSONObject("tracks")?.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        consider(items.optJSONObject(i), "more like: same artist search", samePool)
                    }
                }
            }
        }

        // ── Compose balanced, artist-diverse, interleaved batch ────────────
        fun takeDiverse(pool: List<DjQueueTrack>, n: Int, maxPerArtist: Int = 2): List<DjQueueTrack> {
            if (n <= 0 || pool.isEmpty()) return emptyList()
            val out = ArrayList<DjQueueTrack>(n)
            val perArtist = HashMap<String, Int>()
            for (t in pool.shuffled()) {
                if (out.size >= n) break
                val key = primaryArtist(t.artists).lowercase().ifBlank {
                    t.artistIds.firstOrNull().orEmpty()
                }
                val c = perArtist[key] ?: 0
                if (key.isNotBlank() && c >= maxPerArtist) continue
                out.add(t)
                if (key.isNotBlank()) perArtist[key] = c + 1
            }
            // Fill if diversity cap was too strict
            if (out.size < n) {
                val have = out.map { it.uri }.toHashSet()
                for (t in pool.shuffled()) {
                    if (out.size >= n) break
                    if (have.add(t.uri)) out.add(t)
                }
            }
            return out
        }

        var samePicks = takeDiverse(samePool, wantSame, maxPerArtist = 2)
        var relatedPicks = takeDiverse(relatedPool, wantRelated, maxPerArtist = 1)
        var adjacentPicks = takeDiverse(adjacentPool, wantAdjacent, maxPerArtist = 1)

        // Steal from fuller buckets if one ran short
        fun topUp(need: Int, vararg sources: List<DjQueueTrack>): List<DjQueueTrack> {
            if (need <= 0) return emptyList()
            val have = HashSet<String>()
            samePicks.forEach { have.add(it.uri) }
            relatedPicks.forEach { have.add(it.uri) }
            adjacentPicks.forEach { have.add(it.uri) }
            val extra = ArrayList<DjQueueTrack>(need)
            for (src in sources) {
                for (t in src.shuffled()) {
                    if (extra.size >= need) break
                    if (have.add(t.uri)) extra.add(t)
                }
                if (extra.size >= need) break
            }
            return extra
        }
        val shortfall = target - (samePicks.size + relatedPicks.size + adjacentPicks.size)
        if (shortfall > 0) {
            val fill = topUp(shortfall, relatedPool, adjacentPool, samePool)
            // Prefer stuffing similars first
            relatedPicks = relatedPicks + fill
        }

        // Interleave buckets so you don't get 3 same-artist in a row.
        val picked = ArrayList<DjQueueTrack>(target)
        val buckets = listOf(
            samePicks.toMutableList(),
            relatedPicks.toMutableList(),
            adjacentPicks.toMutableList(),
        ).shuffled() // randomize which bucket leads each press
        // Slightly prefer starting with a similar/related cut over same-artist
        val orderedBuckets = buckets.sortedBy { b ->
            when {
                b.firstOrNull()?.reason?.contains("related") == true -> 0
                b.firstOrNull()?.reason?.contains("genre") == true -> 1
                b.firstOrNull()?.reason?.contains("playlist") == true -> 1
                b.firstOrNull()?.reason?.contains("same artist") == true -> 3
                else -> 2
            }
        }
        var guard = 0
        while (picked.size < target && guard < 64) {
            guard++
            var added = false
            for (b in orderedBuckets) {
                if (picked.size >= target) break
                if (b.isEmpty()) continue
                val t = b.removeAt(0)
                if (picked.none { it.uri == t.uri }) {
                    picked.add(t)
                    added = true
                }
            }
            if (!added) break
        }
        // Final artist-stack soft pass: avoid 3+ consecutive same primary
        val finalList = ArrayList<DjQueueTrack>(picked.size)
        val deferred = ArrayList<DjQueueTrack>()
        for (t in picked) {
            val p = primaryArtist(t.artists).lowercase()
            val lastTwo = finalList.takeLast(2).map { primaryArtist(it.artists).lowercase() }
            if (p.isNotBlank() && lastTwo.size == 2 && lastTwo.all { it == p }) {
                deferred.add(t)
            } else {
                finalList.add(t)
            }
        }
        for (t in deferred) {
            if (finalList.none { it.uri == t.uri }) finalList.add(t)
        }

        if (finalList.isEmpty()) {
            publish(status = "More like this · nothing new", clearError = false)
            appendChat(
                DjChatMessage(
                    id = "sys-mlt-empty-${System.currentTimeMillis()}",
                    role = DjChatRole.System,
                    text = "More like this: nothing new for $label (already played/queued)",
                ),
            )
            return 0
        }

        val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
        // Prepend so the first pick is next up (addFirst in reverse order).
        synchronized(queue) {
            for (t in finalList.asReversed()) {
                if (isDisliked(t) || isPlayed(t.uri)) continue
                if (queue.none { it.uri == t.uri }) {
                    queue.addFirst(t)
                }
            }
            while (queue.size > MAX_DJ_QUEUE) queue.removeLast()
        }
        val newHead = synchronized(queue) { queue.firstOrNull()?.uri }
        if (prevHead != newHead) invalidateStaleBanterCaches(newHead)
        persistRuntimeState()
        val n = finalList.size
        val sameN = finalList.count { it.reason.contains("same artist") }
        val relN = finalList.count {
            it.reason.contains("related") || it.reason.contains("genre") ||
                it.reason.contains("playlist") || it.reason.contains("liked") ||
                it.reason.contains("tops")
        }
        val listLines = finalList.mapIndexed { i, t ->
            val title = t.name.ifBlank { "track" }.take(36)
            val art = t.artists.take(28)
            val tag = when {
                t.reason.contains("same artist") -> "same"
                t.reason.contains("related") -> "related"
                t.reason.contains("genre") -> "genre"
                t.reason.contains("playlist") -> "mix"
                t.reason.contains("liked") || t.reason.contains("tops") -> "you"
                else -> "sim"
            }
            if (art.isNotBlank()) "${i + 1}. [$tag] $title — $art" else "${i + 1}. [$tag] $title"
        }.joinToString("\n")
        // Status clears the in-chat "Finding…" indicator (UI watches this).
        publish(
            status = "More like this · added $n",
            clearError = true,
        )
        appendChat(
            DjChatMessage(
                id = "sys-mlt-${System.currentTimeMillis()}",
                role = DjChatRole.System,
                text = "More like $label — added $n to UP NEXT " +
                    "($sameN same-artist · $relN similar, no talk):\n$listLines",
            ),
        )
        Log.i(
            TAG,
            "moreLikeThis n=$n same=$sameN similar=$relN " +
                "pools=${samePool.size}/${relatedPool.size}/${adjacentPool.size} " +
                "seed=$seedUri artists=${seedArtistIds.joinToString()} genres=${seedGenres.take(4)}",
        )
        return n
    }

    private fun artistIdFromUri(uri: String?): String? {
        val u = uri?.trim().orEmpty()
        if (u.isBlank()) return null
        val id = when {
            u.startsWith("spotify:artist:") -> u.removePrefix("spotify:artist:")
            u.contains("/artist/") -> u.substringAfterLast('/').substringBefore('?')
            else -> ""
        }.trim()
        return id.takeIf { it.isNotBlank() && !it.contains(':') && !it.contains('/') }
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
                    albumArtUrl = SpotifyArtMirror.preferredUrl(this@SpotifyLiveDjService, track.albumArtUrl)
                        .ifBlank { track.albumArtUrl }.ifBlank { null },
                    artistArtUrl = SpotifyArtMirror.preferredUrl(this@SpotifyLiveDjService, track.artistArtUrl)
                        .ifBlank { track.artistArtUrl }.ifBlank { null },
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
        // Cache covers on our host so widgets/UI never re-hit Spotify CDN.
        SpotifyArtMirror.mirrorAllAsync(
            this,
            listOf(track.albumArtUrl, track.artistArtUrl),
        )
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
        if (isRateLimited()) {
            val waitSec = ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(1L)
            publish(
                status = "Queue fill paused — Spotify rate limit (${waitSec}s)",
                error = "rate_limited",
            )
            return
        }
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
            // Stop mid-fill if Spotify started rate-limiting the pool crawl.
            if (isRateLimited() && pool.size < 4) {
                publish(
                    status = "Queue fill cooled off — Spotify rate limit (using ${pool.size} seeds)",
                    error = "rate_limited",
                )
            }
            // If replace still yields nothing (everything marked played), forget more —
            // but keep the freshest recently-played exclusions (re-fetch will re-mark).
            if (replace && pool.size < 8 && playedUris.isNotEmpty() && !isRateLimited()) {
                val keys = playedUris.keys.take(playedUris.size.coerceAtLeast(1) / 2)
                keys.forEach { playedUris.remove(it) }
                store.savePlayedUris(playedUris.toMap())
                pool = gatherRadioPool(curForPool)
            }
            // City set → optional local-show discovery injects a few artist cuts
            if (store.listenerCity.isNotBlank() && !isRateLimited()) {
                publish(status = "Checking shows near ${store.listenerCity}…")
                pool = injectLocalShowTracks(pool, curForPool)
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
            // Never re-add recently played / already heard / disliked
            batch = batch.filter {
                !isPlayed(it.uri) && !isDisliked(it) && it.uri != curForPool?.uri
            }
            val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
            synchronized(queue) {
                batch.forEach { t ->
                    if (queue.none { it.uri == t.uri } &&
                        !isPlayed(t.uri) &&
                        !isDisliked(t)
                    ) {
                        queue.addLast(t)
                    }
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
     * When the listener set a city, ask host Grok which familiar artists have real
     * upcoming local shows and inject a few of their tracks into the radio pool.
     */
    private fun injectLocalShowTracks(
        pool: List<DjQueueTrack>,
        current: DjQueueTrack?,
    ): List<DjQueueTrack> {
        val city = store.listenerCity.trim()
        if (city.isBlank()) return pool
        val seedNames = LinkedHashSet<String>()
        current?.let { seedNames.add(primaryArtist(it.artists)) }
        pool.take(40).forEach { seedNames.add(primaryArtist(it.artists)) }
        // Top artists for “artists I already listen to”
        for (range in listOf("short_term", "medium_term")) {
            val topA = spotifyGet("/v1/me/top/artists?time_range=$range&limit=12")
            val items = topA.json?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val n = items.optJSONObject(i)?.optString("name", "").orEmpty()
                if (n.isNotBlank()) seedNames.add(n)
            }
        }
        val names = seedNames.filter { it.isNotBlank() }.take(18)
        if (names.isEmpty()) return pool

        val system =
            "You research live music shows. USE web search/tools. " +
                "Given a listener city and a list of artists they listen to, return JSON ONLY: " +
                "{\"artists\":[{\"name\":\"Artist\",\"show\":\"short venue/date if real\"}]}. " +
                "Only include artists with a REAL upcoming or recently announced show " +
                "in/near the city (or clearly touring there). Max 5. Empty array if none. " +
                "NEVER invent dates. No markdown."
        val prompt =
            "Listener city: $city\nArtists:\n" +
                names.joinToString("\n") { "- $it" } +
                "\nWhich of these have real upcoming shows near $city? JSON only."
        val opts = JSONObject()
            .put("system", system)
            .put("session_title", "· Spotify Live DJ Shows")
            .toString()
        val hits = try {
            val raw = HostAiClient.complete(applicationContext, prompt, opts)
            val env = runCatching { JSONObject(raw) }.getOrNull() ?: return pool
            if (!env.optBoolean("ok")) return pool
            val json = extractJson(env.optString("text", "")) ?: return pool
            val arr = json.optJSONArray("artists") ?: return pool
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "").trim()
                    if (name.isBlank()) continue
                    add(name to o.optString("show", "").trim())
                }
            }.take(5)
        } catch (e: Exception) {
            Log.w(TAG, "injectLocalShowTracks: ${e.message}")
            return pool
        }
        if (hits.isEmpty()) return pool

        val out = ArrayList(pool)
        val seen = out.map { it.uri }.toMutableSet()
        for ((artist, show) in hits) {
            val q = java.net.URLEncoder.encode(artist, "UTF-8")
            val s = spotifyGet("/v1/search?type=artist&limit=1&q=$q")
            val a = s.json?.optJSONObject("artists")?.optJSONArray("items")?.optJSONObject(0)
            val aid = a?.optString("id", "").orEmpty()
            if (aid.isBlank()) continue
            val tops = spotifyGet(
                "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/top-tracks?market=US",
            )
            val tracks = tops.json?.optJSONArray("tracks") ?: continue
            val pick = (0 until tracks.length()).shuffled().take(2)
            for (j in pick) {
                val t = tracks.optJSONObject(j) ?: continue
                val uri = t.optString("uri", "")
                if (uri.isBlank() || !seen.add(uri)) continue
                if (isPlayed(uri) || current?.uri == uri) continue
                val ids = artistIdsOf(t)
                val arts = artistsOf(t)
                val aUri = artistUriOf(t)
                if (isDisliked(uri, ids, arts, aUri) || store.isArtistIdBlocked(aid)) continue
                val reason = if (show.isNotBlank()) {
                    "in town near $city · $show"
                } else {
                    "in town near $city"
                }
                out.add(
                    DjQueueTrack(
                        uri = uri,
                        name = t.optString("name", ""),
                        artists = arts,
                        reason = reason,
                        artistIds = ids,
                        albumArtUrl = albumArtUrlOf(t),
                        albumUri = albumUriOf(t),
                        artistUri = aUri,
                    ),
                )
            }
        }
        if (hits.isNotEmpty()) {
            val namesHit = hits.joinToString { it.first }
            appendChat(
                DjChatMessage(
                    id = "sys-shows-${System.currentTimeMillis()}",
                    role = DjChatRole.System,
                    text = "Local shows · $city: $namesHit — queued a few cuts from artists with dates nearby.",
                ),
            )
        }
        return out
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
        // Abort expansion once Spotify 429s so we don't dig a deeper rate-limit hole.
        fun rateLimitedOut(): Boolean = isRateLimited()

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
            if (isDisliked(uri, artistIds, artists, artistUri)) return
            if (current?.uri == uri) return
            // Still allow as seed-only when artist is blocked? No — skip entirely.
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
            artistIds.forEach { id ->
                if (id.isNotBlank() && !store.isArtistIdBlocked(id)) seedArtistIds.add(id)
            }
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
        if (rateLimitedOut()) return pool
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
                    ids.forEach { id ->
                        if (id.isNotBlank() && !store.isArtistIdBlocked(id)) seedArtistIds.add(id)
                    }
                    // Seed for song_radio expansion only — not a queue candidate
                    // (skip seeds for permanently blocked artists)
                    val arts = artistsOf(t)
                    val aUri = artistUriOf(t)
                    if (uri.isNotBlank() && !isDisliked(uri, ids, arts, aUri)) {
                        seedTracks.add(
                            DjQueueTrack(
                                uri = uri,
                                name = t.optString("name", ""),
                                artists = arts,
                                reason = "recently played (exclude)",
                                artistIds = ids,
                                albumArtUrl = albumArtUrlOf(t),
                                albumUri = albumUriOf(t),
                                artistUri = aUri,
                            ),
                        )
                    }
                }
                Log.i(TAG, "recently-played exclude seeds=${seedTracks.size} artists=${seedArtistIds.size}")
            }
        }

        // 2) Top tracks (short + medium term — what Spotify DJ leans on)
        for (range in listOf("short_term", "medium_term")) {
            if (rateLimitedOut()) return pool
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
            if (rateLimitedOut()) return pool
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
        if (!rateLimitedOut()) {
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
        }

        // Current track's artists are strong radio seeds
        current?.artistIds?.forEach { if (it.isNotBlank()) seedArtistIds.add(it) }
        if (current != null && current.artistIds.isEmpty() && !rateLimitedOut()) {
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

        if (rateLimitedOut()) return pool

        // Rotate radio modes like Spotify's DJ (artist radio vs song-adjacent vs liked)
        val modes = listOf("artist_radio", "song_radio", "liked_blend", "top_blend")
        val mode = modes[radioModeIdx % modes.size]
        radioModeIdx++

        // Drop any artist seeds the user permanently blocked via Dislike.
        seedArtistIds.removeAll { store.isArtistIdBlocked(it) }

        // 5) Artist radio: top tracks + related artists for a handful of seeds
        val artistPick = seedArtistIds.shuffled().take(
            when (mode) {
                "artist_radio" -> 6
                "song_radio" -> 3
                else -> 4
            },
        )
        for (aid in artistPick) {
            if (store.isArtistIdBlocked(aid)) continue
            if (rateLimitedOut()) return pool
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
                if (rateLimitedOut()) return pool
                val rel = spotifyGet(
                    "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/related-artists",
                )
                val related = rel.json?.optJSONArray("artists")
                if (related != null) {
                    val rPick = (0 until related.length()).shuffled().take(2)
                    for (j in rPick) {
                        if (rateLimitedOut()) return pool
                        val ra = related.optJSONObject(j) ?: continue
                        val rid = ra.optString("id", "")
                        if (rid.isBlank() || store.isArtistIdBlocked(rid)) continue
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
                if (rateLimitedOut()) return pool
                val aid = s.artistIds.firstOrNull { !store.isArtistIdBlocked(it) }.orEmpty()
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

        // 8) Optional genre board — search + artist seeds for selected genres
        val genres = store.selectedGenres
        if (genres.isNotEmpty()) {
            for (g in genres.shuffled().take(MAX_DJ_GENRES)) {
                val q = java.net.URLEncoder.encode("genre:\"$g\"", "UTF-8")
                val search = spotifyGet("/v1/search?type=track&limit=12&q=$q")
                val tracks = search.json
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("items")
                if (tracks != null && tracks.length() > 0) {
                    val pick = (0 until tracks.length()).shuffled().take(6)
                    for (j in pick) {
                        addFromTrackObj(tracks.optJSONObject(j), "genre board: $g")
                    }
                } else {
                    // Fallback: plain keyword search when genre: filter is empty
                    val q2 = java.net.URLEncoder.encode(g, "UTF-8")
                    val s2 = spotifyGet("/v1/search?type=track&limit=10&q=$q2")
                    val t2 = s2.json?.optJSONObject("tracks")?.optJSONArray("items")
                    if (t2 != null) {
                        val pick = (0 until t2.length()).shuffled().take(4)
                        for (j in pick) {
                            addFromTrackObj(t2.optJSONObject(j), "genre board: $g")
                        }
                    }
                }
                // Artist search for the genre label → top tracks
                val aq = java.net.URLEncoder.encode(g, "UTF-8")
                val arts = spotifyGet("/v1/search?type=artist&limit=4&q=$aq")
                    .json?.optJSONObject("artists")?.optJSONArray("items")
                if (arts != null) {
                    val aPick = (0 until arts.length()).shuffled().take(2)
                    for (j in aPick) {
                        val aid = arts.optJSONObject(j)?.optString("id", "").orEmpty()
                        if (aid.isBlank()) continue
                        val tops = spotifyGet(
                            "/v1/artists/${java.net.URLEncoder.encode(aid, "UTF-8")}/top-tracks?market=US",
                        )
                        val tt = tops.json?.optJSONArray("tracks") ?: continue
                        val k = (0 until tt.length()).shuffled().take(2)
                        for (m in k) {
                            addFromTrackObj(tt.optJSONObject(m), "genre artist: $g")
                        }
                    }
                }
            }
            Log.i(TAG, "genre board active=${genres.joinToString()} pool=${pool.size}")
        }

        // 9) Soft city discovery: if city set, lightly boost artists already in seed set
        // (show-aware queueing is primarily research/banter; pool still benefits from
        // related-artist expansion already done above.)

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
        val genres = store.selectedGenres.map { it.lowercase() }
        val scored = pool.map { t ->
            var score = Math.random() * 2.5
            val arts = t.artists.lowercase()
            val reasonL = t.reason.lowercase()
            for (a in curArtists) {
                if (arts.contains(a)) score += 3.5
            }
            when {
                reasonL.contains("liked") -> score += 2.0
                reasonL.contains("top tracks") -> score += 1.8
                // recently played are excluded from pool (used only as radio seeds)
                reasonL.contains("artist radio") -> score += 1.6
                reasonL.contains("related") -> score += 1.3
                reasonL.contains("song radio") -> score += 1.5
                reasonL.startsWith("playlist") -> score += 0.7
                reasonL.contains("genre board") -> score += 2.4
                reasonL.contains("genre artist") -> score += 2.0
            }
            // Soft boost when track reason or artist field mentions a selected genre
            for (g in genres) {
                if (g.isNotBlank() && (reasonL.contains(g) || arts.contains(g))) {
                    score += 1.2
                }
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
        val genres = store.selectedGenres
        val city = store.listenerCity
        val behavior = store.behaviorMode
        val system =
            "You are a radio DJ music director (Spotify DJ style). Reply ONLY with valid JSON: " +
                "{\"picks\":[{\"uri\":\"spotify:track:...\",\"banter_note\":\"short why\"}],\"banter\":\"\"}. " +
                "Pick exactly $n tracks from the CANDIDATES list only (use their uris). " +
                "Blend liked/top seeds with artist-radio variety" +
                (if (genres.isNotEmpty()) {
                    " and lean into these genres when candidates support it: ${genres.joinToString(", ")}."
                } else {
                    "."
                }) +
                " Candidates already exclude recently played and already-heard tracks — never re-pick those. " +
                "Avoid stacking the same primary artist twice in a row. " +
                "Leave banter empty (spoken lines are generated separately). No markdown."
        val prompt = buildString {
            appendLine("CURRENT: $curLine")
            appendLine("Behavior mode (queue energy, not spoken line): ${behavior.label}")
            if (genres.isNotEmpty()) {
                appendLine("Genre board (optional bias — prefer matching candidates): ${genres.joinToString(", ")}")
            }
            if (city.isNotBlank()) {
                appendLine(
                    "Listener city: $city — prefer familiar artists / discovery that fits a " +
                        "local-show-aware set when candidates allow (don't invent).",
                )
            }
            if (vibeHint.isNotBlank()) appendLine("Vibe hint: $vibeHint")
            appendLine()
            appendLine("CANDIDATES (not recently played):")
            appendLine(list)
            appendLine()
            appendLine("Pick $n next tracks for a continuous live DJ set. Do not repeat recently heard songs.")
        }
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
            if (isPlayed(hit.uri) || isDisliked(hit)) continue
            // Keep original pool reason (liked / top / artist radio / chat:…) for attribution.
            // Never let AI banter_note overwrite source — that made the DJ claim "you queued" DJ picks.
            out.add(hit)
            if (out.size >= n) break
        }
        // Spoken banter is generated separately at handoff; ignore fill-time banter text.
        return if (out.isNotEmpty()) out to "" else null
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
        upcoming: List<DjQueueTrack> = emptyList(),
        tracksUntilTalk: Int = 0,
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
        val ai = aiBanterLine(
            prev,
            next,
            allowResearch = allowResearch,
            upcoming = upcoming,
            tracksUntilTalk = tracksUntilTalk,
        )
        if (!ai.isNullOrBlank()) return sanitizeSpoken(ai).take(360)
        return localBanterLine(prev, next)
    }

    /**
     * Spotify-side album metadata for research (name + release year).
     */
    private fun spotifyAlbumMeta(track: DjQueueTrack?): Pair<String, String> {
        if (track == null) return "" to ""
        val id = track.uri.removePrefix("spotify:track:").takeIf {
            track.uri.startsWith("spotify:track:") && it.isNotBlank()
        } ?: return "" to ""
        val json = spotifyGet("/v1/tracks/$id").json ?: return "" to ""
        val album = json.optJSONObject("album")
        val name = album?.optString("name", "").orEmpty()
        val year = album?.optString("release_date", "")?.take(4).orEmpty()
        return name to year
    }

    /**
     * Tool-backed research for on-air color. Each call randomly picks 1–3
     * [DjResearchAngle]s (lyrics, album/song facts, artist facts, shows/tours,
     * recent X/social, radio host color) so banter stays varied. Cached per next URI.
     */
    private fun researchMusicFacts(
        prev: DjQueueTrack?,
        next: DjQueueTrack?,
        city: String,
        genres: List<String>,
        upcoming: List<DjQueueTrack> = emptyList(),
        tracksUntilTalk: Int = 0,
        listenerName: String = "",
    ): List<String> {
        val nextArtist = next?.let { primaryArtist(it.artists) }.orEmpty()
        val nextSong = next?.let { cleanTitle(it.name) }.orEmpty()
        val prevArtist = prev?.let { primaryArtist(it.artists) }.orEmpty()
        val prevSong = prev?.let { cleanTitle(it.name) }.orEmpty()
        if (nextArtist.isBlank() && nextSong.isBlank() && prevArtist.isBlank()) return emptyList()

        val (nextAlbum, nextYear) = spotifyAlbumMeta(next)
        val (prevAlbum, prevYear) = spotifyAlbumMeta(prev)
        val cityLine = city.trim()
        val nameLine = listenerName.trim()
        // Random pack from user-enabled research templates (custom + built-in).
        val angleTemplates = pickResearchTemplates(store.loadPromptTemplates())
        val angleLabels = angleTemplates.joinToString(" + ") { it.label }
        val angleIds = angleTemplates.map { it.id }.toSet()
        val angleBriefs = buildString {
            angleTemplates.forEachIndexed { i, a ->
                val brief = applyPromptPlaceholders(
                    a.body,
                    mapOf("CITY" to cityLine.ifBlank { "(not set)" }),
                )
                append("${i + 1}) $brief\n")
            }
        }
        val system = applyPromptPlaceholders(
            store.systemTemplate(DjPromptKind.ResearchSystem).body
                .ifBlank { DjPromptDefaults.researchSystem().body },
            mapOf("ANGLE_BRIEFS" to angleBriefs.trimEnd()),
        )

        val prompt = buildString {
            appendLine("RESEARCH ANGLES THIS TURN: $angleLabels")
            appendLine(
                "Listener NAME (person — never a place): " +
                    nameLine.ifBlank { "(not set — do not invent a name)" },
            )
            appendLine(
                "Listener CITY (location only — never address them as this): " +
                    cityLine.ifBlank { "(not set — national/global shows only)" },
            )
            if (genres.isNotEmpty()) {
                appendLine("Active genre board (taste bias): ${genres.joinToString(", ")}")
            }
            appendLine()
            appendLine("CURRENT (just finishing / now):")
            appendLine("  artist: ${prevArtist.ifBlank { "(unknown)" }}")
            appendLine("  song: ${prevSong.ifBlank { "(unknown)" }}")
            if (prevAlbum.isNotBlank()) {
                appendLine("  album: $prevAlbum${if (prevYear.isNotBlank()) " ($prevYear)" else ""}")
            }
            appendLine()
            appendLine("NEXT (up next / handoff target):")
            appendLine("  artist: ${nextArtist.ifBlank { "(unknown)" }}")
            appendLine("  song: ${nextSong.ifBlank { "(unknown)" }}")
            if (nextAlbum.isNotBlank()) {
                appendLine("  album: $nextAlbum${if (nextYear.isNotBlank()) " ($nextYear)" else ""}")
            }
            val look = upcoming.filter { it.uri != next?.uri }.take(5)
            if (look.isNotEmpty() || tracksUntilTalk > 0) {
                appendLine()
                appendLine(
                    "BANTER COUNTDOWN: DJ speaks in $tracksUntilTalk track(s) " +
                        "(0 = this handoff is the talk).",
                )
                appendLine("SETLIST LOOKAHEAD (after NEXT — do not invent tracks):")
                look.forEachIndexed { i, t ->
                    appendLine(
                        "  ${i + 2}. ${cleanTitle(t.name)} — ${primaryArtist(t.artists)}",
                    )
                }
                if (look.isEmpty()) {
                    appendLine("  (only NEXT is locked; no deeper queue yet)")
                }
            }
            appendLine()
            appendLine(
                "Use web search / tools now for: $angleLabels. " +
                    "Skip angles not listed. " +
                    if (cityLine.isNotBlank() &&
                        angleIds.any { it.contains("show") || it.contains("tour") }
                    ) {
                        "For shows: check near $cityLine AND broader tour news."
                    } else {
                        ""
                    },
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
            val out = ArrayList<String>(14)
            // Tag so banter knows what this pack focused on.
            out.add("Research focus: $angleLabels")

            fun addLabeled(label: String, value: String, max: Int = 180) {
                val v = value.trim()
                if (v.isNotBlank()) out.add("$label: ${v.take(max)}")
            }
            val wantLyrics = angleIds.any {
                it.contains("lyric") || it.contains("theme") || it.contains("meaning")
            }
            if (wantLyrics) {
                addLabeled("Current song theme", json.optString("current_lyrics_theme", ""))
                addLabeled("Next song theme", json.optString("next_lyrics_theme", ""))
            }

            // Always surface Spotify album meta we already know.
            if (nextAlbum.isNotBlank()) {
                val y = if (nextYear.isNotBlank()) " ($nextYear)" else ""
                out.add("Next album: $nextAlbum$y")
            }
            if (prevAlbum.isNotBlank() && !prevAlbum.equals(nextAlbum, ignoreCase = true)) {
                val y = if (prevYear.isNotBlank()) " ($prevYear)" else ""
                out.add("Current album: $prevAlbum$y")
            }

            fun drainArray(key: String, prefix: String?, limit: Int) {
                val arr = json.optJSONArray(key) ?: return
                var n = 0
                for (i in 0 until arr.length()) {
                    if (n >= limit) break
                    val f = arr.optString(i, "").trim()
                    if (f.isBlank()) continue
                    out.add(if (prefix != null) "$prefix: ${f.take(160)}" else f.take(160))
                    n++
                }
            }
            val wantAlbumArtist = angleIds.any {
                it.contains("album") || it.contains("song_fact") || it.contains("artist_fact") ||
                    it.contains("fact")
            }
            if (wantAlbumArtist || angleTemplates.isNotEmpty()) {
                // Always try structured fields when present — custom angles may still fill them.
                drainArray("album_facts", "Album", 2)
                drainArray("facts", null, 4)
            }
            if (angleIds.any { it.contains("show") || it.contains("tour") } || angleTemplates.any {
                    it.body.contains("SHOWS", ignoreCase = true) ||
                        it.body.contains("TOUR", ignoreCase = true)
                }
            ) {
                drainArray("shows", "Upcoming", 3)
            }
            if (angleIds.any { it.contains("x_social") || it.contains("social") } ||
                angleTemplates.any {
                    it.body.contains("X / SOCIAL", ignoreCase = true) ||
                        it.body.contains("X/social", ignoreCase = true)
                }
            ) {
                drainArray("x_social", "X/social", 3)
            }
            if (angleIds.any { it.contains("radio") || it.contains("host") || it.contains("color") } ||
                angleTemplates.any { it.body.contains("RADIO HOST", ignoreCase = true) }
            ) {
                drainArray("radio_color", "Host color", 3)
            }
            drainArray("setlist_tease", "Later in set", 2)
            // Flat fallback if angle fields empty
            if (out.size <= 2) {
                drainArray("bullets", null, 4)
            }
            Log.i(
                TAG,
                "research facts next=“$nextArtist/$nextSong” angles=[$angleLabels] " +
                    "city=$cityLine name=$nameLine → ${out.size} bullets",
            )
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
        upcoming: List<DjQueueTrack> = emptyList(),
        tracksUntilTalk: Int = 0,
    ): List<String> {
        val uri = next?.uri.orEmpty()
        if (uri.isNotBlank() && researchedForUri == uri) {
            return researchedFacts
        }
        if (!allowResearch) {
            // Handoff path: never block on tools; empty facts → pure handoff line.
            return emptyList()
        }
        val city = store.listenerCity
        val genres = store.selectedGenres
        val name = store.listenerName.ifBlank {
            // One-shot pull so unhinged can roast by name without a settings visit.
            resolveListenerName(applicationContext, store)
        }
        val facts = researchMusicFacts(
            prev,
            next,
            city,
            genres,
            upcoming = upcoming,
            tracksUntilTalk = tracksUntilTalk,
            listenerName = name,
        )
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
        upcoming: List<DjQueueTrack> = emptyList(),
        tracksUntilTalk: Int = 0,
    ): String? {
        val prevTitle = prev?.let { cleanTitle(it.name) }.orEmpty()
        val prevArtist = prev?.let { primaryArtist(it.artists) }.orEmpty()
        val nextTitle = next?.let { cleanTitle(it.name) }.orEmpty()
        val nextArtist = next?.let { primaryArtist(it.artists) }.orEmpty()
        val nextAllArtists = next?.artists.orEmpty()
        val reason = next?.reason.orEmpty()
        val behaviorTpl = store.activeBehaviorTemplate()
        val behaviorLabel = behaviorTpl.label
        val behaviorStyle = behaviorTpl.body.ifBlank {
            store.behaviorMode.systemStyleBlock()
        }
        val unhingedMode = behaviorTpl.hasFlag(DjPromptDefaults.FLAG_UNHINGED_TASTE) ||
            behaviorTpl.id == "unhinged" ||
            behaviorTpl.id == "hype_unhinged"
        val city = store.listenerCity
        val listenerName = store.listenerName.ifBlank {
            resolveListenerName(applicationContext, store)
        }

        // Step 1: tool-backed research only during prefetch; handoff reuses cache.
        val research = factsForTrack(
            prev,
            next,
            allowResearch = allowResearch,
            upcoming = upcoming,
            tracksUntilTalk = tracksUntilTalk,
        )

        val wordCap = when {
            unhingedMode -> 75
            behaviorTpl.id == "comedy" -> 55
            behaviorTpl.id == "soothing" -> 48
            else -> 52
        }

        val unhingedTaste = if (unhingedMode) {
            "• TASTE ROAST (required for this mode): insult the listener's music taste at least " +
                "once — genre board vibes, the last track, or the next one. " +
                "Do NOT claim they queued a DJ pick. Use RESEARCH as roast ammo when present. " +
                "Playful-savage, not bigoted.\n"
        } else {
            ""
        }

        val nameBlock =
            "• NAME vs CITY (critical): LISTENER NAME is how you address the person. " +
                "LISTENER CITY is only a place for shows/weather/local color. " +
                "NEVER greet or address the listener using the city as if it were their name " +
                "(wrong: \"what's up, Aurora\" when Aurora is only the city field). " +
                if (listenerName.isNotBlank()) {
                    "NAME is set to \"$listenerName\" — you MAY use it once naturally " +
                        "(or roast them by that name in unhinged modes). "
                } else {
                    "NAME is blank — use \"you\" / \"folks\"; do not invent a name; " +
                        "do not use the city as a nickname. "
                }

        val nextSource = queueSourceLabel(reason)
        val prevSource = queueSourceLabel(prev?.reason.orEmpty())

        val system = applyPromptPlaceholders(
            store.systemTemplate(DjPromptKind.BanterSystem).body
                .ifBlank { DjPromptDefaults.banterSystem().body },
            mapOf(
                "WORD_CAP" to wordCap.toString(),
                "BEHAVIOR_STYLE" to behaviorStyle,
                "UNHINGED_EXTRA" to unhingedTaste,
                "NAME_BLOCK" to nameBlock,
            ),
        )

        val prompt = buildString {
            appendLine("Behavior mode: $behaviorLabel")
            appendLine(
                "LISTENER NAME (person to address — NOT a place): " +
                    listenerName.ifBlank { "(not set — say you/folks, never use city as name)" },
            )
            appendLine(
                "LISTENER CITY (location only for shows/local color — NEVER a greeting name): " +
                    city.ifBlank { "(not set)" },
            )
            val gens = store.selectedGenres
            if (gens.isNotEmpty()) appendLine("Genre board (taste signal): ${gens.joinToString(", ")}")
            appendLine("Just played:")
            if (prev != null) {
                appendLine("  raw title: ${prev.name}")
                appendLine("  clean title: $prevTitle")
                appendLine("  artists field: ${prev.artists}")
                appendLine("  primary artist: $prevArtist")
                appendLine("  SOURCE: $prevSource")
                if (prev.reason.isNotBlank()) appendLine("  pick detail: ${prev.reason}")
            } else {
                appendLine("  (cold open / nothing specific)")
            }
            appendLine("Up next:")
            if (next != null) {
                appendLine("  raw title: ${next.name}")
                appendLine("  clean title: $nextTitle")
                appendLine("  artists field: $nextAllArtists")
                appendLine("  primary artist: $nextArtist")
                appendLine("  SOURCE: $nextSource")
                if (reason.isNotBlank()) {
                    appendLine(
                        "  pick detail (internal — seed/radio reason, NOT proof the listener queued it): $reason",
                    )
                }
            } else {
                appendLine("  (still digging in the library)")
            }
            appendLine()
            appendLine(
                "BANTER COUNTDOWN: you are speaking at this handoff " +
                    "(tracks-until-talk was $tracksUntilTalk before this line).",
            )
            val look = upcoming.filter { it.uri != next?.uri }.take(5)
            if (look.isNotEmpty()) {
                appendLine("SETLIST AHEAD (after the immediate next cut — tease 0–1 if natural):")
                look.forEachIndexed { i, t ->
                    appendLine(
                        "  +${i + 2}: ${cleanTitle(t.name)} — ${primaryArtist(t.artists)}",
                    )
                }
                appendLine(
                    "Like a real radio DJ you MAY briefly tease something later " +
                        "(\"after this we've got…\") but always introduce the IMMEDIATE next cut clearly.",
                )
            }
            appendLine()
            if (research.isNotEmpty()) {
                appendLine(
                    "RESEARCH (random angle pack this cycle — use at most one beat if it fits):",
                )
                research.forEachIndexed { i, f -> appendLine("  ${i + 1}. $f") }
            } else {
                appendLine("RESEARCH: (none solid — pure handoff, no invented news)")
            }
            appendLine()
            appendLine(
                "Write the on-air line in $behaviorLabel style: close out the previous vibe, " +
                    "optionally drop one researched beat (whichever angle this pack has), " +
                    if (unhingedMode) {
                        "roast their taste, "
                    } else {
                        ""
                    } +
                    "then introduce the next track clearly. " +
                    "Filter delivery through $behaviorLabel personality only after research is chosen.",
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

    private fun isDisliked(t: DjQueueTrack): Boolean =
        store.isDisliked(t.uri, t.artistIds, t.artists, t.artistUri)

    private fun isDisliked(
        uri: String,
        artistIds: List<String> = emptyList(),
        artists: String = "",
        artistUri: String = "",
    ): Boolean = store.isDisliked(uri, artistIds, artists, artistUri)

    /**
     * Persist dislike reasons, purge matching UP NEXT rows, mark the cut heard.
     * @return true when the caller should skip (current track was disliked).
     */
    private fun applyDislike(
        trackUri: String,
        trackName: String,
        artists: String,
        artistUri: String,
        artistIds: List<String>,
        reasons: Set<String>,
        skipIfPlaying: Boolean,
    ): Boolean {
        val uri = trackUri.trim()
        // Prefer live current metadata when the URI matches (richer artist ids).
        val cur = current
        val ids = LinkedHashSet<String>()
        artistIds.forEach { if (it.isNotBlank()) ids.add(it) }
        if (uri.isNotBlank() && cur?.uri == uri) {
            cur.artistIds.forEach { if (it.isNotBlank()) ids.add(it) }
            artistIdFromUri(cur.artistUri)?.let { ids.add(it) }
        }
        artistIdFromUri(artistUri)?.let { ids.add(it) }
        val name = trackName.ifBlank { if (cur?.uri == uri) cur.name else "" }
        val arts = artists.ifBlank { if (cur?.uri == uri) cur.artists else "" }
        val aUri = artistUri.ifBlank { if (cur?.uri == uri) cur.artistUri else "" }

        val summary = store.applyDislike(
            trackUri = uri,
            trackName = name,
            artists = arts,
            artistUri = aUri,
            artistIds = ids.toList(),
            reasons = reasons,
        )
        // Always treat as heard so soft played-set + refill won't re-pick soon.
        if (uri.isNotBlank()) markPlayed(uri)

        // Drop matching rows from UP NEXT (this cut, and whole artist if blocked).
        val reasonKeys = reasons.map { it.lowercase() }.toSet()
        val blockArtist = DjDislikeReason.ARTIST in reasonKeys
        var removed = 0
        val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
        synchronized(queue) {
            val kept = queue.filterNot { t ->
                val hit = when {
                    uri.isNotBlank() && t.uri == uri -> true
                    isDisliked(t) -> true
                    blockArtist && ids.isNotEmpty() && t.artistIds.any { it in ids } -> true
                    blockArtist && arts.isNotBlank() &&
                        t.artists.split(",").map { it.trim().lowercase() }
                            .any { a ->
                                a.isNotEmpty() &&
                                    arts.split(",").map { it.trim().lowercase() }.contains(a)
                            } -> true
                    else -> false
                }
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
        }
        persistRuntimeState()

        val status = if (removed > 0) {
            "$summary · removed $removed from UP NEXT"
        } else {
            summary
        }
        publish(status = status, clearError = true)
        appendChat(
            DjChatMessage(
                id = "sys-dislike-${System.currentTimeMillis()}",
                role = DjChatRole.System,
                text = "👎 $status",
            ),
        )
        Log.i(TAG, "dislike uri=$uri reasons=$reasons removed=$removed")

        val playingThis = uri.isNotBlank() && (
            cur?.uri == uri || lastUri == uri || store.lastCurrentUri == uri
            )
        return skipIfPlaying && playingThis
    }

    /**
     * Remove UP NEXT rows that are already in the played / recently-heard set so the
     * Live DJ list cannot stay “ahead” of songs Spotify already finished or skipped.
     * Also drops durable / temporary dislikes (artist, song, lyrics, tired).
     */
    private fun pruneQueueOfPlayed() {
        val before = synchronized(queue) { queue.size }
        val prevHead = synchronized(queue) { queue.firstOrNull()?.uri }
        synchronized(queue) {
            val keep = queue.filterNot {
                isPlayed(it.uri) || isDisliked(it) || it.uri == current?.uri
            }
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
        val retryAfterSec: Int? = null,
    )

    private fun spotifyGet(path: String): ApiResult = spotifyCall("GET", path, null)

    private fun spotifyPut(path: String, body: String?): ApiResult = spotifyCall("PUT", path, body)

    private fun spotifyPost(path: String, body: String?): ApiResult = spotifyCall("POST", path, body)

    /** Process-wide cool-down (also blocks Control UI / widgets via SpotifyOAuth). */
    private fun isRateLimited(now: Long = System.currentTimeMillis()): Boolean =
        SpotifyOAuth.isRateLimited(now) || now < rateLimitedUntilMs

    private fun isRateLimitResult(res: ApiResult): Boolean =
        res.status == 429 ||
            res.error?.contains("429") == true ||
            res.error?.contains("rate limit", ignoreCase = true) == true ||
            res.error?.contains("too many requests", ignoreCase = true) == true

    /**
     * Human status/error for UI — never surface raw `http_429` etc.
     */
    private fun friendlySpotifyError(status: Int, error: String?): String {
        val err = error.orEmpty()
        return when {
            status == 429 || err.contains("429") ||
                err.contains("rate limit", ignoreCase = true) ||
                err.contains("too many requests", ignoreCase = true) -> {
                val wait = (SpotifyOAuth.rateLimitRemainingMs() / 1000L).coerceAtLeast(1L)
                "Spotify rate limit — cooling ${wait}s"
            }
            status == 401 || err == "not_logged_in" ->
                "Spotify session expired — re-authorize in Account"
            status == 403 || err.contains("insufficient", ignoreCase = true) ||
                err.contains("scope", ignoreCase = true) ->
                "Spotify permission missing — re-authorize"
            status == 404 || err.contains("NO_ACTIVE_DEVICE", ignoreCase = true) ||
                err.contains("no active device", ignoreCase = true) ->
                "No active Spotify device — open Spotify once"
            status == 502 || status == 503 ->
                "Spotify is briefly unavailable"
            err.startsWith("http_") -> {
                val code = err.removePrefix("http_").toIntOrNull() ?: status
                "Spotify error (HTTP $code)"
            }
            err.isNotBlank() -> err.take(120)
            status > 0 -> "Spotify error (HTTP $status)"
            else -> "Spotify request failed"
        }
    }

    private fun noteRateLimit(res: ApiResult) {
        if (!isRateLimitResult(res)) return
        // Single process-wide gate so widgets/UI stop calling while Live DJ cools.
        SpotifyOAuth.noteHttpRateLimit(res.retryAfterSec)
        rateLimitedUntilMs = System.currentTimeMillis() + SpotifyOAuth.rateLimitRemainingMs()
        rateLimitBackoffMs = SpotifyOAuth.rateLimitRemainingMs().coerceAtLeast(15_000L)
        Log.w(
            TAG,
            "Spotify rate-limited status=${res.status} err=${res.error} " +
                "retryAfter=${res.retryAfterSec} backoffMs=$rateLimitBackoffMs",
        )
    }

    /** Soft clear: keep a floor so a single success doesn't instantly re-spam. */
    private fun clearRateLimitSoft() {
        SpotifyOAuth.clearRateLimitSoft()
        if (rateLimitedUntilMs <= 0L && rateLimitBackoffMs <= 0L) return
        val now = System.currentTimeMillis()
        if (now >= rateLimitedUntilMs) {
            rateLimitedUntilMs = 0L
            rateLimitBackoffMs = (rateLimitBackoffMs / 2).coerceAtMost(15_000L)
            if (rateLimitBackoffMs < 5_000L) rateLimitBackoffMs = 0L
        }
    }

    /**
     * Drive remain / near-end / now-line from the local media session (no Web API).
     * @return true if session data was usable.
     */
    private fun pollFromMediaSession(rateLimited: Boolean): Boolean {
        val np = try {
            readNowPlaying(this)
        } catch (e: Exception) {
            Log.w(TAG, "session poll: ${e.message}")
            return false
        }
        if (!np.hasSession || np.packageName !in SPOTIFY_PACKAGES) return false
        val uri = np.trackUri.ifBlank { lastUri.orEmpty() }
        if (uri.isBlank() && np.title.isBlank()) return false
        val duration = np.durationMs
        val progress = np.positionMs
        val playing = np.isPlaying
        val remain = if (duration > 0L) (duration - progress).coerceAtLeast(0L) else lastRemainMs
        lastRemainMs = remain
        if (playing) {
            wasPlaying = true
            midPauseSinceMs = 0L
            releaseAutoHandoff(if (rateLimited) "session_playing_rate_limit" else "session_playing")
            idlePolls = 0
        } else if (duration > 0L && remain > 8_000L && !inInterTrackGrace()) {
            // Sustained mid-track pause from session — freeze auto handoff.
            if (midPauseSinceMs == 0L) midPauseSinceMs = System.currentTimeMillis()
            val pausedFor = System.currentTimeMillis() - midPauseSinceMs
            if (pausedFor >= 4_500L) {
                holdAutoHandoff("session_mid_pause remain=${remain}ms")
            }
        }
        val label = buildString {
            append(if (playing) "▶ " else "⏸ ")
            append(np.title.ifBlank { uri })
            if (np.artist.isNotBlank()) append(" — ").append(np.artist)
        }
        val coolHint = if (rateLimited) {
            val waitSec = ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(0L)
            if (waitSec > 0L) " · API cool ${waitSec}s" else " · session"
        } else {
            " · session"
        }
        publish(
            nowLine = label,
            status = (if (playing) "Playing" else "Paused") +
                " · ${queue.size} queued$coolHint",
            clearError = !rateLimited,
            error = if (rateLimited) "rate_limited" else null,
            persist = false,
        )
        // Near-end / stuck-end via session so handoffs still fire under 429.
        // Never while held — a long pause must not "finish" the cut hours later.
        // Require consecutive low-remain polls: media-session position can jump once
        // and falsely look like the outro mid-song.
        if (!transitioning.get() && !autoHandoffHeld && queue.isNotEmpty()) {
            val pastIntro = duration <= 0L || progress >= 12_000L ||
                (duration > 0L && remain <= duration / 2)
            if (playing && remain in 0L..3_500L && pastIntro && handoffLaunchedForUri != uri) {
                sessionNearEndStreak++
                nearEndArmed = true
                if (sessionNearEndStreak >= 2 && handoffLaunchedForUri == null) {
                    handoffLaunchedForUri = uri
                    sessionNearEndStreak = 0
                    scope.launch { runTransition("near_end_direct") }
                }
            } else if (!playing && remain <= 2_500L && wasPlaying && midPauseSinceMs == 0L) {
                sessionNearEndStreak = 0
                stuckEndPolls++
                if (stuckEndPolls >= 2) {
                    stuckEndPolls = 0
                    armInterTrackGrace(12_000L)
                    scope.launch { runTransition("stuck_end") }
                }
            } else if (playing) {
                sessionNearEndStreak = 0
                stuckEndPolls = 0
            }
        } else if (autoHandoffHeld) {
            sessionNearEndStreak = 0
            stuckEndPolls = 0
        }
        if (uri.isNotBlank()) {
            lastUri = uri
            store.lastCurrentUri = uri
        }
        return true
    }

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
        // Hard gate while cooling down — except play/pause which already prefer session.
        if (isRateLimited() && method.equals("GET", ignoreCase = true)) {
            return ApiResult(
                ok = false,
                status = 429,
                json = null,
                error = "rate_limited",
                retryAfterSec = ((rateLimitedUntilMs - System.currentTimeMillis()) / 1000L)
                    .toInt().coerceAtLeast(1),
            )
        }
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
            val retry = if (o.isNull("retryAfter")) null else o.optInt("retryAfter", 0).takeIf { it > 0 }
            val result = ApiResult(
                ok = ok,
                status = status,
                json = json,
                error = err,
                retryAfterSec = retry,
            )
            if (!ok) noteRateLimit(result)
            result
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
                selectedGenres = store.selectedGenres,
                genreBoard = store.genreBoard,
                behaviorMode = store.behaviorMode,
                listenerCity = store.listenerCity,
                listenerName = store.listenerName,
            ),
        )
    }

    /** Last quiet/media FGS signature — skip identical posts (stops shade thrash). */
    @Volatile private var lastDjNotifSig: String = ""

    /**
     * Live DJ FGS notification.
     *
     * When the **lockscreen controller** is enabled, it owns the visible MediaStyle
     * card (prev / play / next). We only need a quiet keep-alive on
     * [SPOTIFY_DJ_NOTIF_ID] so the DJ service stays in the foreground without
     * fighting the controller (this is the model that worked through ~0.1.125).
     *
     * When the controller is off, we post MediaStyle transport ourselves and
     * attach our MediaSession token so SystemUI can promote the card into the
     * lockscreen media carousel.
     */
    private fun startAsForeground(text: String) {
        val n = buildDjForegroundNotification(text)
        lastDjNotifSig = ""
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    SPOTIFY_DJ_NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(SPOTIFY_DJ_NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            runCatching {
                getSystemService(android.app.NotificationManager::class.java)
                    ?.notify(SPOTIFY_DJ_NOTIF_ID, n)
            }
        }
        // Never cancel or overwrite SPOTIFY_CTRL_NOTIF_ID — controller owns that card.
    }

    private fun updateNotif(text: String) {
        // Keep BT / headset media buttons claimed while Live DJ is armed.
        syncMediaSession(force = false)
        // Controller refreshes the visible media card; don't fight it.
        if (SpotifyControllerStore(this).enabled) {
            // Watchdog: if the controller card vanished (OEM demotion / FGS kill),
            // kick it back without thrashing stopService.
            if (!isSpotifyControllerNotificationPosted(this)) {
                Log.w(TAG, "controller notif missing while Live DJ on — ensure")
                runCatching { ensureSpotifyControllerRunning(this, force = true) }
            }
            // Still re-assert quiet FGS status occasionally so the channel stays valid.
            val quietSig = "quiet|${current?.uri.orEmpty()}|${wasPlaying}"
            if (quietSig == lastDjNotifSig) return
            lastDjNotifSig = quietSig
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            runCatching {
                nm.notify(
                    SPOTIFY_DJ_NOTIF_ID,
                    SpotifyMediaNotif.buildHidden(
                        this,
                        title = "Live DJ",
                        status = if (wasPlaying) "On air" else text.take(48).ifBlank { "Standby" },
                    ),
                )
            }
            return
        }
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val n = buildDjForegroundNotification(text)
        // Bucket by track + play bit so progress ticks don't spam shade rebuilds.
        val curUri = current?.uri.orEmpty()
        val curName = current?.name.orEmpty()
        val qSize = synchronized(queue) { queue.size }
        val sig = "media|$curUri|$curName|${wasPlaying}|$qSize|${text.take(40)}"
        if (sig == lastDjNotifSig) return
        lastDjNotifSig = sig
        runCatching { nm.notify(SPOTIFY_DJ_NOTIF_ID, n) }
    }

    /**
     * Active MediaSession so Bluetooth AVRCP / headset keys route here (Next = DJ skip,
     * Prev = restart, Play/Pause = booth toggle). Metadata mirrors Spotify/now-playing
     * for OEM media UIs that read the active session.
     *
     * Also attached to the Live DJ MediaStyle notification so SystemUI promotes the
     * card into the lockscreen media carousel (actions alone are not enough on many OEMs).
     */
    private fun ensureMediaSession() {
        if (mediaSession != null) return
        val session = MediaSessionCompat(this, "GrokifyLiveDj").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        handleMediaSessionCommand("play")
                    }

                    override fun onPause() {
                        handleMediaSessionCommand("pause")
                    }

                    override fun onSkipToNext() {
                        handleMediaSessionCommand("next")
                    }

                    override fun onSkipToPrevious() {
                        handleMediaSessionCommand("prev")
                    }

                    override fun onStop() {
                        handleMediaSessionCommand("pause")
                    }

                    override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                        if (SystemClock.elapsedRealtime() < ignoreMediaButtonsUntilMs) {
                            return true
                        }
                        val ke = mediaButtonKeyEvent(mediaButtonEvent)
                            ?: return super.onMediaButtonEvent(mediaButtonEvent)
                        if (ke.action != KeyEvent.ACTION_DOWN || ke.repeatCount != 0) {
                            return true
                        }
                        val kind = when (ke.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                            -> "next"
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND,
                            -> "prev"
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            -> "play_pause"
                            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
                            KeyEvent.KEYCODE_MEDIA_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_STOP,
                            -> "pause"
                            else -> return super.onMediaButtonEvent(mediaButtonEvent)
                        }
                        handleMediaSessionCommand(kind)
                        return true
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            val openApp = PendingIntent.getActivity(
                this@SpotifyLiveDjService,
                47200,
                Intent(this@SpotifyLiveDjService, MainActivity::class.java).apply {
                    putExtra("open_app", "spotify_controller")
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setSessionActivity(openApp)
            // Cold-start path: process dead → MEDIA_BUTTON lands on receiver → restarts service.
            val mbr = PendingIntent.getBroadcast(
                this@SpotifyLiveDjService,
                47201,
                Intent(Intent.ACTION_MEDIA_BUTTON).setClass(
                    this@SpotifyLiveDjService,
                    SpotifyLiveDjReceiver::class.java,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setMediaButtonReceiver(mbr)
        }
        mediaSession = session
        LiveDjMediaSessionHolder.publish(session)
        Log.i(TAG, "mediaSession created (BT transport)")
    }

    private fun mediaButtonKeyEvent(intent: Intent?): KeyEvent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun handleMediaSessionCommand(kind: String) {
        if (SystemClock.elapsedRealtime() < ignoreMediaButtonsUntilMs) return
        if (!mediaSessionBusy.compareAndSet(false, true)) {
            Log.d(TAG, "mediaSession busy, drop $kind")
            return
        }
        Log.i(TAG, "mediaSession command=$kind")
        when (kind) {
            "next" -> {
                forceBanter = false
                scope.launch {
                    try {
                        runTransition("skip")
                    } finally {
                        mediaSessionBusy.set(false)
                        syncMediaSession(force = true)
                    }
                }
            }
            "prev" -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        restartOrPrevious()
                    } finally {
                        mediaSessionBusy.set(false)
                        syncMediaSession(force = true)
                    }
                }
            }
            "play_pause" -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        togglePause()
                    } finally {
                        mediaSessionBusy.set(false)
                        syncMediaSession(force = true)
                    }
                }
            }
            "play" -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val playing = sessionIsPlaying() ?: wasPlaying
                        if (!playing) togglePause()
                    } finally {
                        mediaSessionBusy.set(false)
                        syncMediaSession(force = true)
                    }
                }
            }
            "pause" -> {
                scope.launch(Dispatchers.IO) {
                    try {
                        val playing = sessionIsPlaying() ?: wasPlaying
                        if (playing) togglePause()
                    } finally {
                        mediaSessionBusy.set(false)
                        syncMediaSession(force = true)
                    }
                }
            }
            else -> mediaSessionBusy.set(false)
        }
    }

    private fun syncMediaSession(force: Boolean) {
        if (!store.enabled) {
            mediaSession?.isActive = false
            lastMediaSessionSig = ""
            return
        }
        // Controller owns the shade/lockscreen MediaSession + media buttons when
        // its widget is on. Two active sessions from the same package made OEMs
        // drop our card and keep only native Spotify. DJ transport still runs
        // via dispatchMediaCommand → spotifyLiveDjSkip/Previous/togglePause.
        if (SpotifyControllerStore(this).enabled) {
            mediaSession?.let { s ->
                if (s.isActive) s.isActive = false
            }
            lastMediaSessionSig = ""
            return
        }
        ensureMediaSession()
        val session = mediaSession ?: return
        val now = runCatching { nowPlayingForNotification(this) }.getOrNull()
        val cur = current
        val title = when {
            now != null && now.title.isNotBlank() && now.title != "Unknown track" -> now.title
            cur != null && cur.name.isNotBlank() -> cur.name
            else -> "Live DJ"
        }
        val artist = when {
            now != null && now.artist.isNotBlank() -> now.artist
            cur != null -> cur.artists
            else -> ""
        }
        val uri = when {
            now != null && now.trackUri.isNotBlank() -> now.trackUri
            cur != null -> cur.uri
            else -> ""
        }
        val playing = now?.isPlaying ?: wasPlaying
        val pos = (now?.positionMs ?: 0L).coerceAtLeast(0L)
        val dur = (now?.durationMs ?: 0L).coerceAtLeast(0L)
        val artUrl = when {
            now != null && now.albumArtUrl.isNotBlank() -> now.albumArtUrl
            cur != null && cur.albumArtUrl.isNotBlank() -> cur.albumArtUrl
            else -> ""
        }
        val artPair = runCatching {
            SpotifyMediaNotif.resolveArt(
                this,
                now ?: SpotifyNowPlaying(
                    title = title,
                    artist = artist,
                    trackUri = uri,
                    albumArtUrl = artUrl,
                    isPlaying = playing,
                ),
                kickNetwork = false,
            )
        }.getOrNull()
        val sessionBmp = SpotifyMediaNotif.bestSessionArt(artPair)
        // Bitmap size in sig so we re-push when cover finishes decoding (not just URL).
        val artPresence = when {
            sessionBmp != null -> "bmp${sessionBmp.width}x${sessionBmp.height}"
            artUrl.isNotBlank() -> "url"
            else -> "noart"
        }
        // Bucket position so we re-assert PLAYING every ~2s without thrashing every poll.
        val sig = "$uri|$playing|${pos / 2_000L}|$title|$artist|$dur|$artPresence"
        if (!force && sig == lastMediaSessionSig && session.isActive) return
        lastMediaSessionSig = sig
        try {
            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.ifBlank { "Live DJ" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title.ifBlank { "Live DJ" })
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, uri)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, uri)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            if (artUrl.isNotBlank()) {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUrl)
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUrl)
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUrl)
            }
            // Large cover for lockscreen background (not just 128px shade icon).
            sessionBmp?.let {
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            session.setMetadata(metaBuilder.build())
            val actions =
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            val state = if (playing) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            val pb = PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    pos,
                    if (playing) 1.0f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
            session.setPlaybackState(pb)
            if (!session.isActive) {
                session.isActive = true
                Log.i(TAG, "mediaSession active (BT transport)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncMediaSession: ${e.message}")
        }
    }

    private fun releaseMediaSession() {
        runCatching {
            mediaSession?.apply {
                isActive = false
                setCallback(null)
                release()
            }
        }
        mediaSession = null
        LiveDjMediaSessionHolder.publish(null)
        lastMediaSessionSig = ""
        mediaSessionBusy.set(false)
        Log.i(TAG, "mediaSession released")
    }

    private fun buildDjForegroundNotification(text: String): Notification {
        // Controller owns the MediaStyle card — quiet keep-alive only.
        if (SpotifyControllerStore(this).enabled) {
            ensureMediaSession()
            syncMediaSession(force = false)
            return SpotifyMediaNotif.buildHidden(
                this,
                title = "Live DJ",
                status = when {
                    wasPlaying || current != null ->
                        current?.name?.takeIf { it.isNotBlank() }?.let { "On air · $it" }
                            ?: "On air"
                    else -> text.take(48).ifBlank { "Standby" }
                },
            )
        }

        // Prefer Live DJ + session merge so we never stick on the previous cut.
        val now = runCatching { nowPlayingForNotification(this) }.getOrNull()
            ?: SpotifyNowPlaying()
        val cur = current
        val merged = when {
            cur == null -> now
            // Session blank / same cut — fill gaps from service current.
            now.trackUri.isBlank() || now.trackUri == cur.uri -> now.copy(
                title = when {
                    now.title.isNotBlank() && now.title != "Unknown track" -> now.title
                    else -> cur.name.ifBlank { now.title }
                },
                artist = now.artist.ifBlank { cur.artists },
                albumArtUrl = now.albumArtUrl.ifBlank { cur.albumArtUrl },
                artistArtUrl = now.artistArtUrl.ifBlank { cur.artistArtUrl },
                trackUri = now.trackUri.ifBlank { cur.uri },
                // Trust wasPlaying/current during session lag so we don't flip to Standby.
                isPlaying = now.isPlaying || wasPlaying,
                hasSession = now.hasSession || cur.uri.isNotBlank(),
            )
            // Session still on previous cut after a handoff — service current wins.
            else -> now.copy(
                title = cur.name.ifBlank { now.title },
                artist = cur.artists.ifBlank { now.artist },
                albumArtUrl = cur.albumArtUrl.ifBlank { now.albumArtUrl },
                artistArtUrl = cur.artistArtUrl.ifBlank { now.artistArtUrl },
                trackUri = cur.uri.ifBlank { now.trackUri },
                isPlaying = true,
                hasSession = true,
            )
        }
        val onAir = merged.isPlaying || (cur != null && wasPlaying) ||
            SpotifyDjBus.state.value.messages.any {
                it.role == DjChatRole.Track && it.isNowPlaying && it.isPlaying
            }
        // BT/headset + lockscreen carousel — attach token when we own the card.
        ensureMediaSession()
        syncMediaSession(force = false)
        val q = synchronized(queue) { queue.toList() }
        val display = if (onAir) {
            merged.copy(
                isPlaying = true,
                title = merged.title.ifBlank { cur?.name.orEmpty().ifBlank { "Live DJ" } },
                artist = merged.artist.ifBlank {
                    cur?.artists.orEmpty().ifBlank { "On air" }
                },
            )
        } else {
            SpotifyNowPlaying(
                title = merged.title.ifBlank { "Live DJ" },
                artist = merged.artist.ifBlank { "Standby · prev / play / next" },
                albumArtUrl = merged.albumArtUrl,
                trackUri = merged.trackUri,
                isPlaying = false,
                hasSession = true,
                positionMs = merged.positionMs,
                durationMs = merged.durationMs,
                appLabel = "Live DJ",
            )
        }
        return SpotifyMediaNotif.buildPlaying(
            context = this,
            now = display,
            queue = q,
            subText = "Live DJ",
            sessionToken = LiveDjMediaSessionHolder.token(),
        )
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
        /** Prepend mixed same-artist + similar cuts seeded from a chat / now-playing track. */
        const val ACTION_DJ_MORE_LIKE_THIS = "io.grokify.os.SPOTIFY_DJ_MORE_LIKE_THIS"
        /** Apply multi-select dislike reasons so the cut (or artist) stays out of UP NEXT. */
        const val ACTION_DJ_DISLIKE = "io.grokify.os.SPOTIFY_DJ_DISLIKE"
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
        const val EXTRA_ARTIST_IDS = "artist_ids"
        const val EXTRA_DISLIKE_REASONS = "dislike_reasons"
        const val EXTRA_SKIP_IF_PLAYING = "skip_if_playing"
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
            Intent.ACTION_MEDIA_BUTTON -> {
                // Cold-start: MediaSession media-button receiver when process was dead.
                if (!SpotifyDjStore(context).enabled) return
                val ke = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return
                if (ke.action != KeyEvent.ACTION_DOWN || ke.repeatCount != 0) return
                val action = when (ke.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    -> SpotifyLiveDjService.ACTION_DJ_SKIP
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    -> SpotifyLiveDjService.ACTION_DJ_PREVIOUS
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> SpotifyLiveDjService.ACTION_DJ_PAUSE_TOGGLE
                    else -> return
                }
                Log.i(TAG, "MEDIA_BUTTON cold-start → $action key=${ke.keyCode}")
                val i = Intent(context, SpotifyLiveDjService::class.java).setAction(action)
                try {
                    ContextCompat.startForegroundService(context.applicationContext, i)
                } catch (e: Exception) {
                    Log.w(TAG, "MEDIA_BUTTON start service: ${e.message}")
                }
            }
        }
    }
}
