package io.grokify.os.apps

import kotlin.math.sqrt

/**
 * Adaptive energy VAD for passive wake listening.
 *
 * Tracks a slow noise floor so steady media playback through the speaker is
 * treated as background, not speech. Emits complete utterances with a short
 * pre-roll so the wake phrase onset is not clipped.
 *
 * Pure logic — unit-testable, no Android types.
 */
class GrokAssistantWakeVad(
    private val sampleRate: Int = SAMPLE_RATE,
    private val frameMs: Int = FRAME_MS,
) {
    data class Utterance(
        /** PCM s16le mono including pre-roll. */
        val pcm: ByteArray,
        val durationMs: Long,
    )

    private val frameSamples = (sampleRate * frameMs / 1000).coerceAtLeast(80)
    val frameBytes: Int = frameSamples * 2

    private var noiseFloor = 350.0
    private var inSpeech = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private var speechStartFrame = 0
    private var frameIndex = 0L

    /** Rolling PCM history for pre-roll (bytes). */
    private val history = ArrayDeque<ByteArray>()
    private var historyBytes = 0
    private val maxHistoryBytes = sampleRate * 2 * PRE_ROLL_MS / 1000 + frameBytes * 4

    /** Active utterance accumulator once speech starts. */
    private val active = ArrayList<ByteArray>(64)
    private var activeBytes = 0

    fun reset() {
        noiseFloor = 350.0
        inSpeech = false
        speechFrames = 0
        silenceFrames = 0
        speechStartFrame = 0
        frameIndex = 0L
        history.clear()
        historyBytes = 0
        active.clear()
        activeBytes = 0
    }

    /**
     * Feed one PCM frame (exactly [frameBytes] preferred; shorter OK at EOS).
     * Returns a completed utterance when speech ends, else null.
     *
     * @param musicBoost when media is playing, require stronger onset (speaker bleed).
     */
    fun accept(frame: ByteArray, musicBoost: Boolean = false): Utterance? {
        if (frame.isEmpty()) return null
        val rms = rmsS16(frame)
        val onsetMul = if (musicBoost) ONSET_MUL_MUSIC else ONSET_MUL
        val holdMul = if (musicBoost) HOLD_MUL_MUSIC else HOLD_MUL
        val onset = noiseFloor * onsetMul
        val hold = noiseFloor * holdMul

        // Adapt noise floor when not in speech (or very quiet frames).
        if (!inSpeech || rms < hold) {
            val alpha = if (rms < noiseFloor) FLOOR_DOWN else FLOOR_UP
            noiseFloor = (noiseFloor * (1.0 - alpha) + rms * alpha)
                .coerceIn(FLOOR_MIN, FLOOR_MAX)
        }

        pushHistory(frame)
        frameIndex++

        if (!inSpeech) {
            if (rms >= onset) {
                speechFrames++
                if (speechFrames >= START_FRAMES) {
                    inSpeech = true
                    silenceFrames = 0
                    speechStartFrame = 0
                    // Seed active buffer with pre-roll from history.
                    active.clear()
                    activeBytes = 0
                    for (h in history) {
                        active.add(h)
                        activeBytes += h.size
                    }
                }
            } else {
                speechFrames = 0
            }
            return null
        }

        // In speech: keep collecting.
        active.add(frame.copyOf())
        activeBytes += frame.size
        speechStartFrame++

        if (rms < hold) {
            silenceFrames++
        } else {
            silenceFrames = 0
        }

        val durationMs = activeBytes / 2 * 1000L / sampleRate
        val endBySilence = silenceFrames >= END_SILENCE_FRAMES && durationMs >= MIN_UTTER_MS
        val endByMax = durationMs >= MAX_UTTER_MS

        if (!endBySilence && !endByMax) return null

        val pcm = join(active)
        inSpeech = false
        speechFrames = 0
        silenceFrames = 0
        active.clear()
        activeBytes = 0

        // Drop too-short blips (coughs, bumps).
        if (durationMs < MIN_UTTER_MS) return null
        return Utterance(pcm = pcm, durationMs = durationMs)
    }

    private fun pushHistory(frame: ByteArray) {
        val copy = frame.copyOf()
        history.addLast(copy)
        historyBytes += copy.size
        while (historyBytes > maxHistoryBytes && history.isNotEmpty()) {
            historyBytes -= history.removeFirst().size
        }
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val PRE_ROLL_MS = 350
        const val MIN_UTTER_MS = 350L
        const val MAX_UTTER_MS = 4_200L

        private const val START_FRAMES = 4 // ~80ms
        private const val END_SILENCE_FRAMES = 18 // ~360ms

        private const val ONSET_MUL = 3.2
        private const val HOLD_MUL = 1.9
        private const val ONSET_MUL_MUSIC = 4.8
        private const val HOLD_MUL_MUSIC = 2.6

        private const val FLOOR_UP = 0.02
        private const val FLOOR_DOWN = 0.08
        private const val FLOOR_MIN = 120.0
        private const val FLOOR_MAX = 8_000.0

        fun rmsS16(pcm: ByteArray): Double {
            if (pcm.size < 2) return 0.0
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val lo = pcm[i].toInt() and 0xff
                val hi = pcm[i + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                sum += (s * s).toDouble()
                n++
                i += 2
            }
            if (n == 0) return 0.0
            return sqrt(sum / n)
        }
    }
}
