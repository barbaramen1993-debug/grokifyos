package io.grokify.os.apps.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPromptsTest {
    @Test
    fun default_prompt_is_non_blank_warm_friend() {
        val p = CompanionPrompts.DEFAULT_SYSTEM
        assertTrue(p.length > 40)
        assertTrue(p.contains("Companion", ignoreCase = true) || p.contains("friend", ignoreCase = true))
    }

    @Test
    fun assemble_uses_custom_when_non_blank() {
        val custom = "You are Nova, a calm guide."
        assertEquals(custom, CompanionPrompts.assembleSystem(custom))
    }

    @Test
    fun assemble_falls_back_to_default() {
        assertEquals(CompanionPrompts.DEFAULT_SYSTEM, CompanionPrompts.assembleSystem("  "))
        assertEquals(CompanionPrompts.DEFAULT_SYSTEM, CompanionPrompts.assembleSystem(null))
    }

    @Test
    fun history_cap_keeps_last_n() {
        val msgs = (1..50).map {
            CompanionMessage(
                id = "m$it",
                role = if (it % 2 == 0) "assistant" else "user",
                text = "t$it",
                ts = it.toLong(),
                source = "text",
            )
        }
        val capped = CompanionPrompts.capHistory(msgs, 40)
        assertEquals(40, capped.size)
        assertEquals("m11", capped.first().id)
        assertEquals("m50", capped.last().id)
    }

    @Test
    fun context_window_prefers_recent_user_assistant() {
        val msgs = listOf(
            CompanionMessage("1", "system", "sys", 1, "text"),
            CompanionMessage("2", "user", "hi", 2, "text"),
            CompanionMessage("3", "assistant", "hello", 3, "voice"),
            CompanionMessage("4", "error", "x", 4, "text"),
            CompanionMessage("5", "user", "bye", 5, "text"),
        )
        val win = CompanionPrompts.contextWindow(msgs, maxMessages = 4)
        assertTrue(win.none { it.role == "system" || it.role == "error" })
        assertEquals("bye", win.last().text)
    }

    @Test
    fun encode_decode_round_trip() {
        val list = listOf(
            CompanionMessage("a", "user", "hello", 1L, "text"),
            CompanionMessage("b", "assistant", "hi", 2L, "voice"),
        )
        val json = CompanionPrompts.encodeHistory(list)
        val back = CompanionPrompts.decodeHistory(json)
        assertEquals(2, back.size)
        assertEquals("hello", back[0].text)
        assertEquals("voice", back[1].source)
    }
}
