package io.grokify.os.apps

/**
 * Pure wake-phrase detection for "Hey Grok" style hotwords.
 * Used by [GrokAssistantWakeService] and unit tests.
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

    /** Default phrases users are likely to say. Order = preference when overlapping. */
    val DEFAULT_PHRASES: List<String> = listOf(
        "hey grok",
        "okay grok",
        "ok grok",
        "hi grok",
        "yo grok",
        "hello grok",
    )

    /**
     * Normalize for matching: lowercase, collapse whitespace, strip most punctuation
     * but keep apostrophes inside words.
     */
    fun normalize(text: String): String {
        val lower = text.lowercase().trim()
        val cleaned = buildString(lower.length) {
            for (ch in lower) {
                when {
                    ch.isLetterOrDigit() || ch == '\'' -> append(ch)
                    ch.isWhitespace() -> append(' ')
                    else -> append(' ') // punctuation → space
                }
            }
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * If [text] contains a wake phrase, return match with remainder after the first hit.
     * Prefers earlier start index; longer phrases win ties.
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
        val rem = norm.substring(bestEnd).trim().trimStart(',', '.', '!', '?', ':', ';', '-')
            .trim()
        return Match(phrase = bestPhrase, remainder = rem, raw = text)
    }

    /** True if text is (only) a wake phrase or wake + filler like "hey grok please". */
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
 * Shared mic arbitration so wake loop and overlay hold-to-talk never fight.
 */
object GrokAssistantMic {
    enum class Owner { None, Wake, Overlay }

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
        // Overlay can preempt wake; wake cannot preempt overlay.
        if (who == Owner.Overlay) {
            owner = Owner.Overlay
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
