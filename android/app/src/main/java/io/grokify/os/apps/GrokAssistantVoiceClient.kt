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
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private val socket = AtomicReference<WebSocket?>(null)
    private val intentionalClose = AtomicBoolean(false)
    private val sessionConfigured = AtomicBoolean(false)

    val isConnected: Boolean get() = socket.get() != null
    val isSessionReady: Boolean get() = sessionConfigured.get() && isConnected

    fun connect(authToken: String, model: String = DEFAULT_MODEL) {
        intentionalClose.set(true)
        socket.getAndSet(null)?.close(1000, "reconnect")
        intentionalClose.set(false)
        sessionConfigured.set(false)

        val url = "$WS_BASE?model=${model.trim().ifBlank { DEFAULT_MODEL }}"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${authToken.trim()}")
            .build()

        val ws = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onState(true, null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val event = JSONObject(text)
                        if (event.optString("type") == "session.updated" ||
                            event.optString("type") == "session.created"
                        ) {
                            sessionConfigured.set(true)
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
                    sessionConfigured.set(false)
                    if (!intentionalClose.get()) {
                        onState(false, "Closed $code${if (reason.isNotBlank()) ": $reason" else ""}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sessionConfigured.set(false)
                    if (intentionalClose.get()) return
                    val msg = when {
                        response != null -> "HTTP ${response.code}: ${t.message ?: "failed"}"
                        else -> t.message ?: "connection failed"
                    }
                    onState(false, msg)
                }
            },
        )
        socket.set(ws)
    }

    fun disconnect() {
        intentionalClose.set(true)
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
        useBinaryAudio: Boolean = true,
        /**
         * xAI default is "high" (long silent reasoning). Pass "none" for snappy voice.
         * Supported on grok-voice-latest / grok-voice-think-fast-1.0.
         */
        reasoningEffort: String = "none",
    ): Boolean {
        val audio = JSONObject()
            .put(
                "input",
                JSONObject()
                    .put(
                        "format",
                        JSONObject().put("type", "audio/pcm").put("rate", sampleRate),
                    )
                    .put("transport", if (useBinaryAudio) "binary" else "json")
                    .put(
                        "transcription",
                        JSONObject()
                            // grok-transcribe enables live
                            // conversation.item.input_audio_transcription.updated captions
                            .put("model", "grok-transcribe")
                            .put("language_hint", "en")
                            .put(
                                "keyterms",
                                JSONArray()
                                    .put("Grok")
                                    .put("GrokifyOS")
                                    .put("SpaceXAI")
                                    .put("xAI")
                                    .put("Okay Grok"),
                            ),
                    ),
            )
            .put(
                "output",
                JSONObject()
                    .put(
                        "format",
                        JSONObject().put("type", "audio/pcm").put("rate", sampleRate),
                    )
                    .put("transport", if (useBinaryAudio) "binary" else "json"),
            )
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
                    // Higher = less echo/self-trigger while speaker is hot.
                    .put("threshold", 0.9)
                    // Slightly longer silence so natural pauses don't end the turn early.
                    .put("silence_duration_ms", 900)
                    .put("prefix_padding_ms", 300),
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
        const val WS_BASE = "wss://api.x.ai/v1/realtime"
        const val DEFAULT_MODEL = "grok-voice-latest"
        const val SAMPLE_RATE = 24_000
        const val CLIENT_SECRETS_URL = "https://api.x.ai/v1/realtime/client_secrets"

        /**
         * Mint a short-lived client secret from a SpaceXAI / xAI API key.
         * Falls back to returning the API key itself if minting fails (server-style auth).
         */
        fun mintAuthToken(apiKey: String, http: OkHttpClient = defaultHttp()): String {
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
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "client_secrets HTTP ${resp.code}: ${raw.take(200)}")
                        return key // fallback: use API key on WS
                    }
                    val json = JSONObject(raw)
                    // Response shapes: {value}, {client_secret:{value}}, {secret}
                    val token = json.optString("value", "")
                        .ifBlank {
                            json.optJSONObject("client_secret")?.optString("value").orEmpty()
                        }
                        .ifBlank { json.optString("secret", "") }
                        .ifBlank { json.optString("client_secret", "") }
                        .trim()
                    if (token.isBlank()) {
                        Log.w(TAG, "client_secrets missing value — using API key")
                        key
                    } else {
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
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}
