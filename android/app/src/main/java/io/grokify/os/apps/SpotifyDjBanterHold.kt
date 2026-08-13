package io.grokify.os.apps

/**
 * How far ahead Live DJ starts research + TTS for the next spoken handoff.
 * 2 = bake while the song *before* the pre-banter cut is still on.
 */
const val BANTER_PREFETCH_AHEAD = 2

/** Planned bake: introduce [targetUri] after [prevUri] finishes. */
data class BanterPrefetchPlan(
    val targetIndex: Int,
    val targetUri: String,
    val prevUri: String?,
)

/**
 * Upcoming-queue index of the cut the next spoken line introduces.
 * `tracksUntilTalk` 0/1 → next handoff (index 0). 2 → after one silent cut (index 1).
 * Further out → null (do not start yet).
 */
fun banterPrefetchTargetIndex(tracksUntilTalk: Int): Int? {
    if (tracksUntilTalk > BANTER_PREFETCH_AHEAD) return null
    return (tracksUntilTalk - 1).coerceAtLeast(0)
}

fun planBanterPrefetch(
    upcomingUris: List<String>,
    currentUri: String?,
    tracksUntilTalk: Int,
    banterEnabled: Boolean,
): BanterPrefetchPlan? {
    if (!banterEnabled) return null
    val idx = banterPrefetchTargetIndex(tracksUntilTalk) ?: return null
    val target = upcomingUris.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: return null
    val prev = if (idx <= 0) {
        currentUri?.takeIf { it.isNotBlank() }
    } else {
        upcomingUris.getOrNull(idx - 1)?.takeIf { it.isNotBlank() }
    }
    return BanterPrefetchPlan(
        targetIndex = idx,
        targetUri = target,
        prevUri = prev,
    )
}

/** Keep a held bake only while it still matches the planned talk target. */
fun shouldKeepHeldBanter(heldTargetUri: String?, plannedTargetUri: String?): Boolean {
    val held = heldTargetUri?.takeIf { it.isNotBlank() } ?: return false
    return held == plannedTargetUri
}

/**
 * Silent handoff (banter not due): drop the bake only if it was for *this* next cut.
 * A bake for a later target must survive so skip on the song-before can play instantly.
 */
fun shouldDiscardHeldBanterOnSilentHandoff(
    heldTargetUri: String?,
    nextUri: String?,
    banterDue: Boolean,
): Boolean {
    if (banterDue) return false
    val held = heldTargetUri?.takeIf { it.isNotBlank() } ?: return false
    val next = nextUri?.takeIf { it.isNotBlank() } ?: return false
    return held == next
}

/** Skip / natural end should speak the held line when it introduces [nextUri]. */
fun shouldConsumeHeldBanter(
    heldTargetUri: String?,
    nextUri: String?,
    banterDue: Boolean,
    forcedTalk: Boolean,
    hardSkip: Boolean,
): Boolean {
    if (hardSkip) return false
    val held = heldTargetUri?.takeIf { it.isNotBlank() } ?: return false
    val next = nextUri?.takeIf { it.isNotBlank() } ?: return false
    if (held != next) return false
    return banterDue || forcedTalk
}

/**
 * How long the live handoff may wait for research + TTS.
 *
 * Short when a bake is already expected to be ready. Long when research is
 * still running or a custom angle must produce findings — otherwise the
 * booth falls back to the canned "finishing up with some X" line.
 */
fun banterHandoffWaitBudgetMs(
    prefetchInFlight: Boolean,
    allowTalkOver: Boolean,
    needsCustomResearch: Boolean,
): Long {
    if (prefetchInFlight || needsCustomResearch) {
        return if (needsCustomResearch) 90_000L else 60_000L
    }
    return if (allowTalkOver) 16_000L else 28_000L
}
