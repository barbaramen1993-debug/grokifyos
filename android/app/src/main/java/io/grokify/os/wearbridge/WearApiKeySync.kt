package io.grokify.os.wearbridge

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Pushes SpaceXAI vault key + host device token to Wear via Data Layer / MessageClient.
 * Answers watch requests while the host process is alive (listener service covers bg).
 */
object WearApiKeySync : MessageClient.OnMessageReceivedListener {
    private const val TAG = "WearApiKeySync"
    private var job: Job? = null
    private var appCtx: Context? = null
    private var tokenStore: TokenStore? = null

    fun start(context: Context, tokenStore: TokenStore, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        appCtx = app
        this.tokenStore = tokenStore
        try {
            Wearable.getMessageClient(app).addListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "addMessageListener: ${e.message}")
        }
        job = scope.launch {
            advertiseCapability(app)
            // Initial push (covers cold start when vault already has a key).
            pushCurrent(app)
            // Re-push a few times while BT/Wear link comes up after boot.
            launch {
                repeat(3) { i ->
                    kotlinx.coroutines.delay(2_500L * (i + 1))
                    pushCurrent(app)
                }
            }
            launch {
                tokenStore.tokenFlow
                    .map { it?.trim().orEmpty() }
                    .distinctUntilChanged()
                    .collect { token ->
                        pushDeviceToken(app, token)
                    }
            }
            tokenStore.apiKeyVaultFlow
                .map { vault ->
                    vault[ApiKeyIds.SPACEXAI]?.value?.trim().orEmpty()
                        .ifBlank { vault[ApiKeyIds.LEGACY_XAI]?.value?.trim().orEmpty() }
                }
                .distinctUntilChanged()
                .collect { key ->
                    pushKey(app, key)
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        appCtx?.let { ctx ->
            runCatching { Wearable.getMessageClient(ctx).removeListener(this) }
        }
        appCtx = null
        tokenStore = null
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val app = appCtx ?: return
        val source = messageEvent.sourceNodeId
        when (messageEvent.path) {
            WearApiKeyPaths.MSG_REQUEST_SPACEXAI -> {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val key = HostApiKeyStore.getValue(app, ApiKeyIds.SPACEXAI).orEmpty()
                            .ifBlank { HostApiKeyStore.getValue(app, ApiKeyIds.LEGACY_XAI).orEmpty() }
                        pushKey(app, key)
                        Wearable.getMessageClient(app)
                            .sendMessage(
                                source,
                                WearApiKeyPaths.MSG_PUSH_SPACEXAI,
                                key.toByteArray(Charsets.UTF_8),
                            )
                            .await()
                        // Also push token on key request (watch OTA needs it).
                        val token = readDeviceToken(app)
                        if (token.isNotBlank()) {
                            pushDeviceToken(app, token)
                            Wearable.getMessageClient(app)
                                .sendMessage(
                                    source,
                                    WearApiKeyPaths.MSG_PUSH_DEVICE_TOKEN,
                                    token.toByteArray(Charsets.UTF_8),
                                )
                                .await()
                        }
                        Log.i(TAG, "in-process answered key request from $source len=${key.length}")
                    } catch (e: Exception) {
                        Log.w(TAG, "in-process key answer failed: ${e.message}")
                    }
                }
            }
            WearApiKeyPaths.MSG_REQUEST_DEVICE_TOKEN -> {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val token = readDeviceToken(app)
                        pushDeviceToken(app, token)
                        Wearable.getMessageClient(app)
                            .sendMessage(
                                source,
                                WearApiKeyPaths.MSG_PUSH_DEVICE_TOKEN,
                                token.toByteArray(Charsets.UTF_8),
                            )
                            .await()
                        Log.i(TAG, "in-process answered token request from $source len=${token.length}")
                    } catch (e: Exception) {
                        Log.w(TAG, "in-process token answer failed: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun pushCurrent(context: Context) {
        val app = context.applicationContext
        val key = HostApiKeyStore.getValue(app, ApiKeyIds.SPACEXAI).orEmpty()
            .ifBlank { HostApiKeyStore.getValue(app, ApiKeyIds.LEGACY_XAI).orEmpty() }
        pushKey(app, key)
        pushDeviceToken(app, readDeviceToken(app))
    }

    suspend fun pushKey(context: Context, key: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cleaned = key.trim()
        try {
            val req = PutDataMapRequest.create(WearApiKeyPaths.DATA_SPACEXAI).apply {
                dataMap.putString(WearApiKeyPaths.KEY_VALUE, cleaned)
                // Always bump timestamp so Wear Data Layer delivers even if value unchanged.
                dataMap.putLong(WearApiKeyPaths.KEY_UPDATED_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(app).putDataItem(req).await()
            Log.i(TAG, "DataItem put spacexai len=${cleaned.length}")
        } catch (e: Exception) {
            Log.w(TAG, "putDataItem failed: ${e.message}")
        }
        broadcastMessage(app, WearApiKeyPaths.MSG_PUSH_SPACEXAI, cleaned)
    }

    suspend fun pushDeviceToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cleaned = token.trim()
        if (cleaned.isEmpty()) {
            Log.i(TAG, "skip empty device token push")
            return@withContext
        }
        try {
            val req = PutDataMapRequest.create(WearApiKeyPaths.DATA_DEVICE_TOKEN).apply {
                dataMap.putString(WearApiKeyPaths.KEY_VALUE, cleaned)
                dataMap.putLong(WearApiKeyPaths.KEY_UPDATED_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(app).putDataItem(req).await()
            Log.i(TAG, "DataItem put device_token len=${cleaned.length}")
        } catch (e: Exception) {
            Log.w(TAG, "putDataItem device_token failed: ${e.message}")
        }
        broadcastMessage(app, WearApiKeyPaths.MSG_PUSH_DEVICE_TOKEN, cleaned)
    }

    private suspend fun broadcastMessage(app: Context, path: String, payload: String) {
        val nodes = resolveWearNodes(app)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        for (node in nodes) {
            try {
                Wearable.getMessageClient(app)
                    .sendMessage(node.id, path, bytes)
                    .await()
                Log.i(TAG, "pushed $path → ${node.displayName} (${node.id}) len=${payload.length}")
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage $path ${node.id}: ${e.message}")
            }
        }
        if (nodes.isEmpty()) {
            Log.i(TAG, "no wear nodes reachable for $path")
        }
    }

    private suspend fun readDeviceToken(context: Context): String {
        tokenStore?.let { store ->
            return store.tokenFlow.first()?.trim().orEmpty()
        }
        return try {
            TokenStore(context.applicationContext).tokenFlow.first()?.trim().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "readDeviceToken: ${e.message}")
            ""
        }
    }

    private suspend fun advertiseCapability(app: Context) {
        try {
            Wearable.getCapabilityClient(app)
                .addLocalCapability(WearApiKeyPaths.CAPABILITY_HOST)
                .await()
            Log.i(TAG, "advertised ${WearApiKeyPaths.CAPABILITY_HOST}")
        } catch (e: Exception) {
            Log.w(TAG, "addLocalCapability: ${e.message}")
        }
    }

    private data class NodeRef(val id: String, val displayName: String)

    private suspend fun resolveWearNodes(app: Context): List<NodeRef> {
        val out = LinkedHashMap<String, NodeRef>()
        try {
            for (n in Wearable.getNodeClient(app).connectedNodes.await()) {
                out[n.id] = NodeRef(n.id, n.displayName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes: ${e.message}")
        }
        try {
            val cap = Wearable.getCapabilityClient(app)
                .getCapability(WearApiKeyPaths.CAPABILITY_WEAR, CapabilityClient.FILTER_REACHABLE)
                .await()
            for (n in cap.nodes) {
                out[n.id] = NodeRef(n.id, n.displayName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCapability wear: ${e.message}")
        }
        try {
            val cap = Wearable.getCapabilityClient(app)
                .getCapability(WearApiKeyPaths.CAPABILITY_WEAR, CapabilityClient.FILTER_ALL)
                .await()
            for (n in cap.nodes) {
                out[n.id] = NodeRef(n.id, n.displayName)
            }
        } catch (_: Exception) {
        }
        return out.values.toList()
    }
}
