package io.grokify.os.apps.companion

import android.util.Log
import io.grokify.os.apps.GrokAssistantVoiceTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side Voice Agent tools for Companion body control.
 *
 * **Primary path:** [TOOL_BODY_POSE] plays joint-XYZ motion templates rebuilt
 * for the loaded VRM (shoulders / rest / reach / camera). Instant, no bridge.
 *
 * **Secondary:** [TOOL_AI_MOVE] / [TOOL_GESTURE] → [CompanionMovementAgent]
 * (template match first, bridge planner only for novel poses).
 *
 * Direct [TOOL_SET_HANDS] / [TOOL_LOOK] remain for advanced use.
 */
object CompanionBodyTools {
    private const val TAG = "CompanionBodyTools"

    const val TOOL_BODY_POSE = "body_pose"
    const val TOOL_AI_MOVE = "ai_move"
    const val TOOL_GESTURE = "body_gesture"
    const val TOOL_SET_HANDS = "set_hands"
    const val TOOL_LOOK = "look_at"
    const val TOOL_RESET = "reset_body"
    const val TOOL_OBSERVE = "observe_environment"

    /** Latest committed user transcript — used to fill missing wave/point side. */
    @Volatile
    var lastUserTextForSide: String = ""
        private set

    /**
     * True after a [TOOL_GESTURE] runs this user turn. Voice session keyword net
     * skips if the model already drove the body.
     */
    @Volatile
    var gestureToolFiredThisTurn: Boolean = false
        private set

    fun noteUserTranscript(text: String) {
        lastUserTextForSide = text.trim()
        gestureToolFiredThisTurn = false
    }

    fun markGestureToolFired() {
        gestureToolFiredThisTurn = true
    }

    /**
     * Infer wave side from natural language when the model omits tools or `side`.
     * Returns null when no explicit left/right/both is present (do not guess right).
     */
    fun inferWaveSideFromUserText(text: String): String? {
        val t = text.lowercase()
        val wantsWave =
            Regex("""\b(wave|waving|hello)\b""").containsMatchIn(t) ||
                (t.contains("hand") && Regex("""\b(raise|lift|up)\b""").containsMatchIn(t))
        if (!wantsWave) return null
        val left = Regex("""\bleft\b""").containsMatchIn(t)
        val right = Regex("""\bright\b""").containsMatchIn(t)
        val both = Regex("""\bboth\b""").containsMatchIn(t) ||
            (left && right) ||
            Regex("""\bhands\b""").containsMatchIn(t) && !left && !right
        return when {
            both && !left && !right -> "both"
            left && right -> "both"
            left -> "left"
            right -> "right"
            else -> null
        }
    }

    /**
     * Host-side safety net: if the voice model never called a body tool but the
     * user clearly asked for motion, kick the bridge **movement agent**.
     */
    fun maybeKeywordWaveFallback(userText: String = lastUserTextForSide): Boolean {
        if (gestureToolFiredThisTurn) return false
        if (!CompanionMovementAgent.wantsMotion(userText)) return false
        val side = inferWaveSideFromUserText(userText)
        val intent = if (side != null) {
            "User asked for a wave/gesture. Side preference: $side. Perform a natural wave."
        } else {
            "User asked for body motion: ${userText.take(200)}"
        }
        runCatching {
            Log.i(TAG, "keyword motion → movement agent text=${userText.take(80)}")
        }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Sys,
                "keyword",
                "ai_move fallback",
                userText.take(500),
            )
        }
        markGestureToolFired()
        CompanionMovementAgent.requestAsync(
            intent = intent,
            userText = userText,
            source = "keyword_fallback",
        )
        return true
    }

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
        "goodbye",
        "yes",
        "no",
        "celebrate",
        "jump",
        "angry",
        "sad",
        "sleepy",
        "surprised",
        "blush",
        "lookaround",
        "relax",
        "reset",
    )

    fun sessionTools(): JSONArray {
        val tools = JSONArray()
        tools.put(bodyPoseTool())
        tools.put(aiMoveTool())
        tools.put(observeTool())
        tools.put(gestureTool())
        tools.put(setHandsTool())
        tools.put(lookTool())
        tools.put(resetTool())
        return tools
    }

    fun toolInstructions(): String = buildString {
        append(
            "YOU are the voice agent. Body motion prefers PORTABLE VRMA CLIPS that retarget " +
                "to ANY loaded VRM humanoid (relative joint animation). " +
                "Fallback: joint-XYZ templates measured for this avatar. " +
                "You do NOT invent joint meters.\n",
        )
        append("ALWAYS speak your reply aloud in the same turn as any body tool.\n")
        append(
            "MOVEMENT RULES (mandatory):\n" +
                "1. PREFERRED: body_pose({pose:\"wave\"}) — wave/hello/goodbye, clap, think, " +
                "cheer/jump, shrug/relax, angry, sad, sleepy, surprised, blush, lookaround " +
                "play real VRMA on any VRM. Also: point, nod, shake_head, bow, lean_in, " +
                "hands_on_hips, crossed_arms, yes, no.\n" +
                "2. For wave/point pass side left|right|both when the user says it; " +
                "if omitted, host defaults to right.\n" +
                "3. ai_move only for novel / freeform poses not in the catalog.\n" +
                "4. body_gesture is an alias for the same catalog.\n" +
                "5. Do NOT use set_hands unless user demands exact coords AND you observe_environment first.\n" +
                "6. look_at for gaze; reset_body to cancel.\n",
        )
        append(
            "EXAMPLES:\n" +
                "User: Wave your left hand. → body_pose({pose:\"wave\",side:\"left\"}) + speak.\n" +
                "User: Wave. → body_pose({pose:\"wave\",side:\"right\"}) + speak.\n" +
                "User: Clap. → body_pose({pose:\"clap\"}) + speak.\n" +
                "User: Point at me. → body_pose({pose:\"point\",side:\"right\"}) + speak.\n" +
                "User: Jazz hands above your head. → ai_move({intent:\"jazz hands above head\"}) + speak.\n",
        )
        append(
            "observe_environment includes motion_library (VRMA + joint-XYZ) + live joints. " +
                "reset_body: stop VRMA + soft hang rest.",
        )
    }

    fun isBodyTool(name: String): Boolean {
        val n = name.trim().lowercase()
        return n == TOOL_BODY_POSE ||
            n == TOOL_AI_MOVE ||
            n == TOOL_GESTURE ||
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
            TOOL_BODY_POSE -> execBodyPose(call)
            TOOL_AI_MOVE -> execAiMove(call)
            TOOL_OBSERVE -> execObserve(call)
            TOOL_GESTURE -> execGesture(call)
            TOOL_SET_HANDS -> execSetHands(call)
            TOOL_LOOK -> execLook(call)
            TOOL_RESET -> execReset(call)
            else -> err(call.callId, "unknown_function", call.name)
        }
    }

    private fun bodyPoseTool(): JSONObject {
        val poses = CompanionMovementAgent.TEMPLATE_IDS.joinToString(", ")
        val props = JSONObject()
            .put(
                "pose",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "Template id from the joint-XYZ library: $poses",
                    ),
            )
            .put(
                "side",
                JSONObject()
                    .put("type", "string")
                    .put("description", "left | right | both (for wave/point/cheer)"),
            )
            .put(
                "intensity",
                JSONObject()
                    .put("type", "number")
                    .put("description", "0.2–1.5 (default 1)"),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_BODY_POSE)
            .put(
                "description",
                "PREFERRED body control. Plays VRMA clips (portable to any VRM) when available, " +
                    "else joint-XYZ templates from measured shoulders/rest/reach/camera. " +
                    "Use for wave, clap, think, point, nod, shrug, etc. Instant — no planning delay.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray().put("pose")),
            )
    }

    private fun aiMoveTool(): JSONObject {
        val props = JSONObject()
            .put(
                "intent",
                JSONObject()
                    .put("type", "string")
                    .put(
                        "description",
                        "Natural-language motion for novel poses not in body_pose templates. " +
                            "Known moves (wave/point/nod) still match templates first. " +
                            "Example: \"hold both hands out like carrying a tray\"",
                    ),
            )
            .put(
                "side",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Optional left | right | both hint"),
            )
        return JSONObject()
            .put("type", "function")
            .put("name", TOOL_AI_MOVE)
            .put(
                "description",
                "Freeform / novel body motion. Prefers joint-XYZ templates when intent matches; " +
                    "otherwise plans via host bridge from live joints. Prefer body_pose for " +
                    "wave/point/nod/shrug/etc.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray().put("intent")),
            )
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
                    "Includes motion_library catalog of joint-XYZ templates for this avatar. " +
                    "Call before custom set_hands when you need real numbers.",
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
                    .put(
                        "description",
                        "REQUIRED for wave, point, hello: left | right | both. " +
                            "Omitting side returns side_required error.",
                    ),
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
                "Named gesture → joint-XYZ template (same as body_pose). " +
                    "Prefer body_pose. For wave/point/hello pass side when known. " +
                    "Example: {\"gesture\":\"wave\",\"side\":\"left\"}.",
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
                "Set absolute wrist controller positions in hips-local meters. " +
                    "ALWAYS derive numbers from a prior observe_environment call " +
                    "(measured shoulder + arm_reach of this VRM). " +
                    "Never invent absolute coordinates from human scale. " +
                    "Prefer vr.rest / control_schema.examples. " +
                    "After hold_sec, gravity springs them back to measured soft hang rest.",
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

    private val ASYMMETRIC_GESTURES = setOf("wave", "point", "hello")

    private fun execBodyPose(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        markGestureToolFired()
        val args = parseArgs(call.argumentsJson)
        val pose = args.optString("pose", "").trim().ifBlank {
            args.optString("name", "").trim().ifBlank {
                args.optString("template", "").trim().ifBlank {
                    args.optString("gesture", "").trim()
                }
            }
        }.lowercase().replace(Regex("""[\s-]+"""), "_")
        if (pose.isEmpty()) return err(call.callId, "missing_pose")
        if (pose == "reset" || pose == "reset_body" || pose == "idle") {
            CompanionMovementAgent.cancel()
            CompanionStageHost.stopVrma()
            CompanionStageHost.resetBody()
            return ok(call.callId, JSONObject().put("reset", true).put("mode", "rest"))
        }
        val sideRaw = args.optString("side", "").trim().lowercase()
        var side = when (sideRaw) {
            "left", "l" -> "left"
            "right", "r" -> "right"
            "both", "all" -> "both"
            else -> ""
        }
        var sideSource = if (side.isNotEmpty()) "arg" else "default"
        if (pose in ASYMMETRIC_GESTURES || pose.startsWith("wave") || pose.startsWith("point")) {
            if (side.isEmpty()) {
                val inferred = inferWaveSideFromUserText(lastUserTextForSide)
                if (inferred != null) {
                    side = inferred
                    sideSource = "user_text"
                } else if (pose.contains("left")) {
                    side = "left"
                } else if (pose.contains("right")) {
                    side = "right"
                } else if (pose.contains("both")) {
                    side = "both"
                } else {
                    side = "right"
                    sideSource = "default_right"
                }
            }
        }
        val intensity = args.optDouble("intensity", 1.0).coerceIn(0.2, 1.5)
        val resolvedSide = side
        runCatching {
            Log.i(TAG, "body_pose pose=$pose side=$resolvedSide src=$sideSource")
        }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.In,
                "body_pose",
                "$pose side=$resolvedSide",
                args.toString().take(500),
            )
        }
        // Direct template play — joint XYZ recomputed on stage for this VRM.
        val played = CompanionStageHost.playTemplate(pose, intensity, resolvedSide)
        // Also route through agent so match/logging stays consistent if stage missed.
        if (!played) {
            CompanionMovementAgent.requestAsync(
                intent = "template:$pose side=$resolvedSide",
                userText = lastUserTextForSide,
                source = "tool_body_pose",
            )
        }
        return ok(
            call.callId,
            JSONObject()
                .put("pose", pose)
                .put("side", resolvedSide)
                .put("side_source", sideSource)
                .put("intensity", intensity)
                .put("mode", "vrma_or_joint_xyz")
                .put("played", played)
                .put("stage", CompanionStageHost.isAttached())
                .put(
                    "hint",
                    "Stage prefers VRMA (any VRM) then joint-XYZ templates from measured joints.",
                ),
        )
    }

    private fun execAiMove(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        markGestureToolFired()
        val args = parseArgs(call.argumentsJson)
        val intent = args.optString("intent", "").trim().ifBlank {
            args.optString("goal", "").trim().ifBlank {
                args.optString("description", "").trim()
            }
        }
        if (intent.isEmpty()) return err(call.callId, "missing_intent")
        val sideRaw = args.optString("side", "").trim().lowercase()
        val side = when (sideRaw) {
            "left", "l" -> "left"
            "right", "r" -> "right"
            "both", "all" -> "both"
            else -> CompanionBodyTools.inferWaveSideFromUserText(lastUserTextForSide).orEmpty()
        }
        val fullIntent = if (side.isNotEmpty() && !intent.contains(side, ignoreCase = true)) {
            "$intent (side=$side)"
        } else {
            intent
        }
        runCatching { Log.i(TAG, "ai_move intent=${fullIntent.take(120)}") }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.In,
                "ai_move",
                fullIntent.take(200),
                args.toString().take(500),
            )
        }
        CompanionMovementAgent.requestAsync(
            intent = fullIntent,
            userText = lastUserTextForSide,
            source = "tool_ai_move",
        )
        return ok(
            call.callId,
            JSONObject()
                .put("queued", true)
                .put("mode", "movement_agent")
                .put("intent", fullIntent)
                .put("side", side)
                .put("stage", CompanionStageHost.isAttached())
                .put(
                    "hint",
                    "Matches joint-XYZ template when possible; else bridge planner. Speak now.",
                ),
        )
    }

    private fun execGesture(
        call: GrokAssistantVoiceTools.FunctionCall,
    ): GrokAssistantVoiceTools.FunctionResult {
        markGestureToolFired()
        val args = parseArgs(call.argumentsJson)
        val gesture = args.optString("gesture", "").trim().ifBlank {
            args.optString("name", "").trim()
        }.lowercase()
        if (gesture.isEmpty()) return err(call.callId, "missing_gesture")
        if (gesture == "reset" || gesture == "reset_body" || gesture == "idle") {
            CompanionStageHost.resetBody()
            CompanionMovementAgent.cancel()
            return ok(call.callId, JSONObject().put("reset", true).put("mode", "reset"))
        }
        val sideRaw = args.optString("side", "").trim().lowercase()
        var side = when (sideRaw) {
            "left", "l" -> "left"
            "right", "r" -> "right"
            "both", "all" -> "both"
            else -> ""
        }
        var sideSource = if (side.isNotEmpty()) "arg" else "missing"
        if (gesture in ASYMMETRIC_GESTURES && side.isEmpty()) {
            val inferred = inferWaveSideFromUserText(lastUserTextForSide)
            if (inferred != null) {
                side = inferred
                sideSource = "user_text"
            } else {
                side = "right"
                sideSource = "default_right"
            }
        }
        val intensity = args.optDouble("intensity", 1.0)
        val resolvedSide = side.ifBlank { "right" }
        runCatching {
            Log.i(TAG, "body_gesture→template $gesture side=$resolvedSide src=$sideSource")
        }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.In,
                "gesture",
                "$gesture → template side=$resolvedSide src=$sideSource",
                args.toString().take(500),
            )
        }
        val played = CompanionStageHost.playTemplate(gesture, intensity, resolvedSide)
        if (!played) {
            CompanionMovementAgent.requestAsync(
                intent = "Named gesture: $gesture. Side: $resolvedSide.",
                userText = lastUserTextForSide,
                source = "tool_body_gesture",
            )
        }
        return ok(
            call.callId,
            JSONObject()
                .put("gesture", gesture)
                .put("side", resolvedSide)
                .put("side_source", sideSource)
                .put("intensity", intensity)
                .put("mode", if (played) "vrma_or_joint_xyz" else "movement_agent")
                .put("played", played)
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
        CompanionMovementAgent.cancel()
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
