package io.grokify.os.wear.voice

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * xAI Voice Agent Realtime (Voice 2.0) WebSocket client for Wear.
 * @see <a href="https://docs.x.ai/developers/model-capabilities/audio/voice-agent">docs</a>
 */
class WearVoiceClient(
    private val onEvent: (JSONObject) -> Unit,
    private val onBinaryAudio: (ByteArray) -> Unit = {},
    private val onState: (connected: Boolean, detail: String?) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .build()

    private val socket = AtomicReference<WebSocket?>(null)
    private val intentionalClose = AtomicBoolean(false)
    private val sessionConfigured = AtomicBoolean(false)
    private val opened = AtomicBoolean(false)

    val isOpen: Boolean get() = opened.get() && socket.get() != null
    val isSessionReady: Boolean get() = sessionConfigured.get() && isOpen

    fun connect(
        authToken: String,
        model: String = DEFAULT_MODEL,
        conversationId: String? = null,
    ) {
        intentionalClose.set(true)
        socket.getAndSet(null)?.close(1000, "reconnect")
        intentionalClose.set(false)
        sessionConfigured.set(false)
        opened.set(false)

        val modelQ = model.trim().ifBlank { DEFAULT_MODEL }
        val resume = conversationId?.trim().orEmpty()
        val url = buildString {
            append(WS_BASE)
            append("?model=")
            append(modelQ)
            if (resume.isNotEmpty()) {
                append("&conversation_id=")
                append(resume)
            }
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${authToken.trim()}")
            .build()

        val ws = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.set(true)
                    Log.i(TAG, "WebSocket open code=${response.code}")
                    onState(true, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = JSONObject(text)
                        val type = event.optString("type")
                        if (type == "session.updated") {
                            sessionConfigured.set(true)
                            Log.i(TAG, "session.updated — ready")
                        } else if (type == "session.created") {
                            Log.d(TAG, "session.created (awaiting session.update)")
                        } else if (type == "error") {
                            val err = event.optJSONObject("error")?.optString("message")
                                .orEmpty()
                                .ifBlank { event.optString("error", "") }
                                .ifBlank { event.optString("message", "error") }
                            Log.e(TAG, "realtime error: ${err.take(200)}")
                        }
                        onEvent(event)
                    } catch (e: Exception) {
                        Log.w(TAG, "bad event: ${e.message}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onBinaryAudio(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.set(false)
                    sessionConfigured.set(false)
                    if (!intentionalClose.get()) {
                        onState(false, "Closed $code${if (reason.isNotBlank()) ": $reason" else ""}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.set(false)
                    sessionConfigured.set(false)
                    if (intentionalClose.get()) return
                    val msg = response?.let { "HTTP ${it.code}: ${t.message ?: "failed"}" }
                        ?: (t.message ?: "connection failed")
                    Log.e(TAG, "WebSocket failure: $msg", t)
                    onState(false, msg)
                }
            },
        )
        socket.set(ws)
    }

    fun disconnect() {
        intentionalClose.set(true)
        opened.set(false)
        sessionConfigured.set(false)
        socket.getAndSet(null)?.close(1000, "bye")
        onState(false, null)
    }

    fun sendJson(obj: JSONObject): Boolean {
        val ws = socket.get() ?: return false
        return ws.send(obj.toString())
    }

    fun sessionUpdate(
        instructions: String,
        voice: String,
        tools: JSONArray,
        sampleRate: Int = SAMPLE_RATE,
        reasoningEffort: String = "none",
    ): Boolean {
        sessionConfigured.set(false)
        val input = JSONObject()
            .put(
                "format",
                JSONObject().put("type", "audio/pcm").put("rate", sampleRate),
            )
            .put(
                "transcription",
                JSONObject()
                    .put("model", "grok-transcribe")
                    .put("language_hint", "en")
                    .put(
                        "keyterms",
                        JSONArray()
                            .put("Carina")
                            .put("Grokify")
                            .put("GrokifyOS")
                            .put("Spotify")
                            .put("Galaxy")
                            .put("Watch"),
                    ),
            )
        val output = JSONObject()
            .put(
                "format",
                JSONObject().put("type", "audio/pcm").put("rate", sampleRate),
            )
        val audio = JSONObject().put("input", input).put("output", output)
        val effort = if (reasoningEffort.trim().equals("high", true)) "high" else "none"
        val session = JSONObject()
            .put("instructions", instructions)
            .put("voice", voice.ifBlank { VOICE_CARINA })
            .put("tools", tools)
            .put("reasoning", JSONObject().put("effort", effort))
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.75)
                    .put("silence_duration_ms", 3_200)
                    .put("prefix_padding_ms", 500),
            )
            .put("audio", audio)
            .put("resumption", JSONObject().put("enabled", true))
        return sendJson(
            JSONObject()
                .put("type", "session.update")
                .put("session", session),
        )
    }

    fun appendInputAudioBase64(b64: String): Boolean =
        sendJson(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", b64),
        )

    fun clearInputAudioBuffer(): Boolean =
        sendJson(JSONObject().put("type", "input_audio_buffer.clear"))

    fun responseCreate(): Boolean =
        sendJson(JSONObject().put("type", "response.create"))

    fun cancelResponse(): Boolean =
        sendJson(JSONObject().put("type", "response.cancel"))

    fun sendFunctionOutput(callId: String, outputJson: String): Boolean =
        sendJson(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", outputJson),
                ),
        ) && responseCreate()

    companion object {
        private const val TAG = "WearVoiceClient"
        const val WS_BASE = "wss://api.x.ai/v1/realtime"
        const val DEFAULT_MODEL = "grok-voice-latest"
        const val VOICE_CARINA = "carina"
        const val SAMPLE_RATE = 24_000
        const val SEND_FRAME_MS = 20
        private const val CLIENT_SECRETS_URL = "https://api.x.ai/v1/realtime/client_secrets"

        fun mintAuthToken(apiKey: String): String {
            val key = apiKey.trim()
            if (key.isEmpty()) return ""
            return try {
                val body = JSONObject()
                    .put("expires_after", JSONObject().put("seconds", 300))
                    .toString()
                val req = Request.Builder()
                    .url(CLIENT_SECRETS_URL)
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val http = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(12, TimeUnit.SECONDS)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "client_secrets HTTP ${resp.code}")
                        return key
                    }
                    val json = JSONObject(raw)
                    val token = json.optString("value", "")
                        .ifBlank {
                            json.optJSONObject("client_secret")?.optString("value").orEmpty()
                        }
                        .trim()
                    if (token.isBlank()) key else token
                }
            } catch (e: Exception) {
                Log.w(TAG, "mintAuthToken: ${e.message}")
                key
            }
        }

        fun pcm16ToBase64(pcm: ByteArray): String =
            Base64.encodeToString(pcm, Base64.NO_WRAP)

        fun base64ToPcm16(b64: String): ByteArray =
            Base64.decode(b64, Base64.DEFAULT)

        fun isBenignCancelError(message: String): Boolean {
            val m = message.lowercase()
            return m.contains("no active response") ||
                m.contains("response_cancel_not_active") ||
                m.contains("cancellation failed")
        }

        fun resamplePcm16Mono(
            input: ByteArray,
            inputBytes: Int,
            fromRate: Int,
            toRate: Int,
        ): ByteArray {
            val inSamples = (inputBytes / 2).coerceAtLeast(0)
            if (inSamples == 0) return ByteArray(0)
            if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) {
                return if (inputBytes == input.size) input.copyOf() else input.copyOf(inputBytes and 0.inv())
            }
            val outSamples = max(1, (inSamples.toLong() * toRate / fromRate).toInt())
            val out = ByteArray(outSamples * 2)
            val ratio = fromRate.toDouble() / toRate.toDouble()
            var o = 0
            for (i in 0 until outSamples) {
                val srcPos = i * ratio
                val i0 = srcPos.toInt().coerceIn(0, inSamples - 1)
                val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
                val frac = (srcPos - i0).toFloat()
                val s0 = readLe16(input, i0 * 2)
                val s1 = readLe16(input, i1 * 2)
                val g = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[o] = (g and 0xff).toByte()
                out[o + 1] = ((g shr 8) and 0xff).toByte()
                o += 2
            }
            return out
        }

        private fun readLe16(buf: ByteArray, offset: Int): Int {
            val lo = buf[offset].toInt() and 0xff
            val hi = buf[offset + 1].toInt()
            return ((hi shl 8) or lo).toShort().toInt()
        }
    }
}
