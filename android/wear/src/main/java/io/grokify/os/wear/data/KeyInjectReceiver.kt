package io.grokify.os.wear.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log

/**
 * Accepts a SpaceXAI key pushed from the phone via wireless ADB (Watch Deploy).
 * Backup path when the Wear Data Layer link is slow or not yet up after install.
 *
 * Prefer base64 extra (shell-safe):
 *   adb shell am broadcast -n <pkg>/io.grokify.os.wear.data.KeyInjectReceiver \
 *     -a io.grokify.os.INJECT_SPACEXAI_KEY --es value_b64 '<base64>'
 */
class KeyInjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INJECT) return
        val key = decodeKey(intent)
        if (key.isEmpty()) {
            Log.w(TAG, "inject ignored: empty value")
            return
        }
        val prefs = WearPrefs(context.applicationContext)
        prefs.spaceXaiApiKey = key
        prefs.keySource = WearPrefs.SOURCE_PHONE
        Log.i(TAG, "injected key via ADB/broadcast len=${key.length}")
    }

    private fun decodeKey(intent: Intent): String {
        val b64 = intent.getStringExtra(EXTRA_VALUE_B64)?.trim().orEmpty()
        if (b64.isNotEmpty()) {
            return try {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (e: Exception) {
                Log.w(TAG, "bad value_b64: ${e.message}")
                ""
            }
        }
        return intent.getStringExtra(EXTRA_VALUE)?.trim().orEmpty()
    }

    companion object {
        private const val TAG = "KeyInjectReceiver"
        const val ACTION_INJECT = "io.grokify.os.INJECT_SPACEXAI_KEY"
        const val EXTRA_VALUE = "value"
        const val EXTRA_VALUE_B64 = "value_b64"
    }
}
