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
    fun rmsPcm16(samples: ShortArray, count: Int): Float =
        rmsPcm16(samples, 0, count)

    fun rmsPcm16(samples: ShortArray, offset: Int, count: Int): Float {
        val start = offset.coerceAtLeast(0)
        val n = min(count, samples.size - start)
        if (n <= 0) return 0f
        var sum = 0.0
        val end = start + n
        for (i in start until end) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    /** Peak absolute amplitude 0..1. */
    fun peakPcm16(samples: ShortArray, count: Int): Float =
        peakPcm16(samples, 0, count)

    fun peakPcm16(samples: ShortArray, offset: Int, count: Int): Float {
        val start = offset.coerceAtLeast(0)
        val n = min(count, samples.size - start)
        if (n <= 0) return 0f
        var peak = 0f
        val end = start + n
        for (i in start until end) {
            val a = abs(samples[i] / 32768f)
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * Zero-crossing rate 0..1 (fraction of samples that cross zero).
     * Higher ≈ brighter / more sibilant; lower ≈ vowels / silence.
     */
    fun zeroCrossingRate(samples: ShortArray, count: Int): Float =
        zeroCrossingRate(samples, 0, count)

    fun zeroCrossingRate(samples: ShortArray, offset: Int, count: Int): Float {
        val start = offset.coerceAtLeast(0)
        val n = min(count, samples.size - start)
        if (n < 2) return 0f
        var crosses = 0
        var prev = samples[start].toInt()
        val end = start + n
        for (i in (start + 1) until end) {
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
     *
     * Tuned hot for TTS: quiet neural speech still drives a visible jaw,
     * onsets snap open, closures stay snappy so lips chatter with the audio.
     */
    class MouthSmoother(
        private val attack: Float = 0.98f,
        private val release: Float = 0.68f,
        private val gain: Float = 14.5f,
        private val noiseGate: Float = 0.002f,
        private val peakWeight: Float = 0.68f,
    ) {
        private var value = 0f

        fun next(rms: Float, peak: Float = rms): Float {
            val blended = rms * (1f - peakWeight) + peak * peakWeight
            val target = when {
                blended < noiseGate -> 0f
                else -> {
                    // Soft knee + mild expand so mid speech reads clearly open.
                    val x = ((blended - noiseGate) * gain).coerceAtLeast(0f)
                    val shaped = 1f - 1f / (1f + x * 2.1f)
                    // Floor a little openness above gate so quiet vowels still move lips.
                    min(1f, (shaped * 1.45f + 0.06f).coerceAtMost(1f))
                }
            }
            val coef = if (target > value) attack else release
            value += (target - value) * coef
            // Snap shut on true silence so lips don't hang open.
            if (target < 0.008f && value < 0.06f) {
                value *= 0.22f
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
        if (open < 0.015f) return w
        val bright = zcr.coerceIn(0f, 1f)
        val loud = rms.coerceIn(0f, 1f)
        // Closed / mid → ou, oh; open vowel → aa; bright → ee/ih.
        // Stronger secondary so lips don't lock on a single morph.
        w[0] = open * (0.42f + 0.48f * loud) * (1f - bright * 0.5f) // aa
        w[1] = open * (0.22f + 0.5f * bright) * (0.55f + 0.45f * (1f - loud)) // ih
        w[2] = open * (0.18f + 0.32f * (1f - bright)) * (0.45f + 0.55f * (1f - loud)) // ou
        w[3] = open * bright * (0.35f + 0.55f * loud) // ee
        w[4] = open * (0.28f + 0.4f * loud) * (0.75f - bright * 0.35f).coerceAtLeast(0.2f) // oh
        // Normalize so max is ~open (expression manager stacks morphs).
        var maxW = 0f
        for (v in w) if (v > maxW) maxW = v
        if (maxW > 0.001f) {
            val scale = open / maxW
            for (i in w.indices) w[i] = (w[i] * scale).coerceIn(0f, 1f)
        }
        return w
    }

    /**
     * Analyze a full PCM chunk in short windows so multi-frame deltas still
     * surface syllable peaks instead of averaging them away.
     *
     * @return Triple(rmsPeak, peakPeak, zcrAtPeak) — envelope stats for the loudest window.
     */
    fun windowedEnvelope(
        samples: ShortArray,
        window: Int = 480,
    ): FloatArray {
        if (samples.isEmpty()) return floatArrayOf(0f, 0f, 0f)
        val win = window.coerceIn(80, samples.size)
        var bestRms = 0f
        var bestPeak = 0f
        var bestZcr = 0f
        var sumRms = 0f
        var windows = 0
        var i = 0
        while (i < samples.size) {
            val n = min(win, samples.size - i)
            val rms = rmsPcm16(samples, i, n)
            val peak = peakPcm16(samples, i, n)
            val zcr = zeroCrossingRate(samples, i, n)
            sumRms += rms
            windows++
            if (peak >= bestPeak || rms >= bestRms) {
                bestRms = max(bestRms, rms)
                bestPeak = max(bestPeak, peak)
                bestZcr = zcr
            }
            i += n
        }
        // Blend peak window with mean so sustained soft speech still registers.
        val meanRms = if (windows > 0) sumRms / windows else 0f
        val rms = max(bestRms, meanRms * 1.15f)
        return floatArrayOf(rms, bestPeak, bestZcr)
    }
}
