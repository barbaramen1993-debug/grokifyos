package io.grokify.os.apps.companion

import android.content.Context

class CompanionStore(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var systemPrompt: String
        get() = prefs.getString(KEY_PROMPT, null)?.let {
            if (it.isBlank()) CompanionPrompts.DEFAULT_SYSTEM else it
        } ?: CompanionPrompts.DEFAULT_SYSTEM
        set(v) = prefs.edit().putString(KEY_PROMPT, v).apply()

    fun resetSystemPrompt() {
        systemPrompt = CompanionPrompts.DEFAULT_SYSTEM
    }

    var voiceId: String
        get() = prefs.getString(KEY_VOICE, "eve")?.ifBlank { "eve" } ?: "eve"
        set(v) = prefs.edit().putString(KEY_VOICE, v.ifBlank { "eve" }).apply()

    var preferDeviceTts: Boolean
        get() = prefs.getBoolean(KEY_PREFER_DEVICE, false)
        set(v) = prefs.edit().putBoolean(KEY_PREFER_DEVICE, v).apply()

    /** bundled | user */
    var modelSource: String
        get() = prefs.getString(KEY_MODEL_SOURCE, SOURCE_BUNDLED)?.ifBlank { SOURCE_BUNDLED } ?: SOURCE_BUNDLED
        set(v) = prefs.edit().putString(
            KEY_MODEL_SOURCE,
            if (v == SOURCE_USER) SOURCE_USER else SOURCE_BUNDLED,
        ).apply()

    var userModelPath: String
        get() = prefs.getString(KEY_USER_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_USER_MODEL, v).apply()

    fun history(): List<CompanionMessage> =
        CompanionPrompts.decodeHistory(prefs.getString(KEY_HISTORY, null))

    fun saveHistory(messages: List<CompanionMessage>) {
        val capped = CompanionPrompts.capHistory(messages)
        prefs.edit().putString(KEY_HISTORY, CompanionPrompts.encodeHistory(capped)).apply()
    }

    fun appendMessage(msg: CompanionMessage): List<CompanionMessage> {
        val next = CompanionPrompts.capHistory(history() + msg)
        saveHistory(next)
        return next
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val PREFS = "companion_prefs"
        private const val KEY_PROMPT = "system_prompt"
        private const val KEY_VOICE = "voice_id"
        private const val KEY_PREFER_DEVICE = "prefer_device_tts"
        private const val KEY_MODEL_SOURCE = "model_source"
        private const val KEY_USER_MODEL = "user_model_path"
        private const val KEY_HISTORY = "chat_history_v1"

        const val SOURCE_BUNDLED = "bundled"
        const val SOURCE_USER = "user"
    }
}
