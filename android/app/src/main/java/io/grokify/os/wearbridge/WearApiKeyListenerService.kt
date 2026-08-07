package io.grokify.os.wearbridge

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * Background Wearable listener: watch requests for SpaceXAI key / device token.
 * Uses [runBlocking] so replies finish before the service is torn down.
 */
class WearApiKeyListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val app = applicationContext
        val source = messageEvent.sourceNodeId
        when (messageEvent.path) {
            WearApiKeyPaths.MSG_REQUEST_SPACEXAI -> runBlocking {
                try {
                    val key = HostApiKeyStore.getValue(app, ApiKeyIds.SPACEXAI).orEmpty()
                        .ifBlank { HostApiKeyStore.getValue(app, ApiKeyIds.LEGACY_XAI).orEmpty() }
                    Log.i(TAG, "key request from $source vaultLen=${key.length}")
                    WearApiKeySync.pushKey(app, key)
                    Wearable.getMessageClient(app)
                        .sendMessage(
                            source,
                            WearApiKeyPaths.MSG_PUSH_SPACEXAI,
                            key.toByteArray(Charsets.UTF_8),
                        )
                        .await()
                    val token = TokenStore(app).tokenFlow.first()?.trim().orEmpty()
                    if (token.isNotBlank()) {
                        WearApiKeySync.pushDeviceToken(app, token)
                        Wearable.getMessageClient(app)
                            .sendMessage(
                                source,
                                WearApiKeyPaths.MSG_PUSH_DEVICE_TOKEN,
                                token.toByteArray(Charsets.UTF_8),
                            )
                            .await()
                    }
                    Log.i(TAG, "answered key request from $source len=${key.length}")
                } catch (e: Exception) {
                    Log.w(TAG, "key request failed: ${e.message}")
                }
            }
            WearApiKeyPaths.MSG_REQUEST_DEVICE_TOKEN -> runBlocking {
                try {
                    val token = TokenStore(app).tokenFlow.first()?.trim().orEmpty()
                    Log.i(TAG, "token request from $source len=${token.length}")
                    WearApiKeySync.pushDeviceToken(app, token)
                    Wearable.getMessageClient(app)
                        .sendMessage(
                            source,
                            WearApiKeyPaths.MSG_PUSH_DEVICE_TOKEN,
                            token.toByteArray(Charsets.UTF_8),
                        )
                        .await()
                    Log.i(TAG, "answered token request from $source")
                } catch (e: Exception) {
                    Log.w(TAG, "token request failed: ${e.message}")
                }
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name != WearApiKeyPaths.CAPABILITY_WEAR) return
        if (capabilityInfo.nodes.isEmpty()) return
        runBlocking {
            try {
                WearApiKeySync.pushCurrent(applicationContext)
                Log.i(TAG, "capability wear online — pushed key+token to ${capabilityInfo.nodes.size}")
            } catch (e: Exception) {
                Log.w(TAG, "capability push: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WearApiKeyListener"
    }
}
