package io.grokify.os

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.grokify.os.data.TokenStore

class GrokifyApp : Application() {
    lateinit var tokenStore: TokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenStore = TokenStore(this)
        createChannels()
        // Re-arm place-note geofence location updates after process start.
        runCatching { io.grokify.os.apps.LocationNoteWatcher.sync(this) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // FGS keep-alive: use MIN so it sinks below the fold (no heads-up / badge).
        // New channel id — importance is immutable once a channel exists on device.
        runCatching { nm.deleteNotificationChannel("grokify_assistant") }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASSISTANT,
                getString(R.string.notification_channel_assistant),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_assistant_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEARBY_WIFI,
                getString(R.string.notification_channel_nearby_wifi),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_nearby_wifi_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEARBY_BT,
                getString(R.string.notification_channel_nearby_bt),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_nearby_bt_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLACE_NOTES,
                getString(R.string.notification_channel_place_notes),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_place_notes_desc)
            }
        )
        // Delete the old LOW channel so devices that already installed 0.1.33
        // pick up DEFAULT importance (LOW often stays off the lockscreen).
        runCatching { nm.deleteNotificationChannel("grokify_spotify_ctrl") }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SPOTIFY_CTRL,
                getString(R.string.notification_channel_spotify_ctrl),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_spotify_ctrl_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SPOTIFY_DJ,
                getString(R.string.notification_channel_spotify_dj),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_spotify_dj_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SPACEXAI_USAGE,
                getString(R.string.notification_channel_spacexai_usage),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_channel_spacexai_usage_desc)
            }
        )
        // Re-arm lockscreen Spotify controller after process start
        runCatching {
            if (io.grokify.os.apps.SpotifyControllerStore(this).enabled) {
                io.grokify.os.apps.setSpotifyControllerEnabled(this, true)
            }
        }
        // Live DJ: resume only when “resume after restart” is on (default).
        // Does not wipe enabled on transient FGS start failures after OTA.
        runCatching { io.grokify.os.apps.maybeResumeLiveDj(this) }
        runCatching {
            if (io.grokify.os.apps.SpaceXaiUsageAlertStore(this).enabled) {
                io.grokify.os.apps.scheduleUsageAlertChecks(this)
            }
        }
    }

    companion object {
        // v2: IMPORTANCE_MIN quiet keep-alive (old "grokify_assistant" was LOW)
        const val CHANNEL_ASSISTANT = "grokify_assistant_min"
        const val CHANNEL_UPDATES = "grokify_updates"
        const val CHANNEL_NEARBY_WIFI = "grokify_nearby_wifi"
        const val CHANNEL_NEARBY_BT = "grokify_nearby_bt"
        const val CHANNEL_PLACE_NOTES = "grokify_place_notes"
        // v2 id: forces a fresh channel on upgrade (importance is immutable once created)
        const val CHANNEL_SPOTIFY_CTRL = "grokify_spotify_ctrl_v2"
        const val CHANNEL_SPOTIFY_DJ = "grokify_spotify_dj"
        const val CHANNEL_SPACEXAI_USAGE = "grokify_spacexai_usage"

        lateinit var instance: GrokifyApp
            private set
    }
}
