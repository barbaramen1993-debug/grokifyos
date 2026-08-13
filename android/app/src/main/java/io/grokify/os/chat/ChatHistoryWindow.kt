package io.grokify.os.chat

/**
 * Caps the history payload sent to the bridge so a long chat cannot
 * blow Linux MAX_ARG_STRLEN (E2BIG) or flood the WebSocket.
 *
 * Thinking traces are dropped; each message and the whole window are size-capped.
 */
object ChatHistoryWindow {
    const val MAX_MESSAGES = 20
    const val MAX_CHARS = 80_000
    const val MAX_MESSAGE_CHARS = 8_000

    data class Turn(val role: String, val content: String)

    fun compactContent(raw: String): String {
        var s = THINKING_BLOCK.replace(raw, "").trim()
        s = MULTI_BLANK.replace(s, "\n\n")
        if (s.length > MAX_MESSAGE_CHARS) {
            s = "…" + s.takeLast(MAX_MESSAGE_CHARS)
        }
        return s
    }

    fun fit(turns: List<Turn>): List<Turn> {
        val cleaned = turns.mapNotNull { t ->
            val role = t.role.lowercase()
            if (role != "user" && role != "assistant" && role != "system") return@mapNotNull null
            val content = compactContent(t.content)
            if (content.isBlank()) null else Turn(role, content)
        }.takeLast(MAX_MESSAGES)

        if (cleaned.isEmpty()) return emptyList()
        var window = cleaned
        while (window.size > 1 && window.sumOf { it.content.length } > MAX_CHARS) {
            window = window.drop(1)
        }
        if (window.size == 1 && window[0].content.length > MAX_CHARS) {
            val only = window[0]
            window = listOf(only.copy(content = "…" + only.content.takeLast(MAX_CHARS)))
        }
        return window
    }

    private val THINKING_BLOCK = Regex("(?is)<thinking>.*?</thinking>")
    private val MULTI_BLANK = Regex("\n{3,}")
}
