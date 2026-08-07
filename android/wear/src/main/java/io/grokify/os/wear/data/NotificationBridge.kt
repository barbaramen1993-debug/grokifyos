package io.grokify.os.wear.data

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional bridge for media + message-like notifications.
 * Requires the user to enable notification access for Grokify Wear.
 */
object NotificationBridge {
    @Volatile var lastMediaTitle: String? = null
    @Volatile var lastMediaArtist: String? = null
    @Volatile var lastMessage: String? = null
}

class WearNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val isMedia = n.category == Notification.CATEGORY_TRANSPORT ||
            (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        val isMsg = n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_EMAIL ||
            n.category == Notification.CATEGORY_SOCIAL

        if (isMedia) {
            NotificationBridge.lastMediaTitle = title.ifEmpty { text }.take(48)
            NotificationBridge.lastMediaArtist = text.take(48).ifEmpty { null }
        } else if (isMsg || title.isNotEmpty()) {
            val line = when {
                title.isNotEmpty() && text.isNotEmpty() -> "$title: $text"
                title.isNotEmpty() -> title
                else -> text
            }.take(64)
            NotificationBridge.lastMessage = line
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
