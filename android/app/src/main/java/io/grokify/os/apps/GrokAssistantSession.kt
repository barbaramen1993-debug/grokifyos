package io.grokify.os.apps

import android.content.Context
import io.grokify.os.apps.plugin.HostAiClient
import org.json.JSONObject

/**
 * Shared send pipeline for the Grok Assistant pane and floating overlay.
 * Thread-safe enough for a single in-flight request via [busy] CAS-style flag.
 */
object GrokAssistantSession {
    @Volatile
    private var busyFlag: Boolean = false

    val isBusy: Boolean get() = busyFlag

    data class SendResult(
        val ok: Boolean,
        val replyText: String? = null,
        val errorText: String? = null,
    )

    /**
     * Appends the user message (unless [userAlreadyAppended]), calls Grok Build,
     * appends assistant/error, optionally speaks. Returns after persistence is updated.
     */
    suspend fun send(
        ctx: Context,
        userText: String,
        userAlreadyAppended: Boolean = false,
    ): SendResult {
        val appCtx = ctx.applicationContext
        val store = GrokAssistantStore(appCtx)
        if (!store.enabled) {
            return SendResult(ok = false, errorText = "Assistant is off — enable in Setup")
        }
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            return SendResult(ok = false, errorText = "empty")
        }
        if (busyFlag) {
            return SendResult(ok = false, errorText = "busy")
        }
        busyFlag = true
        return try {
            if (!userAlreadyAppended) {
                store.appendMessage("user", trimmed)
            }
            val system = store.systemPrompt()
            // Transcript already includes the current user turn — exclude it from "prior".
            val prior = store.transcript().dropLast(1)
            val history = AssistantTranscript.formatHistoryForPrompt(
                AssistantTranscript.historyWindow(prior),
            )
            val promptBody = buildString {
                if (history.isNotBlank()) {
                    append("Recent conversation:\n")
                    append(history)
                    append("\n\n")
                }
                append(trimmed)
            }
            val options = JSONObject()
                .put("system", system)
                .put("session_title", "Grok Assistant")
                .toString()
            val raw = HostAiClient.complete(appCtx, promptBody, options)
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (json.optBoolean("ok", false)) {
                val reply = json.optString("text", "").trim()
                    .ifBlank { json.optString("content", "").trim() }
                if (reply.isBlank()) {
                    store.appendMessage("error", "Empty reply — try again")
                    SendResult(ok = false, errorText = "Empty reply — try again")
                } else {
                    store.appendMessage("assistant", reply)
                    if (store.speakReplies) {
                        val speakOpts = JSONObject()
                            .put("voice_id", store.voiceId)
                            .put("prefer_device", store.preferDeviceTts)
                            .put("language", "en")
                            .toString()
                        runCatching { HostAiClient.speak(appCtx, reply, speakOpts) }
                    }
                    SendResult(ok = true, replyText = reply)
                }
            } else {
                val err = json.optString("error", "request_failed")
                val hint = json.optString("hint", "")
                val msg = listOf(err, hint).filter { it.isNotBlank() }.joinToString(" — ")
                store.appendMessage("error", msg)
                SendResult(ok = false, errorText = msg)
            }
        } catch (e: Exception) {
            val msg = e.message ?: "send_failed"
            runCatching { store.appendMessage("error", msg) }
            SendResult(ok = false, errorText = msg)
        } finally {
            busyFlag = false
        }
    }
}
