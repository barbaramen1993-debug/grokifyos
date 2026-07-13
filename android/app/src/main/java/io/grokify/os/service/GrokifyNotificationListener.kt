package io.grokify.os.service

import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads device notifications for assistant context.
 * User must enable GrokifyOS under system Settings → Notification access.
 * Snapshots are mirrored to the server so Grok can pull them.
 */
class GrokifyNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationMirror.setListenerBound(true)
        try {
            NotificationMirror.replaceAll(this, activeNotifications)
        } catch (e: Exception) {
            Log.w(TAG, "activeNotifications: ${e.message}")
        }
        Log.i(TAG, "listener connected; count=${NotificationMirror.snapshot().size}")
    }

    override fun onListenerDisconnected() {
        NotificationMirror.setListenerBound(false)
        super.onListenerDisconnected()
        Log.i(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        NotificationMirror.upsertFromSbn(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NotificationMirror.removeFromSbn(sbn)
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        private const val TAG = "GrokifyNotifListener"
    }
}
