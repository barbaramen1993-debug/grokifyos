package io.grokify.os.apps.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CompanionMessage(
    val id: String,
    val role: String, // user | assistant | system | error
    val text: String,
    val ts: Long,
    val source: String = "text", // voice | text
) {
    companion object {
        fun user(text: String, source: String = "text") =
            CompanionMessage(UUID.randomUUID().toString(), "user", text.trim(), System.currentTimeMillis(), source)

        fun assistant(text: String, source: String = "text") =
            CompanionMessage(UUID.randomUUID().toString(), "assistant", text.trim(), System.currentTimeMillis(), source)

        fun error(text: String) =
            CompanionMessage(UUID.randomUUID().toString(), "error", text.trim(), System.currentTimeMillis(), "text")
    }
}

object CompanionPrompts {
    const val HISTORY_CAP = 40
    const val CONTEXT_MAX_MESSAGES = 24

    val DEFAULT_SYSTEM: String = """
        You are Companion, a warm, supportive friend living as an animated character on the user's phone.
        Be casual, curious, and lightly humorous without being chaotic. Keep replies concise and easy to speak aloud.
        Never claim you ran tools, opened files, or changed device settings unless the host actually did.
        Stay in character as Companion. If unsure, ask a short clarifying question.
    """.trimIndent()

    fun assembleSystem(custom: String?): String {
        val t = custom?.trim().orEmpty()
        return if (t.isEmpty()) DEFAULT_SYSTEM else t
    }

    fun capHistory(messages: List<CompanionMessage>, cap: Int = HISTORY_CAP): List<CompanionMessage> {
        if (messages.size <= cap) return messages
        return messages.takeLast(cap)
    }

    fun contextWindow(
        messages: List<CompanionMessage>,
        maxMessages: Int = CONTEXT_MAX_MESSAGES,
    ): List<CompanionMessage> {
        val filtered = messages.filter { it.role == "user" || it.role == "assistant" }
        return filtered.takeLast(maxMessages)
    }

    /** Flatten recent turns into a short block for voice session.instructions or complete history. */
    fun formatHistoryBlock(messages: List<CompanionMessage>): String {
        val win = contextWindow(messages)
        if (win.isEmpty()) return ""
        return win.joinToString("\n") { m ->
            val who = if (m.role == "user") "User" else "Companion"
            "$who: ${m.text}"
        }
    }

    fun encodeHistory(messages: List<CompanionMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role)
                    .put("text", m.text)
                    .put("ts", m.ts)
                    .put("source", m.source),
            )
        }
        return arr.toString()
    }

    fun decodeHistory(raw: String?): List<CompanionMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                    val role = o.optString("role", "user")
                    val text = o.optString("text", "")
                    val ts = o.optLong("ts", 0L)
                    val source = o.optString("source", "text").ifBlank { "text" }
                    if (text.isNotBlank() || role == "error") {
                        add(CompanionMessage(id, role, text, ts, source))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
