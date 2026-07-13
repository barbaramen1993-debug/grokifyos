package io.grokify.os.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("grokifyos_prefs")

class TokenStore(private val context: Context) {
    private val keyToken = stringPreferencesKey("device_token")
    private val keyDeviceName = stringPreferencesKey("device_name")
    private val keyUseHistory = booleanPreferencesKey("use_history")
    private val keyKeepScreenOn = booleanPreferencesKey("keep_screen_on")
    /** When true, Enter inserts a newline; when false, Enter sends the message. */
    private val keyEnterForNewline = booleanPreferencesKey("enter_for_newline")
    /** When true, active phone notifications are shared with Grok as prompt context. */
    private val keyShareNotifications = booleanPreferencesKey("share_notifications")
    /** When true, tool call cards are shown in the chat transcript. */
    private val keyShowTools = booleanPreferencesKey("show_tools")
    /** When true, thinking / thoughts cards are shown in the chat transcript. */
    private val keyShowThoughts = booleanPreferencesKey("show_thoughts")
    private val keyModel = stringPreferencesKey("preferred_model")
    private val keySessionId = stringPreferencesKey("active_session_id")
    /** Optional Mapbox public access token (pk.…). Empty/null → use built-in default. */
    private val keyMapboxAccessToken = stringPreferencesKey("mapbox_access_token")
    /**
     * JSON vault of host API keys for built-in apps.
     * Shape: { "key_id": { "label", "value", "description" }, ... }
     */
    private val keyApiVault = stringPreferencesKey("api_key_vault_json")
    /**
     * Comma-separated built-in app ids in display order for the Apps hub.
     * Empty / absent → default catalog order.
     */
    private val keyAppOrder = stringPreferencesKey("app_order")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[keyToken] }
    val deviceNameFlow: Flow<String?> = context.dataStore.data.map { it[keyDeviceName] }
    val useHistoryFlow: Flow<Boolean> = context.dataStore.data.map { it[keyUseHistory] ?: true }
    val keepScreenOnFlow: Flow<Boolean> = context.dataStore.data.map { it[keyKeepScreenOn] ?: true }
    val enterForNewlineFlow: Flow<Boolean> = context.dataStore.data.map { it[keyEnterForNewline] ?: true }
    val shareNotificationsFlow: Flow<Boolean> =
        context.dataStore.data.map { it[keyShareNotifications] ?: true }
    val showToolsFlow: Flow<Boolean> =
        context.dataStore.data.map { it[keyShowTools] ?: true }
    val showThoughtsFlow: Flow<Boolean> =
        context.dataStore.data.map { it[keyShowThoughts] ?: true }
    val modelFlow: Flow<String?> = context.dataStore.data.map { it[keyModel] }
    val sessionIdFlow: Flow<String?> = context.dataStore.data.map { it[keySessionId] }
    val mapboxAccessTokenFlow: Flow<String?> =
        context.dataStore.data.map { it[keyMapboxAccessToken] }
    val apiKeyVaultFlow: Flow<Map<String, ApiKeyEntry>> =
        context.dataStore.data.map { prefs ->
            ApiKeyVaultCodec.decode(prefs[keyApiVault])
        }

    /** User-defined Apps hub order (subset or superset of known built-in ids). */
    val appOrderFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        parseIdList(prefs[keyAppOrder].orEmpty())
    }

    suspend fun setToken(token: String) {
        context.dataStore.edit {
            val cleaned = token.trim()
            if (cleaned.isEmpty()) it.remove(keyToken) else it[keyToken] = cleaned
        }
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[keyDeviceName] = name }
    }

    suspend fun setUseHistory(enabled: Boolean) {
        context.dataStore.edit { it[keyUseHistory] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[keyKeepScreenOn] = enabled }
    }

    suspend fun setEnterForNewline(enabled: Boolean) {
        context.dataStore.edit { it[keyEnterForNewline] = enabled }
    }

    suspend fun setShareNotifications(enabled: Boolean) {
        context.dataStore.edit { it[keyShareNotifications] = enabled }
    }

    suspend fun setShowTools(enabled: Boolean) {
        context.dataStore.edit { it[keyShowTools] = enabled }
    }

    suspend fun setShowThoughts(enabled: Boolean) {
        context.dataStore.edit { it[keyShowThoughts] = enabled }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[keyModel] = model }
    }

    suspend fun setSessionId(id: String) {
        context.dataStore.edit {
            if (id.isBlank()) it.remove(keySessionId) else it[keySessionId] = id
        }
    }

    /** Persist a custom Mapbox public token, or clear to fall back to the built-in default. */
    suspend fun setMapboxAccessToken(token: String?) {
        context.dataStore.edit {
            val cleaned = token?.trim().orEmpty()
            if (cleaned.isEmpty()) it.remove(keyMapboxAccessToken) else it[keyMapboxAccessToken] = cleaned
        }
        // Mirror into the generic vault so apps can request "mapbox_access_token".
        if (token.isNullOrBlank()) {
            removeApiKey(ApiKeyIds.MAPBOX)
        } else {
            upsertApiKey(
                ApiKeyEntry(
                    id = ApiKeyIds.MAPBOX,
                    label = ApiKeyPresets.labelFor(ApiKeyIds.MAPBOX),
                    value = token.trim(),
                    description = ApiKeyPresets.descriptionFor(ApiKeyIds.MAPBOX),
                    preset = true,
                ),
            )
        }
    }

    suspend fun upsertApiKey(entry: ApiKeyEntry) {
        val cleanedId = entry.id.trim()
        if (cleanedId.isEmpty()) return
        val cleanedValue = entry.value.trim()
        context.dataStore.edit { prefs ->
            val current = ApiKeyVaultCodec.decode(prefs[keyApiVault]).toMutableMap()
            if (cleanedValue.isEmpty()) {
                current.remove(cleanedId)
            } else {
                current[cleanedId] = entry.copy(
                    id = cleanedId,
                    value = cleanedValue,
                    label = entry.label.ifBlank { ApiKeyPresets.labelFor(cleanedId) },
                    description = entry.description.ifBlank {
                        ApiKeyPresets.descriptionFor(cleanedId)
                    },
                    preset = ApiKeyPresets.byId(cleanedId) != null,
                )
            }
            if (current.isEmpty()) {
                prefs.remove(keyApiVault)
            } else {
                prefs[keyApiVault] = ApiKeyVaultCodec.encode(current)
            }
        }
        // Keep legacy Mapbox field in sync when that key is written via vault.
        if (cleanedId == ApiKeyIds.MAPBOX) {
            context.dataStore.edit { prefs ->
                if (cleanedValue.isEmpty()) prefs.remove(keyMapboxAccessToken)
                else prefs[keyMapboxAccessToken] = cleanedValue
            }
        }
    }

    suspend fun removeApiKey(id: String) {
        val cleanedId = id.trim()
        if (cleanedId.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = ApiKeyVaultCodec.decode(prefs[keyApiVault]).toMutableMap()
            current.remove(cleanedId)
            if (current.isEmpty()) prefs.remove(keyApiVault)
            else prefs[keyApiVault] = ApiKeyVaultCodec.encode(current)
        }
        if (cleanedId == ApiKeyIds.MAPBOX) {
            context.dataStore.edit { it.remove(keyMapboxAccessToken) }
        }
    }

    suspend fun setApiKeyValue(id: String, value: String, label: String? = null, description: String? = null) {
        val cleanedId = id.trim()
        if (cleanedId.isEmpty()) return
        val preset = ApiKeyPresets.byId(cleanedId)
        upsertApiKey(
            ApiKeyEntry(
                id = cleanedId,
                label = label?.takeIf { it.isNotBlank() } ?: preset?.label ?: cleanedId,
                value = value,
                description = description?.takeIf { it.isNotBlank() }
                    ?: preset?.description.orEmpty(),
                preset = preset != null,
            ),
        )
    }

    suspend fun setAppOrder(ids: List<String>) {
        context.dataStore.edit {
            val cleaned = ids
                .map { id -> id.trim() }
                .filter { id -> id.isNotEmpty() }
                .distinct()
            if (cleaned.isEmpty()) it.remove(keyAppOrder)
            else it[keyAppOrder] = cleaned.joinToString(",")
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private fun parseIdList(raw: String): List<String> =
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
