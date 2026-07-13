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

/** Well-known key ids used by built-in / marketplace scripts. */
object ApiKeyIds {
    const val XAI = "xai_api_key"
    const val SPOTIFY_CLIENT_ID = "spotify_client_id"
    const val SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
    const val SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
    const val SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token"
    const val SPOTIFY_TOKEN_EXPIRES_AT = "spotify_token_expires_at"
    const val MAPBOX = "mapbox_access_token"

    /** User-facing keys that show in Settings presets (not auto OAuth tokens). */
    val USER_FACING: Set<String> = setOf(
        XAI,
        SPOTIFY_CLIENT_ID,
        SPOTIFY_CLIENT_SECRET,
        MAPBOX,
    )

    /** OAuth / runtime tokens managed by host — not free-form in Add dialog. */
    val INTERNAL: Set<String> = setOf(
        SPOTIFY_ACCESS_TOKEN,
        SPOTIFY_REFRESH_TOKEN,
        SPOTIFY_TOKEN_EXPIRES_AT,
    )
}

object ApiKeyPresets {
    val all: List<ApiKeyEntry> = listOf(
        ApiKeyEntry(
            id = ApiKeyIds.XAI,
            label = "xAI API key",
            value = "",
            description = "console.x.ai → API Keys → Create key. Used for Grok Voice TTS " +
                "(DJ banter / Speak). NOT used for playlist research — that uses host Grok Build " +
                "(same device token as Chat). Voices: eve, ara, leo, rex, sal, carina, helix, …",
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

    fun byId(id: String): ApiKeyEntry? =
        all.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

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
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val o = root.optJSONObject(id) ?: continue
                    val value = o.optString("value", "").trim()
                    // Keep empty placeholders only if label is custom; normally skip blanks
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
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun statusArray(
        vault: Map<String, ApiKeyEntry>,
        filterIds: Collection<String>? = null,
        includeInternal: Boolean = false,
    ): JSONArray {
        val ids = when {
            filterIds != null -> filterIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            else -> vault.keys
                .filter { includeInternal || it !in ApiKeyIds.INTERNAL }
                .sorted()
        }
        val arr = JSONArray()
        ids.forEach { id ->
            val stored = vault[id]
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
