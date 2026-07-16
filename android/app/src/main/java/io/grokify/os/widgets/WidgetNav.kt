package io.grokify.os.widgets

import android.content.Context
import android.content.Intent
import io.grokify.os.MainActivity
import io.grokify.os.apps.plugin.BuiltinPluginCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deep-link navigation from home-screen widgets into the Apps hub / inner modules.
 */
object WidgetNav {
    const val EXTRA_OPEN_PLUGIN = "io.grokify.os.EXTRA_OPEN_PLUGIN"
    const val EXTRA_SPOTIFY_TAB = "io.grokify.os.EXTRA_SPOTIFY_TAB"
    const val ACTION_OPEN_PLUGIN = "io.grokify.os.OPEN_PLUGIN"

    data class Request(
        val pluginId: String,
        /** Spotify hub tab: 0 Control, 1 Live DJ, 2 Build, 3 Account. */
        val spotifyTab: Int? = null,
        val nonce: Long = System.nanoTime(),
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** One-shot Spotify tab applied when [SpotifyControllerPane] mounts. */
    @Volatile
    var pendingSpotifyTab: Int? = null

    fun openPlugin(pluginId: String, spotifyTab: Int? = null) {
        val resolved = when (pluginId) {
            "spotify_dj" -> BuiltinPluginCatalog.SPOTIFY_CONTROLLER
            else -> pluginId
        }
        if (spotifyTab != null) pendingSpotifyTab = spotifyTab
        _pending.value = Request(pluginId = resolved, spotifyTab = spotifyTab)
    }

    fun consume(): Request? {
        val cur = _pending.value
        _pending.value = null
        return cur
    }

    fun consumeSpotifyTab(): Int? {
        val t = pendingSpotifyTab
        pendingSpotifyTab = null
        return t
    }

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val plugin = intent.getStringExtra(EXTRA_OPEN_PLUGIN)?.trim().orEmpty()
        if (plugin.isBlank()) return
        val tab = intent.getIntExtra(EXTRA_SPOTIFY_TAB, -1).takeIf { it in 0..3 }
        openPlugin(plugin, tab)
        // Consume so cold-start + onNewIntent don't double-apply if re-delivered.
        intent.removeExtra(EXTRA_OPEN_PLUGIN)
        intent.removeExtra(EXTRA_SPOTIFY_TAB)
    }

    fun openPluginIntent(
        context: Context,
        pluginId: String,
        spotifyTab: Int? = null,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_PLUGIN
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_OPEN_PLUGIN, pluginId)
        if (spotifyTab != null) putExtra(EXTRA_SPOTIFY_TAB, spotifyTab)
    }
}
