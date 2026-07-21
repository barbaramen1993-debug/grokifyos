package io.grokify.os.chat

import io.grokify.os.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket client for the GrokifyOS chat bridge (same protocol as the web dashboard).
 */
class BridgeClient(
    private val onEvent: (JSONObject) -> Unit,
    private val onState: (connected: Boolean, detail: String?) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private val socket = AtomicReference<WebSocket?>(null)
    @Volatile private var intentionalClose = false

    fun connect(wsToken: String, wsUrl: String = BuildConfig.WS_URL) {
        // Silent close so reconnect does not flip UI / schedule another reconnect loop.
        intentionalClose = true
        socket.getAndSet(null)?.close(1000, "reconnect")
        intentionalClose = false

        val url = buildWsUrl(wsUrl, wsToken)
        if (url == null) {
            onState(false, "Invalid WS URL")
            return
        }
        val req = Request.Builder().url(url).build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState(true, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    onEvent(JSONObject(text))
                } catch (_: Exception) {
                    // ignore malformed frames
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalClose) {
                    onState(false, "Closed $code${if (reason.isNotBlank()) ": $reason" else ""}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (intentionalClose) return
                val code = response?.code
                val msg = when {
                    code != null -> "HTTP $code: ${t.message ?: "failed"}"
                    else -> t.message ?: "connection failed"
                }
                onState(false, msg)
            }
        })
        socket.set(ws)
    }

    fun disconnect(notify: Boolean = true) {
        intentionalClose = true
        socket.getAndSet(null)?.close(1000, "bye")
        if (notify) onState(false, null)
    }

    fun isConnected(): Boolean = socket.get() != null

    /**
     * @param images optional ACP-style image attachments for vision analysis:
     *   each map/JSONObject should have `data` (base64) and `mimeType` (e.g. image/jpeg).
     */
    fun sendPrompt(
        prompt: String,
        sessionId: String,
        model: String = "",
        notes: List<String> = emptyList(),
        history: List<JSONObject> = emptyList(),
        images: List<JSONObject> = emptyList(),
    ): Boolean {
        val ws = socket.get() ?: return false
        // Same payload shape as assets/system-chat.js (+ optional images for multimodal)
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("session_id", sessionId)
            .put("model", model)
        if (notes.isNotEmpty()) {
            payload.put("notes", org.json.JSONArray(notes))
        }
        if (history.isNotEmpty()) {
            payload.put("history", org.json.JSONArray(history))
        }
        if (images.isNotEmpty()) {
            payload.put("images", org.json.JSONArray(images))
        }
        return ws.send(payload.toString())
    }

    fun reconnect(sessionId: String): Boolean {
        val ws = socket.get() ?: return false
        return ws.send(
            JSONObject()
                .put("type", "reconnect")
                .put("session_id", sessionId)
                .toString()
        )
    }

    companion object {
        /**
         * Keep trailing slash on path (Apache ProxyPass is `/grokify-ws/`) and
         * URL-encode the token (base64 may contain `+`, `/`, `=`).
         *
         * OkHttp's HttpUrl only parses http/https — map ws/wss first, restore after.
         */
        fun buildWsUrl(base: String, token: String): String? {
            val raw = base.trim().ifBlank { BuildConfig.WS_URL }
            val withSlash = when {
                raw.contains("?") -> raw.substringBefore("?")
                raw.endsWith("/") -> raw
                else -> "$raw/"
            }
            // OkHttp HttpUrl only accepts http/https schemes.
            val httpCompatible = withSlash
                .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
                .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            val parsed = httpCompatible.toHttpUrlOrNull() ?: return null
            return parsed.newBuilder()
                .setQueryParameter("token", token)
                .build()
                .toString()
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
        }
    }
}
