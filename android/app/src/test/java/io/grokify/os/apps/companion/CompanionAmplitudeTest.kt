package io.grokify.os.apps.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun mouth_clamps_and_smooths() {
        val s = CompanionAmplitude.MouthSmoother(attack = 0.6f, release = 0.25f)
        val a = s.next(0f)
        assertEquals(0f, a, 0.001f)
        val b = s.next(1f)
        assertTrue(b in 0.01f..1f)
        val c = s.next(0f)
        assertTrue(c < b)
        assertTrue(s.next(2f) <= 1f)
        assertTrue(s.next(-1f) >= 0f)
    }

    @Test
    fun bytes_to_shorts_little_endian() {
        val bytes = byteArrayOf(0x00, 0x10, 0xFF.toByte(), 0x7F)
        val shorts = CompanionAmplitude.pcm16LeToShorts(bytes)
        assertEquals(2, shorts.size)
        assertEquals(0x1000.toShort(), shorts[0])
        assertEquals(0x7FFF.toShort(), shorts[1])
    }
}
