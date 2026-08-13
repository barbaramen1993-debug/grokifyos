package io.grokify.os.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryWindowTest {
    @Test
    fun dropsThinkingAndCapsMessage() {
        val raw = "hi\n<thinking>\nsecret dump\n</thinking>\n\n\nactual"
        val out = ChatHistoryWindow.compactContent(raw)
        assertFalse(out.contains("secret dump"))
        assertTrue(out.contains("actual"))
    }

    @Test
    fun keepsRecentTurnsUnderBudget() {
        val turns = (1..40).map { i ->
            ChatHistoryWindow.Turn(
                role = if (i % 2 == 0) "assistant" else "user",
                content = "turn $i " + "x".repeat(6_000),
            )
        }
        val fitted = ChatHistoryWindow.fit(turns)
        assertTrue(fitted.size <= ChatHistoryWindow.MAX_MESSAGES)
        assertTrue(fitted.sumOf { it.content.length } <= ChatHistoryWindow.MAX_CHARS + 1)
        assertTrue(fitted.last().content.contains("turn 40"))
    }

    @Test
    fun skipsEmptyAfterThinkingStrip() {
        val fitted = ChatHistoryWindow.fit(
            listOf(
                ChatHistoryWindow.Turn("assistant", "<thinking>only thoughts</thinking>"),
                ChatHistoryWindow.Turn("user", "hello"),
            ),
        )
        assertEquals(1, fitted.size)
        assertEquals("user", fitted[0].role)
        assertEquals("hello", fitted[0].content)
    }
}
