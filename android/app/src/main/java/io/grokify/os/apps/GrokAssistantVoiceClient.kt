package io.grokify.os.apps

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
import kotlin.math.min

/**
 * Low-level WebSocket client for xAI Voice Agent Realtime API.
 *
 * @see <a href="https://docs.x.ai/developers/model-capabilities/audio/voice-agent">Voice Agent docs</a>
 */
class GrokAssistantVoiceClient(
    private val onEvent: (JSONObject) -> Unit,
    private val onBinaryAudio: (ByteArray) -> Unit = {},
    private val onState: (connected: Boolean, detail: String?) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Faster fail on bad networks — Companion shows "retry" sooner.
        .connectTimeout(12, TimeUnit.SECONDS)
        .build()

    private val socket = AtomicReference<WebSocket?>(null)
    private val intentionalClose = AtomicBoolean(false)
    private val sessionConfigured = AtomicBoolean(false)
    /** True only after WebSocket onOpen — [socket] is set earlier and is not enough. */
    private val opened = AtomicBoolean(false)

    /** Socket object exists (may still be handshaking). */
    val isConnected: Boolean get() = socket.get() != null

    /** Handshake finished — safe to send session.update. */
    val isOpen: Boolean get() = opened.get() && socket.get() != null

    val isSessionReady: Boolean get() = sessionConfigured.get() && isOpen

    /**
     * @param conversationId optional xAI conversation id for [Session Resumption]
     *   (`?conversation_id=`). Requires `resumption.enabled` on session.update.
     */
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
                        // Docs: session.created is pre-config; only session.updated is ready.
                        if (type == "session.updated") {
                            sessionConfigured.set(true)
                            Log.i(TAG, "session.updated — session ready")
                        } else if (type == "session.created") {
                            Log.d(TAG, "session.created (defaults; awaiting session.update)")
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
                    val bodyHint = runCatching {
                        response?.body?.string()?.take(120)
                    }.getOrNull().orEmpty()
                    val msg = when {
                        response != null && bodyHint.isNotBlank() ->
                            "HTTP ${response.code}: ${bodyHint.take(80)}"
                        response != null ->
                            "HTTP ${response.code}: ${t.message ?: "failed"}"
                        else -> t.message ?: "connection failed"
                    }
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

    fun sendBinary(pcm: ByteArray): Boolean {
        val ws = socket.get() ?: return false
        if (pcm.isEmpty()) return true
        return ws.send(ByteString.of(*pcm))
    }

    fun sessionUpdate(
        instructions: String,
        voice: String,
        tools: JSONArray,
        sampleRate: Int = SAMPLE_RATE,
        /**
         * Wire path for PCM. Docs default is "json" (base64 in
         * response.output_audio.delta / input_audio_buffer.append). Official
         * xAI cookbook uses JSON only. Binary is optional and strict on output.
         */
        useBinaryAudio: Boolean = false,
        /**
         * xAI default is "high" (long silent reasoning). Pass "none" for snappy voice.
         * Supported on grok-voice-latest / grok-voice-think-fast-1.0.
         */
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
                    // grok-transcribe enables live
                    // conversation.item.input_audio_transcription.updated captions
                    .put("model", "grok-transcribe")
                    .put("language_hint", "en")
                    .put("keyterms", defaultKeyterms()),
            )
        val output = JSONObject()
            .put(
                "format",
                JSONObject().put("type", "audio/pcm").put("rate", sampleRate),
            )
        // Only set transport when requesting binary — omit so server uses docs
        // default "json" (matches cookbook). Output transport is strict.
        if (useBinaryAudio) {
            input.put("transport", "binary")
            output.put("transport", "binary")
        }
        val audio = JSONObject()
            .put("input", input)
            .put("output", output)
        val effort = reasoningEffort.trim().lowercase().let {
            if (it == "high") "high" else "none"
        }
        val session = JSONObject()
            .put("instructions", instructions)
            .put("voice", voice.ifBlank { "eve" })
            .put("tools", tools)
            .put("reasoning", JSONObject().put("effort", effort))
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    // Docs default threshold=0.85 / silence often ends turns on a breath.
                    // Mid threshold + longer silence lets natural pauses finish the thought
                    // without feeling unresponsive after a real stop.
                    .put("threshold", VAD_THRESHOLD)
                    .put("silence_duration_ms", VAD_SILENCE_MS)
                    .put("prefix_padding_ms", VAD_PREFIX_PADDING_MS),
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

    /** Drop any uncommitted mic audio (call when muting to avoid echo). */
    fun clearInputAudioBuffer(): Boolean =
        sendJson(JSONObject().put("type", "input_audio_buffer.clear"))

    fun createUserText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val ok = sendJson(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_text")
                                    .put("text", trimmed),
                            ),
                        ),
                ),
        )
        return ok && responseCreate()
    }

    fun responseCreate(): Boolean =
        sendJson(JSONObject().put("type", "response.create"))

    fun sendFunctionOutput(result: GrokAssistantVoiceTools.FunctionResult): Boolean {
        val out = GrokAssistantVoiceTools.functionOutputEvent(result)
        return sendJson(out)
    }

    fun cancelResponse(): Boolean =
        sendJson(JSONObject().put("type", "response.cancel"))

    companion object {
        private const val TAG = "GrokAssistantVoiceClient"

        /**
         * Server errors that mean "nothing to cancel" — benign races, not session faults.
         * xAI often auto-cancels on barge-in; a second response.cancel fails with this.
         */
        fun isBenignRealtimeCancelError(message: String): Boolean {
            val m = message.lowercase()
            if (m.contains("no active response")) return true
            if (m.contains("response_cancel_not_active")) return true
            if (m.contains("cancellation failed")) return true
            return false
        }
        const val WS_BASE = "wss://api.x.ai/v1/realtime"
        const val DEFAULT_MODEL = "grok-voice-latest"
        /** Wire sample rate we declare + send after client-side resample. */
        const val SAMPLE_RATE = 24_000
        /** xAI cookbook uses 20ms PCM frames for smooth VAD / ASR. */
        const val SEND_FRAME_MS = 20
        const val CLIENT_SECRETS_URL = "https://api.x.ai/v1/realtime/client_secrets"
        /**
         * Server VAD threshold (docs default 0.85). Too low (≤0.6) fires on
         * speaker ring-out after TTS → phantom second turns. Too high misses quiet speech.
         */
        const val VAD_THRESHOLD = 0.75
        /**
         * End-of-turn silence (docs max 10_000). Short values cut mid-sentence on
         * natural pauses → multiple user bubbles + premature Thinking. 3.6s still
         * feels responsive after a real stop while letting people think mid-thought.
         */
        const val VAD_SILENCE_MS = 3_600
        /**
         * Audio before VAD speech start. Docs default 333ms; slightly more
         * protects first syllables that ASR otherwise mangles.
         */
        const val VAD_PREFIX_PADDING_MS = 600

        fun defaultKeyterms(): JSONArray =
            JSONArray()
                .put("Grok")
                .put("Grokify")
                .put("GrokifyOS")
                .put("SpaceXAI")
                .put("xAI")
                .put("Okay Grok")
                .put("Hey Grok")
                .put("Build")
                .put("Grok Build")
                .put("Spotify")
                .put("Bluetooth")
                .put("Wi-Fi")
                .put("Android")
                .put("Samsung")

        /**
         * Linear-interpolation downsample of little-endian PCM16 mono.
         * Used when the device only opens at 48 kHz (common on Samsung) so we
         * don't rely on the HAL's often-poor resampler for ASR accuracy.
         */
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
                val frac = srcPos - i0
                val s0 = readLe16(input, i0 * 2)
                val s1 = readLe16(input, i1 * 2)
                val mixed = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[o++] = (mixed and 0xff).toByte()
                out[o++] = ((mixed shr 8) and 0xff).toByte()
            }
            return out
        }

        /** Soft gain so quiet mics still hit ASR / VAD without hard clipping. */
        fun softGainPcm16(pcm: ByteArray, targetPeak: Int = 12_000, maxGain: Float = 3.5f): ByteArray {
            if (pcm.size < 2) return pcm
            var peak = 1
            var i = 0
            while (i + 1 < pcm.size) {
                val s = kotlin.math.abs(readLe16(pcm, i))
                if (s > peak) peak = s
                i += 2
            }
            if (peak >= targetPeak) return pcm
            val gain = min(maxGain, targetPeak.toFloat() / peak.toFloat())
            if (gain <= 1.05f) return pcm
            val out = ByteArray(pcm.size)
            i = 0
            while (i + 1 < pcm.size) {
                val s = readLe16(pcm, i)
                val g = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i] = (g and 0xff).toByte()
                out[i + 1] = ((g shr 8) and 0xff).toByte()
                i += 2
            }
            return out
        }

        private fun readLe16(buf: ByteArray, offset: Int): Int {
            val lo = buf[offset].toInt() and 0xff
            val hi = buf[offset + 1].toInt()
            return ((hi shl 8) or lo).toShort().toInt()
        }

        /**
         * Mint a short-lived client secret (docs: POST /v1/realtime/client_secrets).
         * Response: `{ "value": "xai-realtime-client-secret-…", "expires_at": … }`.
         * Falls back to the API key itself for native Bearer auth if mint fails.
         */
        fun mintAuthToken(apiKey: String, http: OkHttpClient = defaultHttp()): String {
            val key = apiKey.trim()
            if (key.isEmpty()) return ""
            return try {
                // Docs: expires_after.seconds max 3600; 300s is plenty for connect.
                val body = JSONObject()
                    .put("expires_after", JSONObject().put("seconds", 300))
                    .toString()
                val req = Request.Builder()
                    .url(CLIENT_SECRETS_URL)
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "client_secrets HTTP ${resp.code}: ${raw.take(200)}")
                        return key // fallback: use API key on WS (native/server-style)
                    }
                    val json = JSONObject(raw)
                    // Canonical: { value, expires_at }. Also accept nested shapes.
                    val token = json.optString("value", "")
                        .ifBlank {
                            json.optJSONObject("client_secret")?.optString("value").orEmpty()
                        }
                        .ifBlank { json.optString("secret", "") }
                        .ifBlank {
                            val nested = json.opt("client_secret")
                            if (nested is String) nested else ""
                        }
                        .trim()
                    if (token.isBlank()) {
                        Log.w(TAG, "client_secrets missing value — using API key")
                        key
                    } else {
                        Log.i(
                            TAG,
                            "minted client_secret len=${token.length} " +
                                "expires_at=${json.optLong("expires_at", 0L)}",
                        )
                        token
                    }
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

        private fun defaultHttp(): OkHttpClient =
            OkHttpClient.Builder()
                // Mint is a short POST — don't burn 20–30s before falling back to API key.
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .build()
    }
}
