package io.grokify.os.apps

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Cloud speech-to-text for wake clips via xAI [POST /v1/stt].
 *
 * Used by the passive wake engine so we never open system [android.speech.SpeechRecognizer]
 * (which takes audio focus and ducks Spotify/media).
 */
object GrokAssistantWakeStt {
    private const val TAG = "GrokAssistantWakeStt"
    private const val STT_URL = "https://api.x.ai/v1/stt"

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Encode mono s16le PCM as a minimal WAV and transcribe.
     * @return transcript text or null on failure / empty.
     */
    fun transcribePcm(
        authToken: String,
        pcmS16le: ByteArray,
        sampleRate: Int = GrokAssistantWakeVad.SAMPLE_RATE,
    ): String? {
        if (authToken.isBlank() || pcmS16le.isEmpty()) return null
        val wav = pcmToWav(pcmS16le, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "wake.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val req = Request.Builder()
            .url(STT_URL)
            .header("Authorization", "Bearer ${authToken.trim()}")
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "stt HTTP ${resp.code}: ${raw.take(180)}")
                    return null
                }
                val text = parseTranscript(raw)
                if (text.isNullOrBlank()) null else text.trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stt failed: ${e.message}")
            null
        }
    }

    fun parseTranscript(json: String): String? {
        if (json.isBlank()) return null
        return try {
            val o = JSONObject(json)
            sequenceOf("text", "transcript", "transcription")
                .map { key -> o.optString(key).trim() }
                .firstOrNull { it.isNotBlank() }
                ?: o.optJSONObject("result")?.optString("text")?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // Plain-text fallback
            json.trim().takeIf { !it.startsWith("{") && it.isNotBlank() }
        }
    }

    /** Minimal RIFF/WAVE header + s16le mono PCM. */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // PCM chunk size
        buf.putShort(1) // audio format PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(pcm)
        return buf.array()
    }
}
