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
    fun match_phoneticSttNearMissesForGrok() {
        // STT often swaps Grok for near-homophones — still wake.
        val samples = listOf(
            "okay brock" to "okay brock",
            "Okay Brock, what's the weather?" to "okay brock",
            "ok rock set a timer" to "ok rock",
            "hey crock" to "hey crock",
            "hi flock, open maps" to "hi flock",
            "yo jock" to "yo jock",
            "hello truck what's 2+2" to "hello truck",
            "Okay Quack!" to "okay quack",
            "OK. Grock?" to "ok grock",
            "okay croak tell me a joke" to "okay croak",
            "hey brok" to "hey brok",
        )
        for ((raw, expectedPhrase) in samples) {
            val m = GrokAssistantWake.match(raw)
            assertNotNull("should match phonetic: $raw", m)
            assertEquals("phrase for '$raw'", expectedPhrase, m!!.phrase)
        }
        val withCmd = GrokAssistantWake.match("Okay Brock, what's the weather?")
        assertNotNull(withCmd)
        assertTrue(withCmd!!.remainder.contains("weather"))
        assertFalse(GrokAssistantWake.isWakeOnly(withCmd))
    }

    @Test
    fun match_noFalsePositiveOnBareHomophone() {
        // Without a wake prefix, bare "rock" / "truck" must not fire.
        assertNull(GrokAssistantWake.match("tell rock about this"))
        assertNull(GrokAssistantWake.match("the truck is late"))
        assertNull(GrokAssistantWake.match("flock of birds"))
        assertNull(GrokAssistantWake.match("brock"))
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
        GrokAssistantMic.release(GrokAssistantMic.Owner.Voice)
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake)) // same owner re-entrant
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Overlay)) // preempt
        assertFalse(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)) // preempt overlay
        assertFalse(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        GrokAssistantMic.release(GrokAssistantMic.Owner.Voice)
        assertTrue(GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake))
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
    }

    @Test
    fun stt_parseTranscript_textField() {
        assertEquals("okay grok", GrokAssistantWakeStt.parseTranscript("""{"text":"okay grok"}"""))
        assertEquals("hey grok", GrokAssistantWakeStt.parseTranscript("""{"transcript":"hey grok"}"""))
    }

    @Test
    fun stt_pcmToWav_hasRiffHeaderAndSize() {
        val pcm = ByteArray(3200) // 100ms @ 16k mono s16
        val wav = GrokAssistantWakeStt.pcmToWav(pcm, sampleRate = 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
    }

    @Test
    fun vad_emitsUtteranceAfterSpeechThenSilence() {
        val vad = GrokAssistantWakeVad()
        val frameBytes = vad.frameBytes
        // Quiet frames to settle floor
        val quiet = ByteArray(frameBytes)
        repeat(30) { assertNull(vad.accept(quiet)) }
        // Loud speech-like frames (square-ish s16)
        val loud = ByteArray(frameBytes)
        var i = 0
        while (i + 1 < loud.size) {
            loud[i] = 0
            loud[i + 1] = 0x30 // ~12288 amplitude
            i += 2
        }
        var got: GrokAssistantWakeVad.Utterance? = null
        // ~500ms speech + silence to end
        repeat(30) {
            val u = vad.accept(loud)
            if (u != null) got = u
        }
        repeat(40) {
            val u = vad.accept(quiet)
            if (u != null) got = u
        }
        assertNotNull("VAD should emit an utterance", got)
        assertTrue(got!!.durationMs >= GrokAssistantWakeVad.MIN_UTTER_MS)
        assertTrue(got.pcm.isNotEmpty())
    }
}
