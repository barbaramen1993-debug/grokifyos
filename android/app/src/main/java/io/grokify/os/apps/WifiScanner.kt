package io.grokify.os.apps

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
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
import kotlin.math.pow

/** Sort order for the live AP list. */
enum class WifiSortMode(val label: String) {
    DISTANCE("Distance"),
    SIGNAL("Signal"),
    SEEN("Times seen"),
    NAME("Name"),
}

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val atMs: Long = System.currentTimeMillis(),
) {
    fun formatShort(): String {
        val acc = if (accuracyM > 0f && accuracyM < 500f) " ±${accuracyM.toInt()}m" else ""
        return String.format(Locale.US, "%.5f, %.5f%s", lat, lon, acc)
    }
}

data class WifiAp(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val channel: Int,
    val capabilities: String,
    val seenAtMs: Long = System.currentTimeMillis(),
    /** Free-space / log-distance estimate in meters (null if unknown). */
    val distanceM: Double? = null,
    /** How many scans have included this BSSID (persisted). */
    val seenCount: Int = 1,
    /** True if this scan was the first time we ever recorded this BSSID. */
    val firstSighting: Boolean = false,
    /** GPS where this AP was last observed (phone location at scan time). */
    val lat: Double? = null,
    val lon: Double? = null,
)

data class WifiScanSnapshot(
    val id: Long,
    val atMs: Long,
    val networks: List<WifiAp>,
    val gps: GpsFix? = null,
)

/** Convert MHz → 2.4/5/6 GHz channel (best-effort). */
fun wifiChannel(freqMhz: Int): Int = when {
    freqMhz in 2412..2484 -> (freqMhz - 2407) / 5
    freqMhz in 5170..5825 -> (freqMhz - 5000) / 5
    freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
    else -> 0
}

/**
 * Log-distance path-loss estimate (meters). Rough indoor-ish model; good enough for ranking.
 * d = 10 ^ ((27.55 - 20·log10(fMHz) + |RSSI|) / 20)
 */
fun estimateDistanceM(levelDbm: Int, freqMhz: Int): Double {
    val f = freqMhz.coerceAtLeast(2400).toDouble()
    val exp = (27.55 - 20.0 * kotlin.math.log10(f) + kotlin.math.abs(levelDbm.toDouble())) / 20.0
    return 10.0.pow(exp).coerceIn(0.5, 5000.0)
}

fun formatDistance(m: Double?): String {
    if (m == null) return "—"
    return when {
        m < 10 -> String.format(Locale.US, "%.1f m", m)
        m < 1000 -> String.format(Locale.US, "%.0f m", m)
        else -> String.format(Locale.US, "%.1f km", m / 1000.0)
    }
}

/** Match watched router by SSID name or MAC / BSSID. */
enum class WifiWatchKind {
    SSID,
    BSSID,
}

data class WifiAlertWatch(
    val id: String,
    val kind: WifiWatchKind,
    /** SSID text (case-insensitive) or MAC/BSSID (normalized). */
    val pattern: String,
) {
    fun label(): String = when (kind) {
        WifiWatchKind.SSID -> "SSID · $pattern"
        WifiWatchKind.BSSID -> "MAC · $pattern"
    }

    fun matches(ap: WifiAp): Boolean = when (kind) {
        WifiWatchKind.SSID -> ap.ssid.equals(pattern, ignoreCase = true)
        WifiWatchKind.BSSID -> normalizeMac(ap.bssid) == normalizeMac(pattern)
    }
}

/** Why a notification fired. */
enum class WifiAlertReason {
    STRONG_NEARBY,
    UNSEEN,
    WATCHED,
}

data class WifiAlertHit(
    val ap: WifiAp,
    val reasons: Set<WifiAlertReason>,
)

/** Normalize MAC for comparison: lowercase, strip separators. */
fun normalizeMac(raw: String): String =
    raw.lowercase(Locale.US).replace(Regex("[^0-9a-f]"), "")

/** True if string looks like a MAC / BSSID (at least 6 hex pairs-ish). */
fun looksLikeMac(raw: String): Boolean {
    val hex = normalizeMac(raw)
    return hex.length in 12..16 && hex.all { it in '0'..'9' || it in 'a'..'f' }
}

/** Persisted BSSID ledger: times seen + last GPS + alert prefs. */
class WifiSightingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(
        val bssid: String,
        val ssid: String,
        val count: Int,
        val lat: Double?,
        val lon: Double?,
        val lastSeenMs: Long,
        /** True when this record() call created the first sighting. */
        val firstSighting: Boolean = false,
    )

    fun get(bssid: String): Entry? {
        val raw = prefs.getString(key(bssid), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Entry(
                bssid = bssid,
                ssid = o.optString("ssid", ""),
                count = o.optInt("count", 0),
                lat = o.optDouble("lat").takeIf { o.has("lat") && !o.isNull("lat") },
                lon = o.optDouble("lon").takeIf { o.has("lon") && !o.isNull("lon") },
                lastSeenMs = o.optLong("lastSeenMs", 0L),
                firstSighting = false,
            )
        }.getOrNull()
    }

    fun record(bssid: String, ssid: String, gps: GpsFix?): Entry {
        val prev = get(bssid)
        val first = prev == null || prev.count <= 0
        val next = Entry(
            bssid = bssid,
            ssid = ssid,
            count = (prev?.count ?: 0) + 1,
            lat = gps?.lat ?: prev?.lat,
            lon = gps?.lon ?: prev?.lon,
            lastSeenMs = System.currentTimeMillis(),
            firstSighting = first,
        )
        val o = JSONObject()
            .put("ssid", next.ssid)
            .put("count", next.count)
            .put("lastSeenMs", next.lastSeenMs)
        next.lat?.let { o.put("lat", it) }
        next.lon?.let { o.put("lon", it) }
        // commit() so leave/kill right after a scan does not drop the write.
        prefs.edit().putString(key(bssid), o.toString()).commit()
        return next
    }

    /** Persist recent scan snapshots across app restarts (max 20). */
    fun saveHistory(snaps: List<WifiScanSnapshot>) {
        val arr = JSONArray()
        snaps.take(20).forEach { snap -> arr.put(wifiSnapshotToJson(snap)) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).commit()
    }

    fun loadHistory(): List<WifiScanSnapshot> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    wifiSnapshotFromJson(o)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveLastResults(networks: List<WifiAp>, gps: GpsFix?) {
        val o = JSONObject()
            .put("atMs", System.currentTimeMillis())
            .put("networks", wifiApsToJson(networks))
        if (gps != null) {
            o.put(
                "gps",
                JSONObject()
                    .put("lat", gps.lat)
                    .put("lon", gps.lon)
                    .put("accuracyM", gps.accuracyM.toDouble())
                    .put("atMs", gps.atMs),
            )
        }
        prefs.edit().putString(KEY_LAST_RESULTS, o.toString()).commit()
    }

    fun loadLastResults(): Pair<List<WifiAp>, GpsFix?>? {
        val raw = prefs.getString(KEY_LAST_RESULTS, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val networks = wifiApsFromJson(o.optJSONArray("networks"))
            if (networks.isEmpty()) return@runCatching null
            val gpsObj = o.optJSONObject("gps")
            val gps = if (gpsObj != null) {
                GpsFix(
                    lat = gpsObj.optDouble("lat"),
                    lon = gpsObj.optDouble("lon"),
                    accuracyM = gpsObj.optDouble("accuracyM", -1.0).toFloat(),
                    atMs = gpsObj.optLong("atMs", 0L),
                ).takeIf { it.lat.isFinite() && it.lon.isFinite() }
            } else {
                null
            }
            networks to gps
        }.getOrNull()
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

    fun watches(): List<WifiAlertWatch> {
        val raw = prefs.getString(KEY_WATCHES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = runCatching {
                        WifiWatchKind.valueOf(o.optString("kind", WifiWatchKind.SSID.name))
                    }.getOrDefault(WifiWatchKind.SSID)
                    val pattern = o.optString("pattern", "").trim()
                    if (pattern.isEmpty()) continue
                    add(
                        WifiAlertWatch(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            kind = kind,
                            pattern = pattern,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setWatches(list: List<WifiAlertWatch>) {
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

    fun addWatch(kind: WifiWatchKind, pattern: String): List<WifiAlertWatch> {
        val p = pattern.trim()
        if (p.isEmpty()) return watches()
        val normalized = if (kind == WifiWatchKind.BSSID) {
            // Keep user-readable colon form when possible.
            formatMacDisplay(p)
        } else {
            p
        }
        val cur = watches().toMutableList()
        val exists = cur.any {
            it.kind == kind && when (kind) {
                WifiWatchKind.SSID -> it.pattern.equals(normalized, ignoreCase = true)
                WifiWatchKind.BSSID -> normalizeMac(it.pattern) == normalizeMac(normalized)
            }
        }
        if (!exists) {
            cur.add(
                WifiAlertWatch(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    pattern = normalized,
                ),
            )
            setWatches(cur)
        }
        return watches()
    }

    fun removeWatch(id: String): List<WifiAlertWatch> {
        setWatches(watches().filterNot { it.id == id })
        return watches()
    }

    fun sortMode(): WifiSortMode {
        val raw = prefs.getString(KEY_SORT, WifiSortMode.DISTANCE.name) ?: WifiSortMode.DISTANCE.name
        return runCatching { WifiSortMode.valueOf(raw) }.getOrDefault(WifiSortMode.DISTANCE)
    }

    fun setSortMode(mode: WifiSortMode) {
        prefs.edit().putString(KEY_SORT, mode.name).apply()
    }

    /** All stored APs that have GPS coordinates (for the map). */
    fun allLocated(): List<Entry> {
        val out = ArrayList<Entry>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith("ap_") || v !is String) continue
            val bssid = k.removePrefix("ap_")
            runCatching {
                val o = JSONObject(v)
                if (!o.has("lat") || o.isNull("lat") || !o.has("lon") || o.isNull("lon")) return@runCatching
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@runCatching
                out.add(
                    Entry(
                        bssid = bssid,
                        ssid = o.optString("ssid", ""),
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
        private const val PREFS = "wifi_scanner"
        private const val KEY_ALERTS = "nearby_alerts"
        private const val KEY_ALERT_STRONG = "alert_strong_nearby"
        private const val KEY_ALERT_UNSEEN = "alert_unseen"
        private const val KEY_ALERT_WATCHED = "alert_watched"
        private const val KEY_WATCHES = "alert_watches"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_HISTORY = "scan_history_json"
        private const val KEY_LAST_RESULTS = "last_results_json"
        private fun key(bssid: String) = "ap_${bssid.lowercase(Locale.US)}"
    }
}

private fun wifiApsToJson(networks: List<WifiAp>): JSONArray {
    val arr = JSONArray()
    networks.forEach { a ->
        val o = JSONObject()
            .put("ssid", a.ssid)
            .put("bssid", a.bssid)
            .put("level", a.level)
            .put("frequency", a.frequency)
            .put("channel", a.channel)
            .put("capabilities", a.capabilities)
            .put("seenAtMs", a.seenAtMs)
            .put("seenCount", a.seenCount)
            .put("firstSighting", a.firstSighting)
        a.distanceM?.let { o.put("distanceM", it) }
        a.lat?.let { o.put("lat", it) }
        a.lon?.let { o.put("lon", it) }
        arr.put(o)
    }
    return arr
}

private fun wifiApsFromJson(arr: JSONArray?): List<WifiAp> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val bssid = o.optString("bssid", "").trim()
            if (bssid.isEmpty()) continue
            add(
                WifiAp(
                    ssid = o.optString("ssid", "(hidden)"),
                    bssid = bssid,
                    level = o.optInt("level", -100),
                    frequency = o.optInt("frequency", 0),
                    channel = o.optInt("channel", 0),
                    capabilities = o.optString("capabilities", ""),
                    seenAtMs = o.optLong("seenAtMs", 0L),
                    distanceM = if (o.has("distanceM") && !o.isNull("distanceM")) {
                        o.optDouble("distanceM")
                    } else {
                        null
                    },
                    seenCount = o.optInt("seenCount", 1),
                    firstSighting = o.optBoolean("firstSighting", false),
                    lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
                    lon = if (o.has("lon") && !o.isNull("lon")) o.optDouble("lon") else null,
                ),
            )
        }
    }
}

private fun wifiSnapshotToJson(snap: WifiScanSnapshot): JSONObject {
    val o = JSONObject()
        .put("id", snap.id)
        .put("atMs", snap.atMs)
        .put("networks", wifiApsToJson(snap.networks))
    snap.gps?.let { g ->
        o.put(
            "gps",
            JSONObject()
                .put("lat", g.lat)
                .put("lon", g.lon)
                .put("accuracyM", g.accuracyM.toDouble())
                .put("atMs", g.atMs),
        )
    }
    return o
}

private fun wifiSnapshotFromJson(o: JSONObject): WifiScanSnapshot? {
    val networks = wifiApsFromJson(o.optJSONArray("networks"))
    val id = o.optLong("id", 0L)
    val atMs = o.optLong("atMs", id)
    if (id == 0L && networks.isEmpty()) return null
    val gpsObj = o.optJSONObject("gps")
    val gps = if (gpsObj != null) {
        GpsFix(
            lat = gpsObj.optDouble("lat"),
            lon = gpsObj.optDouble("lon"),
            accuracyM = gpsObj.optDouble("accuracyM", -1.0).toFloat(),
            atMs = gpsObj.optLong("atMs", atMs),
        ).takeIf { it.lat.isFinite() && it.lon.isFinite() }
    } else {
        null
    }
    return WifiScanSnapshot(
        id = if (id != 0L) id else atMs,
        atMs = atMs,
        networks = networks,
        gps = gps,
    )
}

/** Pretty-print MAC if we have 12 hex digits. */
fun formatMacDisplay(raw: String): String {
    val hex = normalizeMac(raw)
    if (hex.length != 12) return raw.trim()
    return hex.chunked(2).joinToString(":")
}

@SuppressLint("MissingPermission")
fun readWifiResults(
    wm: WifiManager,
    store: WifiSightingStore,
    gps: GpsFix?,
    recordSightings: Boolean,
): List<WifiAp> {
    val now = System.currentTimeMillis()
    val boot = SystemClock.elapsedRealtime()
    return try {
        wm.scanResults
            .orEmpty()
            .map { r ->
                val ssid = r.ssidCompat().ifBlank { "(hidden)" }
                val bssid = r.BSSID ?: "??"
                val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ageBoot = boot - (r.timestamp / 1000L)
                    now - ageBoot.coerceAtLeast(0L)
                } else {
                    now
                }
                val dist = estimateDistanceM(r.level, r.frequency)
                val entry = if (recordSightings && bssid != "??") {
                    store.record(bssid, ssid, gps)
                } else {
                    store.get(bssid)
                }
                WifiAp(
                    ssid = ssid,
                    bssid = bssid,
                    level = r.level,
                    frequency = r.frequency,
                    channel = wifiChannel(r.frequency),
                    capabilities = r.capabilities ?: "",
                    seenAtMs = ageMs,
                    distanceM = dist,
                    seenCount = entry?.count ?: 1,
                    firstSighting = entry?.firstSighting == true,
                    lat = entry?.lat ?: gps?.lat,
                    lon = entry?.lon ?: gps?.lon,
                )
            }
            .let { sortWifiList(it, WifiSortMode.SIGNAL) }
    } catch (_: SecurityException) {
        emptyList()
    }
}

fun sortWifiList(list: List<WifiAp>, mode: WifiSortMode): List<WifiAp> = when (mode) {
    WifiSortMode.DISTANCE -> list.sortedWith(
        compareBy<WifiAp> { it.distanceM ?: Double.MAX_VALUE }
            .thenByDescending { it.level }
            .thenBy { it.ssid.lowercase(Locale.US) },
    )
    WifiSortMode.SIGNAL -> list.sortedWith(
        compareByDescending<WifiAp> { it.level }.thenBy { it.ssid.lowercase(Locale.US) },
    )
    WifiSortMode.SEEN -> list.sortedWith(
        compareByDescending<WifiAp> { it.seenCount }
            .thenByDescending { it.level }
            .thenBy { it.ssid.lowercase(Locale.US) },
    )
    WifiSortMode.NAME -> list.sortedWith(
        compareBy<WifiAp> { it.ssid.lowercase(Locale.US) }.thenByDescending { it.level },
    )
}

private fun ScanResult.ssidCompat(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        wifiSsid?.toString()?.trim('"') ?: SSID.orEmpty()
    } else {
        @Suppress("DEPRECATION")
        SSID.orEmpty()
    }
}

fun wifiPermsOk(context: Context): Boolean {
    val loc = PermissionHelper.status(context, AppPermissionId.LOCATION).granted
    val nearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionHelper.status(context, AppPermissionId.NEARBY_WIFI).granted
    } else {
        true
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        nearby || loc
    } else {
        loc
    }
}

fun locationPermsOk(context: Context): Boolean =
    PermissionHelper.status(context, AppPermissionId.LOCATION).granted

/** Nearby = strong enough that the phone is likely close (≈ under ~25 m estimate or ≥ −60 dBm). */
fun WifiAp.isNearby(): Boolean {
    val d = distanceM
    return level >= -60 || (d != null && d <= 25.0)
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
    return best?.toGpsFix()
}

private fun Location.toGpsFix(): GpsFix = GpsFix(
    lat = latitude,
    lon = longitude,
    accuracyM = if (hasAccuracy()) accuracy else -1f,
    atMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
)

private fun reasonLabel(r: WifiAlertReason): String = when (r) {
    WifiAlertReason.STRONG_NEARBY -> "nearby"
    WifiAlertReason.UNSEEN -> "new"
    WifiAlertReason.WATCHED -> "watched"
}

private fun notifyWifiAlerts(context: Context, hits: List<WifiAlertHit>) {
    if (hits.isEmpty()) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    val top = hits.sortedByDescending { it.ap.level }.take(4)
    val allReasons = hits.flatMap { it.reasons }.toSet()
    val title = when {
        hits.size == 1 && WifiAlertReason.WATCHED in hits.first().reasons ->
            "Watched router: ${hits.first().ap.ssid}"
        hits.size == 1 && WifiAlertReason.UNSEEN in hits.first().reasons ->
            "New network: ${hits.first().ap.ssid}"
        hits.size == 1 ->
            "Nearby: ${hits.first().ap.ssid}"
        allReasons == setOf(WifiAlertReason.UNSEEN) ->
            "${hits.size} new Wi‑Fi networks"
        WifiAlertReason.WATCHED in allReasons ->
            "${hits.size} watched / nearby Wi‑Fi hits"
        else ->
            "${hits.size} Wi‑Fi alerts"
    }
    val body = top.joinToString("\n") { hit ->
        val tags = hit.reasons.joinToString(", ") { reasonLabel(it) }
        "${hit.ap.ssid} · ${hit.ap.bssid} · ${hit.ap.level} dBm · $tags"
    }
    val open = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("open_app", "wifi_scanner")
    }
    val pi = PendingIntent.getActivity(
        context,
        4401,
        open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(context, GrokifyApp.CHANNEL_NEARBY_WIFI)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(body.replace("\n", " · "))
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()
    nm.notify(NEARBY_WIFI_NOTIF_ID, n)
}

/**
 * Collect alert hits for this scan vs the previous scan's BSSID set.
 * @param prevKeys BSSIDs present on the previous scan (empty until primed).
 */
fun collectWifiAlertHits(
    list: List<WifiAp>,
    prevKeys: Set<String>,
    strongNearby: Boolean,
    unseen: Boolean,
    watchedEnabled: Boolean,
    watches: List<WifiAlertWatch>,
): List<WifiAlertHit> {
    val hits = linkedMapOf<String, MutableSet<WifiAlertReason>>()
    val apByKey = list.associateBy { it.bssid.lowercase(Locale.US) }

    for (ap in list) {
        val key = ap.bssid.lowercase(Locale.US)
        if (key == "??") continue
        val newlyInRange = key !in prevKeys
        val reasons = mutableSetOf<WifiAlertReason>()

        if (strongNearby && newlyInRange && ap.isNearby() && ap.level >= ALERT_LEVEL_DBM) {
            reasons.add(WifiAlertReason.STRONG_NEARBY)
        }
        if (unseen && ap.firstSighting) {
            reasons.add(WifiAlertReason.UNSEEN)
        }
        if (watchedEnabled && watches.isNotEmpty() && newlyInRange) {
            // Watched: fire when the target newly appears in radio range (any strength).
            if (watches.any { it.matches(ap) }) {
                reasons.add(WifiAlertReason.WATCHED)
            }
        }
        if (reasons.isNotEmpty()) {
            hits.getOrPut(key) { mutableSetOf() }.addAll(reasons)
        }
    }
    return hits.mapNotNull { (key, reasons) ->
        val ap = apByKey[key] ?: return@mapNotNull null
        WifiAlertHit(ap, reasons)
    }
}

private const val NEARBY_WIFI_NOTIF_ID = 4402
/** Strong-signal threshold for auto-alerts. */
private const val ALERT_LEVEL_DBM = -60
/** Re-scan interval while nearby alerts are on and pane is open. */
private const val ALERT_RESCAN_MS = 45_000L

@Composable
fun WifiScannerPane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val wm = remember {
        appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    val store = remember { WifiSightingStore(appCtx) }
    val lm = remember {
        appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap Scan to discover nearby networks") }
    val restored = remember { store.loadLastResults() }
    var results by remember { mutableStateOf(restored?.first.orEmpty()) }
    var history by remember { mutableStateOf(store.loadHistory()) }
    var showHistory by remember { mutableStateOf(false) }
    /** list | map | history */
    var viewMode by remember { mutableStateOf("list") }
    /** Selected BSSID id on the map view (list ↔ pin highlight). */
    var selectedMapId by remember { mutableStateOf<String?>(null) }
    /** Edge-to-edge map; hides chrome + AP list under the map. */
    var mapFullscreen by remember { mutableStateOf(false) }
    val mapListState = rememberLazyListState()
    var lastScanAt by remember {
        mutableStateOf(history.firstOrNull()?.atMs ?: 0L)
    }
    var gps by remember { mutableStateOf(restored?.second) }
    var sortMode by remember { mutableStateOf(store.sortMode()) }
    var nearbyAlerts by remember { mutableStateOf(store.nearbyAlertsEnabled()) }
    var alertStrong by remember { mutableStateOf(store.alertStrongNearby()) }
    var alertUnseen by remember { mutableStateOf(store.alertUnseen()) }
    var alertWatched by remember { mutableStateOf(store.alertWatched()) }
    var watches by remember { mutableStateOf(store.watches()) }
    var watchInput by remember { mutableStateOf("") }
    var showAlertOptions by remember { mutableStateOf(store.nearbyAlertsEnabled()) }
    // Shared mutable holders so BroadcastReceiver always sees latest alert state.
    val prevScanKeys = remember { mutableStateOf<Set<String>>(emptySet()) }
    val alertsPrimed = remember { mutableStateOf(false) }
    val sortModeState = rememberUpdatedState(sortMode)
    val nearbyAlertsState = rememberUpdatedState(nearbyAlerts)
    val alertStrongState = rememberUpdatedState(alertStrong)
    val alertUnseenState = rememberUpdatedState(alertUnseen)
    val alertWatchedState = rememberUpdatedState(alertWatched)
    val watchesState = rememberUpdatedState(watches)

    fun refreshGpsFromCache() {
        gps = readLastGps(appCtx) ?: gps
    }

    fun applyResults(list: List<WifiAp>, fromScan: Boolean, scanGps: GpsFix?) {
        results = list
        if (fromScan) {
            val mode = sortModeState.value
            val snap = WifiScanSnapshot(
                id = System.currentTimeMillis(),
                atMs = System.currentTimeMillis(),
                networks = list,
                gps = scanGps,
            )
            history = (listOf(snap) + history).take(20)
            lastScanAt = snap.atMs
            store.saveHistory(history)
            store.saveLastResults(list, scanGps)
            status = if (list.isEmpty()) {
                "Scan finished — no networks (check location/Wi‑Fi permissions)"
            } else {
                val near = list.count { it.isNearby() }
                val first = list.count { it.firstSighting }
                val pinned = list.count { it.lat != null && it.lon != null }
                buildString {
                    append("Found ${list.size} · $near nearby")
                    if (first > 0) append(" · $first new")
                    if (pinned > 0) append(" · $pinned mapped")
                    append(" · sorted by ${mode.label.lowercase()}")
                }
            }

            // Alerts: strong nearby, first-seen, and watched SSID/MAC (after baseline).
            if (nearbyAlertsState.value) {
                val keys = list.map { it.bssid.lowercase(Locale.US) }.filter { it != "??" }.toSet()
                if (alertsPrimed.value) {
                    val hits = collectWifiAlertHits(
                        list = list,
                        prevKeys = prevScanKeys.value,
                        strongNearby = alertStrongState.value,
                        unseen = alertUnseenState.value,
                        watchedEnabled = alertWatchedState.value,
                        watches = watchesState.value,
                    )
                    if (hits.isNotEmpty()) {
                        notifyWifiAlerts(appCtx, hits)
                    }
                }
                prevScanKeys.value = keys
                alertsPrimed.value = true
            }
        }
        scanning = false
    }

    val displayResults = remember(results, sortMode) { sortWifiList(results, sortMode) }

    // Merge live scan APs + historically stored GPS points for the map.
    val mapMarkers = remember(results, viewMode) {
        if (viewMode != "map") emptyList()
        else buildWifiMapMarkers(results, store.allLocated())
    }
    val mapListMarkers = remember(mapMarkers) {
        mapMarkers.sortedWith(
            compareByDescending<WifiMapMarker> { it.live }
                .thenByDescending { it.level ?: Int.MIN_VALUE }
                .thenBy { it.ssid.lowercase(Locale.US) },
        )
    }

    // Keep list scrolled to the pin selected from the map.
    LaunchedEffect(selectedMapId, mapListMarkers) {
        val id = selectedMapId ?: return@LaunchedEffect
        val idx = mapListMarkers.indexOfFirst { it.id == id }
        if (idx >= 0) {
            mapListState.animateScrollToItem(idx)
        }
    }

    // Clear selection / fullscreen when leaving map mode.
    LaunchedEffect(viewMode) {
        if (viewMode != "map") {
            selectedMapId = null
            mapFullscreen = false
        }
    }

    // Live location updates while the pane is open (if permitted).
    DisposableEffect(Unit) {
        refreshGpsFromCache()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gps = location.toGpsFix()
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
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
                } else {
                    true
                }
                refreshGpsFromCache()
                val fix = gps
                val list = readWifiResults(wm, store, fix, recordSightings = true)
                if (ok || list.isNotEmpty()) {
                    applyResults(list, fromScan = true, scanGps = fix)
                } else {
                    scanning = false
                    status = "Scan throttled or failed — try again in a few seconds"
                    if (list.isNotEmpty()) results = list
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appCtx.registerReceiver(receiver, filter)
        }
        if (wifiPermsOk(appCtx)) {
            refreshGpsFromCache()
            val cached = readWifiResults(wm, store, gps, recordSightings = false)
            if (cached.isNotEmpty()) {
                results = cached
                status = "Cached ${cached.size} network(s) — tap Scan to refresh"
            } else if (results.isNotEmpty()) {
                val pinned = results.count { it.lat != null && it.lon != null }
                status = buildString {
                    append("Restored ${results.size} network${if (results.size == 1) "" else "s"}")
                    if (pinned > 0) append(" · $pinned mapped")
                    append(" — tap Scan to refresh")
                }
            } else if (history.isNotEmpty()) {
                status = "${history.size} saved scan${if (history.size == 1) "" else "s"} — tap Scan to refresh"
            }
        }
        onDispose {
            try {
                appCtx.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    // Auto-rescan while nearby alerts are enabled (pane must be open).
    LaunchedEffect(nearbyAlerts) {
        if (!nearbyAlerts) return@LaunchedEffect
        while (true) {
            delay(ALERT_RESCAN_MS)
            if (!nearbyAlertsState.value) break
            if (!scanning && wifiPermsOk(appCtx) && wm.isWifiEnabled) {
                scanning = true
                status = "Auto-scan (nearby alerts)…"
                @Suppress("DEPRECATION")
                try {
                    if (!wm.startScan()) {
                        refreshGpsFromCache()
                        val fix = gps
                        val cached = readWifiResults(wm, store, fix, recordSightings = true)
                        if (cached.isNotEmpty()) {
                            applyResults(cached, fromScan = true, scanGps = fix)
                            status = "Throttled — cached ${cached.size} (alerts on)"
                        } else {
                            scanning = false
                        }
                    }
                } catch (_: SecurityException) {
                    scanning = false
                }
            }
        }
    }

    fun startScan() {
        if (!wifiPermsOk(appCtx)) {
            status = "Location or Nearby Wi‑Fi permission required"
            onRequestPermissions()
            return
        }
        if (!wm.isWifiEnabled) {
            status = "Wi‑Fi is off — turn it on, then scan again"
            return
        }
        refreshGpsFromCache()
        if (gps == null && locationPermsOk(appCtx)) {
            status = "Scanning… (waiting for GPS fix)"
        } else {
            status = "Scanning…"
        }
        scanning = true
        @Suppress("DEPRECATION")
        val started = try {
            wm.startScan()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            val fix = gps
            val cached = readWifiResults(wm, store, fix, recordSightings = true)
            if (cached.isNotEmpty()) {
                applyResults(cached, fromScan = true, scanGps = fix)
                status = "Scan throttled — showing latest cached results (${cached.size})"
            } else {
                scanning = false
                status = "Could not start scan (throttled or denied)"
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
                    "Wi‑Fi Scanner",
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

        // GPS strip
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
                        !locationPermsOk(appCtx) -> "Enable Location to tag scans with coordinates"
                        gps == null -> "Waiting for fix…"
                        else -> "Scans tag each AP with this location · last fix ${formatTime(gps!!.atMs)}"
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
                Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
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

        // Nearby alerts master toggle + options
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
                            "Off — tap to enable watched SSID/MAC, unseen, strong nearby"
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
                            if (wifiPermsOk(appCtx)) startScan()
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
                AlertSubToggle(
                    label = "Strong nearby",
                    detail = "New AP ≥ −60 dBm / ~25 m",
                    checked = alertStrong,
                    onCheckedChange = {
                        alertStrong = it
                        store.setAlertStrongNearby(it)
                    },
                )
                AlertSubToggle(
                    label = "Unseen before",
                    detail = "First time this MAC is recorded",
                    checked = alertUnseen,
                    onCheckedChange = {
                        alertUnseen = it
                        store.setAlertUnseen(it)
                    },
                )
                AlertSubToggle(
                    label = "Watched routers",
                    detail = "Match SSID name or MAC / BSSID",
                    checked = alertWatched,
                    onCheckedChange = {
                        alertWatched = it
                        store.setAlertWatched(it)
                    },
                )

                if (alertWatched) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Watch list — SSID or MAC (aa:bb:cc:…)",
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
                                Text("HomeWiFi or aa:bb:cc:dd:ee:ff", fontSize = 12.sp)
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
                                val kind = if (looksLikeMac(raw)) WifiWatchKind.BSSID else WifiWatchKind.SSID
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
                                    fontFamily = if (w.kind == WifiWatchKind.BSSID) {
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

        // Sort chips
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort", color = GrokifyColors.TextDim, fontSize = 11.sp)
            WifiSortMode.entries.forEach { mode ->
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

        if (!wifiPermsOk(appCtx)) {
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
                Icon(Icons.Default.WifiOff, null, tint = GrokifyColors.GlowAmber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Permission needed", color = GrokifyColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "Allow Nearby Wi‑Fi and Location to scan and tag GPS."
                        } else {
                            "Allow Location to scan Wi‑Fi and tag GPS."
                        },
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
                        HistoryCard(snap) {
                            results = snap.networks
                            showHistory = false
                            viewMode = "list"
                            status = "Showing scan from ${formatTime(snap.atMs)}"
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
                    // Fullscreen toggle — top-left so it clears Mapbox nav (top-right).
                    MapChromeIconButton(
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
                        // Compact chrome: pin count + scan (exit via fullscreen button)
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
                            MapChromeIconButton(
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
                        "Networks on map",
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    if (mapListMarkers.isEmpty()) {
                        Text(
                            "Scan with location on to place routers here.",
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
                                MapApListRow(
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
                        Icons.Default.Wifi,
                        null,
                        tint = GrokifyColors.TextDim,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No networks yet", color = GrokifyColors.TextMuted)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(displayResults, key = { it.bssid + it.ssid }) { ap ->
                        val watched = watches.any { it.matches(ap) }
                        WifiApRow(
                            ap = ap,
                            watched = watched,
                            onWatchSsid = {
                                watches = store.addWatch(WifiWatchKind.SSID, ap.ssid)
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
                                watches = store.addWatch(WifiWatchKind.BSSID, ap.bssid)
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
                                val ids = watches.filter { it.matches(ap) }.map { it.id }
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

/** Prefer live scan rows; fill gaps from stored sightings with GPS. */
private fun buildWifiMapMarkers(
    live: List<WifiAp>,
    stored: List<WifiSightingStore.Entry>,
): List<WifiMapMarker> {
    val byBssid = linkedMapOf<String, WifiMapMarker>()
    for (ap in live) {
        val lat = ap.lat ?: continue
        val lon = ap.lon ?: continue
        val key = ap.bssid.lowercase(Locale.US)
        if (key == "??" || key.isBlank()) continue
        byBssid[key] = WifiMapMarker(
            id = key,
            ssid = ap.ssid,
            bssid = ap.bssid,
            lat = lat,
            lon = lon,
            level = ap.level,
            distanceM = ap.distanceM,
            seenCount = ap.seenCount,
            live = true,
        )
    }
    for (e in stored) {
        val lat = e.lat ?: continue
        val lon = e.lon ?: continue
        val key = e.bssid.lowercase(Locale.US)
        if (key in byBssid) continue
        byBssid[key] = WifiMapMarker(
            id = key,
            ssid = e.ssid.ifBlank { "(unknown)" },
            bssid = e.bssid,
            lat = lat,
            lon = lon,
            level = null,
            distanceM = null,
            seenCount = e.count,
            live = false,
        )
    }
    return byBssid.values.toList()
}

@Composable
private fun MapChromeIconButton(
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
private fun MapApListRow(
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
                marker.ssid.ifBlank { "(hidden)" },
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
private fun AlertSubToggle(
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
private fun HistoryCard(snap: WifiScanSnapshot, onOpen: () -> Unit) {
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
                Text(formatTime(snap.atMs), color = GrokifyColors.TextPrimary, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append("${snap.networks.size} network${if (snap.networks.size == 1) "" else "s"}")
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
private fun WifiApRow(
    ap: WifiAp,
    watched: Boolean = false,
    onWatchSsid: () -> Unit = {},
    onWatchMac: () -> Unit = {},
    onUnwatch: () -> Unit = {},
) {
    val band = when {
        ap.frequency >= 5900 -> "6 GHz"
        ap.frequency >= 4900 -> "5 GHz"
        else -> "2.4 GHz"
    }
    val bars = when {
        ap.level >= -50 -> "▂▄▆█"
        ap.level >= -60 -> "▂▄▆░"
        ap.level >= -70 -> "▂▄░░"
        else -> "▂░░░"
    }
    val nearby = ap.isNearby()
    val borderColor = when {
        watched -> GrokifyColors.GlowAmber.copy(alpha = 0.55f)
        nearby -> GrokifyColors.GlowMint.copy(alpha = 0.45f)
        ap.firstSighting -> GrokifyColors.GlowCyan.copy(alpha = 0.4f)
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
                ap.ssid,
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (ap.firstSighting) {
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
                color = signalColor(ap.level),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${ap.level} dBm",
                color = signalColor(ap.level),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${ap.bssid}  ·  $band  ·  ch ${ap.channel.coerceAtLeast(0)}  ·  ${ap.frequency} MHz",
            color = GrokifyColors.TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "≈ ${formatDistance(ap.distanceM)}",
                color = GrokifyColors.GlowCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text("  ·  ", color = GrokifyColors.TextDim, fontSize = 11.sp)
            Text(
                "seen ${ap.seenCount}×",
                color = GrokifyColors.GlowAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (ap.lat != null && ap.lon != null) {
                Text("  ·  ", color = GrokifyColors.TextDim, fontSize = 11.sp)
                Text(
                    String.format(Locale.US, "%.5f, %.5f", ap.lat, ap.lon),
                    color = GrokifyColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (ap.capabilities.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                ap.capabilities.take(80),
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
                Text(
                    "Watch SSID",
                    color = GrokifyColors.GlowAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, GrokifyColors.GlowAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable(onClick = onWatchSsid)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
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

private fun signalColor(level: Int): Color = when {
    level >= -55 -> GrokifyColors.GlowMint
    level >= -70 -> GrokifyColors.GlowCyan
    level >= -80 -> GrokifyColors.GlowAmber
    else -> GrokifyColors.GlowRose
}

private fun formatTime(ms: Long): String {
    return SimpleDateFormat("HH:mm:ss · MMM d", Locale.getDefault()).format(Date(ms))
}
