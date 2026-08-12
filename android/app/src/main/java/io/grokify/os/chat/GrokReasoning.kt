package io.grokify.os.chat

/**
 * Grok Build CLI reasoning-effort sets.
 *
 * grok-4.6: low | medium | high | xhigh
 * grok-4.5 (and unknown / older): low | medium | high
 *
 * The CLI rejects unsupported values (4.5 + xhigh is a hard error).
 */
object GrokReasoning {
    val LOW_MED_HIGH: List<String> = listOf("low", "medium", "high")
    val WITH_XHIGH: List<String> = listOf("low", "medium", "high", "xhigh")

    fun realId(model: String): String {
        val m = model.trim()
        return when {
            m.startsWith("gb:") -> m.removePrefix("gb:")
            m.startsWith("grok:") && !m.startsWith("grok-") -> m.removePrefix("grok:")
            else -> m
        }
    }

    fun supportsXhigh(model: String): Boolean {
        val match = Regex("""^grok-(\d+)(?:\.(\d+))?""").find(realId(model).lowercase())
            ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return major > 4 || (major == 4 && minor >= 6)
    }

    fun effortsFor(model: String, advertised: List<String> = emptyList()): List<String> {
        val cleaned = advertised
            .map { it.trim().lowercase() }
            .filter { it == "low" || it == "medium" || it == "high" || it == "xhigh" }
            .distinct()
        if (cleaned.isNotEmpty()) return cleaned
        return if (supportsXhigh(model)) WITH_XHIGH else LOW_MED_HIGH
    }

    fun defaultFor(
        model: String,
        advertisedDefault: String = "",
        advertised: List<String> = emptyList(),
    ): String {
        val allowed = effortsFor(model, advertised)
        val pref = advertisedDefault.trim().lowercase()
        if (pref in allowed) return pref
        return if ("xhigh" in allowed) "xhigh" else "high"
    }

    fun clamp(
        model: String,
        requested: String?,
        advertised: List<String> = emptyList(),
        advertisedDefault: String = "",
    ): String {
        val allowed = effortsFor(model, advertised)
        val req = requested?.trim()?.lowercase().orEmpty()
        if (req in allowed) return req
        return defaultFor(model, advertisedDefault, advertised)
    }
}
