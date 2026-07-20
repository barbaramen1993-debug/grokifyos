package io.grokify.os.apps

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokAssistantVoiceToolsTest {

    @Test
    fun sessionTools_includesSearchAndBuild() {
        val tools = GrokAssistantVoiceTools.sessionTools(devMode = false)
        assertEquals(3, tools.length())
        val types = (0 until tools.length()).map { tools.getJSONObject(it).optString("type") }
        assertTrue(types.contains("web_search"))
        assertTrue(types.contains("x_search"))
        assertTrue(types.contains("function"))
        val fn = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.optString("type") == "function" }
        assertEquals(GrokAssistantVoiceTools.TOOL_PROMPT_BUILD, fn.optString("name"))
        assertTrue(fn.optJSONObject("parameters")!!.has("properties"))
    }

    @Test
    fun buildCliTool_devModeWidensDescription() {
        val conv = GrokAssistantVoiceTools.buildCliTool(devMode = false)
            .optString("description")
        val dev = GrokAssistantVoiceTools.buildCliTool(devMode = true)
            .optString("description")
        assertTrue(dev.contains("Dev mode", ignoreCase = true) || dev.contains("engineering"))
        assertTrue(dev.length >= conv.length)
    }

    @Test
    fun parseFunctionCallEvent_stringArgs() {
        val ev = JSONObject()
            .put("type", "response.function_call_arguments.done")
            .put("name", GrokAssistantVoiceTools.TOOL_PROMPT_BUILD)
            .put("call_id", "call_123")
            .put("arguments", """{"prompt":"research X","reason":"research"}""")
        val call = GrokAssistantVoiceTools.parseFunctionCallEvent(ev)
        assertNotNull(call)
        assertEquals("call_123", call!!.callId)
        assertEquals(GrokAssistantVoiceTools.TOOL_PROMPT_BUILD, call.name)
        val args = JSONObject(call.argumentsJson)
        assertEquals("research X", args.optString("prompt"))
        assertEquals("research", args.optString("reason"))
    }

    @Test
    fun parseFunctionCallEvent_objectArgs() {
        val ev = JSONObject()
            .put("name", "prompt_grok_build")
            .put("call_id", "c2")
            .put("arguments", JSONObject().put("prompt", "hello"))
        val call = GrokAssistantVoiceTools.parseFunctionCallEvent(ev)
        assertNotNull(call)
        assertEquals("hello", JSONObject(call!!.argumentsJson).optString("prompt"))
    }

    @Test
    fun parseFunctionCallEvent_missingFieldsNull() {
        assertEquals(null, GrokAssistantVoiceTools.parseFunctionCallEvent(JSONObject()))
        assertEquals(
            null,
            GrokAssistantVoiceTools.parseFunctionCallEvent(
                JSONObject().put("name", "x").put("call_id", ""),
            ),
        )
    }

    @Test
    fun functionOutputEvent_shape() {
        val result = GrokAssistantVoiceTools.FunctionResult(
            callId = "call_9",
            outputJson = """{"ok":true,"text":"done"}""",
        )
        val ev = GrokAssistantVoiceTools.functionOutputEvent(result)
        assertEquals("conversation.item.create", ev.optString("type"))
        val item = ev.optJSONObject("item")!!
        assertEquals("function_call_output", item.optString("type"))
        assertEquals("call_9", item.optString("call_id"))
        assertTrue(item.optString("output").contains("done"))
    }

    @Test
    fun voiceToolInstructions_mentionBuild() {
        val s = GrokAssistantVoiceTools.voiceToolInstructions(devMode = false)
        assertTrue(s.contains("prompt_grok_build"))
        assertTrue(s.contains("web_search"))
        val dev = GrokAssistantVoiceTools.voiceToolInstructions(devMode = true)
        assertTrue(dev.contains("Dev mode"))
        assertFalse(s.contains("Dev mode is ON"))
    }

    @Test
    fun voiceToolInstructions_singleCohesiveAnswer() {
        val s = GrokAssistantVoiceTools.voiceToolInstructions(devMode = false)
        assertTrue(s.contains("ONE cohesive"))
        assertTrue(s.contains("never answer then retract", ignoreCase = true) ||
            s.lowercase().contains("do not open a second reply"))
    }
}
