package io.grokify.os.apps.companion

import android.util.Log
import io.grokify.os.apps.GrokAssistantVoiceTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side Voice Agent tools that drive the VRM like VRChat controllers
 * (head + hands with gravity). Executed on-device via [CompanionStageHost].
 *
 * Includes a read tool ([TOOL_OBSERVE]) so the model can see live controller /
 * bone positions before emitting absolute set_hands targets.
 */
object CompanionBodyTools {
    private const val TAG = "CompanionBodyTools"

    const val TOOL_GESTURE = "body_gesture"
    const val TOOL_SET_HANDS = "set_hands"
    const val TOOL_LOOK = "look_at"
    const val TOOL_RESET = "reset_body"
    const val TOOL_OBSERVE = "observe_environment"

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
        tools.put(observeTool())
        tools.put(gestureTool())
        tools.put(setHandsTool())
        tools.put(lookTool())
        tools.put(resetTool())
        return tools
    }

    fun toolInstructions(): String = buildString {
        append(
            "You inhabit a 3D VRM avatar driven like a VR game: virtual HMD (head) + " +
                "left/right wrist controllers (two-bone arm IK + gravity). ",
        )
        append(
            "ALWAYS speak your reply aloud first. Body tools are optional garnish — " +
                "never answer with only a tool call and no speech. ",
        )
        append(
            "CLOSED LOOP / VR SENSE: call observe_environment to read the live scene — " +
                "joints (shoulder/elbow/wrist), bones (id + name + hips-local/world/euler), " +
                "joint_labels and named_joints (user-renamed joints; e.g. leftHand may be " +
                "\"left wand\"), VR controller points + soft hang rest (measured from THIS avatar), " +
                "arm_reach, camera_hips_local (viewer), camera_relative.look_toward_camera, " +
                "and control_schema.examples scaled to this VRM. Use the name fields when the " +
                "user refers to joints by the labels they assigned. ",
        )
        append(
            "For custom poses: observe_environment → plan absolute hips-local {x,y,z} " +
                "from joints.shoulders / vr.rest / arm_reach / camera → set_hands. " +
                "Axes: x right+, y up+, z forward+ (toward viewer is usually +z). " +
                "Hang rest is below the shoulders (soft A, not Y/T). " +
                "Wave is shoulder-high toward the camera — never straight up above the head. " +
                "Never invent human-scale Y (~1.0+); use observed joints/arm_reach for this avatar. " +
                "Runtime clamps set_hands to ~0.88 * max_reach of the shoulder. ",
        )
        append(
            "When the user asks you to move, look, or gesture, call the matching tool " +
                "in the same turn as speech. Prefer body_gesture for named moves " +
                "(wave already faces the camera); look_at direction=camera for the viewer; " +
                "set_hands only for custom controller poses. ",
        )
        append(
            "body_gesture: wave, nod, shake_head, point, shrug, think, clap, cheer, bow, " +
                "lean_in, hands_on_hips, crossed_arms, hello, yes, no, celebrate, reset. ",
        )
        append(
            "look_at: x=-1 LEFT .. 1 RIGHT, y=-1 down .. 1 up, " +
                "or direction left|right|up|down|forward|camera. Holds ~5s unless hold_sec set. ",
        )
        append(
            "set_hands places wrist controller points in avatar space. " +
                "After hold_sec, gravity springs them home to measured hang rest. ",
        )
        append("reset_body returns to soft hang rest. Tools are instant and do not replace speech.")
    }

    fun isBodyTool(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == TOOL_GESTURE ||
            n == TOOL_SET_HANDS ||
            n == TOOL_LOOK ||
            n == TOOL_RESET ||
            n == TOOL_OBSERVE
    }

    fun execute(call: GrokAssistantVoiceTools.FunctionCall): GrokAssistantVoiceTools.FunctionResult {
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.In,
                "tool→",
                call.name,
                call.argumentsJson.take(4_000),
            )
        }
        val result = executeInner(call)
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Out,
                "tool←",
                call.name,
                result.outputJson.take(4_000),
            )
        }
        return result
    }

    private fun executeInner(call: GrokAssistantVoiceTools.FunctionCall): GrokAssistantVoiceTools.FunctionResult {
        return when (call.name.trim()) {
            TOOL_OBSERVE -> execObserve(call)
            TOOL_GESTURE -> execGesture(call)
            TOOL_SET_HANDS -> execSetHands(call)
            TOOL_LOOK -> execLook(call)
            TOOL_RESET -> execReset(call)
            else -> err(call.callId, "unknown_function", call.name)
        }
    }

    private fun observeTool(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", TOOL_OBSERVE)
            .put(
                "description",
                "Read the live VR scene like a game engine: joints (shoulder/elbow/wrist chains), " +
                    "all key bones with id + name (user-editable labels) + hips-local/world/euler, " +
                    "joint_labels map, named_joints (display name → id/local/world), " +
                    "VR head/hand controllers (also named), soft hang rest, arm reach, orbit camera " +
                    "(viewer) in hips-local, look-toward-camera, active gesture, and " +
                    "set_hands examples (wave/point) facing the camera for this avatar. " +
                    "Call before custom set_hands when you need real numbers or user joint names.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "detail",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "description",
                                        "full (default) | compact — compact omits bone worlds " +
                                            "and control_schema examples",
                                    ),
                            ),
                    ),
            )

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
                "Play a full-body VR gesture (hand controllers + head). " +
                    "wave/hello faces the orbit camera (viewer) with palm out — " +
                    "not arm straight up. Use while speaking to act natural.",
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
            .put(
                "return_state",
                JSONObject()
                    .put("type", "boolean")
                    .put(
                        "description",
                        "If true, tool result includes a post-apply observe_environment snapshot",
                    ),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_SET_HANDS)
            .put(
                "description",
                "Place virtual VR hand controller(s) in avatar-local space. " +
                    "Prefer absolute points from observe_environment (vr.rest / examples). " +
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
                    .put("description", "Yaw look -1 (clearly left) .. 1 (clearly right)"),
            )
            .put(
                "y",
                JSONObject()
                    .put("type", "number")
                    .put("description", "Pitch look -1 (down) .. 1 (up)"),
            )
            .put(
                "direction",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "Optional alias: left | right | up | down | forward | camera " +
                            "(camera aims gaze at the viewer/orbit camera)",
                    ),
            )
            .put(
                "hold_sec",
                JSONObject()
                    .put("type", "number")
                    .put("description", "Seconds to hold gaze before idle wander (default 5)"),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_LOOK)
            .put(
                "description",
                "Aim the virtual headset (head turn + eyes). " +
                    "x=-1 looks left, x=1 right; direction=camera looks at the viewer.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props),
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

    private fun execObserve(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        val args = parseArgs(call.argumentsJson)
        val detail = args.optString("detail", "full").trim().lowercase()
        runCatching { Log.i(TAG, "observe_environment detail=$detail") }
        val snap = CompanionStageHost.getBodyState()
        if (detail == "compact" && snap.optBoolean("ok", false)) {
            snap.remove("control_schema")
            val bones = snap.optJSONObject("bones")
            if (bones != null) {
                val keys = bones.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val b = bones.optJSONObject(k) ?: continue
                    b.remove("world")
                }
            }
            snap.remove("environment")
        }
        // Ensure stage flag for tests / UI.
        if (!snap.has("stage")) {
            snap.put("stage", CompanionStageHost.isAttached())
        }
        return GrokAssistantVoiceTools.FunctionResult(
            callId = call.callId,
            outputJson = snap.toString(),
        )
    }

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
        val out = JSONObject()
            .put("applied", true)
            .put("stage", CompanionStageHost.isAttached())
        if (args.optBoolean("return_state", false) && CompanionStageHost.isAttached()) {
            try {
                Thread.sleep(60)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            out.put("state", CompanionStageHost.getBodyState(700))
        }
        return ok(call.callId, out)
    }

    private fun execLook(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        val args = parseArgs(call.argumentsJson)
        val direction = args.optString("direction", "").trim().lowercase()
        if (direction.isNotEmpty() && !args.has("x") && !args.has("y")) {
            when (direction) {
                "left" -> {
                    args.put("x", -1.0)
                    args.put("y", 0.0)
                }
                "right" -> {
                    args.put("x", 1.0)
                    args.put("y", 0.0)
                }
                "up" -> {
                    args.put("x", 0.0)
                    args.put("y", 1.0)
                }
                "down" -> {
                    args.put("x", 0.0)
                    args.put("y", -1.0)
                }
                "forward", "center" -> {
                    args.put("x", 0.0)
                    args.put("y", 0.0)
                }
                "camera", "viewer", "user" -> {
                    // Stage resolves toward orbit camera; keep direction for JS.
                    args.put("direction", "camera")
                }
            }
        }
        if (!args.has("x") && !args.has("y") && direction.isEmpty()) {
            return err(call.callId, "missing_look_target")
        }
        val x = args.optDouble("x", 0.0)
        val y = args.optDouble("y", 0.0)
        // Pass full payload so stage gets hold_sec / direction.
        CompanionStageHost.setLookPayload(args)
        return ok(
            call.callId,
            JSONObject()
                .put("x", x)
                .put("y", y)
                .put("direction", direction)
                .put("stage", CompanionStageHost.isAttached()),
        )
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
