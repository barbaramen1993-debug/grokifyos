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
        val primaryId = when {
            ApiKeyIds.isSpaceXaiKeyId(cleaned) -> ApiKeyIds.SPACEXAI
            ApiKeyIds.isSpaceXaiManagementKeyId(cleaned) -> ApiKeyIds.SPACEXAI_MANAGEMENT
            else -> cleaned
        }
        val v = vault[primaryId]?.value?.trim().orEmpty()
        if (v.isNotEmpty()) return v
        // Legacy vault id before SpaceXAI rename (inference key only)
        if (ApiKeyIds.isSpaceXaiKeyId(cleaned)) {
            val legacy = vault[ApiKeyIds.LEGACY_XAI]?.value?.trim().orEmpty()
            if (legacy.isNotEmpty()) return legacy
        }
        // Legacy Mapbox field
        if (cleaned == ApiKeyIds.MAPBOX) {
            return runBlocking {
                store(ctx).mapboxAccessTokenFlow.first()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * Management Key for billing / Usage Analyzer.
     * Prefers [ApiKeyIds.SPACEXAI_MANAGEMENT]; falls back to inference vault only when
     * management is empty (users who previously shared one slot).
     */
    fun getSpaceXaiManagementKey(ctx: Context): String? {
        getValue(ctx, ApiKeyIds.SPACEXAI_MANAGEMENT)?.takeIf { it.isNotBlank() }?.let { return it }
        return getValue(ctx, ApiKeyIds.SPACEXAI)?.takeIf { it.isNotBlank() }
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
        val cleaned = when {
            ApiKeyIds.isSpaceXaiKeyId(id) -> ApiKeyIds.SPACEXAI
            ApiKeyIds.isSpaceXaiManagementKeyId(id) -> ApiKeyIds.SPACEXAI_MANAGEMENT
            else -> id.trim()
        }
        runBlocking {
            store(ctx).setApiKeyValue(cleaned, value, label, description)
            // Drop pre-rename id so vault stays single-source.
            if (ApiKeyIds.isSpaceXaiKeyId(id)) {
                store(ctx).removeApiKey(ApiKeyIds.LEGACY_XAI)
            }
        }
    }

    fun remove(ctx: Context, id: String) {
        val cleaned = when {
            ApiKeyIds.isSpaceXaiKeyId(id) -> ApiKeyIds.SPACEXAI
            ApiKeyIds.isSpaceXaiManagementKeyId(id) -> ApiKeyIds.SPACEXAI_MANAGEMENT
            else -> id.trim()
        }
        runBlocking {
            store(ctx).removeApiKey(cleaned)
            if (ApiKeyIds.isSpaceXaiKeyId(id)) {
                store(ctx).removeApiKey(ApiKeyIds.LEGACY_XAI)
            }
        }
    }
}
