package io.grokify.os.apps

import org.json.JSONArray
import org.json.JSONObject

/**
 * Editable prompt templates for Live DJ.
 *
 * Categories:
 * - [DjPromptKind.Research] — research angle briefs (multi-enable → random pack each talk)
 * - [DjPromptKind.Behavior] — on-mic personality (one active)
 * - [DjPromptKind.BanterSystem] — full on-air banter system rules (one body)
 * - [DjPromptKind.ResearchSystem] — research agent envelope (one body)
 * - [DjPromptKind.ChatSystem] — booth chat system rules (one body)
 * - [DjPromptKind.QueueRankSystem] — AI rank next-tracks music director (one body)
 * - [DjPromptKind.QueueRankUser] — AI rank user/request message shell (one body)
 *
 * Placeholders replaced at runtime (leave them in the body):
 * - Research angle: `{{CITY}}`
 * - Research system: `{{ANGLE_BRIEFS}}`
 * - Banter system: `{{WORD_CAP}}`, `{{BEHAVIOR_STYLE}}`, `{{UNHINGED_EXTRA}}`, `{{NAME_BLOCK}}`
 * - Chat system: `{{BEHAVIOR_STYLE}}`
 * - Queue rank system: `{{N}}`, `{{GENRE_BIAS}}`
 * - Queue rank user: `{{CURRENT}}`, `{{BEHAVIOR}}`, `{{GENRE_BOARD_LINE}}`,
 *   `{{CITY_LINE}}`, `{{VIBE_LINE}}`, `{{CANDIDATES}}`, `{{N}}`
 */
enum class DjPromptKind {
    Research,
    Behavior,
    BanterSystem,
    ResearchSystem,
    ChatSystem,
    QueueRankSystem,
    QueueRankUser,
    ;

    val storageKey: String
        get() = when (this) {
            Research -> "research"
            Behavior -> "behavior"
            BanterSystem -> "banter_system"
            ResearchSystem -> "research_system"
            ChatSystem -> "chat_system"
            QueueRankSystem -> "queue_rank_system"
            QueueRankUser -> "queue_rank_user"
        }

    val sectionLabel: String
        get() = when (this) {
            Research -> "Research angles"
            Behavior -> "Behaviors"
            BanterSystem -> "Banter system"
            ResearchSystem -> "Research system"
            ChatSystem -> "Chat system"
            QueueRankSystem -> "Queue rank system"
            QueueRankUser -> "Queue rank request"
        }

    val sectionBlurb: String
        get() = when (this) {
            Research ->
                "Enabled angles enter the random research pack each talk (1–3 picked). " +
                    "Edit briefs or add your own. Use {{CITY}} for the listener metro."
            Behavior ->
                "Pick one personality for on-mic delivery (after research). " +
                    "Edit body or add a custom vibe."
            BanterSystem ->
                "Core rules for spoken handoff lines. Placeholders: " +
                    "{{WORD_CAP}} {{BEHAVIOR_STYLE}} {{UNHINGED_EXTRA}} {{NAME_BLOCK}}"
            ResearchSystem ->
                "Envelope for the music researcher agent. " +
                    "Must keep JSON-only reply shape. Placeholder: {{ANGLE_BRIEFS}}"
            ChatSystem ->
                "Booth chat steering rules (JSON actions). Placeholder: {{BEHAVIOR_STYLE}}"
            QueueRankSystem ->
                "System rules when AI rank picks next tracks from the candidate pool. " +
                    "Must keep JSON-only reply shape. Placeholders: {{N}} {{GENRE_BIAS}}"
            QueueRankUser ->
                "Request message sent with candidates (AI rank on). Placeholders: " +
                    "{{CURRENT}} {{BEHAVIOR}} {{GENRE_BOARD_LINE}} {{CITY_LINE}} " +
                    "{{VIBE_LINE}} {{CANDIDATES}} {{N}}"
        }

    companion object {
        fun fromStorage(raw: String?): DjPromptKind? =
            when (raw?.lowercase()?.trim()) {
                "research" -> Research
                "behavior" -> Behavior
                "banter_system", "banter" -> BanterSystem
                "research_system" -> ResearchSystem
                "chat_system", "chat" -> ChatSystem
                "queue_rank_system", "queue_rank", "ai_rank", "ai_rank_system" -> QueueRankSystem
                "queue_rank_user", "ai_rank_user" -> QueueRankUser
                else -> null
            }
    }
}

data class DjPromptTemplate(
    val id: String,
    val kind: DjPromptKind,
    val label: String,
    val blurb: String = "",
    /** Prompt body / personality block / angle brief. */
    val body: String,
    /** Research: include in random pool. Behavior: available to select. */
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    /**
     * Optional flags for runtime (e.g. "unhinged_taste" adds roast requirements).
     */
    val flags: List<String> = emptyList(),
) {
    fun hasFlag(flag: String): Boolean =
        flags.any { it.equals(flag, ignoreCase = true) }

    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.storageKey)
            .put("label", label)
            .put("blurb", blurb)
            .put("body", body)
            .put("enabled", enabled)
            .put("builtIn", builtIn)
            .put(
                "flags",
                JSONArray().also { arr -> flags.forEach { arr.put(it) } },
            )

    companion object {
        fun fromJson(o: JSONObject?): DjPromptTemplate? {
            if (o == null) return null
            val id = o.optString("id", "").trim()
            val kind = DjPromptKind.fromStorage(o.optString("kind", "")) ?: return null
            if (id.isBlank()) return null
            val flagsArr = o.optJSONArray("flags")
            val flags = buildList {
                if (flagsArr != null) {
                    for (i in 0 until flagsArr.length()) {
                        val f = flagsArr.optString(i, "").trim()
                        if (f.isNotBlank()) add(f)
                    }
                }
            }
            return DjPromptTemplate(
                id = id,
                kind = kind,
                label = o.optString("label", id).ifBlank { id },
                blurb = o.optString("blurb", ""),
                body = o.optString("body", ""),
                enabled = o.optBoolean("enabled", true),
                builtIn = o.optBoolean("builtIn", false),
                flags = flags,
            )
        }
    }
}

/** Built-in defaults — also used for “Reset to default”. */
object DjPromptDefaults {
    const val FLAG_UNHINGED_TASTE = "unhinged_taste"

    const val ID_BANTER_SYSTEM = "banter_system_core"
    const val ID_RESEARCH_SYSTEM = "research_system_core"
    const val ID_CHAT_SYSTEM = "chat_system_core"
    const val ID_QUEUE_RANK_SYSTEM = "queue_rank_system_core"
    const val ID_QUEUE_RANK_USER = "queue_rank_user_core"

    fun all(): List<DjPromptTemplate> =
        researchAngles() + behaviors() + listOf(
            banterSystem(),
            researchSystem(),
            chatSystem(),
            queueRankSystem(),
            queueRankUser(),
        )

    fun researchAngles(): List<DjPromptTemplate> = listOf(
        DjPromptTemplate(
            id = "lyrics_themes",
            kind = DjPromptKind.Research,
            label = "Lyrics & meaning",
            blurb = "Themes / story of current + next songs",
            body =
                "LYRICS & MEANING: Look up what the CURRENT and NEXT songs are about — themes, " +
                    "story, vibe of the lyrics. Paraphrase only (≤28 words each). Never paste long " +
                    "lyric blocks or copyrighted lines.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "album_song_facts",
            kind = DjPromptKind.Research,
            label = "Album / song facts",
            blurb = "Release year, writers, samples, charts",
            body =
                "ALBUM / SONG FACTS: Album name, release year, writers, samples, chart peaks, " +
                    "awards, collabs, notable production notes. Prefer verified + recent when news.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "artist_facts",
            kind = DjPromptKind.Research,
            label = "Artist facts",
            blurb = "Career color, milestones, trivia",
            body =
                "ARTIST FACTS: Career color, recent milestones, side projects, beefs (tasteful), " +
                    "band lineup notes, fun verified trivia — not Wikipedia dump.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "shows_tours",
            kind = DjPromptKind.Research,
            label = "Shows & tours",
            blurb = "Concerts / tour legs; uses {{CITY}} when set",
            body =
                "SHOWS & TOURS: Real upcoming concerts / tour legs for these artists " +
                    "(city, date, venue when known). " +
                    "If listener city is set ({{CITY}}), check near that metro AND flag major " +
                    "national/international dates if more notable. Also note if familiar artists " +
                    "are coming to {{CITY}}. If city blank, national/global dates are fine.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "recent_x_social",
            kind = DjPromptKind.Research,
            label = "Recent X / social",
            blurb = "Buzz from the last ~2 weeks",
            body =
                "RECENT X / SOCIAL: Search recent posts or headlines about these artists/songs " +
                    "on X (Twitter) or breaking music social buzz in the last ~2 weeks. " +
                    "Short paraphrase only — no full post quotes, no invented viral moments.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "radio_host_color",
            kind = DjPromptKind.Research,
            label = "Radio host color",
            blurb = "Classic on-air spice / did-you-know",
            body =
                "RADIO HOST COLOR: Classic on-air spice — origin stories, sample credits, " +
                    "genre context, \"did you know\" moments, cultural placement, funny true " +
                    "anecdotes that a good radio host would drop. Verified only.",
            builtIn = true,
        ),
    )

    fun behaviors(): List<DjPromptTemplate> = listOf(
        DjPromptTemplate(
            id = "default",
            kind = DjPromptKind.Behavior,
            label = "Default",
            blurb = "Warm radio host — natural handoffs",
            body =
                "PERSONALITY: Default radio host — warm, curious, natural. " +
                    "Sound like a good friend on the air, not a hype reel.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "hype",
            kind = DjPromptKind.Behavior,
            label = "Hype",
            blurb = "High energy hypeman — big energy, still clean",
            body =
                "PERSONALITY: HYPE MODE — high energy, hypeman energy, punchy short sentences, " +
                    "celebrate the drop/cut. Keep it clean enough for a car ride. Use words like " +
                    "\"let's go\", \"this one hits\", \"turn it up\" sparingly (not every line).",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "hype_unhinged",
            kind = DjPromptKind.Behavior,
            label = "Hype Unhinged",
            blurb = "Savage party chaos — loud, explicit, roast-heavy hype",
            body =
                "PERSONALITY: HYPE UNHINGED — absolute party chaos with TEETH. Loud, explicit, " +
                    "over-the-top hype PLUS hard roasts: insult the listener's music taste, the last " +
                    "track, and the next one with love-hate energy (\"this ass-kicker\", \"your trash " +
                    "taste is somehow elite\", \"shut up and turn it up\"). Use RESEARCH as roast fuel " +
                    "when present. Address by LISTENER NAME when set — NEVER treat city as a name. " +
                    "Swear freely (fuck/shit/damn/ass ok). Be cocky, crude, a little mean — still " +
                    "land the handoff clearly. NO hate speech, slurs targeting protected groups, or " +
                    "real threats. Roast songs/vibe/listener playfully savage, not bigoted.",
            builtIn = true,
            flags = listOf(FLAG_UNHINGED_TASTE),
        ),
        DjPromptTemplate(
            id = "comedy",
            kind = DjPromptKind.Behavior,
            label = "Comedy",
            blurb = "Witty comic — jokes, bits, playful roasts",
            body =
                "PERSONALITY: COMEDY MODE — witty stand-up radio. Light jokes, wordplay, gentle " +
                    "roasts of the vibe (not cruel). Punchlines welcome. Still hand off the song " +
                    "cleanly. Prefer clever over cringe.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "soothing",
            kind = DjPromptKind.Behavior,
            label = "Soothing",
            blurb = "Soft late-night — calm, intimate, gentle",
            body =
                "PERSONALITY: SOOTHING MODE — soft late-night host. Slow, intimate, gentle, " +
                    "comforting. Lower intensity language. No yelling energy. Think quiet drive " +
                    "or winding down.",
            builtIn = true,
        ),
        DjPromptTemplate(
            id = "unhinged",
            kind = DjPromptKind.Behavior,
            label = "Unhinged",
            blurb = "Mean goblin energy — crude, insulting, unfiltered",
            body =
                "PERSONALITY: UNHINGED — chaotic mean goblin DJ. Weird tangents, absurdist takes, " +
                    "AND explicit insults: dunk on the track, the artist, the album, AND the " +
                    "listener's music taste (\"your trash ears\", \"this is why friends skip your " +
                    "aux\"). Only say \"you queued this\" when SOURCE is LISTENER; for DJ picks " +
                    "roast the cut without claiming they requested it. Use RESEARCH as roast fuel " +
                    "when present. Address them by LISTENER NAME when set — NEVER treat city/location " +
                    "as a name. Crude/sexual-adjacent jokes ok if brief. Swear freely. " +
                    "Sound unfiltered and a little unhinged, not corporate-safe. Still hand off " +
                    "so the listener knows what's next. Don't invent facts; weirdness + meanness " +
                    "is in delivery. NO hate speech / protected-class slurs / real threats.",
            builtIn = true,
            flags = listOf(FLAG_UNHINGED_TASTE),
        ),
    )

    fun banterSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_BANTER_SYSTEM,
        kind = DjPromptKind.BanterSystem,
        label = "On-air banter rules",
        blurb = "Spoken handoff system prompt",
        body =
            "You are a live radio AI DJ speaking aloud ON AIR. Write 1–3 short sentences " +
                "(max {{WORD_CAP}} words). Rules:\n" +
                "• {{BEHAVIOR_STYLE}}\n" +
                "{{UNHINGED_EXTRA}}" +
                "{{NAME_BLOCK}}\n" +
                "• QUEUE ATTRIBUTION (critical): Who put the NEXT cut in the set is given as SOURCE. " +
                "If SOURCE is LIVE DJ, YOU chose it — never say \"you queued\", \"you put this on\", " +
                "\"your request\", \"as you asked\", or \"from your queue\". " +
                "If SOURCE is LISTENER, they requested it — you may credit them once. " +
                "Liked/top/radio/genre reasons mean DJ pick, not a listener request.\n" +
                "• Prefer vibe intros over full titles: e.g. \"finishing up with some Morgan Wallen\" " +
                "or \"sliding into a little [short title]\" — not \"That was Song Name by Artist Name\".\n" +
                "• Song titles often include (feat. X) / (with X) / - feat. X. NEVER read parentheses " +
                "featuring credits. NEVER say the artist name twice because it appears in the title " +
                "and the artist field. Use the CLEAN title and PRIMARY artist only.\n" +
                "• RESEARCH pack varies each talk (lyrics, album/song facts, artist facts, shows/tours, " +
                "X/social, radio host color). Weave ONE vivid beat that matches what the pack has " +
                "— lyric theme, album+year, artist fact, show date (city only as location), " +
                "X/social buzz, host color, or a later-set tease — then hand off with " +
                "\"here's [clean title] by [primary artist]\" or similar.\n" +
                "• Lyric themes: comment on what the song is about in your own words; " +
                "do NOT recite long lyrics or copyrighted lines.\n" +
                "• If RESEARCH is empty, still do a solid handoff; do not invent tour dates, " +
                "lyrics meaning, X posts, or news.\n" +
                "• CRITICAL: Output ONLY words a listener would hear on the radio. " +
                "NEVER narrate process, research, tools, planning, or drafting. Forbidden phrases include: " +
                "\"Checking for…\", \"Looking up…\", \"Searching…\", \"Before writing…\", \"Let me…\", " +
                "\"I'll check…\", \"public tidbit\", \"writing the DJ line\", \"as I research\", " +
                "\"according to my research\", \"Research focus\". " +
                "Do not mention that you looked anything up.\n" +
                "• No markdown, no hashtags, no emoji, no quotation marks wrapping the whole line.\n" +
                "• Always put a space after periods, commas, question marks, and exclamation points " +
                "(e.g. \"…vibe. Here's…\" not \"…vibe.Here's…\").\n" +
                "• Reply with ONLY the on-air spoken line — nothing before or after it.",
        builtIn = true,
    )

    fun researchSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_RESEARCH_SYSTEM,
        kind = DjPromptKind.ResearchSystem,
        label = "Research agent rules",
        blurb = "Tool-backed fact pack for banter",
        body =
            "You are a music researcher for a live radio AI DJ. " +
                "You HAVE tools/web search — USE them for REAL information. " +
                "This turn focuses ONLY on these research angles (ignore others):\n" +
                "{{ANGLE_BRIEFS}}\n" +
                "Also: if SETLIST LOOKAHEAD is provided, you may add 0–2 short teases for later cuts.\n" +
                "Reply with JSON ONLY (no markdown fences):\n" +
                "{" +
                "\"current_lyrics_theme\":\"≤28 words or empty\"," +
                "\"next_lyrics_theme\":\"≤28 words or empty\"," +
                "\"album_facts\":[\"≤22 words\"]," +
                "\"facts\":[\"≤22 words\"]," +
                "\"shows\":[\"city/date/venue or tour if real\"]," +
                "\"x_social\":[\"≤22 words recent X/social buzz\"]," +
                "\"radio_color\":[\"≤22 words host-y verified color\"]," +
                "\"setlist_tease\":[\"≤20 words each for later cuts\"]" +
                "}\n" +
                "Rules: fill ONLY fields that match this turn's angles (others empty); " +
                "max 2 album_facts, 4 facts, 3 shows, 3 x_social, 3 radio_color, 2 setlist_tease; " +
                "prefer verifiable/recent; NEVER invent tour dates, chart numbers, lyric meaning, " +
                "or viral posts. No commentary outside JSON. " +
                "Listener NAME and CITY are different — city is a place, not a person.",
        builtIn = true,
    )

    fun chatSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_CHAT_SYSTEM,
        kind = DjPromptKind.ChatSystem,
        label = "Booth chat rules",
        blurb = "JSON steering for live radio chat",
        body =
            "You are the Live AI DJ booth chat (not general Grok chat). User is steering live Spotify radio. " +
                "{{BEHAVIOR_STYLE}} " +
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
                "Prefer enqueue_search for “play more X / queue some Y”. Keep reply under 50 words. " +
                "Write the reply field in the active behavior personality.",
        builtIn = true,
    )

    /**
     * System rules for AI rank (music director) — used only when “AI rank next tracks” is on.
     * Placeholders: {{N}} pick count, {{GENRE_BIAS}} genre lean sentence or ".".
     */
    fun queueRankSystem(): DjPromptTemplate = DjPromptTemplate(
        id = ID_QUEUE_RANK_SYSTEM,
        kind = DjPromptKind.QueueRankSystem,
        label = "Queue rank (AI pick) rules",
        blurb = "Music director system when AI ranks the pool",
        body =
            "You are a radio DJ music director (Spotify DJ style). Reply ONLY with valid JSON: " +
                "{\"picks\":[{\"uri\":\"spotify:track:...\",\"banter_note\":\"short why\"}],\"banter\":\"\"}. " +
                "Pick exactly {{N}} tracks from the CANDIDATES list only (use their uris). " +
                "Blend liked/top seeds with artist-radio variety{{GENRE_BIAS}} " +
                "Candidates already exclude recently played and already-heard tracks — never re-pick those. " +
                "Avoid stacking the same primary artist twice in a row. " +
                "Leave banter empty (spoken lines are generated separately). No markdown.",
        builtIn = true,
    )

    /**
     * User/request shell for AI rank. Data lines are filled via placeholders at fill time.
     */
    fun queueRankUser(): DjPromptTemplate = DjPromptTemplate(
        id = ID_QUEUE_RANK_USER,
        kind = DjPromptKind.QueueRankUser,
        label = "Queue rank request",
        blurb = "User message with current cut + candidates",
        body =
            "CURRENT: {{CURRENT}}\n" +
                "Behavior mode (queue energy, not spoken line): {{BEHAVIOR}}\n" +
                "{{GENRE_BOARD_LINE}}" +
                "{{CITY_LINE}}" +
                "{{VIBE_LINE}}" +
                "\n" +
                "CANDIDATES (not recently played):\n" +
                "{{CANDIDATES}}\n" +
                "\n" +
                "Pick {{N}} next tracks for a continuous live DJ set. Do not repeat recently heard songs.",
        builtIn = true,
    )

    fun defaultBody(id: String): String? =
        all().firstOrNull { it.id == id }?.body

    fun defaultFor(id: String): DjPromptTemplate? =
        all().firstOrNull { it.id == id }
}

/** Apply simple {{PLACEHOLDER}} replacements. Missing keys → empty string. */
fun applyPromptPlaceholders(body: String, vars: Map<String, String>): String {
    var out = body
    // Support both {{KEY}} and longer keys; replace known first then strip leftovers lightly.
    vars.forEach { (k, v) ->
        out = out.replace("{{$k}}", v)
    }
    return out
}

/**
 * Merge saved templates with built-in defaults:
 * - Keep user edits (body/label/blurb/enabled/flags) for existing ids
 * - Add any new built-ins the user doesn't have yet
 * - Keep custom (non-builtIn) templates
 * - Ensure system kinds always have exactly one template (re-seed if deleted)
 */
fun mergePromptTemplates(saved: List<DjPromptTemplate>): List<DjPromptTemplate> {
    val defaults = DjPromptDefaults.all()
    val byId = LinkedHashMap<String, DjPromptTemplate>()
    // Start with defaults
    defaults.forEach { byId[it.id] = it }
    // Overlay saved
    for (s in saved) {
        val base = byId[s.id]
        if (base != null && base.builtIn) {
            byId[s.id] = s.copy(builtIn = true, kind = base.kind)
        } else if (!s.builtIn) {
            byId[s.id] = s.copy(builtIn = false)
        } else {
            byId[s.id] = s
        }
    }
    // Ensure system singles exist
    listOf(
        DjPromptDefaults.banterSystem(),
        DjPromptDefaults.researchSystem(),
        DjPromptDefaults.chatSystem(),
        DjPromptDefaults.queueRankSystem(),
        DjPromptDefaults.queueRankUser(),
    ).forEach { sys ->
        if (byId.values.none { it.kind == sys.kind }) {
            byId[sys.id] = sys
        }
    }
    // Ensure at least one behavior + research
    if (byId.values.none { it.kind == DjPromptKind.Behavior }) {
        DjPromptDefaults.behaviors().forEach { byId[it.id] = it }
    }
    if (byId.values.none { it.kind == DjPromptKind.Research }) {
        DjPromptDefaults.researchAngles().forEach { byId[it.id] = it }
    }
    return byId.values.toList().sortedWith(
        compareBy<DjPromptTemplate> { it.kind.ordinal }
            .thenBy { if (it.builtIn) 0 else 1 }
            .thenBy { it.label.lowercase() },
    )
}

/**
 * Pick 1–3 research templates from the enabled pool (same weighting as before).
 * Falls back to all built-in research if none enabled.
 */
fun pickResearchTemplates(
    all: List<DjPromptTemplate>,
    rng: kotlin.random.Random = kotlin.random.Random.Default,
): List<DjPromptTemplate> {
    val pool = all.filter { it.kind == DjPromptKind.Research && it.enabled && it.body.isNotBlank() }
        .ifEmpty {
            all.filter { it.kind == DjPromptKind.Research && it.body.isNotBlank() }
        }
        .ifEmpty { DjPromptDefaults.researchAngles() }
    val count = when (rng.nextInt(10)) {
        in 0..5 -> 1
        in 6..8 -> 2
        else -> 3
    }.coerceIn(1, pool.size)
    return pool.shuffled(rng).take(count)
}

fun encodePromptTemplates(list: List<DjPromptTemplate>): String {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun decodePromptTemplates(raw: String?): List<DjPromptTemplate> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                DjPromptTemplate.fromJson(arr.optJSONObject(i))?.let { add(it) }
            }
        }
    }.getOrElse { emptyList() }
}
