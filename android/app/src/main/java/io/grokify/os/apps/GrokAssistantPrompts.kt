package io.grokify.os.apps

import org.json.JSONArray
import org.json.JSONObject

enum class AssistantPromptKind {
    Core, Mode, Extra;

    val storageKey: String
        get() = when (this) {
            Core -> "core"
            Mode -> "mode"
            Extra -> "extra"
        }

    val sectionLabel: String
        get() = when (this) {
            Core -> "Core identity"
            Mode -> "Mode prompts"
            Extra -> "Style extras"
        }

    companion object {
        fun fromStorage(raw: String?): AssistantPromptKind? =
            when (raw?.lowercase()?.trim()) {
                "core" -> Core
                "mode" -> Mode
                "extra", "style" -> Extra
                else -> null
            }
    }
}

enum class AssistantMode {
    Conversation, Dev;

    val storageKey: String
        get() = when (this) {
            Conversation -> "conversation"
            Dev -> "dev"
        }

    val modePromptId: String
        get() = when (this) {
            Conversation -> AssistantPromptDefaults.ID_MODE_CONVERSATION
            Dev -> AssistantPromptDefaults.ID_MODE_DEV
        }

    companion object {
        fun fromStorage(raw: String?): AssistantMode =
            when (raw?.lowercase()?.trim()) {
                "dev", "developer" -> Dev
                else -> Conversation
            }
    }
}

data class AssistantPromptTemplate(
    val id: String,
    val kind: AssistantPromptKind,
    val label: String,
    val blurb: String = "",
    val body: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind.storageKey)
            .put("label", label)
            .put("blurb", blurb)
            .put("body", body)
            .put("enabled", enabled)
            .put("builtIn", builtIn)

    companion object {
        fun fromJson(o: JSONObject?): AssistantPromptTemplate? {
            if (o == null) return null
            val id = o.optString("id", "").trim()
            val kind = AssistantPromptKind.fromStorage(o.optString("kind", "")) ?: return null
            if (id.isBlank()) return null
            return AssistantPromptTemplate(
                id = id,
                kind = kind,
                label = o.optString("label", id).ifBlank { id },
                blurb = o.optString("blurb", ""),
                body = o.optString("body", ""),
                enabled = o.optBoolean("enabled", true),
                builtIn = o.optBoolean("builtIn", false),
            )
        }
    }
}

object AssistantPromptDefaults {
    const val ID_CORE = "core_identity"
    const val ID_MODE_CONVERSATION = "mode_conversation"
    const val ID_MODE_DEV = "mode_dev"
    const val ID_STYLE_CONCISE = "style_concise"
    const val ID_STYLE_WITTY = "style_witty"
    const val ID_STYLE_SPOKEN = "style_spoken"

    fun all(): List<AssistantPromptTemplate> = listOf(
        AssistantPromptTemplate(
            id = ID_CORE,
            kind = AssistantPromptKind.Core,
            label = "Core identity",
            blurb = "Who the assistant is and hard rules",
            body =
                "You are Grok Assistant, the on-device voice and chat helper for GrokifyOS. " +
                    "Be clear, helpful, and concise. Never invent that you edited files, ran tools, " +
                    "or changed device settings unless the host actually did so. Stay in character. " +
                    "If something needs a host capability that is not wired yet, say so plainly.",
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_MODE_CONVERSATION,
            kind = AssistantPromptKind.Mode,
            label = "Conversation mode",
            blurb = "Everyday Q&A",
            body =
                "MODE: Conversation. Everyday Q&A. Warm and direct. " +
                    "Ask a short clarifying question when the request is ambiguous. " +
                    "Prefer plain language over jargon unless the user is technical.",
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_MODE_DEV,
            kind = AssistantPromptKind.Mode,
            label = "Dev mode",
            blurb = "Engineering partner (text-only tools in v1)",
            body =
                "MODE: Dev. Act as an engineering partner for code, debugging, and architecture. " +
                    "You may reason about files, tools, and patches, but you cannot execute host tools " +
                    "or edit the device filesystem in this mode yet — say when an action needs wiring. " +
                    "Prefer concrete steps, commands, and diffs when useful.",
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_STYLE_CONCISE,
            kind = AssistantPromptKind.Extra,
            label = "Concise",
            blurb = "Prefer short answers",
            body = "STYLE: Keep answers short. Lead with the answer, then brief detail only if needed.",
            enabled = true,
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_STYLE_WITTY,
            kind = AssistantPromptKind.Extra,
            label = "Witty",
            blurb = "Light humor when natural",
            body = "STYLE: Light humor is welcome when it fits; never force jokes over clarity.",
            enabled = false,
            builtIn = true,
        ),
        AssistantPromptTemplate(
            id = ID_STYLE_SPOKEN,
            kind = AssistantPromptKind.Extra,
            label = "Spoken-friendly",
            blurb = "Optimize for TTS",
            body =
                "STYLE: Optimize for speech. Short sentences. No markdown tables. " +
                    "Avoid long code fences unless the user asked for code.",
            enabled = true,
            builtIn = true,
        ),
    )

    fun byId(id: String): AssistantPromptTemplate? = all().find { it.id == id }
}

object AssistantPromptCodec {
    fun encode(list: List<AssistantPromptTemplate>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decode(raw: String?): List<AssistantPromptTemplate> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AssistantPromptTemplate>()
        for (i in 0 until arr.length()) {
            AssistantPromptTemplate.fromJson(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    /** Merge saved with defaults: keep user edits; ensure built-ins exist. */
    fun mergeWithDefaults(saved: List<AssistantPromptTemplate>): List<AssistantPromptTemplate> {
        val defaults = AssistantPromptDefaults.all()
        val byId = saved.associateBy { it.id }.toMutableMap()
        for (d in defaults) {
            if (d.id !in byId) byId[d.id] = d
        }
        val ordered = mutableListOf<AssistantPromptTemplate>()
        val seen = mutableSetOf<String>()
        for (d in defaults) {
            byId[d.id]?.let {
                ordered.add(it)
                seen.add(it.id)
            }
        }
        for (s in saved) {
            if (s.id !in seen) {
                ordered.add(s)
                seen.add(s.id)
            }
        }
        return ordered
    }

    fun resetTemplate(
        list: List<AssistantPromptTemplate>,
        id: String,
    ): List<AssistantPromptTemplate>? {
        val stock = AssistantPromptDefaults.byId(id) ?: return null
        return list.map { if (it.id == id) stock.copy(enabled = it.enabled) else it }
    }
}

object AssistantSystemPrompt {
    const val SPEAK_HINT =
        "Reply in plain speech-friendly prose; avoid code fences unless the user asked for code."

    fun build(
        templates: List<AssistantPromptTemplate>,
        mode: AssistantMode,
        speakReplies: Boolean,
    ): String {
        val core = templates.firstOrNull {
            it.kind == AssistantPromptKind.Core && it.id == AssistantPromptDefaults.ID_CORE
        }?.body?.trim().orEmpty()
            .ifBlank {
                AssistantPromptDefaults.byId(AssistantPromptDefaults.ID_CORE)?.body.orEmpty()
            }

        val modeBody = templates.firstOrNull { it.id == mode.modePromptId }?.body?.trim()
            .orEmpty()
            .ifBlank {
                AssistantPromptDefaults.byId(mode.modePromptId)?.body.orEmpty()
            }

        val extras = templates
            .filter { it.kind == AssistantPromptKind.Extra && it.enabled && it.body.isNotBlank() }
            .map { it.body.trim() }

        val parts = mutableListOf<String>()
        if (core.isNotBlank()) parts += core
        if (modeBody.isNotBlank()) parts += modeBody
        extras.forEach { parts += it }

        val meta =
            "Mode: ${mode.storageKey} · Speak replies: ${if (speakReplies) "on" else "off"}"
        parts += meta

        val spokenExtraOn = templates.any {
            it.id == AssistantPromptDefaults.ID_STYLE_SPOKEN && it.enabled
        }
        if (speakReplies && !spokenExtraOn) {
            parts += SPEAK_HINT
        }

        return parts.joinToString("\n---\n")
    }
}

data class AssistantChatMessage(
    val id: String,
    val role: String, // user | assistant | system | error
    val text: String,
    val ts: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).put("role", role).put("text", text).put("ts", ts)

    companion object {
        fun fromJson(o: JSONObject?): AssistantChatMessage? {
            if (o == null) return null
            val id = o.optString("id", "").ifBlank { return null }
            val role = o.optString("role", "").ifBlank { return null }
            val text = o.optString("text", "")
            return AssistantChatMessage(id, role, text, o.optLong("ts", 0L))
        }
    }
}

/** One assistant conversation (history list entry + messages). */
data class AssistantConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<AssistantChatMessage>,
) {
    val messageCount: Int get() = messages.size

    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("updatedAt", updatedAt)
            .put(
                "messages",
                JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } },
            )

    fun meta(): AssistantSessionMeta =
        AssistantSessionMeta(id = id, title = title, updatedAt = updatedAt, messageCount = messageCount)

    companion object {
        fun fromJson(o: JSONObject?): AssistantConversation? {
            if (o == null) return null
            val id = o.optString("id", "").ifBlank { return null }
            val title = o.optString("title", "New chat").ifBlank { "New chat" }
            val updatedAt = o.optLong("updatedAt", 0L)
            val msgsArr = o.optJSONArray("messages")
            val messages = mutableListOf<AssistantChatMessage>()
            if (msgsArr != null) {
                for (i in 0 until msgsArr.length()) {
                    AssistantChatMessage.fromJson(msgsArr.optJSONObject(i))?.let { messages.add(it) }
                }
            }
            return AssistantConversation(id, title, updatedAt, messages)
        }

        fun titleFromFirstUser(messages: List<AssistantChatMessage>, fallback: String = "New chat"): String {
            val first = messages.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
            if (first.isBlank()) return fallback
            val clean = first.removePrefix("🖼").trim()
            return if (clean.length <= 48) clean else clean.take(45).trimEnd() + "…"
        }
    }
}

data class AssistantSessionMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

object AssistantTranscript {
    const val MAX_STORED = 100
    const val MAX_SESSIONS = 40
    const val MAX_HISTORY_MESSAGES = 24 // 12 turns
    const val MAX_HISTORY_CHARS = 6000

    fun encode(list: List<AssistantChatMessage>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decode(raw: String?): List<AssistantChatMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AssistantChatMessage>()
        for (i in 0 until arr.length()) {
            AssistantChatMessage.fromJson(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun encodeSessions(list: List<AssistantConversation>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun decodeSessions(raw: String?): List<AssistantConversation> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AssistantConversation>()
        for (i in 0 until arr.length()) {
            AssistantConversation.fromJson(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun capStored(list: List<AssistantChatMessage>): List<AssistantChatMessage> =
        if (list.size <= MAX_STORED) list else list.takeLast(MAX_STORED)

    fun capSessions(list: List<AssistantConversation>): List<AssistantConversation> {
        if (list.size <= MAX_SESSIONS) return list
        return list.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
    }

    /**
     * True when [role]+[text] would re-append the same bubble as [last] within [windowMs].
     * Voice Agent often emits both transcription.completed and .done for one utterance.
     */
    fun shouldSkipDuplicate(
        last: AssistantChatMessage?,
        role: String,
        text: String,
        nowMs: Long,
        windowMs: Long = DUPLICATE_WINDOW_MS,
    ): Boolean {
        if (last == null) return false
        if (!last.role.equals(role, ignoreCase = true)) return false
        if (last.text.trim() != text.trim()) return false
        if (text.trim().isEmpty()) return false
        // Legacy messages without ts: still collapse exact consecutive twins.
        if (last.ts <= 0L) return true
        return nowMs - last.ts in 0 until windowMs
    }

    const val DUPLICATE_WINDOW_MS = 5_000L

    /**
     * How long consecutive **user** voice segments may be merged into one bubble.
     * Server VAD ends a turn on silence and emits a new transcription item; mid-thought
     * pauses therefore look like several user messages. Merge while no assistant
     * reply has landed yet (last bubble is still user).
     */
    const val USER_MERGE_WINDOW_MS = 14_000L

    /**
     * True when a new user line should update [last] instead of creating another bubble.
     * Only consecutive user→user within [windowMs]; assistant/error breaks the chain.
     */
    fun shouldMergeUserUtterance(
        last: AssistantChatMessage?,
        role: String,
        text: String,
        nowMs: Long,
        windowMs: Long = USER_MERGE_WINDOW_MS,
    ): Boolean {
        if (last == null) return false
        if (!role.equals("user", ignoreCase = true)) return false
        if (!last.role.equals("user", ignoreCase = true)) return false
        val body = text.trim()
        if (body.isEmpty()) return false
        // Exact twins handled by shouldSkipDuplicate.
        if (last.text.trim().equals(body, ignoreCase = true)) return false
        if (last.ts > 0L && nowMs - last.ts >= windowMs) return false
        // Legacy zero-ts: still allow merge (voice path always sets ts now).
        return true
    }

    /**
     * Combine two user ASR fragments from the same spoken thought.
     * Prefers cumulative supersession, then suffix/prefix overlap, then space-join.
     */
    fun mergeUserUtteranceText(prev: String, next: String): String {
        val a = prev.trim()
        val b = next.trim()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        if (a.equals(b, ignoreCase = true)) return a
        // Cumulative / revised ASR for the same segment.
        if (b.startsWith(a, ignoreCase = true)) return b
        if (a.startsWith(b, ignoreCase = true)) return a
        val aNorm = a.lowercase()
        val bNorm = b.lowercase()
        if (bNorm.contains(aNorm) && b.length > a.length) return b
        if (aNorm.contains(bNorm) && a.length > b.length) return a
        // Avoid "hello world hello world today" when the second fragment restates the first.
        val maxOverlap = minOf(a.length, b.length)
        var overlap = 0
        // Meaningful word-ish overlap only (skip 1–2 letter noise).
        val minOverlap = 4
        for (n in maxOverlap downTo minOverlap) {
            if (a.regionMatches(a.length - n, b, 0, n, ignoreCase = true)) {
                // Prefer overlaps that land on a boundary (space / start).
                val boundary =
                    n == a.length ||
                        a[a.length - n - 1].isWhitespace() ||
                        b.getOrNull(n)?.isWhitespace() == true ||
                        n == b.length
                if (boundary || n >= 8) {
                    overlap = n
                    break
                }
            }
        }
        if (overlap > 0) {
            return (a + b.substring(overlap)).replace(Regex("\\s+"), " ").trim()
        }
        val joiner = when {
            a.endsWith("-") -> ""
            a.last().isWhitespace() || b.first().isWhitespace() -> ""
            else -> " "
        }
        return (a + joiner + b).replace(Regex("\\s+"), " ").trim()
    }

    /** Last N user/assistant messages for model context (not error/system). */
    fun historyWindow(list: List<AssistantChatMessage>): List<AssistantChatMessage> {
        val filtered = list.filter { it.role == "user" || it.role == "assistant" }
        val tail = filtered.takeLast(MAX_HISTORY_MESSAGES)
        var chars = 0
        val out = ArrayDeque<AssistantChatMessage>()
        for (m in tail.asReversed()) {
            val len = m.text.length
            if (out.isNotEmpty() && chars + len > MAX_HISTORY_CHARS) break
            out.addFirst(m)
            chars += len
        }
        return out.toList()
    }

    fun formatHistoryForPrompt(window: List<AssistantChatMessage>): String {
        if (window.isEmpty()) return ""
        return window.joinToString("\n") { m ->
            val who = if (m.role == "user") "User" else "Assistant"
            "$who: ${m.text}"
        }
    }

    /**
     * Block appended to Voice Agent session.instructions so a new WebSocket still
     * knows prior chat (text + earlier voice turns). Keeps the model from re-greeting
     * and losing continuity when resumption is unavailable.
     */
    fun formatHistoryForVoiceInstructions(list: List<AssistantChatMessage>): String {
        val window = historyWindow(list)
        val body = formatHistoryForPrompt(window)
        if (body.isBlank()) return ""
        return buildString {
            append(
                "Recent conversation context (already said — continue naturally; " +
                    "do not re-introduce yourself or restate this block):\n",
            )
            append(body)
        }
    }
}
