package io.grokify.os.apps

import android.content.Context
import java.util.UUID

class GrokAssistantStore(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var mode: AssistantMode
        get() = AssistantMode.fromStorage(prefs.getString(KEY_MODE, null))
        set(v) = prefs.edit().putString(KEY_MODE, v.storageKey).apply()

    var voiceId: String
        get() = prefs.getString(KEY_VOICE, "eve")?.ifBlank { "eve" } ?: "eve"
        set(v) = prefs.edit().putString(KEY_VOICE, v.ifBlank { "eve" }).apply()

    var preferDeviceTts: Boolean
        get() = prefs.getBoolean(KEY_PREFER_DEVICE, false)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_DEVICE, v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(v) = prefs.edit().putBoolean(KEY_SPEAK, v).apply()

    fun templates(): List<AssistantPromptTemplate> {
        val saved = AssistantPromptCodec.decode(prefs.getString(KEY_PROMPTS, null))
        return AssistantPromptCodec.mergeWithDefaults(saved)
    }

    fun saveTemplates(list: List<AssistantPromptTemplate>) {
        prefs.edit().putString(KEY_PROMPTS, AssistantPromptCodec.encode(list)).apply()
    }

    fun upsertTemplate(tpl: AssistantPromptTemplate) {
        val cur = templates().toMutableList()
        val idx = cur.indexOfFirst { it.id == tpl.id }
        if (idx >= 0) cur[idx] = tpl else cur.add(tpl)
        saveTemplates(cur)
    }

    fun setTemplateEnabled(id: String, enabled: Boolean) {
        saveTemplates(templates().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun deleteTemplate(id: String): Boolean {
        val stock = AssistantPromptDefaults.byId(id)
        if (stock != null && stock.builtIn) return false
        val next = templates().filter { it.id != id }
        if (next.size == templates().size) return false
        saveTemplates(next)
        return true
    }

    fun resetTemplate(id: String): Boolean {
        val next = AssistantPromptCodec.resetTemplate(templates(), id) ?: return false
        saveTemplates(next)
        return true
    }

    fun transcript(): List<AssistantChatMessage> =
        AssistantTranscript.decode(prefs.getString(KEY_TRANSCRIPT, null))

    fun saveTranscript(list: List<AssistantChatMessage>) {
        val capped = AssistantTranscript.capStored(list)
        prefs.edit().putString(KEY_TRANSCRIPT, AssistantTranscript.encode(capped)).apply()
    }

    fun appendMessage(role: String, text: String): AssistantChatMessage {
        val msg = AssistantChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            text = text,
        )
        saveTranscript(transcript() + msg)
        return msg
    }

    fun clearTranscript() {
        prefs.edit().remove(KEY_TRANSCRIPT).apply()
    }

    fun systemPrompt(): String =
        AssistantSystemPrompt.build(templates(), mode, speakReplies)

    companion object {
        private const val PREFS = "grok_assistant_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_VOICE = "voice_id"
        private const val KEY_PREFER_DEVICE = "prefer_device_tts"
        private const val KEY_SPEAK = "speak_replies"
        private const val KEY_PROMPTS = "prompt_templates_v1"
        private const val KEY_TRANSCRIPT = "transcript_v1"
    }
}
