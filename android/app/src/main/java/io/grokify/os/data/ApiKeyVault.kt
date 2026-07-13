package io.grokify.os.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-side API key registry.
 *
 * Keys stay on-device (DataStore). Scripts declare which ids they need;
 * the host only exposes those ids to a given plugin, and can prompt the
 * user to add missing ones from Settings or the plugin gate screen.
 */
data class ApiKeyEntry(
    val id: String,
    val label: String,
    val value: String,
    val description: String = "",
    /** When true, listed as a known preset in "Add API key". */
    val preset: Boolean = false,
) {
    val present: Boolean get() = value.isNotBlank()
    fun maskedTail(n: Int = 6): String {
        val v = value.trim()
        if (v.isEmpty()) return ""
        return if (v.length <= n) "…$v" else "…${v.takeLast(n)}"
    }

    fun toPublicJson(includeValue: Boolean = false): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("description", description)
            .put("present", present)
            .put("preset", preset)
        if (present) o.put("endsWith", maskedTail())
        if (includeValue && present) o.put("value", value)
        return o
    }
}

/** Well-known key ids used by built-in apps. */
object ApiKeyIds {
    /** SpaceXAI inference API key (Voice TTS on api.x.ai). */
    const val SPACEXAI = "spacexai_api_key"
    /**
     * SpaceXAI Management Key (billing / usage on management-api.x.ai).
     * Different product type from [SPACEXAI] — keep separate in Settings.
     */
    const val SPACEXAI_MANAGEMENT = "spacexai_management_key"
    /** Pre-rename id — still accepted when reading vault / host store. */
    const val LEGACY_XAI = "xai_api_key"
    const val SPOTIFY_CLIENT_ID = "spotify_client_id"
    const val SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
    const val SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
    const val SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token"
    const val SPOTIFY_TOKEN_EXPIRES_AT = "spotify_token_expires_at"
    const val MAPBOX = "mapbox_access_token"

    /** User-facing keys that show in Settings presets (not auto OAuth tokens). */
    val USER_FACING: Set<String> = setOf(
        SPACEXAI,
        SPACEXAI_MANAGEMENT,
        SPOTIFY_CLIENT_ID,
        SPOTIFY_CLIENT_SECRET,
        MAPBOX,
    )

    /** Dedicated Settings cards — excluded from the generic vault list below. */
    val SETTINGS_DEDICATED: Set<String> = setOf(
        SPACEXAI,
        SPACEXAI_MANAGEMENT,
        MAPBOX,
    )

    /** OAuth / runtime tokens managed by host — not free-form in Add dialog. */
    val INTERNAL: Set<String> = setOf(
        SPOTIFY_ACCESS_TOKEN,
        SPOTIFY_REFRESH_TOKEN,
        SPOTIFY_TOKEN_EXPIRES_AT,
    )

    /** Inference / legacy ids only — not the management key. */
    fun isSpaceXaiKeyId(id: String): Boolean {
        val c = id.trim()
        return c.equals(SPACEXAI, ignoreCase = true) ||
            c.equals(LEGACY_XAI, ignoreCase = true)
    }

    fun isSpaceXaiManagementKeyId(id: String): Boolean =
        id.trim().equals(SPACEXAI_MANAGEMENT, ignoreCase = true)
}

object ApiKeyPresets {
    val all: List<ApiKeyEntry> = listOf(
        ApiKeyEntry(
            id = ApiKeyIds.SPACEXAI,
            label = "SpaceXAI API key",
            value = "",
            description = "console.x.ai → API Keys (inference). Grok Voice TTS on api.x.ai " +
                "(Spotify Live DJ banter). Voices: eve, ara, leo, rex, sal, carina, helix, … " +
                "NOT for Usage Analyzer — use Management key. NOT for playlist research — host Grok Build.",
            preset = true,
        ),
        ApiKeyEntry(
            id = ApiKeyIds.SPACEXAI_MANAGEMENT,
            label = "SpaceXAI Management key",
            value = "",
            description = "console.x.ai → Management Keys with billing read. Prepaid balance, " +
                "period spend, and limits via management-api.x.ai (Usage Analyzer). " +
                "Different from the inference API key used for Voice TTS.",
            preset = true,
        ),
        ApiKeyEntry(
            id = ApiKeyIds.SPOTIFY_CLIENT_ID,
            label = "Spotify Client ID",
            value = "",
            description = "developer.spotify.com → your app → Client ID. Redirect URI must be " +
                "exactly https://grokifyos.grokpot.io/spotify-callback.php",
            preset = true,
        ),
        ApiKeyEntry(
            id = ApiKeyIds.SPOTIFY_CLIENT_SECRET,
            label = "Spotify Client Secret",
            value = "",
            description = "Optional for confidential apps; PKCE works with Client ID alone",
            preset = true,
        ),
        ApiKeyEntry(
            id = ApiKeyIds.MAPBOX,
            label = "Mapbox access token",
            value = "",
            description = "Public pk.… token for maps (also editable in the Mapbox card)",
            preset = true,
        ),
    )

    fun byId(id: String): ApiKeyEntry? {
        val cleaned = id.trim()
        if (ApiKeyIds.isSpaceXaiKeyId(cleaned)) {
            return all.firstOrNull { it.id == ApiKeyIds.SPACEXAI }
        }
        if (ApiKeyIds.isSpaceXaiManagementKeyId(cleaned)) {
            return all.firstOrNull { it.id == ApiKeyIds.SPACEXAI_MANAGEMENT }
        }
        return all.firstOrNull { it.id.equals(cleaned, ignoreCase = true) }
    }

    fun labelFor(id: String, fallback: String = id): String =
        byId(id)?.label ?: fallback

    fun descriptionFor(id: String): String =
        byId(id)?.description.orEmpty()
}

/** Parse/store vault as JSON object: { "key_id": { "label", "value", "description" }, ... } */
object ApiKeyVaultCodec {
    fun encode(entries: Map<String, ApiKeyEntry>): String {
        val root = JSONObject()
        entries.values.forEach { e ->
            if (e.id.isBlank()) return@forEach
            root.put(
                e.id,
                JSONObject()
                    .put("label", e.label)
                    .put("value", e.value)
                    .put("description", e.description),
            )
        }
        return root.toString()
    }

    fun decode(raw: String?): Map<String, ApiKeyEntry> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val built = buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val o = root.optJSONObject(id) ?: continue
                    val value = o.optString("value", "").trim()
                    val label = o.optString("label", "").ifBlank {
                        ApiKeyPresets.labelFor(id, id)
                    }
                    val desc = o.optString("description", "").ifBlank {
                        ApiKeyPresets.descriptionFor(id)
                    }
                    put(
                        id,
                        ApiKeyEntry(
                            id = id,
                            label = label,
                            value = value,
                            description = desc,
                            preset = ApiKeyPresets.byId(id) != null,
                        ),
                    )
                }
            }
            migrateLegacyIds(built)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Rename legacy `xai_api_key` → `spacexai_api_key` when decoding.
     * Prefer the new id if both exist.
     */
    fun migrateLegacyIds(entries: Map<String, ApiKeyEntry>): Map<String, ApiKeyEntry> {
        val legacy = entries[ApiKeyIds.LEGACY_XAI]
            ?: entries.entries.firstOrNull { ApiKeyIds.isSpaceXaiKeyId(it.key) && it.key != ApiKeyIds.SPACEXAI }?.value
        if (legacy == null && !entries.containsKey(ApiKeyIds.LEGACY_XAI)) {
            // Still drop any stray legacy key if present without value path above
            return if (entries.keys.any { it == ApiKeyIds.LEGACY_XAI }) {
                entries.filterKeys { it != ApiKeyIds.LEGACY_XAI }
            } else {
                entries
            }
        }
        val out = entries.toMutableMap()
        out.remove(ApiKeyIds.LEGACY_XAI)
        val existing = out[ApiKeyIds.SPACEXAI]
        if (existing == null || existing.value.isBlank()) {
            if (legacy != null && legacy.value.isNotBlank()) {
                out[ApiKeyIds.SPACEXAI] = legacy.copy(
                    id = ApiKeyIds.SPACEXAI,
                    label = ApiKeyPresets.labelFor(ApiKeyIds.SPACEXAI),
                    description = legacy.description.ifBlank {
                        ApiKeyPresets.descriptionFor(ApiKeyIds.SPACEXAI)
                    },
                    preset = true,
                )
            } else if (existing == null && legacy != null) {
                out[ApiKeyIds.SPACEXAI] = legacy.copy(
                    id = ApiKeyIds.SPACEXAI,
                    label = ApiKeyPresets.labelFor(ApiKeyIds.SPACEXAI),
                    description = ApiKeyPresets.descriptionFor(ApiKeyIds.SPACEXAI),
                    preset = true,
                )
            }
        }
        return out
    }

    fun statusArray(
        vault: Map<String, ApiKeyEntry>,
        filterIds: Collection<String>? = null,
        includeInternal: Boolean = false,
    ): JSONArray {
        val migrated = migrateLegacyIds(vault)
        val ids = when {
            filterIds != null -> filterIds
                .map { id ->
                    if (ApiKeyIds.isSpaceXaiKeyId(id)) ApiKeyIds.SPACEXAI else id.trim()
                }
                .filter { it.isNotEmpty() }
                .distinct()
            else -> migrated.keys
                .filter { includeInternal || it !in ApiKeyIds.INTERNAL }
                .sorted()
        }
        val arr = JSONArray()
        ids.forEach { id ->
            val stored = migrated[id]
            val preset = ApiKeyPresets.byId(id)
            val entry = stored ?: ApiKeyEntry(
                id = id,
                label = preset?.label ?: id,
                value = "",
                description = preset?.description.orEmpty(),
                preset = preset != null,
            )
            arr.put(entry.toPublicJson(includeValue = false))
        }
        return arr
    }
}
