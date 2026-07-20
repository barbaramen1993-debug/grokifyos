package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokAssistantSessionsTest {
    @Test
    fun titleFromFirstUser_truncates() {
        val msgs = listOf(
            AssistantChatMessage("1", "user", "A".repeat(60)),
            AssistantChatMessage("2", "assistant", "ok"),
        )
        val title = AssistantConversation.titleFromFirstUser(msgs)
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= 48)
    }

    @Test
    fun encodeDecode_roundTrip() {
        val conv = AssistantConversation(
            id = "abc",
            title = "Hello",
            updatedAt = 123L,
            messages = listOf(
                AssistantChatMessage("m1", "user", "hi", 1L),
                AssistantChatMessage("m2", "assistant", "hey", 2L),
            ),
        )
        val raw = AssistantTranscript.encodeSessions(listOf(conv))
        val back = AssistantTranscript.decodeSessions(raw)
        assertEquals(1, back.size)
        assertEquals("abc", back[0].id)
        assertEquals("Hello", back[0].title)
        assertEquals(2, back[0].messages.size)
        assertEquals("hi", back[0].messages[0].text)
    }

    @Test
    fun capSessions_keepsNewest() {
        val list = (1..50).map { i ->
            AssistantConversation(
                id = "id$i",
                title = "t$i",
                updatedAt = i.toLong(),
                messages = emptyList(),
            )
        }
        val capped = AssistantTranscript.capSessions(list)
        assertEquals(AssistantTranscript.MAX_SESSIONS, capped.size)
        assertTrue(capped.any { it.id == "id50" })
        assertTrue(capped.none { it.id == "id1" })
    }

    @Test
    fun shouldSkipDuplicate_collapsesSameUserWithinWindow() {
        val last = AssistantChatMessage("1", "user", "hello there", ts = 1_000L)
        assertTrue(
            AssistantTranscript.shouldSkipDuplicate(last, "user", "hello there", nowMs = 2_000L),
        )
        assertTrue(
            !AssistantTranscript.shouldSkipDuplicate(
                last,
                "user",
                "hello there",
                nowMs = 1_000L + AssistantTranscript.DUPLICATE_WINDOW_MS + 1,
            ),
        )
        assertTrue(
            !AssistantTranscript.shouldSkipDuplicate(last, "user", "different", nowMs = 1_500L),
        )
        assertTrue(
            !AssistantTranscript.shouldSkipDuplicate(last, "assistant", "hello there", nowMs = 1_500L),
        )
    }

    @Test
    fun shouldSkipDuplicate_legacyZeroTsStillCollapses() {
        val last = AssistantChatMessage("1", "user", "hey", ts = 0L)
        assertTrue(AssistantTranscript.shouldSkipDuplicate(last, "user", "hey", nowMs = 99_000L))
    }
}
