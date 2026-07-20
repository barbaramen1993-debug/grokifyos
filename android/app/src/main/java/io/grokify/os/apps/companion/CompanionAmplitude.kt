package io.grokify.os.apps.companion

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

    fun rmsPcm16Bytes(bytes: ByteArray): Float {
        val s = pcm16LeToShorts(bytes)
        return rmsPcm16(s, s.size)
    }

    /** Mouth open amount 0..1 from RMS with attack/release smoothing. */
    class MouthSmoother(
        private val attack: Float = 0.55f,
        private val release: Float = 0.22f,
        private val gain: Float = 4.5f,
        private val noiseGate: Float = 0.012f,
    ) {
        private var value = 0f

        fun next(rms: Float): Float {
            val target = when {
                rms < noiseGate -> 0f
                else -> min(1f, (rms - noiseGate) * gain).coerceIn(0f, 1f)
            }
            val coef = if (target > value) attack else release
            value += (target - value) * coef
            return value.coerceIn(0f, 1f)
        }

        fun reset() {
            value = 0f
        }
    }
}
