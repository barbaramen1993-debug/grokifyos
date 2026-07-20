package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokAssistantPromptsTest {
    @Test
    fun build_includesCoreModeAndMeta() {
        val sys = AssistantSystemPrompt.build(
            AssistantPromptDefaults.all(),
            AssistantMode.Dev,
            speakReplies = true,
        )
        assertTrue(sys.contains("Grok Assistant"))
        assertTrue(sys.contains("MODE: Dev") || sys.contains("engineering partner"))
        assertTrue(sys.contains("Mode: dev"))
        assertTrue(sys.contains("Speak replies: on"))
    }

    @Test
    fun mergeWithDefaults_restoresMissingBuiltIns() {
        val merged = AssistantPromptCodec.mergeWithDefaults(emptyList())
        assertEquals(AssistantPromptDefaults.all().size, merged.size)
    }

    @Test
    fun resetTemplate_restoresStockBody() {
        val edited = AssistantPromptDefaults.all().map {
            if (it.id == AssistantPromptDefaults.ID_CORE) it.copy(body = "HACKED") else it
        }
        val reset = AssistantPromptCodec.resetTemplate(edited, AssistantPromptDefaults.ID_CORE)!!
        assertEquals(
            AssistantPromptDefaults.byId(AssistantPromptDefaults.ID_CORE)!!.body,
            reset.first { it.id == AssistantPromptDefaults.ID_CORE }.body,
        )
    }

    @Test
    fun transcript_capAndHistoryWindow() {
        val msgs = (1..120).map {
            AssistantChatMessage(
                id = "$it",
                role = if (it % 2 == 0) "assistant" else "user",
                text = "m$it",
            )
        }
        val capped = AssistantTranscript.capStored(msgs)
        assertEquals(100, capped.size)
        val window = AssistantTranscript.historyWindow(capped)
        assertTrue(window.size <= 24)
    }

    @Test
    fun formatHistoryForVoiceInstructions_includesContinuityBlock() {
        val msgs = listOf(
            AssistantChatMessage("1", "user", "my name is Sam", 1L),
            AssistantChatMessage("2", "assistant", "Hi Sam!", 2L),
            AssistantChatMessage("3", "error", "ignored", 3L),
        )
        val block = AssistantTranscript.formatHistoryForVoiceInstructions(msgs)
        assertTrue(block.contains("Recent conversation context"))
        assertTrue(block.contains("User: my name is Sam"))
        assertTrue(block.contains("Assistant: Hi Sam!"))
        assertTrue(!block.contains("ignored"))
    }

    @Test
    fun formatHistoryForVoiceInstructions_emptyWhenNoChat() {
        assertEquals("", AssistantTranscript.formatHistoryForVoiceInstructions(emptyList()))
        assertEquals(
            "",
            AssistantTranscript.formatHistoryForVoiceInstructions(
                listOf(AssistantChatMessage("e", "error", "x", 1L)),
            ),
        )
    }

    @Test
    fun codec_roundTrip() {
        val list = AssistantPromptDefaults.all()
        val decoded = AssistantPromptCodec.decode(AssistantPromptCodec.encode(list))
        assertEquals(list.size, decoded.size)
        assertEquals(list.first().body, decoded.first().body)
    }
}
