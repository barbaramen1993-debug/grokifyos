package io.grokify.os.apps.plugin

/**
 * Built-in mini-apps shipped with the host APK.
 * Always available in the Apps hub (user can rearrange order).
 */
object BuiltinPluginCatalog {
    const val WIFI_SCANNER = "wifi_scanner"
    const val BT_SCANNER = "bt_scanner"
    const val PLACE_NOTES = "place_notes"
    const val SPOTIFY_CONTROLLER = "spotify_controller"

    val all: List<PluginManifest> = listOf(
        PluginManifest(
            id = WIFI_SCANNER,
            title = "Wi‑Fi Scanner",
            subtitle = "Scan nearby networks with GPS, distance, times seen, and alerts (SSID/MAC watch, unseen, strong nearby).",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = WIFI_SCANNER,
            capabilities = listOf("Nearby Wi‑Fi", "Location"),
            accent = PluginAccent.Cyan,
            icon = PluginIconKey.Wifi,
            featured = true,
        ),
        PluginManifest(
            id = BT_SCANNER,
            title = "Bluetooth Tracker",
            subtitle = "BLE + classic discovery with GPS pins, distance, times seen, and alerts (name/MAC watch, unseen, strong nearby).",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = BT_SCANNER,
            capabilities = listOf("Bluetooth", "Location", "Notifications"),
            accent = PluginAccent.Mint,
            icon = PluginIconKey.Bluetooth,
            featured = true,
        ),
        PluginManifest(
            id = PLACE_NOTES,
            title = "Place Notes",
            subtitle = "Pin notes to GPS spots. On enter: notify, open an app, or show an image. List + map + area monitoring.",
            version = "1.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = PLACE_NOTES,
            capabilities = listOf("Location", "Notifications"),
            accent = PluginAccent.Violet,
            icon = PluginIconKey.Place,
            featured = true,
        ),
        PluginManifest(
            id = SPOTIFY_CONTROLLER,
            title = "Spotify",
            subtitle = "Lockscreen controls + Live AI DJ booth chat (track history, banter, queue chat). OAuth + media in one host module.",
            version = "2.0.0",
            source = PluginSource.Builtin,
            kind = PluginKind.HostModule,
            hostModuleId = SPOTIFY_CONTROLLER,
            capabilities = listOf("Notifications", "Media control", "AI", "Voice"),
            accent = PluginAccent.Mint,
            icon = PluginIconKey.Music,
            featured = true,
            requiredKeys = listOf(
                PluginRequiredKey(
                    id = "spotify_client_id",
                    label = "Spotify Client ID",
                    description = "From developer.spotify.com — Redirect URI: https://grokifyos.grokpot.io/spotify-callback.php",
                    required = false,
                ),
                PluginRequiredKey(
                    id = "xai_api_key",
                    label = "xAI API key",
                    description = "Optional — Grok Voice TTS for Live DJ banter. Device TTS works without it.",
                    required = false,
                ),
            ),
        ),
    )

    private val byId: Map<String, PluginManifest> = all.associateBy { it.id }

    fun get(id: String): PluginManifest? = byId[id]

    fun isKnown(id: String): Boolean = byId.containsKey(id)
}
