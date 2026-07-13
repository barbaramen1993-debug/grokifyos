package io.grokify.os.apps

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import io.grokify.os.GrokifyApp
import io.grokify.os.MainActivity
import io.grokify.os.permission.AppPermissionId
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/** Sort order for the live device list. */
enum class BtSortMode(val label: String) {
    DISTANCE("Distance"),
    SIGNAL("Signal"),
    SEEN("Times seen"),
    NAME("Name"),
}

/** Radio path for a discovered device. */
enum class BtRadioKind(val label: String) {
    BLE("BLE"),
    CLASSIC("Classic"),
    DUAL("Dual"),
}

data class BtDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val radio: BtRadioKind,
    val deviceClassLabel: String = "",
    val bondLabel: String = "",
    val services: String = "",
    val manufacturer: String = "",
    val seenAtMs: Long = System.currentTimeMillis(),
    val distanceM: Double? = null,
    val seenCount: Int = 1,
    val firstSighting: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
)

data class BtScanSnapshot(
    val id: Long,
    val atMs: Long,
    val devices: List<BtDevice>,
    val gps: GpsFix? = null,
)

enum class BtWatchKind {
    NAME,
    ADDRESS,
}

data class BtAlertWatch(
    val id: String,
    val kind: BtWatchKind,
    val pattern: String,
) {
    fun label(): String = when (kind) {
        BtWatchKind.NAME -> "Name · $pattern"
        BtWatchKind.ADDRESS -> "MAC · $pattern"
    }

    fun matches(dev: BtDevice): Boolean = when (kind) {
        BtWatchKind.NAME -> dev.name.equals(pattern, ignoreCase = true)
        BtWatchKind.ADDRESS -> normalizeMac(dev.address) == normalizeMac(pattern)
    }
}

enum class BtAlertReason {
    STRONG_NEARBY,
    UNSEEN,
    WATCHED,
}

data class BtAlertHit(
    val device: BtDevice,
    val reasons: Set<BtAlertReason>,
)

/**
 * BLE/classic path-loss estimate at 2.4 GHz (meters). Rough; good enough for ranking.
 * d = 10 ^ ((27.55 - 20·log10(fMHz) + |RSSI|) / 20)
 */
fun estimateBtDistanceM(rssiDbm: Int): Double {
    val f = 2400.0
    val exp = (27.55 - 20.0 * kotlin.math.log10(f) + kotlin.math.abs(rssiDbm.toDouble())) / 20.0
    return 10.0.pow(exp).coerceIn(0.3, 5000.0)
}

fun BtDevice.isNearby(): Boolean {
    val d = distanceM
    return rssi >= -60 || (d != null && d <= 8.0)
}

fun bluetoothPermsOk(context: Context): Boolean =
    PermissionHelper.status(context, AppPermissionId.BLUETOOTH).granted

/** Persisted address ledger + alert prefs for Bluetooth tracker. */
class BtSightingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(
        val address: String,
        val name: String,
        val count: Int,
        val lat: Double?,
        val lon: Double?,
        val lastSeenMs: Long,
        val firstSighting: Boolean = false,
    )

    fun get(address: String): Entry? {
        val raw = prefs.getString(key(address), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Entry(
                address = address,
                name = o.optString("name", ""),
                count = o.optInt("count", 0),
                lat = o.optDouble("lat").takeIf { o.has("lat") && !o.isNull("lat") },
                lon = o.optDouble("lon").takeIf { o.has("lon") && !o.isNull("lon") },
                lastSeenMs = o.optLong("lastSeenMs", 0L),
                firstSighting = false,
            )
        }.getOrNull()
    }

    fun record(address: String, name: String, gps: GpsFix?): Entry {
        val prev = get(address)
        val first = prev == null || prev.count <= 0
        val next = Entry(
            address = address,
            name = name.ifBlank { prev?.name.orEmpty() },
            count = (prev?.count ?: 0) + 1,
            lat = gps?.lat ?: prev?.lat,
            lon = gps?.lon ?: prev?.lon,
            lastSeenMs = System.currentTimeMillis(),
            firstSighting = first,
        )
        val o = JSONObject()
            .put("name", next.name)
            .put("count", next.count)
            .put("lastSeenMs", next.lastSeenMs)
        next.lat?.let { o.put("lat", it) }
        next.lon?.let { o.put("lon", it) }
        prefs.edit().putString(key(address), o.toString()).apply()
        return next
    }

    fun nearbyAlertsEnabled(): Boolean = prefs.getBoolean(KEY_ALERTS, false)

    fun setNearbyAlertsEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALERTS, on).apply()
    }

    fun alertStrongNearby(): Boolean = prefs.getBoolean(KEY_ALERT_STRONG, true)

    fun setAlertStrongNearby(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_STRONG, on).apply()
    }

    fun alertUnseen(): Boolean = prefs.getBoolean(KEY_ALERT_UNSEEN, true)

    fun setAlertUnseen(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_UNSEEN, on).apply()
    }

    fun alertWatched(): Boolean = prefs.getBoolean(KEY_ALERT_WATCHED, true)

    fun setAlertWatched(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_WATCHED, on).apply()
    }

    fun watches(): List<BtAlertWatch> {
        val raw = prefs.getString(KEY_WATCHES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = runCatching {
                        BtWatchKind.valueOf(o.optString("kind", BtWatchKind.NAME.name))
                    }.getOrDefault(BtWatchKind.NAME)
                    val pattern = o.optString("pattern", "").trim()
                    if (pattern.isEmpty()) continue
                    add(
                        BtAlertWatch(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            kind = kind,
                            pattern = pattern,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setWatches(list: List<BtAlertWatch>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("kind", w.kind.name)
                    .put("pattern", w.pattern),
            )
        }
        prefs.edit().putString(KEY_WATCHES, arr.toString()).apply()
    }

    fun addWatch(kind: BtWatchKind, pattern: String): List<BtAlertWatch> {
        val p = pattern.trim()
        if (p.isEmpty()) return watches()
        val normalized = if (kind == BtWatchKind.ADDRESS) formatMacDisplay(p) else p
        val cur = watches().toMutableList()
        val exists = cur.any {
            it.kind == kind && when (kind) {
                BtWatchKind.NAME -> it.pattern.equals(normalized, ignoreCase = true)
                BtWatchKind.ADDRESS -> normalizeMac(it.pattern) == normalizeMac(normalized)
            }
        }
        if (!exists) {
            cur.add(
                BtAlertWatch(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    pattern = normalized,
                ),
            )
            setWatches(cur)
        }
        return watches()
    }

    fun removeWatch(id: String): List<BtAlertWatch> {
        setWatches(watches().filterNot { it.id == id })
        return watches()
    }

    fun sortMode(): BtSortMode {
        val raw = prefs.getString(KEY_SORT, BtSortMode.DISTANCE.name) ?: BtSortMode.DISTANCE.name
        return runCatching { BtSortMode.valueOf(raw) }.getOrDefault(BtSortMode.DISTANCE)
    }

    fun setSortMode(mode: BtSortMode) {
        prefs.edit().putString(KEY_SORT, mode.name).apply()
    }

    fun allLocated(): List<Entry> {
        val out = ArrayList<Entry>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith("dev_") || v !is String) continue
            val address = k.removePrefix("dev_")
            runCatching {
                val o = JSONObject(v)
                if (!o.has("lat") || o.isNull("lat") || !o.has("lon") || o.isNull("lon")) return@runCatching
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@runCatching
                out.add(
                    Entry(
                        address = address,
                        name = o.optString("name", ""),
                        count = o.optInt("count", 1),
                        lat = lat,
                        lon = lon,
                        lastSeenMs = o.optLong("lastSeenMs", 0L),
                        firstSighting = false,
                    ),
                )
            }
        }
        return out
    }

    companion object {
        private const val PREFS = "bt_scanner"
        private const val KEY_ALERTS = "nearby_alerts"
        private const val KEY_ALERT_STRONG = "alert_strong_nearby"
        private const val KEY_ALERT_UNSEEN = "alert_unseen"
        private const val KEY_ALERT_WATCHED = "alert_watched"
        private const val KEY_WATCHES = "alert_watches"
        private const val KEY_SORT = "sort_mode"
        private fun key(address: String) = "dev_${address.lowercase(Locale.US)}"
    }
}

fun sortBtList(list: List<BtDevice>, mode: BtSortMode): List<BtDevice> = when (mode) {
    BtSortMode.DISTANCE -> list.sortedWith(
        compareBy<BtDevice> { it.distanceM ?: Double.MAX_VALUE }
            .thenByDescending { it.rssi }
            .thenBy { it.name.lowercase(Locale.US) },
    )
    BtSortMode.SIGNAL -> list.sortedWith(
        compareByDescending<BtDevice> { it.rssi }.thenBy { it.name.lowercase(Locale.US) },
    )
    BtSortMode.SEEN -> list.sortedWith(
        compareByDescending<BtDevice> { it.seenCount }
            .thenByDescending { it.rssi }
            .thenBy { it.name.lowercase(Locale.US) },
    )
    BtSortMode.NAME -> list.sortedWith(
        compareBy<BtDevice> { it.name.lowercase(Locale.US) }.thenByDescending { it.rssi },
    )
}

fun collectBtAlertHits(
    list: List<BtDevice>,
    prevKeys: Set<String>,
    strongNearby: Boolean,
    unseen: Boolean,
    watchedEnabled: Boolean,
    watches: List<BtAlertWatch>,
): List<BtAlertHit> {
    val hits = linkedMapOf<String, MutableSet<BtAlertReason>>()
    val byKey = list.associateBy { it.address.lowercase(Locale.US) }

    for (dev in list) {
        val key = dev.address.lowercase(Locale.US)
        if (key.isBlank() || key == "??") continue
        val newlyInRange = key !in prevKeys
        val reasons = mutableSetOf<BtAlertReason>()

        if (strongNearby && newlyInRange && dev.isNearby() && dev.rssi >= BT_ALERT_LEVEL_DBM) {
            reasons.add(BtAlertReason.STRONG_NEARBY)
        }
        if (unseen && dev.firstSighting) {
            reasons.add(BtAlertReason.UNSEEN)
        }
        if (watchedEnabled && watches.isNotEmpty() && newlyInRange) {
            if (watches.any { it.matches(dev) }) {
                reasons.add(BtAlertReason.WATCHED)
            }
        }
        if (reasons.isNotEmpty()) {
            hits.getOrPut(key) { mutableSetOf() }.addAll(reasons)
        }
    }
    return hits.mapNotNull { (key, reasons) ->
        val dev = byKey[key] ?: return@mapNotNull null
        BtAlertHit(dev, reasons)
    }
}

private fun reasonLabel(r: BtAlertReason): String = when (r) {
    BtAlertReason.STRONG_NEARBY -> "nearby"
    BtAlertReason.UNSEEN -> "new"
    BtAlertReason.WATCHED -> "watched"
}

private fun notifyBtAlerts(context: Context, hits: List<BtAlertHit>) {
    if (hits.isEmpty()) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    val top = hits.sortedByDescending { it.device.rssi }.take(4)
    val allReasons = hits.flatMap { it.reasons }.toSet()
    val title = when {
        hits.size == 1 && BtAlertReason.WATCHED in hits.first().reasons ->
            "Watched device: ${hits.first().device.name}"
        hits.size == 1 && BtAlertReason.UNSEEN in hits.first().reasons ->
            "New Bluetooth: ${hits.first().device.name}"
        hits.size == 1 ->
            "Nearby BT: ${hits.first().device.name}"
        allReasons == setOf(BtAlertReason.UNSEEN) ->
            "${hits.size} new Bluetooth devices"
        BtAlertReason.WATCHED in allReasons ->
            "${hits.size} watched / nearby Bluetooth hits"
        else ->
            "${hits.size} Bluetooth alerts"
    }
    val body = top.joinToString("\n") { hit ->
        val tags = hit.reasons.joinToString(", ") { reasonLabel(it) }
        "${hit.device.name} · ${hit.device.address} · ${hit.device.rssi} dBm · $tags"
    }
    val open = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("open_app", "bt_scanner")
    }
    val pi = PendingIntent.getActivity(
        context,
        4411,
        open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, GrokifyApp.CHANNEL_NEARBY_BT)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body.replace("\n", " · "))
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()
    nm.notify(NEARBY_BT_NOTIF_ID, n)
}

@SuppressLint("MissingPermission")
private fun readLastGps(context: Context): GpsFix? {
    if (!locationPermsOk(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    var best: Location? = null
    for (p in providers) {
        if (!lm.isProviderEnabled(p) && p != LocationManager.PASSIVE_PROVIDER) continue
        val loc = try {
            lm.getLastKnownLocation(p)
        } catch (_: SecurityException) {
            null
        } ?: continue
        if (best == null || loc.time > best.time) best = loc
    }
    return best?.toGpsFixBt()
}

private fun Location.toGpsFixBt(): GpsFix = GpsFix(
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else -1f,
    atMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
)

@SuppressLint("MissingPermission")
private fun safeDeviceName(device: BluetoothDevice?): String {
    if (device == null) return "(unknown)"
    return try {
        device.name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
    } catch (_: SecurityException) {
        "(unnamed)"
    }
}

@SuppressLint("MissingPermission")
private fun bondLabel(device: BluetoothDevice?): String {
    if (device == null) return ""
    return try {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "paired"
            BluetoothDevice.BOND_BONDING -> "pairing"
            else -> "not paired"
        }
    } catch (_: SecurityException) {
        ""
    }
}

@SuppressLint("MissingPermission")
private fun classicClassLabel(device: BluetoothDevice?): String {
    if (device == null) return ""
    return try {
        val dc = device.bluetoothClass ?: return ""
        when (dc.majorDeviceClass) {
            0x0100 -> "Computer"
            0x0200 -> "Phone"
            0x0300 -> "LAN/Network"
            0x0400 -> "Audio/Video"
            0x0500 -> "Peripheral"
            0x0600 -> "Imaging"
            0x0700 -> "Wearable"
            0x0800 -> "Toy"
            0x0900 -> "Health"
            else -> "Misc"
        }
    } catch (_: SecurityException) {
        ""
    }
}

private fun manufacturerSummary(result: ScanResult): String {
    val md = result.scanRecord?.manufacturerSpecificData ?: return ""
    if (md.size() == 0) return ""
    val id = md.keyAt(0)
    val bytes = md.valueAt(0) ?: return "mfr 0x${id.toString(16)}"
    val hex = bytes.take(6).joinToString("") { b -> "%02x".format(b) }
    return "mfr 0x${id.toString(16)} · $hex"
}

private fun serviceSummary(uuids: List<ParcelUuid>?): String {
    if (uuids.isNullOrEmpty()) return ""
    return uuids.take(3).joinToString(", ") { it.uuid.toString().take(8) }
}

private fun mergeRadio(a: BtRadioKind?, b: BtRadioKind): BtRadioKind {
    if (a == null) return b
    if (a == b) return a
    return BtRadioKind.DUAL
}

/**
 * Live scan aggregator for BLE + classic discovery.
 * Thread-safe; UI snapshots via [snapshot].
 */
class BtLiveScanSession(
    private val store: BtSightingStore,
) {
    private val map = ConcurrentHashMap<String, MutableDevice>()

    data class MutableDevice(
        var name: String,
        val address: String,
        var rssi: Int,
        var radio: BtRadioKind,
        var deviceClassLabel: String = "",
        var bondLabel: String = "",
        var services: String = "",
        var manufacturer: String = "",
        var seenAtMs: Long = System.currentTimeMillis(),
        var firstSighting: Boolean = false,
        var seenCount: Int = 1,
        var lat: Double? = null,
        var lon: Double? = null,
        var recorded: Boolean = false,
    )

    fun clear() {
        map.clear()
    }

    fun size(): Int = map.size

    @SuppressLint("MissingPermission")
    fun ingestBle(result: ScanResult, gps: GpsFix?, record: Boolean) {
        val device = result.device ?: return
        val address = device.address ?: return
        if (address.isBlank()) return
        val key = address.lowercase(Locale.US)
        val name = safeDeviceName(device).let { n ->
            if (n == "(unnamed)") {
                result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: n
            } else {
                n
            }
        }
        val rssi = result.rssi
        val mfr = manufacturerSummary(result)
        val services = serviceSummary(result.scanRecord?.serviceUuids)
        val bond = bondLabel(device)
        upsert(
            key = key,
            address = address,
            name = name,
            rssi = rssi,
            radio = BtRadioKind.BLE,
            classLabel = "",
            bond = bond,
            services = services,
            manufacturer = mfr,
            gps = gps,
            record = record,
        )
    }

    @SuppressLint("MissingPermission")
    fun ingestClassic(device: BluetoothDevice?, rssi: Short, gps: GpsFix?, record: Boolean) {
        if (device == null) return
        val address = device.address ?: return
        if (address.isBlank()) return
        val key = address.lowercase(Locale.US)
        val name = safeDeviceName(device)
        val rssiInt = if (rssi == Short.MIN_VALUE || rssi.toInt() == 0) -80 else rssi.toInt()
        upsert(
            key = key,
            address = address,
            name = name,
            rssi = rssiInt,
            radio = BtRadioKind.CLASSIC,
            classLabel = classicClassLabel(device),
            bond = bondLabel(device),
            services = "",
            manufacturer = "",
            gps = gps,
            record = record,
        )
    }

    private fun upsert(
        key: String,
        address: String,
        name: String,
        rssi: Int,
        radio: BtRadioKind,
        classLabel: String,
        bond: String,
        services: String,
        manufacturer: String,
        gps: GpsFix?,
        record: Boolean,
    ) {
        val existing = map[key]
        if (existing == null) {
            val entry = if (record) store.record(address, name, gps) else store.get(address)
            map[key] = MutableDevice(
                name = name.ifBlank { entry?.name?.ifBlank { "(unnamed)" } ?: "(unnamed)" },
                address = address,
                rssi = rssi,
                radio = radio,
                deviceClassLabel = classLabel,
                bondLabel = bond,
                services = services,
                manufacturer = manufacturer,
                seenAtMs = System.currentTimeMillis(),
                firstSighting = entry?.firstSighting == true,
                seenCount = entry?.count ?: 1,
                lat = entry?.lat ?: gps?.lat,
                lon = entry?.lon ?: gps?.lon,
                recorded = record,
            )
        } else {
            // Prefer stronger RSSI; merge radio kinds; keep richer name/metadata.
            if (rssi > existing.rssi) existing.rssi = rssi
            existing.radio = mergeRadio(existing.radio, radio)
            if (name.isNotBlank() && name != "(unnamed)") existing.name = name
            if (classLabel.isNotBlank()) existing.deviceClassLabel = classLabel
            if (bond.isNotBlank()) existing.bondLabel = bond
            if (services.isNotBlank()) existing.services = services
            if (manufacturer.isNotBlank()) existing.manufacturer = manufacturer
            existing.seenAtMs = System.currentTimeMillis()
            if (record && !existing.recorded) {
                val entry = store.record(address, existing.name, gps)
                existing.seenCount = entry.count
                existing.firstSighting = entry.firstSighting
                existing.lat = entry.lat ?: existing.lat
                existing.lon = entry.lon ?: existing.lon
                existing.recorded = true
            } else if (gps != null) {
                existing.lat = existing.lat ?: gps.lat
                existing.lon = existing.lon ?: gps.lon
            }
        }
    }

    fun snapshot(sortMode: BtSortMode): List<BtDevice> {
        val list = map.values.map { m ->
            BtDevice(
                name = m.name.ifBlank { "(unnamed)" },
                address = m.address,
                rssi = m.rssi,
                radio = m.radio,
                deviceClassLabel = m.deviceClassLabel,
                bondLabel = m.bondLabel,
                services = m.services,
                manufacturer = m.manufacturer,
                seenAtMs = m.seenAtMs,
                distanceM = estimateBtDistanceM(m.rssi),
                seenCount = m.seenCount,
                firstSighting = m.firstSighting,
                lat = m.lat,
                lon = m.lon,
            )
        }
        return sortBtList(list, sortMode)
    }
}

private const val NEARBY_BT_NOTIF_ID = 4412
private const val BT_ALERT_LEVEL_DBM = -60
private const val BT_SCAN_WINDOW_MS = 12_000L
private const val BT_ALERT_RESCAN_MS = 45_000L

@Composable
fun BluetoothScannerPane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val btManager = remember {
        appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    val adapter = remember { btManager?.adapter }
    val store = remember { BtSightingStore(appCtx) }
    val session = remember { BtLiveScanSession(store) }
    val lm = remember {
        appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap Scan to discover nearby Bluetooth / BLE devices") }
    var results by remember { mutableStateOf<List<BtDevice>>(emptyList()) }
    var history by remember { mutableStateOf<List<BtScanSnapshot>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf("list") }
    var selectedMapId by remember { mutableStateOf<String?>(null) }
    var mapFullscreen by remember { mutableStateOf(false) }
    val mapListState = rememberLazyListState()
    var lastScanAt by remember { mutableStateOf(0L) }
    var gps by remember { mutableStateOf<GpsFix?>(null) }
    var sortMode by remember { mutableStateOf(store.sortMode()) }
    var nearbyAlerts by remember { mutableStateOf(store.nearbyAlertsEnabled()) }
    var alertStrong by remember { mutableStateOf(store.alertStrongNearby()) }
    var alertUnseen by remember { mutableStateOf(store.alertUnseen()) }
    var alertWatched by remember { mutableStateOf(store.alertWatched()) }
    var watches by remember { mutableStateOf(store.watches()) }
    var watchInput by remember { mutableStateOf("") }
    var showAlertOptions by remember { mutableStateOf(store.nearbyAlertsEnabled()) }

    val prevScanKeys = remember { mutableStateOf<Set<String>>(emptySet()) }
    val alertsPrimed = remember { mutableStateOf(false) }
    val sortModeState = rememberUpdatedState(sortMode)
    val nearbyAlertsState = rememberUpdatedState(nearbyAlerts)
    val alertStrongState = rememberUpdatedState(alertStrong)
    val alertUnseenState = rememberUpdatedState(alertUnseen)
    val alertWatchedState = rememberUpdatedState(alertWatched)
    val watchesState = rememberUpdatedState(watches)
    val scanningState = rememberUpdatedState(scanning)
    val gpsState = rememberUpdatedState(gps)

    // Hold scan objects so DisposableEffect can stop them.
    val bleCallbackRef = remember { mutableStateOf<ScanCallback?>(null) }
    val classicReceiverRef = remember { mutableStateOf<BroadcastReceiver?>(null) }
    val stopRunnableRef = remember { mutableStateOf<Runnable?>(null) }

    fun refreshGpsFromCache() {
        gps = readLastGps(appCtx) ?: gps
    }

    fun publishLive() {
        results = session.snapshot(sortModeState.value)
        status = "Scanning… ${results.size} device${if (results.size == 1) "" else "s"} found"
    }

    fun finalizeScan(fromUser: Boolean) {
        val list = session.snapshot(sortModeState.value)
        results = list
        scanning = false
        val fix = gpsState.value
        val snap = BtScanSnapshot(
            id = System.currentTimeMillis(),
            atMs = System.currentTimeMillis(),
            devices = list,
            gps = fix,
        )
        if (fromUser || list.isNotEmpty()) {
            history = (listOf(snap) + history).take(20)
            lastScanAt = snap.atMs
        }
        status = if (list.isEmpty()) {
            "Scan finished — no devices (check Bluetooth / permissions)"
        } else {
            val near = list.count { it.isNearby() }
            val first = list.count { it.firstSighting }
            val ble = list.count { it.radio == BtRadioKind.BLE || it.radio == BtRadioKind.DUAL }
            buildString {
                append("Found ${list.size} · $ble BLE · $near nearby")
                if (first > 0) append(" · $first new")
                append(" · sorted by ${sortModeState.value.label.lowercase()}")
            }
        }

        if (nearbyAlertsState.value) {
            val keys = list.map { it.address.lowercase(Locale.US) }.filter { it.isNotBlank() }.toSet()
            if (alertsPrimed.value) {
                val hits = collectBtAlertHits(
                    list = list,
                    prevKeys = prevScanKeys.value,
                    strongNearby = alertStrongState.value,
                    unseen = alertUnseenState.value,
                    watchedEnabled = alertWatchedState.value,
                    watches = watchesState.value,
                )
                if (hits.isNotEmpty()) notifyBtAlerts(appCtx, hits)
            }
            prevScanKeys.value = keys
            alertsPrimed.value = true
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanInternal() {
        stopRunnableRef.value?.let { mainHandler.removeCallbacks(it) }
        stopRunnableRef.value = null
        val ad = adapter
        if (ad != null && bluetoothPermsOk(appCtx)) {
            try {
                bleCallbackRef.value?.let { cb ->
                    ad.bluetoothLeScanner?.stopScan(cb)
                }
            } catch (_: Exception) {
            }
            try {
                if (ad.isDiscovering) ad.cancelDiscovery()
            } catch (_: Exception) {
            }
        }
        bleCallbackRef.value = null
        classicReceiverRef.value?.let { rx ->
            try {
                appCtx.unregisterReceiver(rx)
            } catch (_: Exception) {
            }
        }
        classicReceiverRef.value = null
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!bluetoothPermsOk(appCtx)) {
            status = "Bluetooth permission required"
            onRequestPermissions()
            return
        }
        val ad = adapter
        if (ad == null) {
            status = "No Bluetooth adapter on this device"
            return
        }
        if (!ad.isEnabled) {
            status = "Bluetooth is off — turn it on, then scan again"
            return
        }

        // Restart clean cycle.
        stopScanInternal()
        session.clear()
        refreshGpsFromCache()
        scanning = true
        status = if (gps == null && locationPermsOk(appCtx)) {
            "Scanning BLE + classic… (waiting for GPS)"
        } else {
            "Scanning BLE + classic…"
        }

        val bleCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                session.ingestBle(result, gpsState.value, record = true)
                mainHandler.post { if (scanningState.value) publishLive() }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results.orEmpty().forEach { session.ingestBle(it, gpsState.value, record = true) }
                mainHandler.post { if (scanningState.value) publishLive() }
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post {
                    if (session.size() == 0) {
                        status = "BLE scan failed (code $errorCode) — classic may still run"
                    }
                }
            }
        }
        bleCallbackRef.value = bleCb

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            ad.bluetoothLeScanner?.startScan(null, settings, bleCb)
                ?: run { status = "BLE scanner unavailable — classic only" }
        } catch (_: SecurityException) {
            status = "Bluetooth permission denied"
            scanning = false
            return
        } catch (e: Exception) {
            status = "Could not start BLE scan: ${e.message?.take(40) ?: "error"}"
        }

        val classicRx = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        session.ingestClassic(device, rssi, gpsState.value, record = true)
                        if (scanningState.value) publishLive()
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Wait for BLE window unless already stopped.
                    }
                }
            }
        }
        classicReceiverRef.value = classicRx
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(classicRx, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appCtx.registerReceiver(classicRx, filter)
            }
        } catch (_: Exception) {
        }

        try {
            if (ad.isDiscovering) ad.cancelDiscovery()
            ad.startDiscovery()
        } catch (_: SecurityException) {
            // BLE may still work
        } catch (_: Exception) {
        }

        val stopper = Runnable {
            stopScanInternal()
            finalizeScan(fromUser = true)
        }
        stopRunnableRef.value = stopper
        mainHandler.postDelayed(stopper, BT_SCAN_WINDOW_MS)
    }

    val displayResults = remember(results, sortMode) { sortBtList(results, sortMode) }

    val mapMarkers = remember(results, viewMode) {
        if (viewMode != "map") emptyList()
        else buildBtMapMarkers(results, store.allLocated())
    }
    val mapListMarkers = remember(mapMarkers) {
        mapMarkers.sortedWith(
            compareByDescending<WifiMapMarker> { it.live }
                .thenByDescending { it.level ?: Int.MIN_VALUE }
                .thenBy { it.ssid.lowercase(Locale.US) },
        )
    }

    LaunchedEffect(selectedMapId, mapListMarkers) {
        val id = selectedMapId ?: return@LaunchedEffect
        val idx = mapListMarkers.indexOfFirst { it.id == id }
        if (idx >= 0) mapListState.animateScrollToItem(idx)
    }

    LaunchedEffect(viewMode) {
        if (viewMode != "map") {
            selectedMapId = null
            mapFullscreen = false
        }
    }

    DisposableEffect(Unit) {
        refreshGpsFromCache()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gps = location.toGpsFixBt()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        if (locationPermsOk(appCtx)) {
            try {
                val minTime = 5_000L
                val minDist = 5f
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        minTime,
                        minDist,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        minTime,
                        minDist,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            } catch (_: SecurityException) {
            }
        }
        onDispose {
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {
            }
            stopScanInternal()
        }
    }

    // Auto-rescan while nearby alerts are enabled (pane must be open).
    LaunchedEffect(nearbyAlerts) {
        if (!nearbyAlerts) return@LaunchedEffect
        while (true) {
            delay(BT_ALERT_RESCAN_MS)
            if (!nearbyAlertsState.value) break
            if (!scanningState.value && bluetoothPermsOk(appCtx) && adapter?.isEnabled == true) {
                startScan()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (mapFullscreen) Modifier
                else Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ),
    ) {
        if (!mapFullscreen) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = GrokifyColors.GlowCyan,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Bluetooth Tracker",
                        style = MaterialTheme.typography.titleMedium,
                        color = GrokifyColors.TextPrimary,
                    )
                    Text(
                        status,
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { startScan() }, enabled = !scanning) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = GrokifyColors.GlowCyan,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, null, tint = GrokifyColors.GlowCyan)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.Panel)
                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = if (gps != null) GrokifyColors.GlowMint else GrokifyColors.TextDim,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (gps != null) "GPS ${gps!!.formatShort()}" else "GPS unavailable",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            !locationPermsOk(appCtx) -> "Enable Location to tag devices with coordinates"
                            gps == null -> "Waiting for fix…"
                            else -> "Scans tag each device with this location · last fix ${formatBtTime(gps!!.atMs)}"
                        },
                        color = GrokifyColors.TextDim,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!locationPermsOk(appCtx)) {
                    TextButton(onClick = onRequestPermissions) {
                        Text("Allow", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { startScan() },
                    enabled = !scanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrokifyColors.GlowCyan,
                        contentColor = Color(0xFF041016),
                        disabledContainerColor = GrokifyColors.PanelBorder,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (scanning) "Scanning…" else "Scan now", fontWeight = FontWeight.SemiBold)
                }
                IconButton(
                    onClick = {
                        viewMode = if (viewMode == "map") "list" else "map"
                        showHistory = false
                    },
                ) {
                    Icon(
                        if (viewMode == "map") Icons.AutoMirrored.Filled.List else Icons.Default.Map,
                        contentDescription = if (viewMode == "map") "List" else "Map",
                        tint = if (viewMode == "map") GrokifyColors.GlowMint else GrokifyColors.GlowCyan,
                    )
                }
                TextButton(
                    onClick = {
                        showHistory = !showHistory
                        if (showHistory) viewMode = "list"
                    },
                ) {
                    Text(
                        if (showHistory) "Live" else "History (${history.size})",
                        color = GrokifyColors.GlowMint,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.Panel)
                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (nearbyAlerts) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                        contentDescription = null,
                        tint = if (nearbyAlerts) GrokifyColors.GlowAmber else GrokifyColors.TextDim,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { if (nearbyAlerts) showAlertOptions = !showAlertOptions },
                    ) {
                        Text(
                            "Nearby alerts",
                            color = GrokifyColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (nearbyAlerts) {
                                buildString {
                                    val parts = mutableListOf<String>()
                                    if (alertStrong) parts += "strong"
                                    if (alertUnseen) parts += "unseen"
                                    if (alertWatched) parts += "watch ${watches.size}"
                                    append(if (parts.isEmpty()) "On — no rules enabled" else "On — ${parts.joinToString(" · ")}")
                                    append(" · auto-scan ~45s")
                                }
                            } else {
                                "Off — watched name/MAC, unseen, strong nearby"
                            },
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                    }
                    Switch(
                        checked = nearbyAlerts,
                        onCheckedChange = { on ->
                            if (on && !PermissionHelper.status(appCtx, AppPermissionId.NOTIFICATIONS).granted &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                onRequestPermissions()
                            }
                            nearbyAlerts = on
                            store.setNearbyAlertsEnabled(on)
                            showAlertOptions = on
                            if (on) {
                                alertsPrimed.value = false
                                prevScanKeys.value = emptySet()
                                if (bluetoothPermsOk(appCtx)) startScan()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GrokifyColors.GlowAmber,
                            checkedTrackColor = GrokifyColors.GlowAmber.copy(alpha = 0.35f),
                            uncheckedThumbColor = GrokifyColors.TextMuted,
                            uncheckedTrackColor = GrokifyColors.PanelBorder,
                        ),
                    )
                }

                if (nearbyAlerts && showAlertOptions) {
                    Spacer(Modifier.height(8.dp))
                    BtAlertSubToggle(
                        label = "Strong nearby",
                        detail = "New device ≥ −60 dBm / ~8 m",
                        checked = alertStrong,
                        onCheckedChange = {
                            alertStrong = it
                            store.setAlertStrongNearby(it)
                        },
                    )
                    BtAlertSubToggle(
                        label = "Unseen before",
                        detail = "First time this MAC is recorded",
                        checked = alertUnseen,
                        onCheckedChange = {
                            alertUnseen = it
                            store.setAlertUnseen(it)
                        },
                    )
                    BtAlertSubToggle(
                        label = "Watched devices",
                        detail = "Match name or MAC address",
                        checked = alertWatched,
                        onCheckedChange = {
                            alertWatched = it
                            store.setAlertWatched(it)
                        },
                    )

                    if (alertWatched) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Watch list — name or MAC (aa:bb:cc:…)",
                            color = GrokifyColors.TextMuted,
                            fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = watchInput,
                                onValueChange = { watchInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = {
                                    Text("AirPods or aa:bb:cc:dd:ee:ff", fontSize = 12.sp)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = GrokifyColors.TextPrimary,
                                    unfocusedTextColor = GrokifyColors.TextPrimary,
                                    focusedBorderColor = GrokifyColors.GlowCyan,
                                    unfocusedBorderColor = GrokifyColors.PanelBorder,
                                    cursorColor = GrokifyColors.GlowCyan,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    val raw = watchInput.trim()
                                    if (raw.isEmpty()) return@IconButton
                                    val kind = if (looksLikeMac(raw)) BtWatchKind.ADDRESS else BtWatchKind.NAME
                                    watches = store.addWatch(kind, raw)
                                    watchInput = ""
                                    alertWatched = true
                                    store.setAlertWatched(true)
                                },
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add watch", tint = GrokifyColors.GlowCyan)
                            }
                        }
                        if (watches.isEmpty()) {
                            Text(
                                "No watches yet — add a name/MAC, or use Watch on a row below.",
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                            watches.forEach { w ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        w.label(),
                                        color = GrokifyColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontFamily = if (w.kind == BtWatchKind.ADDRESS) {
                                            FontFamily.Monospace
                                        } else {
                                            FontFamily.Default
                                        },
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = { watches = store.removeWatch(w.id) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = GrokifyColors.TextMuted,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sort", color = GrokifyColors.TextDim, fontSize = 11.sp)
                BtSortMode.entries.forEach { mode ->
                    val selected = sortMode == mode
                    Text(
                        mode.label,
                        color = if (selected) Color(0xFF041016) else GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (selected) GrokifyColors.GlowCyan else GrokifyColors.Panel,
                            )
                            .border(
                                1.dp,
                                if (selected) GrokifyColors.GlowCyan else GrokifyColors.PanelBorder,
                                RoundedCornerShape(20.dp),
                            )
                            .clickable {
                                sortMode = mode
                                store.setSortMode(mode)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            if (!bluetoothPermsOk(appCtx)) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GrokifyColors.Panel)
                        .border(1.dp, GrokifyColors.GlowAmber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.BluetoothDisabled,
                        null,
                        tint = GrokifyColors.GlowAmber,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Permission needed",
                            color = GrokifyColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Allow Bluetooth (scan + connect) to discover devices. Location tags GPS pins.",
                            color = GrokifyColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onRequestPermissions) {
                        Text("Allow", color = GrokifyColors.GlowCyan)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
        } // end !mapFullscreen chrome

        if (showHistory) {
            if (history.isEmpty()) {
                Text("No saved scans yet.", color = GrokifyColors.TextDim, fontSize = 13.sp)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(history, key = { it.id }) { snap ->
                        BtHistoryCard(snap) {
                            results = snap.devices
                            showHistory = false
                            viewMode = "list"
                            status = "Showing scan from ${formatBtTime(snap.atMs)}"
                        }
                    }
                }
            }
        } else if (viewMode == "map") {
            Column(Modifier.fillMaxSize()) {
                if (!mapFullscreen) {
                    Text(
                        buildString {
                            append("${mapMarkers.size} pin${if (mapMarkers.size == 1) "" else "s"}")
                            val live = mapMarkers.count { it.live }
                            if (live > 0) append(" · $live from last scan")
                            append(" · stacked pins spiral out · tap list or pin")
                        },
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(if (mapFullscreen) 1f else 1.05f),
                ) {
                    WifiMapView(
                        markers = mapMarkers,
                        userGps = gps,
                        selectedId = selectedMapId,
                        onMarkerSelected = { selectedMapId = it },
                        framed = !mapFullscreen,
                        resizeKey = mapFullscreen,
                        modifier = Modifier.fillMaxSize(),
                    )
                    BtMapChromeIconButton(
                        onClick = { mapFullscreen = !mapFullscreen },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    ) {
                        Icon(
                            if (mapFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (mapFullscreen) "Exit fullscreen" else "Fullscreen map",
                            tint = GrokifyColors.GlowCyan,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (mapFullscreen) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(start = 56.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                buildString {
                                    append("${mapMarkers.size} pin${if (mapMarkers.size == 1) "" else "s"}")
                                    val live = mapMarkers.count { it.live }
                                    if (live > 0) append(" · $live live")
                                },
                                color = GrokifyColors.TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GrokifyColors.Panel.copy(alpha = 0.92f))
                                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            )
                            BtMapChromeIconButton(
                                onClick = { startScan() },
                                enabled = !scanning,
                            ) {
                                if (scanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = GrokifyColors.GlowCyan,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Scan",
                                        tint = GrokifyColors.GlowCyan,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (!mapFullscreen) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Devices on map",
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    if (mapListMarkers.isEmpty()) {
                        Text(
                            "Scan with location on to place devices here.",
                            color = GrokifyColors.TextDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        LazyColumn(
                            state = mapListState,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(0.95f),
                        ) {
                            items(mapListMarkers, key = { it.id }) { m ->
                                BtMapListRow(
                                    marker = m,
                                    selected = m.id == selectedMapId,
                                    onClick = {
                                        selectedMapId = if (selectedMapId == m.id) null else m.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            if (results.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Bluetooth,
                        null,
                        tint = GrokifyColors.TextDim,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No devices yet", color = GrokifyColors.TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Scans BLE advertisements + classic discovery (~12s).",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(displayResults, key = { it.address + it.name }) { dev ->
                        val watched = watches.any { it.matches(dev) }
                        BtDeviceRow(
                            device = dev,
                            watched = watched,
                            onWatchName = {
                                watches = store.addWatch(BtWatchKind.NAME, dev.name)
                                if (!nearbyAlerts) {
                                    nearbyAlerts = true
                                    store.setNearbyAlertsEnabled(true)
                                    showAlertOptions = true
                                    alertsPrimed.value = false
                                    prevScanKeys.value = emptySet()
                                }
                                alertWatched = true
                                store.setAlertWatched(true)
                            },
                            onWatchMac = {
                                watches = store.addWatch(BtWatchKind.ADDRESS, dev.address)
                                if (!nearbyAlerts) {
                                    nearbyAlerts = true
                                    store.setNearbyAlertsEnabled(true)
                                    showAlertOptions = true
                                    alertsPrimed.value = false
                                    prevScanKeys.value = emptySet()
                                }
                                alertWatched = true
                                store.setAlertWatched(true)
                            },
                            onUnwatch = {
                                val ids = watches.filter { it.matches(dev) }.map { it.id }
                                ids.forEach { store.removeWatch(it) }
                                watches = store.watches()
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun buildBtMapMarkers(
    live: List<BtDevice>,
    stored: List<BtSightingStore.Entry>,
): List<WifiMapMarker> {
    val byAddr = linkedMapOf<String, WifiMapMarker>()
    for (dev in live) {
        val lat = dev.lat ?: continue
        val lon = dev.lon ?: continue
        val key = dev.address.lowercase(Locale.US)
        if (key.isBlank() || key == "??") continue
        byAddr[key] = WifiMapMarker(
            id = key,
            ssid = dev.name,
            bssid = dev.address,
            lat = lat,
            lon = lon,
            level = dev.rssi,
            distanceM = dev.distanceM,
            seenCount = dev.seenCount,
            live = true,
        )
    }
    for (e in stored) {
        val lat = e.lat ?: continue
        val lon = e.lon ?: continue
        val key = e.address.lowercase(Locale.US)
        if (key in byAddr) continue
        byAddr[key] = WifiMapMarker(
            id = key,
            ssid = e.name.ifBlank { "(unknown)" },
            bssid = e.address,
            lat = lat,
            lon = lon,
            level = null,
            distanceM = null,
            seenCount = e.count,
            live = false,
        )
    }
    return byAddr.values.toList()
}

@Composable
private fun BtMapChromeIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GrokifyColors.Panel.copy(alpha = 0.92f))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun BtMapListRow(
    marker: WifiMapMarker,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val pinColor = when {
        marker.level == null -> Color(0xFF64748B)
        marker.level >= -55 -> Color(0xFF34D399)
        marker.level >= -70 -> Color(0xFF22D3EE)
        marker.level >= -80 -> Color(0xFFFBBF24)
        else -> Color(0xFFFB7185)
    }
    val borderColor = if (selected) GrokifyColors.GlowCyan else GrokifyColors.PanelBorder
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) GrokifyColors.GlowCyan.copy(alpha = 0.10f) else GrokifyColors.Panel,
            )
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(pinColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                marker.ssid.ifBlank { "(unnamed)" },
                color = GrokifyColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                marker.bssid,
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                marker.level?.let { "$it dBm" } ?: "stored",
                color = if (marker.level != null) pinColor else GrokifyColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    append(if (marker.live) "live" else "hist")
                    if (marker.seenCount > 1) append(" · ${marker.seenCount}×")
                    marker.distanceM?.let { append(" · ≈${it.toInt()}m") }
                },
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun BtAlertSubToggle(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = GrokifyColors.TextPrimary, fontSize = 12.sp)
            Text(detail, color = GrokifyColors.TextDim, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GrokifyColors.GlowMint,
                checkedTrackColor = GrokifyColors.GlowMint.copy(alpha = 0.35f),
                uncheckedThumbColor = GrokifyColors.TextMuted,
                uncheckedTrackColor = GrokifyColors.PanelBorder,
            ),
        )
    }
}

@Composable
private fun BtHistoryCard(snap: BtScanSnapshot, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatBtTime(snap.atMs), color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append("${snap.devices.size} device${if (snap.devices.size == 1) "" else "s"}")
                        snap.gps?.let { append(" · GPS ${it.formatShort()}") }
                    },
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpen) {
                Text("View", color = GrokifyColors.GlowCyan)
            }
        }
    }
}

@Composable
private fun BtDeviceRow(
    device: BtDevice,
    watched: Boolean = false,
    onWatchName: () -> Unit = {},
    onWatchMac: () -> Unit = {},
    onUnwatch: () -> Unit = {},
) {
    val bars = when {
        device.rssi >= -50 -> "▂▄▆█"
        device.rssi >= -60 -> "▂▄▆░"
        device.rssi >= -70 -> "▂▄░░"
        else -> "▂░░░"
    }
    val nearby = device.isNearby()
    val borderColor = when {
        watched -> GrokifyColors.GlowAmber.copy(alpha = 0.55f)
        nearby -> GrokifyColors.GlowMint.copy(alpha = 0.45f)
        device.firstSighting -> GrokifyColors.GlowCyan.copy(alpha = 0.4f)
        else -> GrokifyColors.PanelBorder
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                device.name,
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (device.firstSighting) {
                Text(
                    "NEW",
                    color = GrokifyColors.GlowCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (watched) {
                Text(
                    "WATCH",
                    color = GrokifyColors.GlowAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (nearby) {
                Text(
                    "NEAR",
                    color = GrokifyColors.GlowMint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Text(
                bars,
                color = btSignalColor(device.rssi),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${device.rssi} dBm",
                color = btSignalColor(device.rssi),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(device.address)
                append("  ·  ")
                append(device.radio.label)
                if (device.deviceClassLabel.isNotBlank()) {
                    append("  ·  ")
                    append(device.deviceClassLabel)
                }
                if (device.bondLabel.isNotBlank()) {
                    append("  ·  ")
                    append(device.bondLabel)
                }
            },
            color = GrokifyColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "≈ ${formatDistance(device.distanceM)}",
                color = GrokifyColors.GlowCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text("  ·  ", color = GrokifyColors.TextDim, fontSize = 11.sp)
            Text(
                "seen ${device.seenCount}×",
                color = GrokifyColors.GlowAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (device.lat != null && device.lon != null) {
                Text("  ·  ", color = GrokifyColors.TextDim, fontSize = 11.sp)
                Text(
                    String.format(Locale.US, "%.5f, %.5f", device.lat, device.lon),
                    color = GrokifyColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        val meta = listOfNotNull(
            device.manufacturer.takeIf { it.isNotBlank() },
            device.services.takeIf { it.isNotBlank() }?.let { "uuid $it" },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                meta.take(90),
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (watched) {
                Text(
                    "Unwatch",
                    color = GrokifyColors.GlowRose,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, GrokifyColors.GlowRose.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onUnwatch)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                if (device.name != "(unnamed)" && device.name != "(unknown)") {
                    Text(
                        "Watch name",
                        color = GrokifyColors.GlowAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, GrokifyColors.GlowAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onWatchName)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    "Watch MAC",
                    color = GrokifyColors.GlowCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, GrokifyColors.GlowCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onWatchMac)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun btSignalColor(level: Int): Color = when {
    level >= -55 -> GrokifyColors.GlowMint
    level >= -70 -> GrokifyColors.GlowCyan
    level >= -80 -> GrokifyColors.GlowAmber
    else -> GrokifyColors.GlowRose
}

private fun formatBtTime(ms: Long): String {
    return SimpleDateFormat("HH:mm:ss · MMM d", Locale.getDefault()).format(Date(ms))
}
