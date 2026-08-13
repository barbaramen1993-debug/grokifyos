package io.grokify.os.apps

/**
 * How long a Live DJ spoken line actually occupies the air.
 *
 * The old 18s clamps (word-count estimate + talkover arm) made long custom-angle
 * clips start the next cut while the mic was still going.
 */

const val BANTER_SPEECH_MS_MIN = 2_500L
const val BANTER_SPEECH_MS_MAX = 180_000L
const val BANTER_TALKOVER_SPEAK_MIN_MS = 4_000L
const val BANTER_TALKOVER_ARM_MIN_MS = 6_000L
const val BANTER_TALKOVER_HEADROOM_MAX_MS = 90_000L

/** ~125 wpm + pad — prefer slightly long over cutting the mic. */
fun estimateBanterSpeechMs(line: String): Long {
    val words = line.trim().split(Regex("\\s+")).count { it.isNotBlank() }.coerceAtLeast(1)
    return (words * 480L + 1_000L).coerceIn(BANTER_SPEECH_MS_MIN, BANTER_SPEECH_MS_MAX)
}

/**
 * Prefer measured TTS when it looks real. A tiny MediaPlayer duration on a long
 * line is treated as a lie; a long real clip is trusted even if word-count is lower.
 */
fun resolveBanterSpeechMs(line: String, bakedMs: Long): Long {
    val estimate = estimateBanterSpeechMs(line)
    if (bakedMs !in 800L..BANTER_SPEECH_MS_MAX) return estimate
    // Metadata often under-reports MP3s without Xing headers (~a few seconds).
    if (bakedMs < estimate * 3L / 4L) return estimate
    return bakedMs
}

/** Remain to wait for before *starting* the spoken line (talk-over). */
fun talkoverSpeakRemainMs(speechMs: Long): Long {
    return (speechMs + 900L).coerceIn(
        BANTER_TALKOVER_SPEAK_MIN_MS,
        BANTER_TALKOVER_HEADROOM_MAX_MS,
    )
}

/** Remain that arms the near-end handoff so the wait loop still has room. */
fun talkoverArmRemainMs(speechMs: Long): Long {
    return (speechMs + 1_200L).coerceIn(
        BANTER_TALKOVER_ARM_MIN_MS,
        BANTER_TALKOVER_HEADROOM_MAX_MS,
    )
}

/**
 * True when the outro is shorter than the line — pause Spotify so autoplay
 * cannot start the next cut under the last sentences.
 */
fun shouldPauseSpotifySoBanterCanFinish(speechMs: Long, remainMs: Long): Boolean {
    if (speechMs <= 0L) return false
    if (remainMs <= 0L) return true
    return speechMs + 1_500L > remainMs
}

/** Blocking TTS wait: clip length + pad, never the old flat 90s kill. */
fun banterPlayWaitMs(speechMs: Long): Long {
    val raw = if (speechMs > 0L) speechMs + 12_000L else 45_000L
    return raw.coerceIn(30_000L, 180_000L)
}

/** CBR 128 kbps floor when container duration is missing or short. */
fun estimateMp3DurationFromSize(bytes: Long, bitrateKbps: Int = 128): Long {
    if (bytes <= 0L || bitrateKbps <= 0) return 0L
    return (bytes * 8L) / bitrateKbps
}

/**
 * Combine MediaPlayer/retriever duration with a file-size floor.
 * When headers under-report, keep the longer plausible value.
 */
fun pickAudioDurationMs(measuredMs: Long, sizeMs: Long): Long {
    val measured = measuredMs.coerceAtLeast(0L)
    val size = sizeMs.coerceAtLeast(0L)
    if (measured <= 0L) return size
    if (size <= 0L) return measured
    if (measured < size * 3L / 4L) return size
    return maxOf(measured, size)
}
