package io.grokify.os.apps.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionAmplitudeTest {
    @Test
    fun rms_silence_is_near_zero() {
        val pcm = ShortArray(480) { 0 }
        assertTrue(CompanionAmplitude.rmsPcm16(pcm, pcm.size) < 0.001f)
    }

    @Test
    fun rms_loud_is_higher_than_quiet() {
        val quiet = ShortArray(480) { (it % 2 * 800).toShort() }
        val loud = ShortArray(480) { (it % 2 * 12000).toShort() }
        assertTrue(
            CompanionAmplitude.rmsPcm16(loud, loud.size) >
                CompanionAmplitude.rmsPcm16(quiet, quiet.size),
        )
    }

    @Test
    fun peak_tracks_max_sample() {
        val pcm = ShortArray(64) { 0 }
        pcm[10] = 16000
        val peak = CompanionAmplitude.peakPcm16(pcm, pcm.size)
        assertTrue(peak in 0.48f..0.5f)
    }

    @Test
    fun zero_crossing_higher_for_alternating() {
        val flat = ShortArray(100) { 1000 }
        val alt = ShortArray(100) { if (it % 2 == 0) 1000 else -1000 }
        assertTrue(
            CompanionAmplitude.zeroCrossingRate(alt, alt.size) >
                CompanionAmplitude.zeroCrossingRate(flat, flat.size),
        )
    }

    @Test
    fun mouth_clamps_and_smooths() {
        val s = CompanionAmplitude.MouthSmoother(attack = 0.6f, release = 0.25f)
        val a = s.next(0f)
        assertEquals(0f, a, 0.001f)
        val b = s.next(1f, 1f)
        assertTrue(b in 0.01f..1f)
        val c = s.next(0f, 0f)
        assertTrue(c < b)
        assertTrue(s.next(2f, 2f) <= 1f)
        assertTrue(s.next(-1f, 0f) >= 0f)
    }

    @Test
    fun mouth_peak_boosts_onset() {
        val s = CompanionAmplitude.MouthSmoother()
        // Same RMS, higher peak should open more (consonant punch).
        val lowPeak = s.next(0.05f, 0.05f)
        s.reset()
        val highPeak = s.next(0.05f, 0.25f)
        assertTrue(highPeak >= lowPeak)
    }

    @Test
    fun viseme_weights_closed_when_shut() {
        val w = CompanionAmplitude.visemeWeights(0f, 0f, 0f)
        assertTrue(w.all { it == 0f })
    }

    @Test
    fun viseme_weights_open_has_energy() {
        val w = CompanionAmplitude.visemeWeights(0.2f, 0.1f, 0.6f)
        assertTrue(w.sum() > 0.1f)
        assertTrue(w.all { it in 0f..1f })
    }

    @Test
    fun bytes_to_shorts_little_endian() {
        val bytes = byteArrayOf(0x00, 0x10, 0xFF.toByte(), 0x7F)
        val shorts = CompanionAmplitude.pcm16LeToShorts(bytes)
        assertEquals(2, shorts.size)
        assertEquals(0x1000.toShort(), shorts[0])
        assertEquals(0x7FFF.toShort(), shorts[1])
    }

    @Test
    fun windowed_envelope_finds_peak_in_long_chunk() {
        val pcm = ShortArray(2400) { 0 }
        // Quiet padding then a short loud burst (syllable).
        for (i in 1200 until 1400) {
            pcm[i] = if (i % 2 == 0) 14000 else (-14000).toShort()
        }
        val env = CompanionAmplitude.windowedEnvelope(pcm, 240)
        assertTrue(env[0] > 0.05f)
        assertTrue(env[1] > 0.3f)
        // Full-buffer RMS alone would bury the burst; peak window should not.
        val fullRms = CompanionAmplitude.rmsPcm16(pcm, pcm.size)
        assertTrue(env[0] >= fullRms)
    }

    @Test
    fun range_rms_matches_prefix() {
        val pcm = ShortArray(100) { (it * 100).toShort() }
        val a = CompanionAmplitude.rmsPcm16(pcm, 50)
        val b = CompanionAmplitude.rmsPcm16(pcm, 0, 50)
        assertEquals(a, b, 0.0001f)
    }
}
