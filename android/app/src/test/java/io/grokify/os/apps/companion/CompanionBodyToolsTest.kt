package io.grokify.os.apps.companion

import io.grokify.os.apps.GrokAssistantVoiceTools
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBodyToolsTest {
    @Test
    fun sessionTools_includesBodyFunctions() {
        val tools = CompanionBodyTools.sessionTools()
        val names = buildList {
            for (i in 0 until tools.length()) {
                add(tools.getJSONObject(i).optString("name"))
            }
        }
        assertTrue(names.contains(CompanionBodyTools.TOOL_BODY_POSE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_AI_MOVE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_OBSERVE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_GESTURE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_SET_HANDS))
        assertTrue(names.contains(CompanionBodyTools.TOOL_LOOK))
        assertTrue(names.contains(CompanionBodyTools.TOOL_RESET))
    }

    @Test
    fun toolInstructions_mentionBodyPoseAndTemplates() {
        val t = CompanionBodyTools.toolInstructions()
        assertTrue(t.contains("body_pose"))
        assertTrue(t.contains("JOINT-XYZ") || t.contains("joint") || t.contains("template"))
    }

    @Test
    fun observeTool_descriptionMentionsNames() {
        val tools = CompanionBodyTools.sessionTools()
        var observe: JSONObject? = null
        for (i in 0 until tools.length()) {
            val o = tools.getJSONObject(i)
            if (o.optString("name") == CompanionBodyTools.TOOL_OBSERVE) {
                observe = o
                break
            }
        }
        assertTrue(observe != null)
        val desc = observe!!.optString("description")
        assertTrue(desc.contains("named_joints") || desc.contains("joint_labels") || desc.contains("name"))
    }

    @Test
    fun isBodyTool_matchesKnownNames() {
        assertTrue(CompanionBodyTools.isBodyTool("body_pose"))
        assertTrue(CompanionBodyTools.isBodyTool("ai_move"))
        assertTrue(CompanionBodyTools.isBodyTool("observe_environment"))
        assertTrue(CompanionBodyTools.isBodyTool("body_gesture"))
        assertTrue(CompanionBodyTools.isBodyTool("set_hands"))
        assertTrue(CompanionBodyTools.isBodyTool("look_at"))
        assertTrue(CompanionBodyTools.isBodyTool("reset_body"))
        assertFalse(CompanionBodyTools.isBodyTool("web_search"))
        assertFalse(CompanionBodyTools.isBodyTool(""))
    }

    @Test
    fun execute_observe_withoutStage_returnsErrorJson() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_OBSERVE,
            callId = "obs1",
            argumentsJson = "{}",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        // No WebView in unit tests.
        assertFalse(json.optBoolean("ok"))
        assertEquals("stage_not_attached", json.optString("error"))
    }

    @Test
    fun parseJsJson_objectAndDoubleEncoded() {
        val direct = CompanionStageHost.parseJsJson("""{"ok":true,"state":"idle"}""")
        assertTrue(direct.optBoolean("ok"))
        assertEquals("idle", direct.optString("state"))
        val encoded = CompanionStageHost.parseJsJson("\"{\\\"ok\\\":true,\\\"x\\\":1}\"")
        assertTrue(encoded.optBoolean("ok"))
        assertEquals(1, encoded.optInt("x"))
    }

    @Test
    fun execute_gesture_missingName_errors() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_GESTURE,
            callId = "c1",
            argumentsJson = "{}",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertFalse(json.optBoolean("ok"))
        assertEquals("missing_gesture", json.optString("error"))
    }

    @Test
    fun execute_gesture_playsJointXyzTemplate() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_GESTURE,
            callId = "c2",
            argumentsJson = """{"gesture":"wave","side":"right","intensity":1}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertEquals("wave", json.optString("gesture"))
        // No WebView in unit tests → playTemplate false → movement_agent queue
        assertTrue(
            json.optString("mode") == "joint_xyz_template" ||
                json.optString("mode") == "movement_agent",
        )
        assertFalse(json.optBoolean("stage"))
    }

    @Test
    fun execute_wave_missingSide_defaultsRight() {
        CompanionBodyTools.noteUserTranscript("")
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_GESTURE,
            callId = "c2b",
            argumentsJson = """{"gesture":"wave","intensity":1}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertEquals("right", json.optString("side"))
    }

    @Test
    fun execute_wave_missingSide_infersLeftFromUserText() {
        CompanionBodyTools.noteUserTranscript("Can you wave your left hand?")
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_GESTURE,
            callId = "c2c",
            argumentsJson = """{"gesture":"wave","intensity":1}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertEquals("left", json.optString("side"))
        assertEquals("user_text", json.optString("side_source"))
    }

    @Test
    fun execute_bodyPose_waveRight() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_BODY_POSE,
            callId = "bp1",
            argumentsJson = """{"pose":"wave","side":"right"}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertEquals("wave", json.optString("pose"))
        assertEquals("right", json.optString("side"))
        assertTrue(
            json.optString("mode") == "vrma_or_joint_xyz" ||
                json.optString("mode") == "joint_xyz_template",
        )
    }

    @Test
    fun execute_aiMove_requiresIntent() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_AI_MOVE,
            callId = "am0",
            argumentsJson = "{}",
        )
        val result = CompanionBodyTools.execute(call)
        assertEquals("missing_intent", JSONObject(result.outputJson).optString("error"))
    }

    @Test
    fun execute_aiMove_queues() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_AI_MOVE,
            callId = "am1",
            argumentsJson = """{"intent":"wave left hand toward viewer","side":"left"}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertTrue(json.optBoolean("queued"))
        assertEquals("movement_agent", json.optString("mode"))
    }

    @Test
    fun inferWaveSideFromUserText_leftRightBoth() {
        assertEquals("left", CompanionBodyTools.inferWaveSideFromUserText("wave your left hand"))
        assertEquals("right", CompanionBodyTools.inferWaveSideFromUserText("Can you wave your right?"))
        assertEquals("both", CompanionBodyTools.inferWaveSideFromUserText("wave both hands"))
        assertEquals(null, CompanionBodyTools.inferWaveSideFromUserText("wave"))
        assertEquals(null, CompanionBodyTools.inferWaveSideFromUserText("hello there"))
        assertEquals("left", CompanionBodyTools.inferWaveSideFromUserText("hello with your left hand wave"))
    }

    @Test
    fun execute_setHands_requiresPayload() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_SET_HANDS,
            callId = "c3",
            argumentsJson = "{}",
        )
        val result = CompanionBodyTools.execute(call)
        assertEquals("missing_hands", JSONObject(result.outputJson).optString("error"))
    }

    @Test
    fun execute_reset_ok() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_RESET,
            callId = "c4",
            argumentsJson = "{}",
        )
        val result = CompanionBodyTools.execute(call)
        assertTrue(JSONObject(result.outputJson).optBoolean("ok"))
    }

    @Test
    fun parseFunctionCallEvent_roundTrip() {
        val event = JSONObject()
            .put("type", "response.function_call_arguments.done")
            .put("name", CompanionBodyTools.TOOL_GESTURE)
            .put("call_id", "abc")
            .put("arguments", """{"gesture":"nod"}""")
        val call = GrokAssistantVoiceTools.parseFunctionCallEvent(event)
        requireNotNull(call)
        assertEquals(CompanionBodyTools.TOOL_GESTURE, call.name)
        assertEquals("abc", call.callId)
        val out = CompanionBodyTools.execute(call)
        assertTrue(JSONObject(out.outputJson).optBoolean("ok"))
    }

    @Test
    fun toolInstructions_mentionsVrAndGestures() {
        val t = CompanionBodyTools.toolInstructions()
        assertTrue(
            t.contains("VRChat", ignoreCase = true) ||
                t.contains("VR") ||
                t.contains("VRMA", ignoreCase = true) ||
                t.contains("VRM", ignoreCase = true),
        )
        assertTrue(t.contains("body_gesture") || t.contains("wave") || t.contains("body_pose"))
        assertTrue(t.contains("look_at") || t.contains("LEFT", ignoreCase = true))
        assertTrue(
            t.contains("IK", ignoreCase = true) ||
                t.contains("wrist") ||
                t.contains("controller") ||
                t.contains("joint", ignoreCase = true) ||
                t.contains("VRMA", ignoreCase = true),
        )
        assertTrue(t.contains("observe_environment") || t.contains("CLOSED LOOP"))
    }

    @Test
    fun execute_look_directionLeft_ok() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_LOOK,
            callId = "c5",
            argumentsJson = """{"direction":"left"}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertEquals(-1.0, json.optDouble("x"), 0.001)
    }

    @Test
    fun execute_look_missing_errors() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_LOOK,
            callId = "c6",
            argumentsJson = "{}",
        )
        val result = CompanionBodyTools.execute(call)
        assertEquals("missing_look_target", JSONObject(result.outputJson).optString("error"))
    }
}
