package io.grokify.os.apps

/**
 * Pure wake-phrase detection for "Okay Grok" style hotwords.
 * Used by [GrokAssistantWakeService] and unit tests.
 *
 * Speech-to-text often inserts punctuation ("Okay, Grok!", "OK. Grok?") —
 * [normalize] maps punctuation to spaces so phrase matching still hits.
 *
 * STT also frequently mis-hears “Grok” as a near-homophone (brock, rock, crock…).
 * [GROK_VARIANTS] covers those so wake still fires without training the recognizer.
 */
object GrokAssistantWake {
    data class Match(
        /** Phrase that fired (normalized). */
        val phrase: String,
        /** Text after the wake phrase (trimmed); may be empty → listen for command next. */
        val remainder: String,
        /** Original recognized text. */
        val raw: String,
    )

    /** Attention tokens before the name. Primary first. */
    val WAKE_PREFIXES: List<String> = listOf(
        "okay",
        "ok",
        "hey",
        "hi",
        "yo",
        "hello",
    )

    /**
     * “Grok” plus phonetic STT near-misses.
     * Keep list focused on *grok*-like sounds; avoid bare common words without a prefix.
     */
    val GROK_VARIANTS: List<String> = listOf(
        // Correct / close spellings
        "grok",
        "grock",
        "groc",
        "groke",
        "grokk",
        "grog",
        "grak",
        "grawk",
        "gawk",
        // User-reported / common STT confusions for “Grok”
        "brock",
        "brok",
        "rock",
        "crock",
        "croc",
        "croak",
        "flock",
        "jock",
        "truck",
        "quack",
        "prock",
        "frock",
    )

    /**
     * Default phrases = every prefix × every Grok variant.
     * Order = preference when overlapping (earlier start wins; longer phrase on ties).
     * Primary spelling “okay grok” is first so it wins equal-start ties against shorter ok forms.
     */
    val DEFAULT_PHRASES: List<String> = buildList {
        for (prefix in WAKE_PREFIXES) {
            for (name in GROK_VARIANTS) {
                add("$prefix $name")
            }
        }
    }

    /** Primary phrase shown in UI / notifications. */
    const val PRIMARY_PHRASE_DISPLAY = "Okay Grok"

    /**
     * Normalize for matching: lowercase, collapse whitespace, strip most punctuation
     * (including commas/periods STT inserts between words), keep apostrophes inside words.
     */
    fun normalize(text: String): String {
        val lower = text.lowercase().trim()
        val cleaned = buildString(lower.length) {
            for (ch in lower) {
                when {
                    ch.isLetterOrDigit() || ch == '\'' -> append(ch)
                    ch.isWhitespace() -> append(' ')
                    // punctuation / symbols → space so "Okay, Grok!" → "okay  grok "
                    else -> append(' ')
                }
            }
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * If [text] contains a wake phrase, return match with remainder after the first hit.
     * Prefers earlier start index; longer phrases win ties.
     *
     * Also tolerates optional filler words between the two tokens for the primary
     * patterns (e.g. rare STT glitches) via [indexOfPhrase].
     */
    fun match(text: String, phrases: List<String> = DEFAULT_PHRASES): Match? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        var bestStart = Int.MAX_VALUE
        var bestPhrase: String? = null
        var bestEnd = -1
        for (p in phrases) {
            val pn = normalize(p)
            if (pn.isEmpty()) continue
            val idx = indexOfPhrase(norm, pn) ?: continue
            val end = idx + pn.length
            if (idx < bestStart || (idx == bestStart && pn.length > (bestPhrase?.length ?: 0))) {
                bestStart = idx
                bestPhrase = pn
                bestEnd = end
            }
        }
        if (bestPhrase == null) return null
        val rem = norm.substring(bestEnd).trim()
            .trimStart(',', '.', '!', '?', ':', ';', '-', '—', '–')
            .trim()
        return Match(phrase = bestPhrase, remainder = rem, raw = text)
    }

    /** True if text is (only) a wake phrase or wake + tiny filler. */
    fun isWakeOnly(match: Match, minCommandChars: Int = 2): Boolean =
        match.remainder.length < minCommandChars

    private fun indexOfPhrase(haystack: String, phrase: String): Int? {
        // Word-boundary-ish: phrase at start or after space; end at end or before space.
        var from = 0
        while (from <= haystack.length - phrase.length) {
            val idx = haystack.indexOf(phrase, from)
            if (idx < 0) return null
            val beforeOk = idx == 0 || haystack[idx - 1] == ' '
            val afterIdx = idx + phrase.length
            val afterOk = afterIdx == haystack.length || haystack[afterIdx] == ' '
            if (beforeOk && afterOk) return idx
            from = idx + 1
        }
        return null
    }
}

/**
 * Shared mic arbitration so wake loop, overlay STT, and Realtime Voice never fight.
 *
 * Priority: [Owner.Voice] / [Owner.Overlay] preempt [Owner.Wake];
 * Voice and Overlay preempt each other (last acquirer wins).
 */
object GrokAssistantMic {
    enum class Owner { None, Wake, Overlay, Voice }

    @Volatile
    private var owner: Owner = Owner.None

    /** Epoch ms — wake should stay quiet until this (e.g. after TTS). */
    @Volatile
    var quietUntilMs: Long = 0L
        private set

    @Synchronized
    fun tryAcquire(who: Owner): Boolean {
        if (who == Owner.None) return false
        if (owner == Owner.None || owner == who) {
            owner = who
            return true
        }
        // Overlay / Voice can preempt Wake; also each other (exclusive mic path).
        if (who == Owner.Overlay || who == Owner.Voice) {
            owner = who
            return true
        }
        return false
    }

    @Synchronized
    fun release(who: Owner) {
        if (owner == who) owner = Owner.None
    }

    @Synchronized
    fun current(): Owner = owner

    fun isQuietNow(now: Long = System.currentTimeMillis()): Boolean = now < quietUntilMs

    fun quietFor(ms: Long) {
        val until = System.currentTimeMillis() + ms.coerceAtLeast(0L)
        if (until > quietUntilMs) quietUntilMs = until
    }
}
