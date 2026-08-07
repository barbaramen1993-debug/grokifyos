package io.grokify.os.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Background receiver so phone key / device-token pushes land even when HUD is closed.
 */
class PhoneKeyListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PhoneApiKeySync.MSG_PUSH_SPACEXAI -> {
                val key = messageEvent.data.toString(Charsets.UTF_8).trim()
                storeKey(key, "bg-message")
            }
            PhoneApiKeySync.MSG_PUSH_DEVICE_TOKEN -> {
                val token = messageEvent.data.toString(Charsets.UTF_8).trim()
                storeToken(token, "bg-message")
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                when {
                    path == PhoneApiKeySync.DATA_SPACEXAI ||
                        path.endsWith(PhoneApiKeySync.DATA_SPACEXAI) ->
                        storeKey(map.getString(PhoneApiKeySync.KEY_VALUE).orEmpty(), "bg-data")
                    path == PhoneApiKeySync.DATA_DEVICE_TOKEN ||
                        path.endsWith(PhoneApiKeySync.DATA_DEVICE_TOKEN) ->
                        storeToken(map.getString(PhoneApiKeySync.KEY_VALUE).orEmpty(), "bg-data")
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun storeKey(raw: String, source: String) {
        val key = raw.trim()
        if (key.isEmpty()) {
            Log.i(TAG, "empty key from $source")
            return
        }
        val prefs = WearPrefs(applicationContext)
        if (prefs.spaceXaiApiKey != key) {
            prefs.spaceXaiApiKey = key
            prefs.keySource = WearPrefs.SOURCE_PHONE
            Log.i(TAG, "stored key from $source len=${key.length}")
        } else {
            prefs.keySource = WearPrefs.SOURCE_PHONE
        }
    }

    private fun storeToken(raw: String, source: String) {
        val token = raw.trim()
        if (token.isEmpty()) {
            Log.i(TAG, "empty device token from $source")
            return
        }
        val prefs = WearPrefs(applicationContext)
        if (prefs.deviceToken != token) {
            prefs.deviceToken = token
            Log.i(TAG, "stored device token from $source len=${token.length}")
        }
    }

    companion object {
        private const val TAG = "PhoneKeyListener"
    }
}
