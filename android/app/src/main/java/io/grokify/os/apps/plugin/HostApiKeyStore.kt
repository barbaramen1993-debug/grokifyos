package io.grokify.os.apps.plugin

import android.content.Context
import io.grokify.os.GrokifyApp
import io.grokify.os.data.ApiKeyEntry
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.ApiKeyPresets
import io.grokify.os.data.ApiKeyVaultCodec
import io.grokify.os.data.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Synchronous helpers for the WebView JS bridge (must not hang the UI thread
 * longer than a quick DataStore read; network stays elsewhere).
 */
object HostApiKeyStore {
    private fun store(ctx: Context): TokenStore {
        val app = ctx.applicationContext
        return if (app is GrokifyApp) app.tokenStore else TokenStore(app)
    }

    fun snapshot(ctx: Context): Map<String, ApiKeyEntry> = runBlocking {
        store(ctx).apiKeyVaultFlow.first()
    }

    fun getValue(ctx: Context, id: String): String? {
        val cleaned = id.trim()
        if (cleaned.isEmpty()) return null
        val vault = snapshot(ctx)
        val v = vault[cleaned]?.value?.trim().orEmpty()
        if (v.isNotEmpty()) return v
        // Legacy Mapbox field
        if (cleaned == ApiKeyIds.MAPBOX) {
            return runBlocking {
                store(ctx).mapboxAccessTokenFlow.first()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun has(ctx: Context, id: String): Boolean = !getValue(ctx, id).isNullOrBlank()

    fun missing(ctx: Context, required: List<PluginRequiredKey>): List<PluginRequiredKey> =
        required.filter { !has(ctx, it.id) }

    fun statusJson(
        ctx: Context,
        filterIds: Collection<String>? = null,
        includeInternal: Boolean = false,
    ): String {
        val vault = snapshot(ctx).toMutableMap()
        // Surface legacy mapbox if vault missing it
        if (filterIds == null || filterIds.any { it == ApiKeyIds.MAPBOX }) {
            if (!vault.containsKey(ApiKeyIds.MAPBOX) || vault[ApiKeyIds.MAPBOX]?.value.isNullOrBlank()) {
                val legacy = runBlocking {
                    store(ctx).mapboxAccessTokenFlow.first()?.trim().orEmpty()
                }
                if (legacy.isNotEmpty()) {
                    vault[ApiKeyIds.MAPBOX] = ApiKeyEntry(
                        id = ApiKeyIds.MAPBOX,
                        label = ApiKeyPresets.labelFor(ApiKeyIds.MAPBOX),
                        value = legacy,
                        description = ApiKeyPresets.descriptionFor(ApiKeyIds.MAPBOX),
                        preset = true,
                    )
                }
            }
        }
        return ApiKeyVaultCodec.statusArray(vault, filterIds, includeInternal).toString()
    }

    fun save(ctx: Context, id: String, value: String, label: String? = null, description: String? = null) {
        runBlocking {
            store(ctx).setApiKeyValue(id, value, label, description)
        }
    }

    fun remove(ctx: Context, id: String) {
        runBlocking {
            store(ctx).removeApiKey(id)
        }
    }
}
