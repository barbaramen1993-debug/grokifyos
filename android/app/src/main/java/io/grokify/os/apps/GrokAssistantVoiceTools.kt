package io.grokify.os.apps

import android.content.Context
import android.util.Log
import io.grokify.os.apps.plugin.HostAiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool definitions + client-side handlers for Grok Voice Agent.
 *
 * Server-side tools ([web_search], [x_search]) run on xAI.
 * [prompt_grok_build] is a client function that hands deep work to
 * host Grok Build (same path as typed Chat).
 */
object GrokAssistantVoiceTools {
    private const val TAG = "GrokAssistantVoiceTools"

    const val TOOL_PROMPT_BUILD = "prompt_grok_build"

    /** Max chars returned to the voice model from a Build turn. */
    private const val BUILD_RESULT_CAP = 6_000

    /**
     * Tools list for `session.update`.
     * [devMode] widens the Build tool description so the model is freer to use it.
     */
    fun sessionTools(devMode: Boolean): JSONArray {
        val tools = JSONArray()
        tools.put(JSONObject().put("type", "web_search"))
        tools.put(JSONObject().put("type", "x_search"))
        tools.put(buildCliTool(devMode))
        return tools
    }

    fun buildCliTool(devMode: Boolean): JSONObject {
        val desc = if (devMode) {
            "Hand off to Grok Build (host CLI coding/research agent) on the user's GrokifyOS " +
                "server. Use for multi-step research, code generation/debugging, architecture, " +
                "file/tool planning, or any task that needs agent-style depth. Prefer this over " +
                "shallow answers when the user is in Dev mode. Always pass a complete, " +
                "self-contained prompt with conversation context."
        } else {
            "Hand off to Grok Build (host CLI agent) for deep research, multi-step reasoning, " +
                "coding, or long tool-using tasks when web_search/x_search are not enough. " +
                "Skip for quick chit-chat, weather-style facts you can search, or simple Q&A. " +
                "Always pass a complete, self-contained prompt."
        }
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "prompt",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "Full task for Grok Build. Include relevant conversation context; " +
                                    "do not assume the host agent can hear the voice session.",
                            ),
                    )
                    .put(
                        "reason",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "Short category: research | coding | tools | other",
                            ),
                    ),
            )
            .put("required", JSONArray().put("prompt"))
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_PROMPT_BUILD)
            .put("description", desc)
            .put("parameters", params)
    }

    /**
     * Extra system instructions appended for Voice Agent sessions.
     */
    fun voiceToolInstructions(devMode: Boolean): String = buildString {
        append(
            "You are the on-device Grok Assistant voice for GrokifyOS. " +
                "Speak naturally and concisely. Prefer short spoken answers. " +
                "You have web_search and x_search for live facts. ",
        )
        append(
            "For weather, news, sports, or other live facts: use web_search once, then give " +
                "ONE cohesive spoken answer. Do not open a second reply that apologizes or " +
                "says you could not look something up after you already answered. " +
                "If a search fails, say that once and stop — never answer then retract. ",
        )
        append(
            "You also have prompt_grok_build — a host Grok Build CLI agent for deep research, " +
                "coding, and multi-step work. When you call it, first say a brief wait line " +
                "(e.g. “Let me dig into that…”) then wait for the tool result before summarizing. " +
                "Summarize Build results for speech; do not read long code dumps aloud unless asked. ",
        )
        if (devMode) {
            append(
                "Dev mode is ON: lean on prompt_grok_build for engineering tasks, diffs, and " +
                    "debugging plans. Be precise about what the host agent can and cannot do.",
            )
        } else {
            append(
                "Only use prompt_grok_build when the user needs depth beyond a quick search or " +
                    "conversational answer.",
            )
        }
    }

    data class FunctionCall(
        val name: String,
        val callId: String,
        val argumentsJson: String,
    )

    data class FunctionResult(
        val callId: String,
        val outputJson: String,
    )

    /**
     * Parse a `response.function_call_arguments.done` event into a [FunctionCall].
     */
    fun parseFunctionCallEvent(event: JSONObject): FunctionCall? {
        val name = event.optString("name", "").trim()
        val callId = event.optString("call_id", "").trim()
            .ifBlank { event.optString("callId", "").trim() }
        if (name.isEmpty() || callId.isEmpty()) return null
        val args = when {
            event.has("arguments") && event.opt("arguments") is String ->
                event.optString("arguments", "{}")
            event.optJSONObject("arguments") != null ->
                event.optJSONObject("arguments")!!.toString()
            else -> "{}"
        }
        return FunctionCall(name = name, callId = callId, argumentsJson = args)
    }

    /**
     * Execute a client-side function tool. Unknown names return an error payload.
     */
    fun execute(ctx: Context, call: FunctionCall, store: GrokAssistantStore): FunctionResult {
        return when (call.name) {
            TOOL_PROMPT_BUILD -> executeBuild(ctx, call, store)
            else -> FunctionResult(
                callId = call.callId,
                outputJson = JSONObject()
                    .put("ok", false)
                    .put("error", "unknown_function")
                    .put("name", call.name)
                    .toString(),
            )
        }
    }

    private fun executeBuild(
        ctx: Context,
        call: FunctionCall,
        store: GrokAssistantStore,
    ): FunctionResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
        val prompt = args.optString("prompt", "").trim()
        val reason = args.optString("reason", "").trim()
        if (prompt.isEmpty()) {
            return FunctionResult(
                callId = call.callId,
                outputJson = JSONObject()
                    .put("ok", false)
                    .put("error", "missing_prompt")
                    .toString(),
            )
        }
        Log.i(TAG, "prompt_grok_build reason=${reason.ifBlank { "n/a" }} chars=${prompt.length}")
        // Surface tool use in the transcript for the user.
        runCatching {
            store.appendMessage(
                "system",
                "🔧 Grok Build · ${reason.ifBlank { "task" }}",
            )
        }
        val system = buildString {
            append(store.systemPrompt())
            append(
                "\n\nYou are answering a handoff from the on-device Voice Agent. " +
                    "Return a clear, complete written answer the voice model can summarize aloud. " +
                    "Prefer structured bullets for research; include code fences only when useful.",
            )
        }
        val options = JSONObject()
            .put("system", system)
            .put("session_title", "Grok Assistant · Voice Build")
            .toString()
        val raw = try {
            HostAiClient.complete(ctx.applicationContext, prompt, options)
        } catch (e: Exception) {
            Log.e(TAG, "Build handoff failed", e)
            return FunctionResult(
                callId = call.callId,
                outputJson = JSONObject()
                    .put("ok", false)
                    .put("error", e.message ?: "build_failed")
                    .toString(),
            )
        }
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (!json.optBoolean("ok", false)) {
            val err = json.optString("error", "build_failed")
            val hint = json.optString("hint", "")
            runCatching {
                store.appendMessage(
                    "error",
                    listOf(err, hint).filter { it.isNotBlank() }.joinToString(" — "),
                )
            }
            return FunctionResult(
                callId = call.callId,
                outputJson = JSONObject()
                    .put("ok", false)
                    .put("error", err)
                    .put("hint", hint)
                    .toString(),
            )
        }
        val text = json.optString("text", "").trim()
            .ifBlank { json.optString("content", "").trim() }
        val capped = if (text.length > BUILD_RESULT_CAP) {
            text.take(BUILD_RESULT_CAP) + "\n…[truncated for voice]"
        } else {
            text
        }
        // Keep a short note in chat history (not the full dump unless short).
        runCatching {
            val preview = if (capped.length > 400) capped.take(400) + "…" else capped
            store.appendMessage("assistant", "Build result:\n$preview")
        }
        return FunctionResult(
            callId = call.callId,
            outputJson = JSONObject()
                .put("ok", true)
                .put("text", capped)
                .put("provider", json.optString("provider", "grok-build"))
                .put("model", json.optString("model", ""))
                .put("reason", reason)
                .toString(),
        )
    }

    /** Wire format for `conversation.item.create` with function_call_output. */
    fun functionOutputEvent(result: FunctionResult): JSONObject =
        JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", result.callId)
                    .put("output", result.outputJson),
            )
}
