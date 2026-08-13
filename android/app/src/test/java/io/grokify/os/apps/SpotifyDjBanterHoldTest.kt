package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyDjBanterHoldTest {

    private val current = "spotify:track:A"
    private val b = "spotify:track:B"
    private val c = "spotify:track:C"
    private val d = "spotify:track:D"
    private val upcoming = listOf(b, c, d)

    @Test
    fun plan_twoSongsAway_targetsSecondUpcoming_withFirstAsPrev() {
        val plan = planBanterPrefetch(
            upcomingUris = upcoming,
            currentUri = current,
            tracksUntilTalk = 2,
            banterEnabled = true,
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.targetIndex)
        assertEquals(c, plan.targetUri)
        assertEquals(b, plan.prevUri)
    }

    @Test
    fun plan_dueNow_targetsQueueHead_withCurrentAsPrev() {
        for (until in listOf(0, 1)) {
            val plan = planBanterPrefetch(
                upcomingUris = upcoming,
                currentUri = current,
                tracksUntilTalk = until,
                banterEnabled = true,
            )
            assertNotNull("until=$until", plan)
            assertEquals(0, plan!!.targetIndex)
            assertEquals(b, plan.targetUri)
            assertEquals(current, plan.prevUri)
        }
    }

    @Test
    fun plan_threeOrMoreAway_doesNotStart() {
        assertNull(
            planBanterPrefetch(
                upcomingUris = upcoming,
                currentUri = current,
                tracksUntilTalk = 3,
                banterEnabled = true,
            ),
        )
        assertNull(
            planBanterPrefetch(
                upcomingUris = upcoming,
                currentUri = current,
                tracksUntilTalk = 4,
                banterEnabled = true,
            ),
        )
    }

    @Test
    fun plan_disabledOrShallowQueue_isNull() {
        assertNull(
            planBanterPrefetch(
                upcomingUris = upcoming,
                currentUri = current,
                tracksUntilTalk = 2,
                banterEnabled = false,
            ),
        )
        assertNull(
            planBanterPrefetch(
                upcomingUris = listOf(b),
                currentUri = current,
                tracksUntilTalk = 2,
                banterEnabled = true,
            ),
        )
    }

    @Test
    fun keepHold_onlyWhilePlannedTargetMatches() {
        assertTrue(shouldKeepHeldBanter(c, c))
        assertFalse(shouldKeepHeldBanter(c, b))
        assertFalse(shouldKeepHeldBanter(c, null))
        assertFalse(shouldKeepHeldBanter(null, c))
        assertFalse(shouldKeepHeldBanter("", c))
    }

    @Test
    fun silentHandoff_keepsBakeForLaterTarget() {
        assertFalse(
            shouldDiscardHeldBanterOnSilentHandoff(
                heldTargetUri = c,
                nextUri = b,
                banterDue = false,
            ),
        )
    }

    @Test
    fun silentHandoff_dropsBakeIfItWasForThisNext() {
        assertTrue(
            shouldDiscardHeldBanterOnSilentHandoff(
                heldTargetUri = b,
                nextUri = b,
                banterDue = false,
            ),
        )
        assertFalse(
            shouldDiscardHeldBanterOnSilentHandoff(
                heldTargetUri = b,
                nextUri = b,
                banterDue = true,
            ),
        )
    }

    @Test
    fun skipSongBeforeBanter_consumesReadyHold() {
        assertTrue(
            shouldConsumeHeldBanter(
                heldTargetUri = c,
                nextUri = c,
                banterDue = true,
                forcedTalk = false,
                hardSkip = false,
            ),
        )
    }

    @Test
    fun skipTwoSongsBefore_doesNotConsumeLaterHold() {
        assertFalse(
            shouldConsumeHeldBanter(
                heldTargetUri = c,
                nextUri = b,
                banterDue = false,
                forcedTalk = false,
                hardSkip = false,
            ),
        )
    }

    @Test
    fun hardSkip_neverConsumesHold() {
        assertFalse(
            shouldConsumeHeldBanter(
                heldTargetUri = c,
                nextUri = c,
                banterDue = true,
                forcedTalk = true,
                hardSkip = true,
            ),
        )
    }

    @Test
    fun handoffWait_staysShortWhenBakeIsAlreadyReady() {
        assertEquals(
            16_000L,
            banterHandoffWaitBudgetMs(
                prefetchInFlight = false,
                allowTalkOver = true,
                needsCustomResearch = false,
            ),
        )
        assertEquals(
            28_000L,
            banterHandoffWaitBudgetMs(
                prefetchInFlight = false,
                allowTalkOver = false,
                needsCustomResearch = false,
            ),
        )
    }

    @Test
    fun handoffWait_holdsForInFlightCustomResearch() {
        val talk = banterHandoffWaitBudgetMs(
            prefetchInFlight = true,
            allowTalkOver = true,
            needsCustomResearch = true,
        )
        val clean = banterHandoffWaitBudgetMs(
            prefetchInFlight = true,
            allowTalkOver = false,
            needsCustomResearch = true,
        )
        assertTrue("talkover wait $talk should cover research+banter", talk >= 75_000L)
        assertTrue("clean-mic wait $clean should cover research+banter", clean >= 75_000L)
        assertTrue(talk <= 120_000L)
        assertTrue(clean <= 120_000L)
    }

    @Test
    fun afterSilentAdvance_planMovesToHeadAndHoldStillMatches() {
        // A finished, B is now playing, C is next, countdown 2 → 1.
        val later = planBanterPrefetch(
            upcomingUris = listOf(c, d),
            currentUri = b,
            tracksUntilTalk = 1,
            banterEnabled = true,
        )
        assertNotNull(later)
        assertEquals(c, later!!.targetUri)
        assertTrue(shouldKeepHeldBanter(heldTargetUri = c, plannedTargetUri = later.targetUri))
        assertTrue(
            shouldConsumeHeldBanter(
                heldTargetUri = c,
                nextUri = c,
                banterDue = true,
                forcedTalk = false,
                hardSkip = false,
            ),
        )
    }
}
