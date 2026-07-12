package io.grokpot.grokify.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads device notifications for assistant context (user must enable in system settings).
 * Future: forward summaries to Grokify bridge when user opts in.
 */
class GrokifyNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Reserved for assistant context ingestion
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // no-op
    }
}
