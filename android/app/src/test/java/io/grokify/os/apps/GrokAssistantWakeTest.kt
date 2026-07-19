package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokAssistantWakeTest {
    @Test
    fun match_okayGrokWithCommand() {
        val m = GrokAssistantWake.match("Okay Grok, what's the weather?")
        assertNotNull(m)
        assertEquals("okay grok", m!!.phrase)
        assertTrue(m.remainder.contains("weather"))
    }

    @Test
    fun match_okGrokOnly() {
        val m = GrokAssistantWake.match("ok grok")
        assertNotNull(m)
        assertEquals("ok grok", m!!.phrase)
        assertTrue(m.remainder.isEmpty())
        assertTrue(GrokAssistantWake.isWakeOnly(m))
    }

    @Test
    fun match_sttPunctuationVariants() {
        // Speech-to-text often inserts commas / periods / question marks.
        val samples = listOf(
            "Okay, Grok!",
            "OK. Grok?",
            "okay, grok, set a timer",
            "Okay Grok. What's 2+2?",
            "  okay   grok!!!  hello  ",
        )
        for (s in samples) {
            val m = GrokAssistantWake.match(s)
            assertNotNull("should match: $s", m)
            assertTrue(
                "phrase for '$s' was ${m!!.phrase}",
                m.phrase == "okay grok" || m.phrase == "ok grok",
            )
        }
        val withCmd = GrokAssistantWake.match("Okay, Grok! set a timer for 5 minutes")
        assertNotNull(withCmd)
        assertTrue(withCmd!!.remainder.contains("timer"))
    }

    @Test
    fun match_noFalsePositiveOnGrokAlone() {
        assertNull(GrokAssistantWake.match("tell grok about this"))
    }

    @Test
    fun match_heyGrokStillWorksAsAlias() {
        val m = GrokAssistantWake.match("HEY GROK!!! set a timer for 5 minutes")
        assertNotNull(m)
        assertEquals("hey grok", m!!.phrase)
        assertTrue(m.remainder.contains("timer"))
    }

    @Test
    fun match_prefersEarlierPhrase() {
        val m = GrokAssistantWake.match("okay grok then later hey grok ignore")
        assertNotNull(m)
        assertEquals("okay grok", m!!.phrase)
    }

    @Test
    fun normalize_collapsesNoise() {
        assertEquals("okay grok", GrokAssistantWake.normalize("  Okay,   Grok!! "))
        assertEquals("ok grok", GrokAssistantWake.normalize("OK. Grok?"))
    }

    @Test
    fun mic_overlayPreemptsWake() {
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
        GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake)) // same owner re-entrant
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Overlay)) // preempt
        assertFalse(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
    }
}
