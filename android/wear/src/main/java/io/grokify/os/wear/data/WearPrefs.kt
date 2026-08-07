package io.grokify.os.wear.data

import android.content.Context

/**
 * Lightweight prefs for the wear app.
 * SpaceXAI key is preferably synced from the phone host vault; manual entry remains a fallback.
 */
class WearPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var spaceXaiApiKey: String
        get() = prefs.getString(KEY_SPACEXAI, "").orEmpty()
        // commit so SharedPreferences listeners + next read see the key immediately
        set(value) {
            prefs.edit().putString(KEY_SPACEXAI, value.trim()).commit()
        }

    /** "phone" when synced from host; "local" when typed on watch. */
    var keySource: String
        get() = prefs.getString(KEY_SOURCE, SOURCE_LOCAL).orEmpty().ifBlank { SOURCE_LOCAL }
        set(value) {
            prefs.edit().putString(KEY_SOURCE, value).commit()
        }

    /**
     * Host device token (Bearer) for OTA + API. Prefer phone Data Layer sync;
     * manual paste is a fallback when remote / unpaired.
     */
    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TOKEN, value.trim()).commit()
        }

    var carinaConversationId: String?
        get() = prefs.getString(KEY_CONV, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) prefs.edit().remove(KEY_CONV).apply()
            else prefs.edit().putString(KEY_CONV, v).apply()
        }

    fun clearCarinaConversationId() {
        prefs.edit().remove(KEY_CONV).apply()
    }

    companion object {
        private const val PREFS = "grokify_wear"
        private const val KEY_SPACEXAI = "spacexai_api_key"
        private const val KEY_CONV = "carina_conversation_id"
        private const val KEY_SOURCE = "spacexai_key_source"
        private const val KEY_DEVICE_TOKEN = "device_token"
        const val SOURCE_PHONE = "phone"
        const val SOURCE_LOCAL = "local"
    }
}
