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
    private val keyModel = stringPreferencesKey("preferred_model")
    private val keySessionId = stringPreferencesKey("active_session_id")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[keyToken] }
    val deviceNameFlow: Flow<String?> = context.dataStore.data.map { it[keyDeviceName] }
    val useHistoryFlow: Flow<Boolean> = context.dataStore.data.map { it[keyUseHistory] ?: true }
    val keepScreenOnFlow: Flow<Boolean> = context.dataStore.data.map { it[keyKeepScreenOn] ?: true }
    val enterForNewlineFlow: Flow<Boolean> = context.dataStore.data.map { it[keyEnterForNewline] ?: true }
    val modelFlow: Flow<String?> = context.dataStore.data.map { it[keyModel] }
    val sessionIdFlow: Flow<String?> = context.dataStore.data.map { it[keySessionId] }

    suspend fun setToken(token: String) {
        context.dataStore.edit { it[keyToken] = token.trim() }
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

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[keyModel] = model }
    }

    suspend fun setSessionId(id: String) {
        context.dataStore.edit {
            if (id.isBlank()) it.remove(keySessionId) else it[keySessionId] = id
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
