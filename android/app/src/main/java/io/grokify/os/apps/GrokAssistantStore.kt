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

    /**
     * Allow ephemeral floating sessions (wake / Show).
     * Does not mean a permanent bubble — overlay stops when dismissed.
     */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY, v).apply()

    /** Continuous “Okay Grok” speech-recognition wake loop. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE, false)
        set(v) = prefs.edit().putBoolean(KEY_WAKE, v).apply()

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

    // ── Multi-session chat history ──────────────────────────────────────────

    var activeSessionId: String
        get() {
            ensureSessionsMigrated()
            val id = prefs.getString(KEY_ACTIVE_SESSION, null)?.trim().orEmpty()
            if (id.isNotBlank() && conversations().any { it.id == id }) return id
            val first = conversations().firstOrNull()?.id
            if (first != null) {
                prefs.edit().putString(KEY_ACTIVE_SESSION, first).apply()
                return first
            }
            return newSession().id
        }
        set(v) {
            ensureSessionsMigrated()
            val id = v.trim()
            if (id.isBlank()) return
            if (conversations().none { it.id == id }) return
            prefs.edit().putString(KEY_ACTIVE_SESSION, id).apply()
        }

    fun conversations(): List<AssistantConversation> {
        ensureSessionsMigrated()
        return AssistantTranscript.decodeSessions(prefs.getString(KEY_SESSIONS, null))
            .sortedByDescending { it.updatedAt }
    }

    fun sessionMetas(): List<AssistantSessionMeta> = conversations().map { it.meta() }

    fun activeConversation(): AssistantConversation {
        val id = activeSessionId
        return conversations().firstOrNull { it.id == id }
            ?: newSession()
    }

    fun transcript(): List<AssistantChatMessage> = activeConversation().messages

    fun saveTranscript(list: List<AssistantChatMessage>) {
        val id = activeSessionId
        val capped = AssistantTranscript.capStored(list)
        val now = System.currentTimeMillis()
        val existing = conversations().firstOrNull { it.id == id }
        val title = if (existing != null &&
            existing.title.isNotBlank() &&
            existing.title != "New chat"
        ) {
            existing.title
        } else {
            AssistantConversation.titleFromFirstUser(capped)
        }
        val updated = AssistantConversation(
            id = id,
            title = title,
            updatedAt = now,
            messages = capped,
        )
        upsertConversation(updated)
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
        val id = activeSessionId
        val existing = conversations().firstOrNull { it.id == id }
        upsertConversation(
            AssistantConversation(
                id = id,
                title = "New chat",
                updatedAt = System.currentTimeMillis(),
                messages = emptyList(),
            ),
        )
        // Keep same id so UI stays on this empty thread
        if (existing != null) {
            prefs.edit().putString(KEY_ACTIVE_SESSION, id).apply()
        }
    }

    fun newSession(title: String = "New chat"): AssistantConversation {
        ensureSessionsMigrated()
        val conv = AssistantConversation(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "New chat" },
            updatedAt = System.currentTimeMillis(),
            messages = emptyList(),
        )
        val next = AssistantTranscript.capSessions(listOf(conv) + conversations())
        prefs.edit()
            .putString(KEY_SESSIONS, AssistantTranscript.encodeSessions(next))
            .putString(KEY_ACTIVE_SESSION, conv.id)
            .apply()
        return conv
    }

    fun selectSession(id: String): Boolean {
        val found = conversations().any { it.id == id }
        if (!found) return false
        prefs.edit().putString(KEY_ACTIVE_SESSION, id).apply()
        return true
    }

    fun deleteSession(id: String): Boolean {
        val all = conversations()
        if (all.none { it.id == id }) return false
        val next = all.filter { it.id != id }
        if (next.isEmpty()) {
            val fresh = AssistantConversation(
                id = UUID.randomUUID().toString(),
                title = "New chat",
                updatedAt = System.currentTimeMillis(),
                messages = emptyList(),
            )
            prefs.edit()
                .putString(KEY_SESSIONS, AssistantTranscript.encodeSessions(listOf(fresh)))
                .putString(KEY_ACTIVE_SESSION, fresh.id)
                .apply()
            return true
        }
        val active = prefs.getString(KEY_ACTIVE_SESSION, null)
        val newActive = if (active == id) next.maxByOrNull { it.updatedAt }!!.id else active
        prefs.edit()
            .putString(KEY_SESSIONS, AssistantTranscript.encodeSessions(next))
            .putString(KEY_ACTIVE_SESSION, newActive)
            .apply()
        return true
    }

    fun renameSession(id: String, title: String): Boolean {
        val t = title.trim().ifBlank { return false }
        val conv = conversations().firstOrNull { it.id == id } ?: return false
        upsertConversation(conv.copy(title = t.take(80), updatedAt = System.currentTimeMillis()))
        return true
    }

    private fun upsertConversation(conv: AssistantConversation) {
        val rest = conversations().filter { it.id != conv.id }
        val next = AssistantTranscript.capSessions(listOf(conv) + rest)
        prefs.edit()
            .putString(KEY_SESSIONS, AssistantTranscript.encodeSessions(next))
            .putString(KEY_ACTIVE_SESSION, conv.id)
            .apply()
    }

    /**
     * One-time migrate legacy single [KEY_TRANSCRIPT] blob into multi-session storage.
     */
    private fun ensureSessionsMigrated() {
        if (prefs.getBoolean(KEY_SESSIONS_MIGRATED, false)) {
            // If sessions key missing/empty, seed one empty chat.
            val raw = prefs.getString(KEY_SESSIONS, null)
            if (raw.isNullOrBlank()) {
                val fresh = AssistantConversation(
                    id = UUID.randomUUID().toString(),
                    title = "New chat",
                    updatedAt = System.currentTimeMillis(),
                    messages = emptyList(),
                )
                prefs.edit()
                    .putString(KEY_SESSIONS, AssistantTranscript.encodeSessions(listOf(fresh)))
                    .putString(KEY_ACTIVE_SESSION, fresh.id)
                    .apply()
            }
            return
        }
        val legacy = AssistantTranscript.decode(prefs.getString(KEY_TRANSCRIPT, null))
        val id = UUID.randomUUID().toString()
        val conv = AssistantConversation(
            id = id,
            title = AssistantConversation.titleFromFirstUser(legacy),
            updatedAt = legacy.lastOrNull()?.ts ?: System.currentTimeMillis(),
            messages = AssistantTranscript.capStored(legacy),
        )
        prefs.edit()
            .putString(KEY_SESSIONS, AssistantTranscript.encodeSessions(listOf(conv)))
            .putString(KEY_ACTIVE_SESSION, id)
            .putBoolean(KEY_SESSIONS_MIGRATED, true)
            .remove(KEY_TRANSCRIPT)
            .apply()
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
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_WAKE = "wake_word_enabled"
        private const val KEY_PROMPTS = "prompt_templates_v1"
        private const val KEY_TRANSCRIPT = "transcript_v1" // legacy
        private const val KEY_SESSIONS = "sessions_v2"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
        private const val KEY_SESSIONS_MIGRATED = "sessions_migrated_v2"
    }
}
