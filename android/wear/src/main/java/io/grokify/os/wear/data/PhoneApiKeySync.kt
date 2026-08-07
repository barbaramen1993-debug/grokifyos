package io.grokify.os.wear.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Pulls the SpaceXAI API key from the phone host over Wearable Data Layer / MessageClient.
 * Paths must match phone `io.grokify.os.wearbridge.WearApiKeyPaths`.
 */
class PhoneApiKeySync(
    context: Context,
    private val scope: CoroutineScope,
) : DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val app = context.applicationContext
    private val prefs = WearPrefs(app)
    private val rawPrefs = app.getSharedPreferences("grokify_wear", Context.MODE_PRIVATE)
    private val _hasKey = MutableStateFlow(prefs.spaceXaiApiKey.isNotBlank())
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private val _status = MutableStateFlow(
        if (prefs.spaceXaiApiKey.isNotBlank()) {
            "Key ready (${prefs.keySource})"
        } else {
            "No key yet — pair phone + Sync"
        },
    )
    val status: StateFlow<String> = _status.asStateFlow()

    private var started = false
    private var pollJob: Job? = null

    fun start() {
        if (started) {
            // Re-check local prefs (bg listener may have written while we were stopped).
            refreshFromLocal()
            requestFromPhone()
            return
        }
        started = true
        try {
            Wearable.getDataClient(app).addListener(this)
            Wearable.getMessageClient(app).addListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "addListener failed: ${e.message}")
            _status.value = "Wearable API unavailable"
        }
        try {
            rawPrefs.registerOnSharedPreferenceChangeListener(this)
        } catch (_: Exception) {
        }
        scope.launch {
            advertiseCapability()
            pullExistingDataItem()
            refreshFromLocal()
            // Retry a few times — nodes often appear a second after BT is up.
            requestFromPhoneWithRetries()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        pollJob?.cancel()
        pollJob = null
        runCatching { Wearable.getDataClient(app).removeListener(this) }
        runCatching { Wearable.getMessageClient(app).removeListener(this) }
        runCatching { rawPrefs.unregisterOnSharedPreferenceChangeListener(this) }
    }

    /** Ask phone nodes to re-push the vault key. */
    fun requestFromPhone() {
        scope.launch { requestFromPhoneWithRetries() }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "spacexai_api_key" || key == "spacexai_key_source") {
            refreshFromLocal()
        }
    }

    /** Ask phone for host device token (OTA auth). */
    fun requestDeviceTokenFromPhone() {
        scope.launch {
            val nodes = resolvePhoneNodes()
            for (node in nodes) {
                runCatching {
                    Wearable.getMessageClient(app)
                        .sendMessage(node.id, MSG_REQUEST_DEVICE_TOKEN, ByteArray(0))
                        .await()
                }
            }
            pullExistingDataItem()
        }
    }

    private fun refreshFromLocal() {
        val key = prefs.spaceXaiApiKey
        if (key.isNotBlank()) {
            _hasKey.value = true
            if (!_status.value.startsWith("Synced") && !_status.value.startsWith("Key ready")) {
                _status.value = "Key ready (${prefs.keySource})"
            }
        } else {
            _hasKey.value = false
        }
    }

    private suspend fun requestFromPhoneWithRetries() {
        if (prefs.spaceXaiApiKey.isNotBlank()) {
            _hasKey.value = true
            _status.value = "Key ready (${prefs.keySource})"
        }
        _status.value = "Looking for phone…"
        var lastErr: String? = null
        var reachedPhone = false
        repeat(6) { attempt ->
            val nodes = resolvePhoneNodes()
            if (nodes.isEmpty()) {
                lastErr = "No phone linked (BT pair Galaxy Wearable / Wear OS)"
                Log.i(TAG, "no phone nodes attempt=${attempt + 1}")
                delay(1_000L * (attempt + 1))
                return@repeat
            }
            var sent = 0
            for (node in nodes) {
                try {
                    Wearable.getMessageClient(app)
                        .sendMessage(node.id, MSG_REQUEST_SPACEXAI, ByteArray(0))
                        .await()
                    // Key request also triggers device-token push on modern phone builds;
                    // still ask explicitly for older hosts.
                    runCatching {
                        Wearable.getMessageClient(app)
                            .sendMessage(node.id, MSG_REQUEST_DEVICE_TOKEN, ByteArray(0))
                            .await()
                    }
                    sent++
                    Log.i(TAG, "requested key+token from ${node.displayName} (${node.id})")
                } catch (e: Exception) {
                    lastErr = e.message
                    Log.w(TAG, "request ${node.id}: ${e.message}")
                }
            }
            if (sent > 0) {
                reachedPhone = true
                _status.value = "Requested key from phone… ($sent)"
                // Pull DataItem + poll prefs (bg service may write before our Message listener fires).
                pullExistingDataItem()
                if (waitForKey(timeoutMs = 4_000L)) {
                    _status.value = "Synced from phone"
                    return
                }
                // One more request + longer wait — first reply often races process start.
                for (node in nodes) {
                    runCatching {
                        Wearable.getMessageClient(app)
                            .sendMessage(node.id, MSG_REQUEST_SPACEXAI, ByteArray(0))
                            .await()
                    }
                }
                if (waitForKey(timeoutMs = 6_000L)) {
                    _status.value = "Synced from phone"
                    return
                }
                // Distinguish "phone empty vault" vs "no reply".
                pullExistingDataItem()
                if (prefs.spaceXaiApiKey.isNotBlank()) {
                    _hasKey.value = true
                    _status.value = "Synced from phone"
                    return
                }
                _status.value =
                    "Phone answered but vault empty or old wear package. " +
                        "On phone: Watch Deploy → Push SpaceXAI key (or Settings → set spacexai_api_key)."
                return
            }
            delay(800)
        }
        if (!_hasKey.value) {
            _status.value = when {
                reachedPhone ->
                    "Phone linked — no key in reply. Watch Deploy → Push SpaceXAI key."
                else ->
                    (lastErr ?: "No phone link") +
                        " — pair watch, open phone GrokifyOS, or use Watch Deploy → Push key"
            }
        }
    }

    /** Poll until key lands (message, data item, or bg service prefs write). */
    private suspend fun waitForKey(timeoutMs: Long): Boolean {
        val steps = (timeoutMs / 250L).toInt().coerceAtLeast(1)
        repeat(steps) {
            if (_hasKey.value || prefs.spaceXaiApiKey.isNotBlank()) {
                if (prefs.spaceXaiApiKey.isNotBlank()) {
                    _hasKey.value = true
                }
                return true
            }
            pullExistingDataItem()
            delay(250)
        }
        return _hasKey.value || prefs.spaceXaiApiKey.isNotBlank()
    }

    private data class NodeRef(val id: String, val displayName: String)

    private suspend fun resolvePhoneNodes(): List<NodeRef> {
        val out = LinkedHashMap<String, NodeRef>()
        try {
            for (n in Wearable.getNodeClient(app).connectedNodes.await()) {
                out[n.id] = NodeRef(n.id, n.displayName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes: ${e.message}")
        }
        // Capability path (phone advertises CAPABILITY_HOST).
        for (filter in listOf(CapabilityClient.FILTER_REACHABLE, CapabilityClient.FILTER_ALL)) {
            try {
                val cap = Wearable.getCapabilityClient(app)
                    .getCapability(CAPABILITY_HOST, filter)
                    .await()
                for (n in cap.nodes) {
                    out[n.id] = NodeRef(n.id, n.displayName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getCapability host filter=$filter: ${e.message}")
            }
        }
        return out.values.toList()
    }

    private suspend fun advertiseCapability() {
        try {
            Wearable.getCapabilityClient(app)
                .addLocalCapability(CAPABILITY_WEAR)
                .await()
            Log.i(TAG, "advertised $CAPABILITY_WEAR")
        } catch (e: Exception) {
            Log.w(TAG, "addLocalCapability: ${e.message}")
        }
    }

    private suspend fun pullExistingDataItem() = withContext(Dispatchers.IO) {
        try {
            val buffer = Wearable.getDataClient(app).dataItems.await()
            try {
                for (item in buffer) {
                    val path = item.uri.path ?: continue
                    when {
                        path == DATA_SPACEXAI || path.endsWith(DATA_SPACEXAI) -> {
                            val map = DataMapItem.fromDataItem(item).dataMap
                            applyKey(map.getString(KEY_VALUE).orEmpty(), source = "dataItem")
                        }
                        path == DATA_DEVICE_TOKEN || path.endsWith(DATA_DEVICE_TOKEN) -> {
                            val map = DataMapItem.fromDataItem(item).dataMap
                            applyDeviceToken(map.getString(KEY_VALUE).orEmpty(), source = "dataItem")
                        }
                    }
                }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "pullExistingDataItem: ${e.message}")
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                when {
                    path == DATA_SPACEXAI || path.endsWith(DATA_SPACEXAI) ->
                        applyKey(map.getString(KEY_VALUE).orEmpty(), source = "onDataChanged")
                    path == DATA_DEVICE_TOKEN || path.endsWith(DATA_DEVICE_TOKEN) ->
                        applyDeviceToken(map.getString(KEY_VALUE).orEmpty(), source = "onDataChanged")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onDataChanged: ${e.message}")
        } finally {
            dataEvents.release()
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            MSG_PUSH_SPACEXAI -> {
                val key = messageEvent.data.toString(Charsets.UTF_8)
                applyKey(key, source = "message")
            }
            MSG_PUSH_DEVICE_TOKEN -> {
                val token = messageEvent.data.toString(Charsets.UTF_8)
                applyDeviceToken(token, source = "message")
            }
        }
    }

    private fun applyKey(raw: String, source: String) {
        val key = raw.trim()
        if (key.isEmpty()) {
            Log.i(TAG, "empty key from $source (keeping local if any)")
            _hasKey.value = prefs.spaceXaiApiKey.isNotBlank()
            if (!_hasKey.value) {
                _status.value = "Phone vault empty — add SpaceXAI key on phone Settings"
            }
            return
        }
        if (prefs.spaceXaiApiKey != key) {
            prefs.spaceXaiApiKey = key
            prefs.keySource = WearPrefs.SOURCE_PHONE
            Log.i(TAG, "stored phone key from $source len=${key.length}")
        } else {
            prefs.keySource = WearPrefs.SOURCE_PHONE
        }
        _hasKey.value = true
        _status.value = "Synced from phone"
    }

    private fun applyDeviceToken(raw: String, source: String) {
        val token = raw.trim()
        if (token.isEmpty()) {
            Log.i(TAG, "empty device token from $source")
            return
        }
        if (prefs.deviceToken != token) {
            prefs.deviceToken = token
            Log.i(TAG, "stored device token from $source len=${token.length}")
        }
    }

    companion object {
        private const val TAG = "PhoneApiKeySync"
        const val DATA_SPACEXAI = "/grokify/api/spacexai"
        const val DATA_DEVICE_TOKEN = "/grokify/api/device_token"
        const val MSG_REQUEST_SPACEXAI = "/grokify/api/request_spacexai"
        const val MSG_PUSH_SPACEXAI = "/grokify/api/push_spacexai"
        const val MSG_REQUEST_DEVICE_TOKEN = "/grokify/api/request_device_token"
        const val MSG_PUSH_DEVICE_TOKEN = "/grokify/api/push_device_token"
        const val KEY_VALUE = "value"
        const val CAPABILITY_HOST = "grokify_host"
        const val CAPABILITY_WEAR = "grokify_wear"
    }
}
