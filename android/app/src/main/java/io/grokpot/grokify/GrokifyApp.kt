package io.grokpot.grokify

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.grokpot.grokify.data.TokenStore

class GrokifyApp : Application() {
    lateinit var tokenStore: TokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        tokenStore = TokenStore(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASSISTANT,
                getString(R.string.notification_channel_assistant),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    companion object {
        const val CHANNEL_ASSISTANT = "grokify_assistant"
        const val CHANNEL_UPDATES = "grokify_updates"

        lateinit var instance: GrokifyApp
            private set
    }
}
