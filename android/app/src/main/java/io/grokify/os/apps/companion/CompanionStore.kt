package io.grokify.os.apps.companion

import android.content.Context

class CompanionStore(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Serializes history read-modify-write so voice + text commits cannot drop turns. */
    private val historyLock = Any()

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

    /**
     * Last camera/orbit framing from the VRM stage (JSON).
     * Restored on the next Companion open so framing survives app restarts.
     */
    var lastOrbitJson: String
        get() = prefs.getString(KEY_ORBIT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ORBIT, v).apply()

    fun clearLastOrbit() {
        prefs.edit().remove(KEY_ORBIT).apply()
    }

    /** xAI Voice Agent conversation id for session resumption (~30 min cache). */
    var voiceConversationId: String
        get() = prefs.getString(KEY_VOICE_CONV, "") ?: ""
        set(v) = prefs.edit().putString(KEY_VOICE_CONV, v.trim()).apply()

    fun clearVoiceConversationId() {
        prefs.edit().remove(KEY_VOICE_CONV).apply()
    }

    fun history(): List<CompanionMessage> = synchronized(historyLock) {
        CompanionPrompts.decodeHistory(prefs.getString(KEY_HISTORY, null))
    }

    fun saveHistory(messages: List<CompanionMessage>) {
        val capped = CompanionPrompts.capHistory(messages)
        synchronized(historyLock) {
            prefs.edit().putString(KEY_HISTORY, CompanionPrompts.encodeHistory(capped)).apply()
        }
    }

    fun appendMessage(msg: CompanionMessage): List<CompanionMessage> {
        synchronized(historyLock) {
            val next = CompanionPrompts.capHistory(
                CompanionPrompts.decodeHistory(prefs.getString(KEY_HISTORY, null)) + msg,
            )
            prefs.edit().putString(KEY_HISTORY, CompanionPrompts.encodeHistory(next)).apply()
            return next
        }
    }

    fun clearHistory() {
        synchronized(historyLock) {
            prefs.edit().remove(KEY_HISTORY).apply()
        }
    }

    companion object {
        private const val PREFS = "companion_prefs"
        private const val KEY_PROMPT = "system_prompt"
        private const val KEY_VOICE = "voice_id"
        private const val KEY_PREFER_DEVICE = "prefer_device_tts"
        private const val KEY_MODEL_SOURCE = "model_source"
        private const val KEY_USER_MODEL = "user_model_path"
        private const val KEY_HISTORY = "chat_history_v1"
        private const val KEY_ORBIT = "last_orbit_json_v1"
        private const val KEY_VOICE_CONV = "voice_conversation_id"

        const val SOURCE_BUNDLED = "bundled"
        const val SOURCE_USER = "user"
    }
}
