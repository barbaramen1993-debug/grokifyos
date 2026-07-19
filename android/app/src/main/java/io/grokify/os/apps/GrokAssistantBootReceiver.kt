package io.grokify.os.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms Okay Grok wake listening after reboot / update.
 * Does not start the floating overlay — that is ephemeral (wake / manual Show only).
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
            }
        }
    }
}
