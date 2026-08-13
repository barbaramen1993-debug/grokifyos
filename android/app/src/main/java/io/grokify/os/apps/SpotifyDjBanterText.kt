package io.grokify.os.apps

import org.json.JSONArray
import org.json.JSONObject

/** Chat + TTS cap for a spoken DJ line (matches generateBanter take()). */
const val SPOKEN_BANTER_MAX_CHARS = 900

/**
 * Process-talk phrases that must never go on-air.
 * Keep these *specific* — generic "let me" / "tool" / "verify that" are real radio copy.
 */
private val PROCESS_NARRATION_PHRASE = Regex(
    """(?i)(""" +
        """before\s+writing\s+the\s+(dj|spoken)\s+line|""" +
        """public\s+tidbit|""" +
        """writing\s+the\s+dj\s+line|""" +
        """checking\s+for\s+a\s+(real\s+)?(public\s+)?tidbit|""" +
        """according\s+to\s+my\s+research|""" +
        """as\s+i\s+research|""" +
        """research\s+focus\s*:|""" +
        """web\s*search|""" +
        """here'?s\s+my\s+(plan|process|draft)|""" +
        """note\s+to\s+self""" +
        """)""",
)

private val PROCESS_NARRATION_OPENER = Regex(
    """(?i)^\s*(""" +
        """checking\s+for\s+a\s+|""" +
        """looking\s+up\s+(the\s+)?(lyrics|tour|show|facts?|news|web)|""" +
        """searching\s+(the\s+web|for\s+a\s+real)|""" +
        """fetching\s+(lyrics|tour|news|facts)|""" +
        """researching\b|""" +
        """before\s+(i\s+|we\s+)?writ|""" +
        """i('ll| will)\s+(check|look\s+up|search|verify)\s+(the\s+)?(web|tools?|research|lyrics)|""" +
        """i('m| am)\s+(checking|looking\s+up|searching|about\s+to\s+write|going\s+to\s+write)|""" +
        """as\s+i\s+(check|look|search|write|research)\b""" +
        """)""",
)

private val RESEARCH_JSON_KEYS = setOf(
    "custom_notes",
    "custom",
    "news",
    "headlines",
    "facts",
    "album_facts",
    "shows",
    "x_social",
    "radio_color",
    "setlist_tease",
    "current_lyrics_theme",
    "next_lyrics_theme",
    "notes",
    "bullets",
)

/** True when [sentence] is off-mic process talk, not radio copy. */
fun isProcessNarrationSentence(sentence: String): Boolean {
    val t = sentence.trim()
    if (t.isEmpty()) return false
    return PROCESS_NARRATION_PHRASE.containsMatchIn(t) ||
        PROCESS_NARRATION_OPENER.containsMatchIn(t)
}

fun stripSpokenMetaNarration(s: String): String {
    val raw = s.trim()
    if (raw.isEmpty()) return raw
    val parts = raw
        .replace(Regex("[\\r\\n]+"), " ")
        .split(Regex("(?<=[.!?…])\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ""
    return parts.filterNot { isProcessNarrationSentence(it) }.joinToString(" ").trim()
}

fun sanitizeSpokenBanter(s: String): String {
    var t = stripSpokenMetaNarration(s)
        .replace('\u00A0', ' ')
        .replace(Regex("[\\t\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
        t = t.substring(1, t.length - 1).trim()
    }
    t = t.replace(Regex("\\s+([,.;:!?…])"), "$1")
    t = t.replace(Regex("([.!?…])([A-Za-z\"'“‘])"), "$1 $2")
    t = t.replace(Regex("([,;:])([A-Za-z\"'“‘])"), "$1 $2")
    return t.replace(Regex("\\s+"), " ").trim()
}

fun looksLikeSpokenBanter(s: String): Boolean {
    val body = sanitizeSpokenBanter(s).ifBlank { s.trim() }
    if (body.length < 8 || body.length > SPOKEN_BANTER_MAX_CHARS) return false
    if (body.startsWith("{") || body.startsWith("[")) return false
    if (body.contains("```")) return false
    if (isProcessNarrationSentence(body)) return false
    return true
}

/** Prefer a usable AI line; only then the canned local fallback. */
fun chooseSpokenBanter(aiRaw: String?, local: String): String {
    val cleaned = sanitizeSpokenBanter(aiRaw.orEmpty())
    if (looksLikeSpokenBanter(cleaned)) return cleaned.take(SPOKEN_BANTER_MAX_CHARS)
    return local
}

/** Prefer ok text; on timeout / agent error keep a non-empty partial. */
fun hostAiCompletionText(env: JSONObject): String {
    val okText = env.optString("text", "").trim()
    if (env.optBoolean("ok") && okText.isNotBlank()) return okText
    val partial = env.optString("partial", "").trim()
    if (partial.isNotBlank()) return partial
    return okText
}

/**
 * Pull the research JSON object out of model text. Prefers an object that
 * actually has research keys so thinking like `{custom_notes}` is ignored.
 */
fun extractDjJsonObject(text: String): JSONObject? {
    if (text.isBlank()) return null
    val trimmed = text.trim()
    val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(trimmed)
    val candidate = fence?.groupValues?.getOrNull(1)?.trim() ?: trimmed
    runCatching { JSONObject(candidate) }.getOrNull()?.let { if (it.length() > 0) return it }

    val found = ArrayList<JSONObject>()
    var i = 0
    while (i < candidate.length) {
        val start = candidate.indexOf('{', i)
        if (start < 0) break
        val end = matchingJsonBraceEnd(candidate, start)
        if (end == null) {
            i = start + 1
            continue
        }
        runCatching { JSONObject(candidate.substring(start, end + 1)) }.getOrNull()?.let { found.add(it) }
        i = start + 1
    }
    if (found.isEmpty()) {
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(candidate.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }
    return found.maxByOrNull { obj ->
        RESEARCH_JSON_KEYS.count { obj.has(it) } * 10 + obj.length()
    }
}

private fun matchingJsonBraceEnd(s: String, start: Int): Int? {
    var depth = 0
    var inStr = false
    var esc = false
    for (i in start until s.length) {
        val c = s[i]
        if (inStr) {
            if (esc) {
                esc = false
                continue
            }
            when (c) {
                '\\' -> esc = true
                '"' -> inStr = false
            }
            continue
        }
        when (c) {
            '"' -> inStr = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}

fun formatDjResearchBullets(
    json: JSONObject,
    angleLabels: String,
    customAngleLabels: List<String>,
    nextAlbum: String = "",
    nextYear: String = "",
    prevAlbum: String = "",
    prevYear: String = "",
    includeLyrics: Boolean = false,
    includeShows: Boolean = false,
    includeSocial: Boolean = false,
    includeRadio: Boolean = false,
    includeAlbumArtist: Boolean = true,
): List<String> {
    val out = ArrayList<String>(16)
    if (angleLabels.isNotBlank()) out.add("Research focus: $angleLabels")

    fun addLabeled(label: String, value: String, max: Int = 180) {
        val v = value.trim()
        if (v.isNotBlank()) out.add("$label: ${v.take(max)}")
    }
    if (includeLyrics) {
        addLabeled("Current song theme", json.optString("current_lyrics_theme", ""))
        addLabeled("Next song theme", json.optString("next_lyrics_theme", ""))
    }
    if (nextAlbum.isNotBlank()) {
        val y = if (nextYear.isNotBlank()) " ($nextYear)" else ""
        out.add("Next album: $nextAlbum$y")
    }
    if (prevAlbum.isNotBlank() && !prevAlbum.equals(nextAlbum, ignoreCase = true)) {
        val y = if (prevYear.isNotBlank()) " ($prevYear)" else ""
        out.add("Current album: $prevAlbum$y")
    }

    fun drainArray(key: String, prefix: String?, limit: Int) {
        val arr = json.optJSONArray(key) ?: jsonStringArrayFromAlt(json, key) ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            if (n >= limit) break
            val f = arr.optString(i, "").trim()
            if (f.isBlank()) continue
            out.add(if (prefix != null) "$prefix: ${f.take(160)}" else f.take(160))
            n++
        }
    }

    if (includeAlbumArtist) {
        drainArray("album_facts", "Album", 2)
        drainArray("facts", null, 4)
    }
    if (includeShows) drainArray("shows", "Upcoming", 3)
    if (includeSocial) drainArray("x_social", "X/social", 3)
    if (includeRadio) drainArray("radio_color", "Host color", 3)
    drainArray("setlist_tease", "Later in set", 2)

    val customLabel = if (customAngleLabels.isEmpty()) {
        "Custom"
    } else {
        "Custom (${customAngleLabels.joinToString("/")})"
    }
    drainArray("custom_notes", customLabel, 4)
    drainArray("custom", customLabel, 4)
    drainArray("news", "News", 3)
    drainArray("headlines", "News", 3)
    drainArray("notes", customLabel, 3)
    drainArray("bullets", customLabel, 4)
    if (out.size <= 2) {
        drainArray("bullets", null, 4)
    }
    return out
}

private fun jsonStringArrayFromAlt(json: JSONObject, key: String): JSONArray? {
    if (json.has(key) && json.opt(key) is String) {
        val s = json.optString(key, "").trim()
        if (s.isBlank()) return null
        return JSONArray().put(s)
    }
    return null
}

/**
 * True when the pack has a real Custom:/News: finding — not just
 * "Research focus: USA news (custom)".
 */
fun researchHasUsableCustomBeat(research: List<String>): Boolean =
    research.any { line ->
        val t = line.trim()
        if (t.startsWith("Research focus", ignoreCase = true)) return@any false
        t.startsWith("Custom", ignoreCase = true) ||
            t.startsWith("News:", ignoreCase = true) ||
            t.startsWith("News ", ignoreCase = true)
    }

fun djResearchNeedsCustomRetry(
    research: List<String>,
    customAngleLabels: List<String>,
): Boolean = customAngleLabels.isNotEmpty() && !researchHasUsableCustomBeat(research)
