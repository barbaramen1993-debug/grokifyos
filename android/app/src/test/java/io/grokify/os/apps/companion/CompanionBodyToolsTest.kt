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
        assertTrue(names.contains(CompanionBodyTools.TOOL_GESTURE))
        assertTrue(names.contains(CompanionBodyTools.TOOL_SET_HANDS))
        assertTrue(names.contains(CompanionBodyTools.TOOL_LOOK))
        assertTrue(names.contains(CompanionBodyTools.TOOL_RESET))
    }

    @Test
    fun isBodyTool_matchesKnownNames() {
        assertTrue(CompanionBodyTools.isBodyTool("body_gesture"))
        assertTrue(CompanionBodyTools.isBodyTool("set_hands"))
        assertTrue(CompanionBodyTools.isBodyTool("look_at"))
        assertTrue(CompanionBodyTools.isBodyTool("reset_body"))
        assertFalse(CompanionBodyTools.isBodyTool("web_search"))
        assertFalse(CompanionBodyTools.isBodyTool(""))
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
    }
}
