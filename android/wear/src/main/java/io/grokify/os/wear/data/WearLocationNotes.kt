package io.grokify.os.wear.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WearLocationNote(
    val id: String,
    val text: String,
    val latitude: Double?,
    val longitude: Double?,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("lat", latitude ?: JSONObject.NULL)
        .put("lon", longitude ?: JSONObject.NULL)
        .put("at", createdAtMs)

    companion object {
        fun fromJson(o: JSONObject): WearLocationNote? {
            val id = o.optString("id", "").ifBlank { return null }
            val text = o.optString("text", "").ifBlank { return null }
            val lat = if (o.isNull("lat")) null else o.optDouble("lat")
            val lon = if (o.isNull("lon")) null else o.optDouble("lon")
            return WearLocationNote(
                id = id,
                text = text,
                latitude = lat,
                longitude = lon,
                createdAtMs = o.optLong("at", System.currentTimeMillis()),
            )
        }
    }
}

class WearLocationNoteStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<WearLocationNote> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val n = WearLocationNote.fromJson(arr.getJSONObject(i))
                    if (n != null) add(n)
                }
            }.sortedByDescending { it.createdAtMs }
        }.getOrDefault(emptyList())
    }

    fun add(text: String, lat: Double?, lon: Double?): WearLocationNote {
        val note = WearLocationNote(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            latitude = lat,
            longitude = lon,
            createdAtMs = System.currentTimeMillis(),
        )
        val next = listOf(note) + list().take(MAX - 1)
        save(next)
        return note
    }

    fun recentSummary(limit: Int = 5): String {
        val notes = list().take(limit)
        if (notes.isEmpty()) return "No location notes yet."
        return notes.joinToString("\n") { n ->
            val loc = if (n.latitude != null && n.longitude != null) {
                String.format(java.util.Locale.US, " @ %.4f,%.4f", n.latitude, n.longitude)
            } else ""
            "• ${n.text}$loc"
        }
    }

    private fun save(notes: List<WearLocationNote>) {
        val arr = JSONArray()
        notes.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "wear_location_notes"
        private const val KEY = "notes_json"
        private const val MAX = 40
    }
}
