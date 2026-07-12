package io.grokify.os.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.MainActivity
import io.grokify.os.R

/**
 * Keeps the assistant bridge alive for background chat / hardware hooks.
 */
class GrokifyForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, GrokifyApp.CHANNEL_ASSISTANT)
            .setContentTitle(getString(R.string.fg_title))
            .setContentText(getString(R.string.fg_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 42001
    }
}
