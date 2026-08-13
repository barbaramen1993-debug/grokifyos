package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyDjBanterTimingTest {

    private fun words(n: Int): String = (1..n).joinToString(" ") { "word$it" }

    @Test
    fun estimate_longCustomAngleLine_isWellPastEighteenSeconds() {
        val line = words(70)
        val ms = estimateBanterSpeechMs(line)
        assertTrue("70-word line estimated ${ms}ms, expected > 18s", ms > 18_000L)
        assertTrue("estimate $ms should stay under 3 minutes", ms < 180_000L)
    }

    @Test
    fun estimate_scalesWithWordCount_andHasFloor() {
        val short = estimateBanterSpeechMs("Hey.")
        val mid = estimateBanterSpeechMs(words(20))
        val long = estimateBanterSpeechMs(words(60))
        assertTrue("floor ${short}ms", short >= 2_500L)
        assertTrue("20 words ${mid}ms should beat 8 words-ish floor", mid > short)
        assertTrue("60 words ${long}ms should beat 20", long > mid)
    }

    @Test
    fun resolve_usesBakedWhenItBeatsTheOldEighteenSecondCap() {
        val line = words(70)
        val resolved = resolveBanterSpeechMs(line, bakedMs = 32_400L)
        assertEquals(32_400L, resolved)
    }

    @Test
    fun resolve_usesEstimateWhenBakeHasNoDuration() {
        val line = words(55)
        val est = estimateBanterSpeechMs(line)
        assertEquals(est, resolveBanterSpeechMs(line, bakedMs = 0L))
        assertEquals(est, resolveBanterSpeechMs(line, bakedMs = -1L))
    }

    @Test
    fun resolve_doesNotTrustTinyMetadataOnALongLine() {
        val line = words(70)
        val est = estimateBanterSpeechMs(line)
        val resolved = resolveBanterSpeechMs(line, bakedMs = 4_000L)
        assertEquals(est, resolved)
        assertTrue(resolved > 18_000L)
    }

    @Test
    fun resolve_keepsLongRealClipInsteadOfFallingBackToEstimateCap() {
        val line = words(40)
        val resolved = resolveBanterSpeechMs(line, bakedMs = 56_000L)
        assertEquals(56_000L, resolved)
    }

    @Test
    fun talkoverHeadroom_followsLongSpeech_notEighteenSecondClamp() {
        val remain = talkoverSpeakRemainMs(32_000L)
        assertTrue("speak remain $remain should cover a 32s line", remain >= 32_000L)
        assertTrue("speak remain $remain should not jump to mid-song", remain <= 90_000L)
        val arm = talkoverArmRemainMs(32_000L)
        assertTrue("arm remain $arm should be at least speak remain", arm >= remain)
    }

    @Test
    fun talkoverHeadroom_stillHasASaneFloorForTinyClips() {
        assertTrue(talkoverSpeakRemainMs(1_500L) >= 4_000L)
        assertTrue(talkoverArmRemainMs(1_500L) >= 6_000L)
    }

    @Test
    fun pauseSpotify_whenSpeechWillOutlastTheOutro() {
        assertTrue(shouldPauseSpotifySoBanterCanFinish(speechMs = 32_000L, remainMs = 10_000L))
        assertTrue(shouldPauseSpotifySoBanterCanFinish(speechMs = 28_000L, remainMs = 18_000L))
        assertFalse(shouldPauseSpotifySoBanterCanFinish(speechMs = 12_000L, remainMs = 20_000L))
        assertFalse(shouldPauseSpotifySoBanterCanFinish(speechMs = 8_000L, remainMs = 9_500L))
    }

    @Test
    fun playWait_outlastsTheClip_andDoesNotDieAtNinetySeconds() {
        val mid = banterPlayWaitMs(32_000L)
        assertTrue("32s clip wait $mid", mid >= 40_000L)
        val long = banterPlayWaitMs(100_000L)
        assertTrue("100s clip wait $long must exceed the old 90s hard cap", long > 90_000L)
        assertTrue(long <= 180_000L)
    }

    @Test
    fun mp3SizeEstimate_isAUsefulFloorWhenHeadersLie() {
        // 128 kbps × 30s ≈ 480_000 bytes
        val thirty = estimateMp3DurationFromSize(480_000L)
        assertTrue("480KB ~30s got ${thirty}ms", thirty in 25_000L..40_000L)
        assertEquals(0L, estimateMp3DurationFromSize(0L))
        assertEquals(0L, estimateMp3DurationFromSize(-8L))
    }

    @Test
    fun pickAudioDuration_prefersLongerPlausibleSource() {
        assertEquals(32_000L, pickAudioDurationMs(measuredMs = 18_000L, sizeMs = 32_000L))
        assertEquals(28_000L, pickAudioDurationMs(measuredMs = 28_000L, sizeMs = 20_000L))
        assertEquals(24_000L, pickAudioDurationMs(measuredMs = 0L, sizeMs = 24_000L))
        assertEquals(0L, pickAudioDurationMs(measuredMs = 0L, sizeMs = 0L))
    }
}
