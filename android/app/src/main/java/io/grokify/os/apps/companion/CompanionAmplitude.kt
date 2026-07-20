package io.grokify.os.apps.companion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object CompanionAmplitude {
    fun pcm16LeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        var i = 0
        var j = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xff
            val hi = bytes[i + 1].toInt()
            out[j++] = ((hi shl 8) or lo).toShort()
            i += 2
        }
        return out
    }

    /** RMS of PCM16 samples normalized roughly to 0..1 (full-scale ~1). */
    fun rmsPcm16(samples: ShortArray, count: Int): Float {
        val n = min(count, samples.size)
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    /** Peak absolute amplitude 0..1. */
    fun peakPcm16(samples: ShortArray, count: Int): Float {
        val n = min(count, samples.size)
        if (n <= 0) return 0f
        var peak = 0f
        for (i in 0 until n) {
            val a = abs(samples[i] / 32768f)
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * Zero-crossing rate 0..1 (fraction of samples that cross zero).
     * Higher ≈ brighter / more sibilant; lower ≈ vowels / silence.
     */
    fun zeroCrossingRate(samples: ShortArray, count: Int): Float {
        val n = min(count, samples.size)
        if (n < 2) return 0f
        var crosses = 0
        var prev = samples[0].toInt()
        for (i in 1 until n) {
            val cur = samples[i].toInt()
            if ((prev >= 0 && cur < 0) || (prev < 0 && cur >= 0)) {
                crosses++
            }
            prev = cur
        }
        return crosses.toFloat() / (n - 1)
    }

    fun rmsPcm16Bytes(bytes: ByteArray): Float {
        val s = pcm16LeToShorts(bytes)
        return rmsPcm16(s, s.size)
    }

    /**
     * Envelope for lip-sync: blend RMS (body) + peak (consonant punch),
     * soft-knee map, then attack/release smoothing.
     */
    class MouthSmoother(
        private val attack: Float = 0.94f,
        private val release: Float = 0.52f,
        private val gain: Float = 8.2f,
        private val noiseGate: Float = 0.004f,
        private val peakWeight: Float = 0.55f,
    ) {
        private var value = 0f

        fun next(rms: Float, peak: Float = rms): Float {
            val blended = rms * (1f - peakWeight) + peak * peakWeight
            val target = when {
                blended < noiseGate -> 0f
                else -> {
                    // Soft knee: speech levels open clearly; silence snaps shut.
                    val x = ((blended - noiseGate) * gain).coerceAtLeast(0f)
                    val shaped = 1f - 1f / (1f + x * 1.75f)
                    min(1f, shaped * 1.2f)
                }
            }
            val coef = if (target > value) attack else release
            value += (target - value) * coef
            // Snap shut on true silence so lips don't hang open.
            if (target < 0.01f && value < 0.05f) {
                value *= 0.3f
            }
            return value.coerceIn(0f, 1f)
        }

        fun reset() {
            value = 0f
        }
    }

    /**
     * Crude formant proxy from RMS + ZCR → blend weights for aa/ih/ou/ee/oh.
     * Not phoneme-accurate; enough variety that the mouth doesn't look locked on "aa".
     */
    fun visemeWeights(rms: Float, zcr: Float, open: Float): FloatArray {
        // Indices: aa, ih, ou, ee, oh
        val w = FloatArray(5)
        if (open < 0.02f) return w
        val bright = zcr.coerceIn(0f, 1f)
        val loud = rms.coerceIn(0f, 1f)
        // Closed / mid → ou, oh; open vowel → aa; bright → ee/ih.
        w[0] = open * (0.35f + 0.45f * loud) * (1f - bright * 0.55f) // aa
        w[1] = open * (0.15f + 0.4f * bright) * (0.6f + 0.4f * (1f - loud)) // ih
        w[2] = open * (0.12f + 0.25f * (1f - bright)) * (0.5f + 0.5f * (1f - loud)) // ou
        w[3] = open * bright * (0.25f + 0.5f * loud) // ee
        w[4] = open * (0.2f + 0.35f * loud) * (0.7f - bright * 0.4f).coerceAtLeast(0.15f) // oh
        // Normalize so max is ~open (expression manager stacks morphs).
        var maxW = 0f
        for (v in w) if (v > maxW) maxW = v
        if (maxW > 0.001f) {
            val scale = open / maxW
            for (i in w.indices) w[i] = (w[i] * scale).coerceIn(0f, 1f)
        }
        return w
    }
}
