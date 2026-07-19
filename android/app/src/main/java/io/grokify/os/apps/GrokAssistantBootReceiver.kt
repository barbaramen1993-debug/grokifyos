package io.grokify.os.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms Hey Grok wake listening (and overlay if prefs say so) after reboot / update.
 */
class GrokAssistantBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> {
                val app = context.applicationContext
                val store = GrokAssistantStore(app)
                if (!store.enabled) return
                GrokAssistantWakeService.sync(app)
                if (store.overlayEnabled && GrokAssistantOverlayService.canDrawOverlays(app)) {
                    // Collapsed bubble after boot — less intrusive.
                    GrokAssistantOverlayService.start(app, expand = false)
                }
            }
        }
    }
}
