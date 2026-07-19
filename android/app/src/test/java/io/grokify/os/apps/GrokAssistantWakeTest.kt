package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokAssistantWakeTest {
    @Test
    fun match_heyGrokWithCommand() {
        val m = GrokAssistantWake.match("Hey Grok, what's the weather?")
        assertNotNull(m)
        assertEquals("hey grok", m!!.phrase)
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
    fun match_noFalsePositiveOnGrokAlone() {
        // Single "grok" without hey/ok should not fire (not in phrase list alone).
        assertNull(GrokAssistantWake.match("tell grok about this"))
    }

    @Test
    fun match_punctuationAndCase() {
        val m = GrokAssistantWake.match("HEY GROK!!! set a timer for 5 minutes")
        assertNotNull(m)
        assertEquals("hey grok", m!!.phrase)
        assertTrue(m.remainder.contains("timer"))
    }

    @Test
    fun match_prefersEarlierPhrase() {
        val m = GrokAssistantWake.match("hey grok then later ok grok ignore")
        assertNotNull(m)
        assertEquals("hey grok", m!!.phrase)
    }

    @Test
    fun normalize_collapsesNoise() {
        assertEquals("hey grok", GrokAssistantWake.normalize("  Hey,   Grok!! "))
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
