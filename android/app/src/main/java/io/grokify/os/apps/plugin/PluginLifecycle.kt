package io.grokify.os.apps.plugin

import android.content.Context
import android.util.Log
import io.grokify.os.apps.setSpotifyControllerEnabled
import io.grokify.os.apps.setSpotifyLiveDjEnabled

/**
 * Host-side install/uninstall hooks so plugins do not leave services running
 * after the user removes them from the hub.
 */
object PluginLifecycle {
    private const val TAG = "PluginLifecycle"

    fun onInstalled(context: Context, id: String, manifest: PluginManifest?) {
        Log.i(TAG, "installed plugin=$id kind=${manifest?.kind}")
        // Host modules need no extra work; WebView packages are downloaded by the caller.
    }

    fun onUninstalled(context: Context, id: String, manifest: PluginManifest?) {
        Log.i(TAG, "uninstalled plugin=$id kind=${manifest?.kind}")
        val appCtx = context.applicationContext
        val hostId = manifest?.resolvedHostModuleId() ?: id

        // Stop host-module background work
        when (hostId) {
            BuiltinPluginCatalog.SPOTIFY_CONTROLLER, "spotify_dj" -> {
                try {
                    setSpotifyControllerEnabled(appCtx, false)
                } catch (e: Exception) {
                    Log.w(TAG, "stop Spotify controller: ${e.message}")
                }
                try {
                    setSpotifyLiveDjEnabled(appCtx, false)
                } catch (e: Exception) {
                    Log.w(TAG, "stop Live DJ: ${e.message}")
                }
            }
            BuiltinPluginCatalog.PLACE_NOTES -> {
                try {
                    io.grokify.os.apps.LocationNoteWatcher.setEnabled(appCtx, false)
                } catch (e: Exception) {
                    Log.w(TAG, "stop place-notes monitoring: ${e.message}")
                }
            }
        }

        // Always try to drop any cached package files for this id
        try {
            PluginPackageStore(appCtx).uninstallPackage(id)
            if (hostId != id) PluginPackageStore(appCtx).uninstallPackage(hostId)
        } catch (e: Exception) {
            Log.w(TAG, "remove package files: ${e.message}")
        }
    }
}
