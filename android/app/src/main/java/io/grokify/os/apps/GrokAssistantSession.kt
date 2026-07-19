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
            handleReply(store, appCtx, raw)
        } catch (e: Exception) {
            val msg = e.message ?: "send_failed"
            runCatching { store.appendMessage("error", msg) }
            SendResult(ok = false, errorText = msg)
        } finally {
            busyFlag = false
        }
    }

    /**
     * Screen-look path: user message + JPEG crop → SpaceXAI vision (not Grok Build).
     * [userText] may be blank (default prompt is applied).
     */
    suspend fun sendWithImage(
        ctx: Context,
        userText: String,
        imageJpeg: ByteArray,
    ): SendResult {
        val appCtx = ctx.applicationContext
        val store = GrokAssistantStore(appCtx)
        if (!store.enabled) {
            return SendResult(ok = false, errorText = "Assistant is off — enable in Setup")
        }
        if (imageJpeg.isEmpty()) {
            return SendResult(ok = false, errorText = "empty image")
        }
        if (busyFlag) {
            return SendResult(ok = false, errorText = "busy")
        }
        busyFlag = true
        return try {
            val display = userText.trim().ifBlank {
                "Look at my screen and describe what you see. Call out anything important."
            }
            store.appendMessage("user", "🖼 $display")
            val system = buildString {
                append(store.systemPrompt())
                append(
                    "\n\nThe user shared a screenshot (possibly cropped) of their Android screen. " +
                        "Answer from what is visible. Be concise and practical. " +
                        "If text is unreadable, say so.",
                )
            }
            val options = JSONObject()
                .put("system", system)
                .toString()
            val raw = HostAiClient.completeWithImage(appCtx, display, imageJpeg, options)
            handleReply(store, appCtx, raw)
        } catch (e: Exception) {
            val msg = e.message ?: "vision_send_failed"
            runCatching { store.appendMessage("error", msg) }
            SendResult(ok = false, errorText = msg)
        } finally {
            busyFlag = false
        }
    }

    private fun handleReply(
        store: GrokAssistantStore,
        appCtx: Context,
        raw: String,
    ): SendResult {
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (json.optBoolean("ok", false)) {
            val reply = json.optString("text", "").trim()
                .ifBlank { json.optString("content", "").trim() }
            if (reply.isBlank()) {
                store.appendMessage("error", "Empty reply — try again")
                return SendResult(ok = false, errorText = "Empty reply — try again")
            }
            store.appendMessage("assistant", reply)
            if (store.speakReplies) {
                val speakOpts = JSONObject()
                    .put("voice_id", store.voiceId)
                    .put("prefer_device", store.preferDeviceTts)
                    .put("language", "en")
                    .toString()
                runCatching { HostAiClient.speak(appCtx, reply, speakOpts) }
            }
            return SendResult(ok = true, replyText = reply)
        }
        val err = json.optString("error", "request_failed")
        val hint = json.optString("hint", "")
        val msg = listOf(err, hint).filter { it.isNotBlank() }.joinToString(" — ")
        store.appendMessage("error", msg)
        return SendResult(ok = false, errorText = msg)
    }
}
