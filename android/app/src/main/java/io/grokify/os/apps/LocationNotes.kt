package io.grokify.os.apps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import io.grokify.os.GrokifyApp
import io.grokify.os.MainActivity
import io.grokify.os.R
import io.grokify.os.permission.AppPermissionId
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PLACE_NOTIF_ID_BASE = 4600
private const val ACTION_LOCATION = "io.grokify.os.PLACE_NOTE_LOCATION"
private const val ACTION_OPEN_NOTE = "io.grokify.os.PLACE_NOTE_OPEN"
private const val ACTION_OPEN_APP = "io.grokify.os.PLACE_NOTE_OPEN_APP"
private const val ACTION_OPEN_IMAGE = "io.grokify.os.PLACE_NOTE_OPEN_IMAGE"
/** Widget: toggle global place monitor. */
const val ACTION_PLACE_TOGGLE_MONITOR = "io.grokify.os.PLACE_NOTE_TOGGLE_MONITOR"
/** Widget: arm/disarm a single place note. */
const val ACTION_PLACE_TOGGLE_NOTE = "io.grokify.os.PLACE_NOTE_TOGGLE_NOTE"
/** Widget: re-read GPS + distances. */
const val ACTION_PLACE_REFRESH = "io.grokify.os.PLACE_NOTE_REFRESH"
/** Widget: pin a new note at current GPS. */
const val ACTION_PLACE_PIN_HERE = "io.grokify.os.PLACE_NOTE_PIN_HERE"
/** Widget: open note location in maps. */
const val ACTION_PLACE_OPEN_MAPS = "io.grokify.os.PLACE_NOTE_OPEN_MAPS"
/** Notification action: stop area monitoring FGS. */
const val ACTION_PLACE_STOP_MONITOR = "io.grokify.os.PLACE_NOTE_STOP_MONITOR"
const val EXTRA_NOTE_ID = "note_id"
/** Min gap between enter fires for the same note (process restart / race backup). */
private const val MIN_RETRIGGER_MS = 120_000L
const val PLACE_MONITOR_NOTIF_ID = 4610
private const val TAG_PLACE = "PlaceNotes"
private const val EXTRA_APP_PACKAGE = "app_package"
private const val EXTRA_IMAGE_PATH = "image_path"
private const val MAX_OPEN_APPS = 8
private const val MAX_OPEN_IMAGES = 8

/** App to launch when entering a place note's radius. */
data class PlaceOpenApp(
    val packageName: String,
    val label: String = "",
)

/** A GPS-pinned note with optional enter-area actions. */
data class LocationNote(
    val id: String,
    val title: String,
    val body: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Float = 60f,
    val enabled: Boolean = true,
    val notifyOnEnter: Boolean = true,
    /** Apps to open (or offer) on enter — order is priority. */
    val openApps: List<PlaceOpenApp> = emptyList(),
    /** Image file paths to open (or offer) on enter. */
    val imagePaths: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastTriggeredMs: Long = 0L,
    /** Lifetime enter-fire count (does not reset while still inside). */
    val triggerCount: Int = 0,
) {
    /** First app package — widget / legacy helpers. */
    val openAppPackage: String get() = openApps.firstOrNull()?.packageName.orEmpty()
    val openAppLabel: String get() = openApps.firstOrNull()?.label.orEmpty()
    val imagePath: String get() = imagePaths.firstOrNull().orEmpty()

    fun hasAppAction(): Boolean = openApps.isNotEmpty()
    fun hasImageAction(): Boolean = imagePaths.isNotEmpty()
    fun actionSummary(): String = buildList {
        if (notifyOnEnter) add("notify")
        when (openApps.size) {
            0 -> Unit
            1 -> add("app")
            else -> add("${openApps.size} apps")
        }
        when (imagePaths.size) {
            0 -> Unit
            1 -> add("image")
            else -> add("${imagePaths.size} images")
        }
        if (isEmpty()) add("note only")
    }.joinToString(" · ")
}

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

/** Persisted place notes + monitoring state. */
class LocationNoteStore(context: Context) {
    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<LocationNote> {
        val raw = prefs.getString(KEY_NOTES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.toNote())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): LocationNote? = list().firstOrNull { it.id == id }

    fun upsert(note: LocationNote) {
        val next = list().filterNot { it.id == note.id } + note
        saveAll(next)
        io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(appCtx)
    }

    fun delete(id: String) {
        val note = get(id)
        note?.imagePaths?.forEach { path ->
            if (path.isNotBlank()) runCatching { File(path).takeIf { it.exists() }?.delete() }
        }
        saveAll(list().filterNot { it.id == id })
        clearInside(id)
        io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(appCtx)
    }

    fun monitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITOR, false)

    fun setMonitoringEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR, on).apply()
        io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(appCtx)
    }

    fun isInside(id: String): Boolean =
        prefs.getStringSet(KEY_INSIDE, emptySet())?.contains(id) == true

    fun setInside(id: String, inside: Boolean) {
        val cur = prefs.getStringSet(KEY_INSIDE, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (inside) cur.add(id) else cur.remove(id)
        prefs.edit().putStringSet(KEY_INSIDE, cur).apply()
    }

    fun clearInside(id: String) = setInside(id, false)

    fun markTriggered(id: String, atMs: Long = System.currentTimeMillis()) {
        val n = get(id) ?: return
        upsert(
            n.copy(
                lastTriggeredMs = atMs,
                triggerCount = (n.triggerCount + 1).coerceAtLeast(1),
            ),
        )
    }

    private fun saveAll(notes: List<LocationNote>) {
        val arr = JSONArray()
        notes.sortedByDescending { it.createdAtMs }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    private fun LocationNote.toJson(): JSONObject {
        val appsArr = JSONArray()
        openApps.forEach { app ->
            appsArr.put(
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("label", app.label),
            )
        }
        val imgsArr = JSONArray()
        imagePaths.forEach { imgsArr.put(it) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("body", body)
            .put("lat", lat)
            .put("lon", lon)
            .put("radiusM", radiusM.toDouble())
            .put("enabled", enabled)
            .put("notifyOnEnter", notifyOnEnter)
            .put("openApps", appsArr)
            // Legacy single fields kept for older readers / widgets mid-update.
            .put("openAppPackage", openAppPackage)
            .put("openAppLabel", openAppLabel)
            .put("imagePaths", imgsArr)
            .put("imagePath", imagePath)
            .put("createdAtMs", createdAtMs)
            .put("lastTriggeredMs", lastTriggeredMs)
            .put("triggerCount", triggerCount)
    }

    private fun JSONObject.toNote(): LocationNote {
        val apps = mutableListOf<PlaceOpenApp>()
        val appsJson = optJSONArray("openApps")
        if (appsJson != null) {
            for (i in 0 until appsJson.length()) {
                val o = appsJson.optJSONObject(i) ?: continue
                val pkg = o.optString("packageName", "").trim()
                if (pkg.isBlank()) continue
                apps.add(PlaceOpenApp(pkg, o.optString("label", "")))
            }
        }
        // Migrate legacy single-app fields.
        if (apps.isEmpty()) {
            val legacyPkg = optString("openAppPackage", "").trim()
            if (legacyPkg.isNotBlank()) {
                apps.add(PlaceOpenApp(legacyPkg, optString("openAppLabel", "")))
            }
        }

        val images = mutableListOf<String>()
        val imgsJson = optJSONArray("imagePaths")
        if (imgsJson != null) {
            for (i in 0 until imgsJson.length()) {
                val p = imgsJson.optString(i, "").trim()
                if (p.isNotBlank()) images.add(p)
            }
        }
        if (images.isEmpty()) {
            val legacyImg = optString("imagePath", "").trim()
            if (legacyImg.isNotBlank()) images.add(legacyImg)
        }

        return LocationNote(
            id = optString("id", UUID.randomUUID().toString()),
            title = optString("title", "Place"),
            body = optString("body", ""),
            lat = optDouble("lat", 0.0),
            lon = optDouble("lon", 0.0),
            radiusM = optDouble("radiusM", 60.0).toFloat().coerceIn(15f, 2000f),
            enabled = optBoolean("enabled", true),
            notifyOnEnter = optBoolean("notifyOnEnter", true),
            openApps = apps.distinctBy { it.packageName }.take(MAX_OPEN_APPS),
            imagePaths = images.distinct().take(MAX_OPEN_IMAGES),
            createdAtMs = optLong("createdAtMs", System.currentTimeMillis()),
            lastTriggeredMs = optLong("lastTriggeredMs", 0L),
            triggerCount = optInt("triggerCount", 0).coerceAtLeast(0),
        )
    }

    companion object {
        private const val PREFS = "location_notes"
        private const val KEY_NOTES = "notes_json"
        private const val KEY_MONITOR = "monitor"
        private const val KEY_INSIDE = "inside_ids"
    }
}

/** Haversine distance in meters. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow2() +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow2()
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

private fun Double.pow2(): Double = this * this

fun formatPlaceTime(ms: Long): String {
    if (ms <= 0L) return "—"
    return SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(ms))
}

@SuppressLint("MissingPermission")
fun readPlaceGps(context: Context): GpsFix? {
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
    return best?.let {
        GpsFix(
            lat = it.latitude,
            lon = it.longitude,
            accuracyM = if (it.hasAccuracy()) it.accuracy else -1f,
            atMs = it.time.takeIf { t -> t > 0L } ?: System.currentTimeMillis(),
        )
    }
}

fun listLaunchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return resolved.mapNotNull { ri ->
        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
        val label = ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
        LaunchableApp(pkg, label)
    }.distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.US) }
}

fun copyImageToNotes(context: Context, uri: Uri): String? {
    return runCatching {
        val dir = File(context.filesDir, "place_notes").apply { mkdirs() }
        val nameHint = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
        val ext = nameHint?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 } ?: "jpg"
        val out = File(dir, "img_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out.absolutePath
    }.getOrNull()
}

fun openAppPackage(context: Context, packageName: String): Boolean {
    if (packageName.isBlank()) return false
    val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(launch)
        true
    }.getOrDefault(false)
}

fun openNoteImage(context: Context, path: String): Boolean {
    if (path.isBlank()) return false
    val file = File(path)
    if (!file.exists()) return false
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/**
 * Start / stop place-note area monitoring.
 *
 * Uses a location foreground service so GPS keeps running when the UI is closed.
 * PendingIntent location updates alone are throttled / dropped in the background
 * on modern Android, which is why enter alerts never fired after leaving the app.
 */
object LocationNoteWatcher {
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val appCtx = context.applicationContext
        val store = LocationNoteStore(appCtx)
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val pi = locationPendingIntent(appCtx)
        // Always clear stale PI registrations first.
        if (lm != null) runCatching { lm.removeUpdates(pi) }

        if (!store.monitoringEnabled()) {
            stopService(appCtx)
            return
        }
        if (!locationPermsOk(appCtx)) {
            Log.w(TAG_PLACE, "monitor on but location permission missing")
            stopService(appCtx)
            return
        }
        val active = store.list().any { it.enabled }
        if (!active) {
            // Keep the preference on, but no work until a note is armed.
            stopService(appCtx)
            return
        }

        // Primary path: location FGS (survives app background / process limits).
        startService(appCtx)

        // Backup path: LocationManager → BroadcastReceiver (boot / OEM quirks).
        val minTime = 15_000L
        val minDist = 8f
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            if (lm == null || !lm.isProviderEnabled(p)) return@forEach
            runCatching {
                lm.requestLocationUpdates(p, minTime, minDist, pi)
            }.onFailure { e ->
                Log.w(TAG_PLACE, "requestLocationUpdates($p) failed: ${e.message}")
            }
        }
        // Immediate evaluation from last fix
        readPlaceGps(appCtx)?.let { evaluate(appCtx, it) }
    }

    fun stop(context: Context) {
        val appCtx = context.applicationContext
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            runCatching { lm.removeUpdates(locationPendingIntent(appCtx)) }
        }
        stopService(appCtx)
    }

    /** Persist + start/stop monitoring (used by UI, widgets, plugin uninstall). */
    fun setEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        LocationNoteStore(appCtx).setMonitoringEnabled(enabled)
        if (enabled) sync(appCtx) else stop(appCtx)
        io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(appCtx)
    }

    /**
     * Evaluate geofences. Synchronized so concurrent GPS sources (FGS, UI, broadcast)
     * cannot double-fire enter for the same note. While still inside, enter actions
     * do not re-run; exit is required before the next trigger. [triggerCount] increments
     * only when actions actually fire.
     */
    @Synchronized
    fun evaluate(context: Context, gps: GpsFix) {
        val appCtx = context.applicationContext
        val store = LocationNoteStore(appCtx)
        if (!store.monitoringEnabled()) return
        val now = System.currentTimeMillis()
        var changed = false
        for (note in store.list()) {
            if (!note.enabled) {
                // Disarmed while inside — clear so re-arming requires a fresh enter.
                if (store.isInside(note.id)) {
                    store.setInside(note.id, false)
                    changed = true
                }
                continue
            }
            val dist = distanceMeters(gps.lat, gps.lon, note.lat, note.lon)
            val wasInside = store.isInside(note.id)
            val enterR = note.radiusM.toDouble()
            // Hysteresis: larger exit radius avoids GPS jitter re-enter spam.
            val exitR = note.radiusM * 1.35
            val inside = if (wasInside) dist <= exitR else dist <= enterR
            if (inside && !wasInside) {
                // Mark inside FIRST so any nested evaluate sees presence.
                store.setInside(note.id, true)
                changed = true
                val cool = note.lastTriggeredMs > 0L &&
                    now - note.lastTriggeredMs < MIN_RETRIGGER_MS
                if (!cool) {
                    store.markTriggered(note.id, now)
                    onEnterArea(appCtx, note, dist)
                    Log.i(
                        TAG_PLACE,
                        "enter “${note.title}” d=${dist.toInt()}m triggers=${note.triggerCount + 1}",
                    )
                } else {
                    Log.d(TAG_PLACE, "enter suppressed (cooldown) “${note.title}”")
                }
            } else if (!inside && wasInside) {
                store.setInside(note.id, false)
                changed = true
                Log.d(TAG_PLACE, "exit “${note.title}” d=${dist.toInt()}m")
            }
        }
        if (changed) {
            io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(appCtx)
            // Refresh FGS text (armed / nearest) when enter/exit state flips.
            LocationNoteMonitorService.refreshNotification(appCtx)
        }
    }

    private fun onEnterArea(context: Context, note: LocationNote, distM: Double) {
        if (note.notifyOnEnter || note.hasAppAction() || note.hasImageAction()) {
            notifyPlaceEnter(context, note, distM)
        }
        // Best-effort auto-open (may be blocked when backgrounded — notification actions remain).
        // Open the first app; if none, open the first image. Extra targets stay on the notif.
        val firstApp = note.openApps.firstOrNull()
        if (firstApp != null) {
            openAppPackage(context, firstApp.packageName)
        } else {
            note.imagePaths.firstOrNull()?.let { openNoteImage(context, it) }
        }
    }

    private fun startService(context: Context) {
        val intent = Intent(context, LocationNoteMonitorService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG_PLACE, "startForegroundService failed: ${e.message}", e)
        }
    }

    private fun stopService(context: Context) {
        runCatching {
            context.stopService(Intent(context, LocationNoteMonitorService::class.java))
        }
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(PLACE_MONITOR_NOTIF_ID)
        }
    }

    private fun locationPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LocationNoteReceiver::class.java).setAction(ACTION_LOCATION)
        return PendingIntent.getBroadcast(
            context,
            4701,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Location-type foreground service that keeps GPS hot while Place Notes monitoring is on.
 * This is what produces the system “running in background” / ongoing notification.
 */
class LocationNoteMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!LocationNoteStore(this@LocationNoteMonitorService).monitoringEnabled()) {
                stopSelf()
                return
            }
            // Re-check last fix even if providers are quiet (indoor / fused stall).
            readPlaceGps(this@LocationNoteMonitorService)?.let {
                LocationNoteWatcher.evaluate(this@LocationNoteMonitorService, it)
            }
            refreshNotificationInternal()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = LocationNoteStore(this)
        if (!store.monitoringEnabled() || !store.list().any { it.enabled }) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!locationPermsOk(this)) {
            Log.w(TAG_PLACE, "FGS start without location permission")
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildMonitorNotification(this)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, PLACE_MONITOR_NOTIF_ID, n, fgsType)
            Log.i(TAG_PLACE, "monitor FGS started")
        } catch (e: Exception) {
            Log.e(TAG_PLACE, "startForeground failed: ${e.message}", e)
            try {
                startForeground(PLACE_MONITOR_NOTIF_ID, n)
            } catch (e2: Exception) {
                Log.e(TAG_PLACE, "plain startForeground failed: ${e2.message}", e2)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        attachLocationListener()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        detachLocationListener()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun attachLocationListener() {
        detachLocationListener()
        if (!locationPermsOk(this)) return
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        val locListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                LocationNoteWatcher.evaluate(
                    this@LocationNoteMonitorService,
                    GpsFix(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = if (location.hasAccuracy()) location.accuracy else -1f,
                        atMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
                refreshNotificationInternal()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = locListener
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            if (!lm.isProviderEnabled(p)) return@forEach
            runCatching {
                lm.requestLocationUpdates(p, 12_000L, 6f, locListener, Looper.getMainLooper())
            }.onFailure { e ->
                Log.w(TAG_PLACE, "FGS requestLocationUpdates($p): ${e.message}")
            }
        }
        readPlaceGps(this)?.let { LocationNoteWatcher.evaluate(this, it) }
    }

    private fun detachLocationListener() {
        val l = listener ?: return
        listener = null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(l) }
    }

    private fun refreshNotificationInternal() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!LocationNoteStore(this).monitoringEnabled()) return
        nm.notify(PLACE_MONITOR_NOTIF_ID, buildMonitorNotification(this))
    }

    companion object {
        private const val TICK_MS = 25_000L

        fun refreshNotification(context: Context) {
            val appCtx = context.applicationContext
            if (!LocationNoteStore(appCtx).monitoringEnabled()) return
            val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
            // Only update if FGS notif is expected; avoid re-posting after stop.
            nm.notify(PLACE_MONITOR_NOTIF_ID, buildMonitorNotification(appCtx))
        }

        fun buildMonitorNotification(context: Context): Notification {
            val store = LocationNoteStore(context)
            val notes = store.list().filter { it.enabled }
            val gps = readPlaceGps(context)
            val nearest = if (gps != null && notes.isNotEmpty()) {
                notes.minByOrNull { distanceMeters(gps.lat, gps.lon, it.lat, it.lon) }
            } else {
                null
            }
            val nearestLine = when {
                nearest == null -> null
                gps == null -> null
                else -> {
                    val d = distanceMeters(gps.lat, gps.lon, nearest.lat, nearest.lon)
                    val inside = d <= nearest.radiusM
                    if (inside) "Inside “${nearest.title}”"
                    else String.format(Locale.US, "Nearest: %s · %.0f m", nearest.title, d)
                }
            }
            val text = buildString {
                append(
                    when (notes.size) {
                        0 -> context.getString(R.string.place_monitor_text)
                        1 -> "Watching 1 place in the background"
                        else -> "Watching ${notes.size} places in the background"
                    },
                )
                if (nearestLine != null) {
                    append(" · ")
                    append(nearestLine)
                }
            }
            val openMain = PendingIntent.getActivity(
                context,
                4710,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_app", "place_notes")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopPi = PendingIntent.getBroadcast(
                context,
                4711,
                Intent(context, LocationNoteReceiver::class.java)
                    .setAction(ACTION_PLACE_STOP_MONITOR),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, GrokifyApp.CHANNEL_PLACE_MONITOR)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(context.getString(R.string.place_monitor_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openMain)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(R.string.place_monitor_stop), stopPi)
                .build()
        }
    }
}

fun notifyPlaceEnter(context: Context, note: LocationNote, distM: Double) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    val openMain = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("open_app", "place_notes")
        putExtra(EXTRA_NOTE_ID, note.id)
    }
    val mainPi = PendingIntent.getActivity(
        context,
        note.id.hashCode() and 0xFFFF,
        openMain,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val body = buildString {
        if (note.body.isNotBlank()) append(note.body.trim())
        if (isNotEmpty()) append("\n")
        append(String.format(Locale.US, "≈ %.0f m from pin · r %.0f m", distM, note.radiusM))
        if (note.openApps.isNotEmpty()) {
            append("\nApps: ")
            append(
                note.openApps.joinToString(", ") {
                    it.label.ifBlank { it.packageName }.take(24)
                },
            )
        }
        if (note.imagePaths.isNotEmpty()) {
            append("\n")
            append(
                if (note.imagePaths.size == 1) "1 image attached"
                else "${note.imagePaths.size} images attached",
            )
        }
        if (note.triggerCount > 0) {
            append("\nEnter #${note.triggerCount + 1}")
        }
    }
    val builder = NotificationCompat.Builder(context, GrokifyApp.CHANNEL_PLACE_NOTES)
        .setSmallIcon(android.R.drawable.ic_dialog_map)
        .setContentTitle("At: ${note.title}")
        .setContentText(body.replace("\n", " · "))
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(mainPi)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)

    // Android surfaces ~3 actions; prefer first app + first image.
    var actionSeed = 11
    note.openApps.firstOrNull()?.let { app ->
        val label = app.label.ifBlank { "Open app" }.take(18)
        val appPi = PendingIntent.getBroadcast(
            context,
            (note.id.hashCode() + actionSeed) and 0xFFFF,
            Intent(context, LocationNoteReceiver::class.java)
                .setAction(ACTION_OPEN_APP)
                .putExtra(EXTRA_NOTE_ID, note.id)
                .putExtra(EXTRA_APP_PACKAGE, app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, label, appPi)
        actionSeed += 3
    }
    note.imagePaths.firstOrNull()?.let { path ->
        val imgPi = PendingIntent.getBroadcast(
            context,
            (note.id.hashCode() + actionSeed) and 0xFFFF,
            Intent(context, LocationNoteReceiver::class.java)
                .setAction(ACTION_OPEN_IMAGE)
                .putExtra(EXTRA_NOTE_ID, note.id)
                .putExtra(EXTRA_IMAGE_PATH, path),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, "Open image", imgPi)
    }

    nm.notify(PLACE_NOTIF_ID_BASE + (note.id.hashCode() and 0x0FFF), builder.build())
}

/** Location updates + notification actions for place notes. */
class LocationNoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val store = LocationNoteStore(context)
        when (action) {
            ACTION_LOCATION -> {
                val loc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
                }
                if (loc != null) {
                    LocationNoteWatcher.evaluate(
                        context,
                        GpsFix(
                            lat = loc.latitude,
                            lon = loc.longitude,
                            accuracyM = if (loc.hasAccuracy()) loc.accuracy else -1f,
                            atMs = loc.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        ),
                    )
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (store.monitoringEnabled()) LocationNoteWatcher.sync(context)
            }
            ACTION_PLACE_STOP_MONITOR -> {
                LocationNoteWatcher.setEnabled(context, false)
            }
            ACTION_OPEN_APP -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                val note = store.get(id) ?: return
                val pkg = intent.getStringExtra(EXTRA_APP_PACKAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: note.openAppPackage
                if (pkg.isNotBlank()) {
                    openAppPackage(context, pkg)
                } else {
                    // Open every attached app if no specific package was requested.
                    note.openApps.forEach { openAppPackage(context, it.packageName) }
                }
            }
            ACTION_OPEN_IMAGE -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                val note = store.get(id) ?: return
                val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
                    ?.takeIf { it.isNotBlank() }
                    ?: note.imagePath
                if (path.isNotBlank()) {
                    openNoteImage(context, path)
                } else {
                    note.imagePaths.forEach { openNoteImage(context, it) }
                }
            }
            ACTION_OPEN_NOTE -> {
                // reserved — main activity handles open_app
            }
            ACTION_PLACE_TOGGLE_MONITOR -> {
                LocationNoteWatcher.setEnabled(context, !store.monitoringEnabled())
            }
            ACTION_PLACE_TOGGLE_NOTE -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                val note = store.get(id) ?: return
                store.upsert(note.copy(enabled = !note.enabled))
                if (store.monitoringEnabled()) LocationNoteWatcher.sync(context)
                io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(context)
            }
            ACTION_PLACE_REFRESH -> {
                if (store.monitoringEnabled()) {
                    LocationNoteWatcher.sync(context)
                    readPlaceGps(context)?.let { LocationNoteWatcher.evaluate(context, it) }
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(context)
            }
            ACTION_PLACE_PIN_HERE -> {
                val gps = readPlaceGps(context)
                if (gps != null) {
                    val stamp = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
                        .format(Date())
                    store.upsert(
                        LocationNote(
                            id = UUID.randomUUID().toString(),
                            title = "Pinned · $stamp",
                            body = "",
                            lat = gps.lat,
                            lon = gps.lon,
                            radiusM = 60f,
                            enabled = true,
                            notifyOnEnter = true,
                        ),
                    )
                    if (store.monitoringEnabled()) LocationNoteWatcher.sync(context)
                }
                io.grokify.os.widgets.GrokifyWidgets.refreshPlaceNotes(context)
            }
            ACTION_PLACE_OPEN_MAPS -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                val note = store.get(id) ?: return
                openPlaceMaps(context, note)
            }
        }
    }
}

/** Open a place pin in the device maps app (no Grokify activity). */
fun openPlaceMaps(context: Context, note: LocationNote): Boolean {
    val label = Uri.encode(note.title.ifBlank { "Place" })
    val geo = Uri.parse("geo:${note.lat},${note.lon}?q=${note.lat},${note.lon}($label)")
    val intent = Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

@Composable
private fun placeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    focusedBorderColor = GrokifyColors.GlowViolet,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    focusedLabelColor = GrokifyColors.GlowViolet,
    unfocusedLabelColor = GrokifyColors.TextMuted,
    cursorColor = GrokifyColors.GlowViolet,
    focusedContainerColor = GrokifyColors.PanelSoft,
    unfocusedContainerColor = GrokifyColors.PanelSoft,
)

@Composable
fun LocationNotesPane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val store = remember { LocationNoteStore(appCtx) }

    var notes by remember { mutableStateOf(store.list()) }
    var gps by remember { mutableStateOf(readPlaceGps(appCtx)) }
    var monitoring by remember { mutableStateOf(store.monitoringEnabled()) }
    var viewMode by remember { mutableStateOf("list") } // list | map
    var mapFullscreen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LocationNote?>(null) }
    var creating by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Place notes at GPS spots · auto-actions on enter") }
    var showAppPicker by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }
    /** Map in editor is collapsible so the note field stays above the keyboard. */
    var editorMapExpanded by remember { mutableStateOf(true) }
    var draft by remember {
        mutableStateOf(
            LocationNote(
                id = "",
                title = "",
                body = "",
                lat = 0.0,
                lon = 0.0,
            ),
        )
    }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val keyboardOpen = imeBottom > 0

    fun reload() {
        notes = store.list()
    }

    fun beginCreate() {
        val fix = readPlaceGps(appCtx) ?: gps
        if (fix == null) {
            status = "Need a GPS fix first — enable Location and wait a moment"
            onRequestPermissions()
            return
        }
        draft = LocationNote(
            id = UUID.randomUUID().toString(),
            title = "",
            body = "",
            lat = fix.lat,
            lon = fix.lon,
            radiusM = 60f,
            enabled = true,
            notifyOnEnter = true,
        )
        creating = true
        editing = null
        viewMode = "list"
        mapFullscreen = false
    }

    fun beginEdit(note: LocationNote) {
        draft = note
        editing = note
        creating = false
        viewMode = "list"
        mapFullscreen = false
    }

    fun saveDraft() {
        val title = draft.title.trim().ifBlank { "Place note" }
        val cleaned = draft.copy(
            title = title,
            body = draft.body.trim(),
            radiusM = draft.radiusM.coerceIn(15f, 2000f),
            openApps = draft.openApps
                .map { it.copy(packageName = it.packageName.trim(), label = it.label.trim()) }
                .filter { it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .take(MAX_OPEN_APPS),
            imagePaths = draft.imagePaths.filter { it.isNotBlank() }.distinct().take(MAX_OPEN_IMAGES),
        )
        store.upsert(cleaned)
        reload()
        creating = false
        editing = null
        selectedId = cleaned.id
        status = "Saved “${cleaned.title}”"
        if (monitoring) LocationNoteWatcher.sync(appCtx)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = copyImageToNotes(appCtx, uri)
        if (path != null) {
            if (draft.imagePaths.contains(path)) {
                status = "Image already attached"
            } else if (draft.imagePaths.size >= MAX_OPEN_IMAGES) {
                status = "Max $MAX_OPEN_IMAGES images"
            } else {
                val next = draft.imagePaths + path
                draft = draft.copy(imagePaths = next)
                status = "Image added (${next.size} total)"
            }
        } else {
            status = "Could not copy image"
        }
    }

    DisposableEffect(Unit) {
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = GpsFix(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else -1f,
                    atMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
                gps = fix
                if (monitoring) LocationNoteWatcher.evaluate(appCtx, fix)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        if (locationPermsOk(appCtx)) {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        8_000L,
                        5f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        12_000L,
                        10f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            } catch (_: SecurityException) {
            }
        }
        if (monitoring) LocationNoteWatcher.sync(appCtx)
        onDispose {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            val fix = readPlaceGps(appCtx)
            if (fix != null) {
                gps = fix
                if (monitoring) LocationNoteWatcher.evaluate(appCtx, fix)
            }
        }
    }

    val formOpen = creating || editing != null
    // Collapse pin map while the keyboard is open so the note field stays visible.
    LaunchedEffect(keyboardOpen, formOpen) {
        if (formOpen && keyboardOpen) editorMapExpanded = false
    }
    LaunchedEffect(creating, editing?.id) {
        if (formOpen) editorMapExpanded = true
    }
    val sortedNotes = remember(notes, gps) {
        if (gps == null) notes.sortedByDescending { it.createdAtMs }
        else notes.sortedBy {
            distanceMeters(gps!!.lat, gps!!.lon, it.lat, it.lon)
        }
    }
    val mapMarkers = remember(notes, gps) {
        notes.map { n ->
            val dist = gps?.let { distanceMeters(it.lat, it.lon, n.lat, n.lon) }
            WifiMapMarker(
                id = n.id,
                ssid = n.title.ifBlank { "Place" },
                bssid = n.actionSummary(),
                lat = n.lat,
                lon = n.lon,
                level = if (n.enabled) -50 else -90,
                distanceM = dist,
                seenCount = 1,
                live = n.enabled,
                radiusM = n.radiusM.toDouble(),
            )
        }
    }
    // Pin geometry only — title/body typing must not re-fit the map (that felt "static").
    val draftPinKey = "${draft.lat}|${draft.lon}|${draft.radiusM}"
    var editorLastFitPin by remember { mutableStateOf("") }
    val editorShouldAutoFit = formOpen && draftPinKey != editorLastFitPin
    LaunchedEffect(draftPinKey, formOpen) {
        if (!formOpen) return@LaunchedEffect
        // Keep autoFit true briefly so the WebView can apply fitBounds, then lock pan free.
        delay(450)
        editorLastFitPin = draftPinKey
    }
    val draftMapMarkers = remember(draftPinKey, formOpen, gps, creating, draft.title, draft.id, draft.enabled) {
        if (!formOpen || !draft.lat.isFinite() || !draft.lon.isFinite()) emptyList()
        else {
            val dist = gps?.let { distanceMeters(it.lat, it.lon, draft.lat, draft.lon) }
            listOf(
                WifiMapMarker(
                    id = draft.id.ifBlank { "draft" },
                    ssid = draft.title.ifBlank { if (creating) "New place" else "Place" },
                    bssid = "r ${draft.radiusM.toInt()}m · tap map to move pin",
                    lat = draft.lat,
                    lon = draft.lon,
                    level = -50,
                    distanceM = dist,
                    seenCount = 1,
                    live = draft.enabled,
                    radiusM = draft.radiusM.toDouble(),
                ),
            )
        }
    }

    if (showAppPicker) {
        val apps = remember { listLaunchableApps(appCtx) }
        val selectedPkgs = remember(draft.openApps) { draft.openApps.map { it.packageName }.toSet() }
        val filtered = remember(appQuery, apps) {
            val q = appQuery.trim().lowercase(Locale.US)
            if (q.isEmpty()) apps.take(80)
            else apps.filter {
                it.label.lowercase(Locale.US).contains(q) ||
                    it.packageName.lowercase(Locale.US).contains(q)
            }.take(80)
        }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("Add apps to open on enter", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
                    Text(
                        "${draft.openApps.size}/$MAX_OPEN_APPS selected · tap to add/remove",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = appQuery,
                        onValueChange = { appQuery = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                        colors = placeFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        filtered.forEach { app ->
                            val selected = app.packageName in selectedPkgs
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selected) {
                                            draft = draft.copy(
                                                openApps = draft.openApps.filterNot {
                                                    it.packageName == app.packageName
                                                },
                                            )
                                        } else if (draft.openApps.size >= MAX_OPEN_APPS) {
                                            status = "Max $MAX_OPEN_APPS apps"
                                        } else {
                                            draft = draft.copy(
                                                openApps = draft.openApps + PlaceOpenApp(
                                                    packageName = app.packageName,
                                                    label = app.label,
                                                ),
                                            )
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.label,
                                        color = if (selected) GrokifyColors.GlowMint else GrokifyColors.TextPrimary,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        app.packageName,
                                        color = GrokifyColors.TextDim,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                if (selected) {
                                    Text("✓", color = GrokifyColors.GlowMint, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAppPicker = false
                    appQuery = ""
                }) {
                    Text("Done", color = GrokifyColors.GlowCyan)
                }
            },
            containerColor = GrokifyColors.Panel,
        )
    }

    Box(Modifier.fillMaxSize().background(GrokifyColors.Void)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(if (mapFullscreen && !formOpen) 0.dp else 12.dp),
        ) {
            if (!(mapFullscreen && viewMode == "map" && !formOpen)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = {
                        if (formOpen) {
                            creating = false
                            editing = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GrokifyColors.GlowViolet,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (formOpen) {
                                if (creating) "New place note" else "Edit place note"
                            } else {
                                "Place Notes"
                            },
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
                    if (!formOpen) {
                        IconButton(onClick = {
                            gps = readPlaceGps(appCtx)
                            reload()
                            status = if (gps != null) "GPS refreshed" else "No GPS fix yet"
                        }) {
                            Icon(Icons.Default.Refresh, null, tint = GrokifyColors.GlowViolet)
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
                                !locationPermsOk(appCtx) -> "Enable Location to pin notes and watch areas"
                                gps == null -> "Waiting for fix…"
                                else -> "Notes use this location · ${formatPlaceTime(gps!!.atMs)}"
                            },
                            color = GrokifyColors.TextDim,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                    if (!locationPermsOk(appCtx)) {
                        TextButton(onClick = onRequestPermissions) {
                            Text("Allow", color = GrokifyColors.GlowViolet, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (formOpen) {
                Spacer(Modifier.height(8.dp))
                // Editor column: collapsible map + scrollable form with IME padding so
                // the note field is not buried under the keyboard.
                Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(GrokifyColors.Panel)
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                editorMapExpanded = !editorMapExpanded
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = GrokifyColors.GlowCyan,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (editorMapExpanded) "Pin map" else "Pin map (collapsed)",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                String.format(
                                    Locale.US,
                                    "%.5f, %.5f · r %dm · tap to %s",
                                    draft.lat,
                                    draft.lon,
                                    draft.radiusM.toInt(),
                                    if (editorMapExpanded) "hide" else "show map",
                                ),
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            if (editorMapExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (editorMapExpanded) "Collapse map" else "Expand map",
                            tint = GrokifyColors.TextMuted,
                        )
                    }
                    if (editorMapExpanded && !keyboardOpen) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        ) {
                            WifiMapView(
                                markers = draftMapMarkers,
                                userGps = gps,
                                selectedId = draft.id.ifBlank { "draft" },
                                emptyHint = "Tap the map to drop a pin",
                                onMapTapped = { lat, lon ->
                                    draft = draft.copy(lat = lat, lon = lon)
                                    status = "Pin moved · ${String.format(Locale.US, "%.5f, %.5f", lat, lon)}"
                                },
                                // Fit only when pin/radius changes — free pan/zoom while typing.
                                autoFit = editorShouldAutoFit,
                                framed = true,
                                resizeKey = "editor-${draft.id}-${editorMapExpanded}",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            "Tap map to place pin · pan/zoom freely · ring = enter radius",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                    } else if (keyboardOpen) {
                        Text(
                            "Map hidden while typing — collapse lifts the note field above the keyboard",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    PlaceNoteEditor(
                        draft = draft,
                        onDraft = { draft = it },
                        gps = gps,
                        onUseGps = {
                            val fix = readPlaceGps(appCtx) ?: gps
                            if (fix != null) {
                                draft = draft.copy(lat = fix.lat, lon = fix.lon)
                                status = "Pinned to current GPS"
                            } else {
                                status = "No GPS fix"
                                onRequestPermissions()
                            }
                        },
                        onPickImage = {
                            onRequestPermissions()
                            imagePicker.launch("image/*")
                        },
                        onRemoveImage = { path ->
                            if (path.isNotBlank()) runCatching { File(path).delete() }
                            draft = draft.copy(imagePaths = draft.imagePaths.filterNot { it == path })
                        },
                        onPickApp = { showAppPicker = true },
                        onRemoveApp = { pkg ->
                            draft = draft.copy(
                                openApps = draft.openApps.filterNot { it.packageName == pkg },
                            )
                        },
                        onSave = { saveDraft() },
                        onCancel = {
                            creating = false
                            editing = null
                        },
                        onFieldFocused = { editorMapExpanded = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                if (!mapFullscreen) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { beginCreate() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GrokifyColors.GlowViolet,
                                contentColor = Color(0xFF12081F),
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Note here", fontWeight = FontWeight.SemiBold)
                        }
                        // Labeled List | Map control (easier than a lone icon).
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(GrokifyColors.Panel)
                                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf(
                                "list" to Icons.AutoMirrored.Filled.List,
                                "map" to Icons.Default.Map,
                            ).forEach { (mode, icon) ->
                                val sel = viewMode == mode
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (sel) GrokifyColors.GlowViolet.copy(alpha = 0.28f)
                                            else Color.Transparent,
                                        )
                                        .clickable { viewMode = mode }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = mode,
                                        tint = if (sel) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        mode.replaceFirstChar { it.uppercase() },
                                        color = if (sel) GrokifyColors.TextPrimary else GrokifyColors.TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(GrokifyColors.Panel)
                            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (monitoring) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = if (monitoring) GrokifyColors.GlowAmber else GrokifyColors.TextDim,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Area monitoring",
                                color = GrokifyColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (monitoring) {
                                    "On — runs in background · enter radius → notify / app / image"
                                } else {
                                    "Off — enable to watch places in the background"
                                },
                                color = GrokifyColors.TextDim,
                                fontSize = 11.sp,
                                maxLines = 2,
                            )
                        }
                        Switch(
                            checked = monitoring,
                            onCheckedChange = { on ->
                                if (on) {
                                    if (!locationPermsOk(appCtx)) onRequestPermissions()
                                    if (!PermissionHelper.status(appCtx, AppPermissionId.NOTIFICATIONS).granted &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                    ) {
                                        onRequestPermissions()
                                    }
                                }
                                monitoring = on
                                LocationNoteWatcher.setEnabled(appCtx, on)
                                status = if (on) {
                                    if (!locationPermsOk(appCtx)) {
                                        "Need Location permission for background watch"
                                    } else if (notes.none { it.enabled }) {
                                        "Monitoring on — arm a place note to start watching"
                                    } else {
                                        "Area monitoring on · keep the Place Notes notification"
                                    }
                                } else {
                                    "Area monitoring off"
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GrokifyColors.GlowAmber,
                                checkedTrackColor = GrokifyColors.GlowAmber.copy(alpha = 0.35f),
                                uncheckedThumbColor = GrokifyColors.TextMuted,
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (viewMode == "map") {
                    if (!mapFullscreen) {
                        Text(
                            buildString {
                                append("${mapMarkers.size} place${if (mapMarkers.size == 1) "" else "s"}")
                                append(" · rings = enter radius · tap pin for details")
                            },
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        WifiMapView(
                            markers = mapMarkers,
                            userGps = gps,
                            selectedId = selectedId,
                            onMarkerSelected = { id ->
                                selectedId = id
                                notes.firstOrNull { it.id == id }?.let {
                                    status = "${it.title} · ${it.actionSummary()}"
                                }
                            },
                            emptyHint = if (mapMarkers.isEmpty()) {
                                "No places yet — tap Note here to pin one"
                            } else {
                                null
                            },
                            framed = !mapFullscreen,
                            resizeKey = mapFullscreen,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Row(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconButton(
                                onClick = { mapFullscreen = !mapFullscreen },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(GrokifyColors.Panel.copy(alpha = 0.92f)),
                            ) {
                                Icon(
                                    if (mapFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    null,
                                    tint = GrokifyColors.GlowCyan,
                                )
                            }
                        }
                        selectedId?.let { sid ->
                            val n = notes.firstOrNull { it.id == sid }
                            if (n != null && !mapFullscreen) {
                                PlaceNoteMiniCard(
                                    note = n,
                                    gps = gps,
                                    onEdit = { beginEdit(n) },
                                    onDelete = {
                                        store.delete(n.id)
                                        selectedId = null
                                        reload()
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(10.dp),
                                )
                            }
                        }
                    }
                } else {
                    if (sortedNotes.isEmpty()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Place,
                                null,
                                tint = GrokifyColors.GlowViolet.copy(alpha = 0.7f),
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No place notes yet",
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tap “Note here” to pin a note at your GPS.\nOn enter: notify, open apps, or show images — once per visit.",
                                color = GrokifyColors.TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(sortedNotes, key = { it.id }) { note ->
                                PlaceNoteRow(
                                    note = note,
                                    gps = gps,
                                    selected = note.id == selectedId,
                                    onClick = { selectedId = note.id },
                                    onEdit = { beginEdit(note) },
                                    onDelete = {
                                        store.delete(note.id)
                                        if (selectedId == note.id) selectedId = null
                                        reload()
                                        if (monitoring) LocationNoteWatcher.sync(appCtx)
                                    },
                                    onToggleEnabled = { on ->
                                        store.upsert(note.copy(enabled = on))
                                        reload()
                                        if (monitoring) LocationNoteWatcher.sync(appCtx)
                                    },
                                    onTestOpen = {
                                        var opened = false
                                        note.openApps.forEach { app ->
                                            if (openAppPackage(appCtx, app.packageName)) opened = true
                                        }
                                        note.imagePaths.forEach { path ->
                                            if (openNoteImage(appCtx, path)) opened = true
                                        }
                                        status = when {
                                            opened -> "Opened ${note.actionSummary()}"
                                            else -> note.body.ifBlank { note.title }
                                        }
                                    },
                                )
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceNoteEditor(
    draft: LocationNote,
    onDraft: (LocationNote) -> Unit,
    gps: GpsFix?,
    onUseGps: () -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: (path: String) -> Unit,
    onPickApp: () -> Unit,
    onRemoveApp: (packageName: String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onFieldFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var manualPkg by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onDraft(draft.copy(title = it)) },
            label = { Text("Title") },
            singleLine = true,
            colors = placeFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFieldFocused() },
        )
        OutlinedTextField(
            value = draft.body,
            onValueChange = { onDraft(draft.copy(body = it)) },
            label = { Text("Note") },
            minLines = 4,
            maxLines = 10,
            colors = placeFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(
                onDone = { keyboard?.hide() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFieldFocused() },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Text("Location", color = GrokifyColors.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                String.format(Locale.US, "%.6f, %.6f", draft.lat, draft.lon),
                color = GrokifyColors.TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
            if (gps != null) {
                val d = distanceMeters(gps.lat, gps.lon, draft.lat, draft.lon)
                Text(
                    String.format(Locale.US, "≈ %.0f m from you now", d),
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onUseGps) {
                Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(16.dp), tint = GrokifyColors.GlowMint)
                Spacer(Modifier.width(6.dp))
                Text("Use current GPS", color = GrokifyColors.GlowMint, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("Radius: ${draft.radiusM.toInt()} m", color = GrokifyColors.TextPrimary, fontSize = 13.sp)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(30f, 60f, 100f, 200f, 500f).forEach { r ->
                    val sel = draft.radiusM == r
                    Text(
                        "${r.toInt()}m",
                        color = if (sel) Color(0xFF12081F) else GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) GrokifyColors.GlowViolet else GrokifyColors.PanelSoft)
                            .clickable { onDraft(draft.copy(radiusM = r)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Notify on enter", color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                Text("Shows the note when you walk into the radius", color = GrokifyColors.TextDim, fontSize = 11.sp)
            }
            Switch(
                checked = draft.notifyOnEnter,
                onCheckedChange = { onDraft(draft.copy(notifyOnEnter = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GrokifyColors.GlowAmber,
                    checkedTrackColor = GrokifyColors.GlowAmber.copy(alpha = 0.35f),
                ),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Apps, null, tint = GrokifyColors.GlowCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Open apps on enter",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "First app auto-opens · others via notification",
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    "${draft.openApps.size}/$MAX_OPEN_APPS",
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (draft.openApps.isEmpty()) {
                Text("No apps yet", color = GrokifyColors.TextDim, fontSize = 12.sp)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    draft.openApps.forEach { app ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GrokifyColors.PanelSoft)
                                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(8.dp))
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                app.label.ifBlank { app.packageName }.take(22),
                                color = GrokifyColors.GlowMint,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                            IconButton(
                                onClick = { onRemoveApp(app.packageName) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove app",
                                    tint = GrokifyColors.GlowRose,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onPickApp) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp), tint = GrokifyColors.GlowCyan)
                Spacer(Modifier.width(4.dp))
                Text("Add app", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
            }
            OutlinedTextField(
                value = manualPkg,
                onValueChange = { manualPkg = it },
                label = { Text("Package name (manual add)") },
                singleLine = true,
                colors = placeFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val pkg = manualPkg.trim()
                        if (pkg.isBlank()) return@KeyboardActions
                        if (draft.openApps.any { it.packageName == pkg }) {
                            manualPkg = ""
                            return@KeyboardActions
                        }
                        if (draft.openApps.size >= MAX_OPEN_APPS) return@KeyboardActions
                        onDraft(
                            draft.copy(
                                openApps = draft.openApps + PlaceOpenApp(pkg, pkg.substringAfterLast('.')),
                            ),
                        )
                        manualPkg = ""
                        keyboard?.hide()
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) onFieldFocused() },
            )
            if (manualPkg.isNotBlank()) {
                TextButton(
                    onClick = {
                        val pkg = manualPkg.trim()
                        if (pkg.isBlank() || draft.openApps.any { it.packageName == pkg }) return@TextButton
                        if (draft.openApps.size >= MAX_OPEN_APPS) return@TextButton
                        onDraft(
                            draft.copy(
                                openApps = draft.openApps + PlaceOpenApp(pkg, pkg.substringAfterLast('.')),
                            ),
                        )
                        manualPkg = ""
                    },
                ) {
                    Text("Add package", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Image, null, tint = GrokifyColors.GlowMint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Open images on enter",
                        color = GrokifyColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "First image opens if no app · more via notification",
                        color = GrokifyColors.TextDim,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    "${draft.imagePaths.size}/$MAX_OPEN_IMAGES",
                    color = GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (draft.imagePaths.isEmpty()) {
                Text("No images yet", color = GrokifyColors.TextDim, fontSize = 12.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.imagePaths.forEach { path ->
                        if (File(path).exists()) {
                            Box {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                IconButton(
                                    onClick = { onRemoveImage(path) },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(GrokifyColors.Panel.copy(alpha = 0.9f)),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove image",
                                        tint = GrokifyColors.GlowRose,
                                    )
                                }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Missing file",
                                    color = GrokifyColors.GlowRose,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onRemoveImage(path) }) {
                                    Text("Remove", color = GrokifyColors.GlowRose, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onPickImage) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp), tint = GrokifyColors.GlowMint)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (draft.imagePaths.isEmpty()) "Add image" else "Add another image",
                    color = GrokifyColors.GlowMint,
                    fontSize = 13.sp,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Watch this place", color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                Text(
                    buildString {
                        append("Included when area monitoring is on")
                        if (draft.triggerCount > 0) {
                            append(" · triggered ${draft.triggerCount}×")
                        }
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = draft.enabled,
                onCheckedChange = { onDraft(draft.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GrokifyColors.GlowMint,
                    checkedTrackColor = GrokifyColors.GlowMint.copy(alpha = 0.35f),
                ),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancel", color = GrokifyColors.TextMuted)
            }
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrokifyColors.GlowViolet,
                    contentColor = Color(0xFF12081F),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        }
        // Extra space so the last fields clear the IME when scrolled.
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun PlaceNoteRow(
    note: LocationNote,
    gps: GpsFix?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTestOpen: () -> Unit,
) {
    val dist = gps?.let { distanceMeters(it.lat, it.lon, note.lat, note.lon) }
    val inside = dist != null && dist <= note.radiusM
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) GrokifyColors.PanelSoft else GrokifyColors.Panel)
            .border(
                1.dp,
                when {
                    inside -> GrokifyColors.GlowMint.copy(alpha = 0.7f)
                    selected -> GrokifyColors.GlowViolet.copy(alpha = 0.55f)
                    else -> GrokifyColors.PanelBorder
                },
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.Notes,
                null,
                tint = if (note.enabled) GrokifyColors.GlowViolet else GrokifyColors.TextDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    note.title,
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        if (dist != null) append(String.format(Locale.US, "≈ %.0f m · ", dist))
                        append("r ${note.radiusM.toInt()} m · ")
                        append(note.actionSummary())
                        if (inside) append(" · HERE")
                    },
                    color = if (inside) GrokifyColors.GlowMint else GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = note.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GrokifyColors.GlowMint,
                    checkedTrackColor = GrokifyColors.GlowMint.copy(alpha = 0.35f),
                ),
            )
        }
        if (note.body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                note.body,
                color = GrokifyColors.TextMuted,
                fontSize = 13.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val thumbs = note.imagePaths.filter { File(it).exists() }.take(3)
        if (thumbs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                thumbs.forEach { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 96.dp, height = 72.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                if (note.imagePaths.size > thumbs.size) {
                    Text(
                        "+${note.imagePaths.size - thumbs.size}",
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = GrokifyColors.GlowCyan)
                Spacer(Modifier.width(4.dp))
                Text("Edit", color = GrokifyColors.GlowCyan, fontSize = 12.sp)
            }
            if (note.hasAppAction() || note.hasImageAction()) {
                TextButton(onClick = onTestOpen) {
                    Text("Test open", color = GrokifyColors.GlowMint, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp), tint = GrokifyColors.GlowRose)
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = GrokifyColors.GlowRose, fontSize = 12.sp)
            }
            if (note.triggerCount > 0 || note.lastTriggeredMs > 0L) {
                Text(
                    buildString {
                        if (note.triggerCount > 0) append("${note.triggerCount}×")
                        if (note.lastTriggeredMs > 0L) {
                            if (isNotEmpty()) append(" · ")
                            append(formatPlaceTime(note.lastTriggeredMs))
                        }
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaceNoteMiniCard(
    note: LocationNote,
    gps: GpsFix?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dist = gps?.let { distanceMeters(it.lat, it.lon, note.lat, note.lon) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel.copy(alpha = 0.95f))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(note.title, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            buildString {
                if (dist != null) append(String.format(Locale.US, "≈ %.0f m · ", dist))
                append(note.actionSummary())
            },
            color = GrokifyColors.TextMuted,
            fontSize = 12.sp,
        )
        Row {
            TextButton(onClick = onEdit) { Text("Edit", color = GrokifyColors.GlowCyan) }
            TextButton(onClick = onDelete) { Text("Delete", color = GrokifyColors.GlowRose) }
        }
    }
}
