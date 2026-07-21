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
        assertTrue(names.contains(CompanionBodyTools.TOOL_OBSERVE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_GESTURE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_SET_HANDS))
        assertTrue(names.contains(CompanionBodyTools.TOOL_LOOK))
        assertTrue(names.contains(CompanionBodyTools.TOOL_RESET))
    }

    @Test
    fun toolInstructions_mentionJointLabels() {
        val t = CompanionBodyTools.toolInstructions()
        assertTrue(t.contains("named_joints") || t.contains("joint_labels"))
        assertTrue(t.contains("observe_environment"))
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
    fun execute_gesture_ok_evenWithoutStage() {
        val call = GrokAssistantVoiceTools.FunctionCall(
            name = CompanionBodyTools.TOOL_GESTURE,
            callId = "c2",
            argumentsJson = """{"gesture":"wave","side":"right","intensity":1}""",
        )
        val result = CompanionBodyTools.execute(call)
        val json = JSONObject(result.outputJson)
        assertTrue(json.optBoolean("ok"))
        assertEquals("wave", json.optString("gesture"))
        // Stage may be null in unit tests.
        assertFalse(json.optBoolean("stage"))
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
        assertTrue(t.contains("VRChat", ignoreCase = true) || t.contains("VR"))
        assertTrue(t.contains("body_gesture") || t.contains("wave"))
        assertTrue(t.contains("look_at") || t.contains("LEFT", ignoreCase = true))
        assertTrue(
            t.contains("IK", ignoreCase = true) ||
                t.contains("wrist") ||
                t.contains("controller"),
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
