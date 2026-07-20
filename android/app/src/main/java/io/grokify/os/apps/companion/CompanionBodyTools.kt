package io.grokify.os.apps.companion

import android.util.Log
import io.grokify.os.apps.GrokAssistantVoiceTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side Voice Agent tools that drive the VRM like VRChat controllers
 * (head + hands with gravity). Executed on-device via [CompanionStageHost].
 */
object CompanionBodyTools {
    private const val TAG = "CompanionBodyTools"

    const val TOOL_GESTURE = "body_gesture"
    const val TOOL_SET_HANDS = "set_hands"
    const val TOOL_LOOK = "look_at"
    const val TOOL_RESET = "reset_body"

    private val GESTURES = listOf(
        "wave",
        "nod",
        "shake_head",
        "point",
        "shrug",
        "think",
        "clap",
        "cheer",
        "bow",
        "lean_in",
        "hands_on_hips",
        "crossed_arms",
        "hello",
        "yes",
        "no",
        "celebrate",
        "reset",
    )

    fun sessionTools(): JSONArray {
        val tools = JSONArray()
        tools.put(gestureTool())
        tools.put(setHandsTool())
        tools.put(lookTool())
        tools.put(resetTool())
        return tools
    }

    fun toolInstructions(): String = buildString {
        append(
            "You inhabit a 3D VRM avatar in a small stage with gravity, driven like a " +
                "VRChat body (virtual headset + hand controllers). ",
        )
        append(
            "ALWAYS speak your reply aloud first. Body tools are optional garnish — " +
                "never answer with only a tool call and no speech. ",
        )
        append(
            "When gesturing, call at most one short body_gesture in the same turn as speech " +
                "(wave on greetings, nod when agreeing, think when pondering). ",
        )
        append(
            "body_gesture: wave, nod, shake_head, point, shrug, think, clap, cheer, bow, " +
                "lean_in, hands_on_hips, crossed_arms, hello, yes, no, celebrate, reset. ",
        )
        append(
            "set_hands places left/right controllers in avatar space " +
                "(x right+, y up+, z forward+; rest ~ ±0.2, 0.75, 0.1). ",
        )
        append("look_at aims gaze (x/y -1..1). reset_body returns to soft hang rest. ")
        append("Tools run instantly on-device and do not replace spoken audio.")
    }

    fun isBodyTool(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == TOOL_GESTURE || n == TOOL_SET_HANDS || n == TOOL_LOOK || n == TOOL_RESET
    }

    fun execute(call: GrokAssistantVoiceTools.FunctionCall): GrokAssistantVoiceTools.FunctionResult {
        return when (call.name.trim()) {
            TOOL_GESTURE -> execGesture(call)
            TOOL_SET_HANDS -> execSetHands(call)
            TOOL_LOOK -> execLook(call)
            TOOL_RESET -> execReset(call)
            else -> err(call.callId, "unknown_function", call.name)
        }
    }

    private fun gestureTool(): JSONObject {
        val props = JSONObject()
            .put(
                "gesture",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "One of: ${GESTURES.joinToString(", ")}",
                    ),
            )
            .put(
                "side",
                JSONObject()
                    .put("type", "string")
                    .put("description", "left | right | both (for wave/point)"),
            )
            .put(
                "intensity",
                JSONObject()
                    .put("type", "number")
                    .put("description", "0.2–1.5 strength (default 1)"),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_GESTURE)
            .put(
                "description",
                "Play a full-body VR gesture on the companion avatar " +
                    "(hand controllers + head). Use while speaking to act natural.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray().put("gesture")),
            )
    }

    private fun setHandsTool(): JSONObject {
        val handVec = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("x", JSONObject().put("type", "number"))
                    .put("y", JSONObject().put("type", "number"))
                    .put("z", JSONObject().put("type", "number")),
            )
        val props = JSONObject()
            .put("left", handVec)
            .put("right", handVec)
            .put(
                "hand",
                JSONObject()
                    .put("type", "string")
                    .put("description", "left or right when setting a single hand"),
            )
            .put("x", JSONObject().put("type", "number"))
            .put("y", JSONObject().put("type", "number"))
            .put("z", JSONObject().put("type", "number"))
            .put(
                "hold_sec",
                JSONObject()
                    .put("type", "number")
                    .put("description", "Seconds to hold before gravity returns hands to rest"),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_SET_HANDS)
            .put(
                "description",
                "Place virtual VR hand controller(s) in avatar-local space. " +
                    "After hold_sec, gravity springs them back to rest A-pose.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props),
            )
    }

    private fun lookTool(): JSONObject {
        val props = JSONObject()
            .put(
                "x",
                JSONObject()
                    .put("type", "number")
                    .put("description", "Yaw look -1 (left) .. 1 (right)"),
            )
            .put(
                "y",
                JSONObject()
                    .put("type", "number")
                    .put("description", "Pitch look -1 (down) .. 1 (up)"),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_LOOK)
            .put("description", "Aim the avatar's gaze (virtual headset look target).")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray().put("x").put("y")),
            )
    }

    private fun resetTool(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", TOOL_RESET)
            .put(
                "description",
                "Reset avatar body to soft A-pose rest (unlock hands, clear gesture).",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()),
            )

    private fun execGesture(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        val args = parseArgs(call.argumentsJson)
        val gesture = args.optString("gesture", "").trim().ifBlank {
            args.optString("name", "").trim()
        }
        if (gesture.isEmpty()) return err(call.callId, "missing_gesture")
        val side = args.optString("side", "right").ifBlank { "right" }
        val intensity = args.optDouble("intensity", 1.0)
        runCatching { Log.i(TAG, "body_gesture=$gesture side=$side intensity=$intensity") }
        CompanionStageHost.playGesture(gesture, intensity, side)
        return ok(
            call.callId,
            JSONObject()
                .put("gesture", gesture)
                .put("side", side)
                .put("intensity", intensity)
                .put("stage", CompanionStageHost.isAttached()),
        )
    }

    private fun execSetHands(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        val args = parseArgs(call.argumentsJson)
        if (!args.has("left") && !args.has("right") && !args.has("hand")) {
            return err(call.callId, "missing_hands")
        }
        runCatching { Log.i(TAG, "set_hands ${args.toString().take(120)}") }
        CompanionStageHost.setHands(args)
        return ok(call.callId, JSONObject().put("applied", true).put("stage", CompanionStageHost.isAttached()))
    }

    private fun execLook(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        val args = parseArgs(call.argumentsJson)
        val x = args.optDouble("x", 0.0)
        val y = args.optDouble("y", 0.0)
        CompanionStageHost.setLook(x, y)
        return ok(call.callId, JSONObject().put("x", x).put("y", y))
    }

    private fun execReset(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        CompanionStageHost.resetBody()
        return ok(call.callId, JSONObject().put("reset", true))
    }

    private fun parseArgs(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse { JSONObject() }

    private fun ok(callId: String, data: JSONObject): GrokAssistantVoiceTools.FunctionResult =
        GrokAssistantVoiceTools.FunctionResult(
            callId = callId,
            outputJson = data.put("ok", true).toString(),
        )

    private fun err(
        callId: String,
        error: String,
        detail: String? = null,
    ): GrokAssistantVoiceTools.FunctionResult {
        val o = JSONObject().put("ok", false).put("error", error)
        if (!detail.isNullOrBlank()) o.put("detail", detail)
        return GrokAssistantVoiceTools.FunctionResult(callId = callId, outputJson = o.toString())
    }
}
