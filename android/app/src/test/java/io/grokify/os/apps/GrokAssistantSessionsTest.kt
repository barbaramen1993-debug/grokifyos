package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun shouldSkipDuplicate_allowsCommitAfterPartialThenFullDifferentText() {
        // Incomplete live caption flushed on session end, then full reply later.
        val partial = AssistantChatMessage("1", "assistant", "Hello, I", ts = 1_000L)
        assertTrue(
            !AssistantTranscript.shouldSkipDuplicate(
                partial,
                "assistant",
                "Hello, I can help with that.",
                nowMs = 1_500L,
            ),
        )
    }

    @Test
    fun voiceVad_allowsLongerPausesThanDefault() {
        // Docs default silence is often ~700–900ms; we need multi-second pauses.
        assertTrue(GrokAssistantVoiceClient.VAD_SILENCE_MS >= 3_000)
        assertTrue(GrokAssistantVoiceClient.VAD_SILENCE_MS <= 10_000)
        assertTrue(GrokAssistantVoiceClient.VAD_THRESHOLD in 0.4..0.85)
        assertTrue(GrokAssistantVoiceClient.VAD_PREFIX_PADDING_MS >= 500)
        assertTrue(GrokAssistantVoiceClient.SEND_FRAME_MS == 20)
    }

    @Test
    fun shouldMergeUserUtterance_consecutiveUserWithinWindow() {
        val last = AssistantChatMessage("1", "user", "hey can you", ts = 1_000L)
        assertTrue(
            AssistantTranscript.shouldMergeUserUtterance(
                last,
                "user",
                "look up the weather",
                nowMs = 3_000L,
            ),
        )
        assertFalse(
            AssistantTranscript.shouldMergeUserUtterance(
                last,
                "user",
                "look up the weather",
                nowMs = 1_000L + AssistantTranscript.USER_MERGE_WINDOW_MS + 1,
            ),
        )
        assertFalse(
            AssistantTranscript.shouldMergeUserUtterance(
                last,
                "assistant",
                "sure",
                nowMs = 2_000L,
            ),
        )
        val asstLast = AssistantChatMessage("2", "assistant", "ok", ts = 2_000L)
        assertFalse(
            AssistantTranscript.shouldMergeUserUtterance(
                asstLast,
                "user",
                "next turn",
                nowMs = 2_500L,
            ),
        )
        // Exact twin is not a "merge" (dedupe path handles it).
        assertFalse(
            AssistantTranscript.shouldMergeUserUtterance(
                last,
                "user",
                "hey can you",
                nowMs = 2_000L,
            ),
        )
    }

    @Test
    fun mergeUserUtteranceText_joinsAndSupersedes() {
        assertEquals(
            "hey can you look up the weather",
            AssistantTranscript.mergeUserUtteranceText(
                "hey can you",
                "look up the weather",
            ),
        )
        assertEquals(
            "hello how are you",
            AssistantTranscript.mergeUserUtteranceText(
                "hello how",
                "hello how are you",
            ),
        )
        assertEquals(
            "hello world today",
            AssistantTranscript.mergeUserUtteranceText(
                "hello world",
                "world today",
            ),
        )
        assertEquals(
            "same line",
            AssistantTranscript.mergeUserUtteranceText("same line", "same line"),
        )
    }

    @Test
    fun resamplePcm16_48kTo24k_halvesSampleCount() {
        // 100 samples @ 48k → ~50 @ 24k
        val n = 100
        val input = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (i * 300).toShort().toInt()
            input[i * 2] = (v and 0xff).toByte()
            input[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        val out = GrokAssistantVoiceClient.resamplePcm16Mono(input, input.size, 48_000, 24_000)
        assertEquals(50 * 2, out.size)
    }

    @Test
    fun resamplePcm16_sameRate_preservesLength() {
        val input = ByteArray(640) { it.toByte() }
        val out = GrokAssistantVoiceClient.resamplePcm16Mono(input, input.size, 24_000, 24_000)
        assertEquals(640, out.size)
    }

    @Test
    fun softGain_boostsQuietPcm() {
        // Peak ~1000 → should boost toward ~12000
        val input = ByteArray(200)
        for (i in 0 until 100) {
            val v = if (i % 2 == 0) 800 else -800
            input[i * 2] = (v and 0xff).toByte()
            input[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        val out = GrokAssistantVoiceClient.softGainPcm16(input)
        var peak = 0
        var i = 0
        while (i + 1 < out.size) {
            val lo = out[i].toInt() and 0xff
            val hi = out[i + 1].toInt()
            val s = kotlin.math.abs(((hi shl 8) or lo).toShort().toInt())
            if (s > peak) peak = s
            i += 2
        }
        assertTrue(peak > 2000)
    }

    @Test
    fun defaultKeyterms_coverProductNames() {
        val terms = GrokAssistantVoiceClient.defaultKeyterms()
        val list = (0 until terms.length()).map { terms.getString(it) }
        assertTrue(list.any { it.contains("Grok", ignoreCase = true) })
        assertTrue(list.any { it.contains("Grokify", ignoreCase = true) })
    }

    @Test
    fun benignCancelError_detectsXaiNoActiveResponse() {
        val raw =
            """{"message":"Cancellation failed: no active response found","type":"invalid_request_error"}"""
        assertTrue(GrokAssistantVoiceClient.isBenignRealtimeCancelError(raw))
        assertTrue(
            GrokAssistantVoiceClient.isBenignRealtimeCancelError(
                "Cancellation failed: no active response found",
            ),
        )
        assertTrue(
            GrokAssistantVoiceClient.isBenignRealtimeCancelError(
                "response_cancel_not_active",
            ),
        )
        assertFalse(GrokAssistantVoiceClient.isBenignRealtimeCancelError("rate limit exceeded"))
        assertFalse(GrokAssistantVoiceClient.isBenignRealtimeCancelError("connection failed"))
    }
}
