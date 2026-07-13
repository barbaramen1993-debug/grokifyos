package io.grokify.os.apps

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import io.grokify.os.GrokifyApp
import io.grokify.os.MainActivity
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
private const val EXTRA_NOTE_ID = "note_id"
private const val MIN_RETRIGGER_MS = 90_000L

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
    val openAppPackage: String = "",
    val openAppLabel: String = "",
    val imagePath: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastTriggeredMs: Long = 0L,
) {
    fun hasAppAction(): Boolean = openAppPackage.isNotBlank()
    fun hasImageAction(): Boolean = imagePath.isNotBlank()
    fun actionSummary(): String = buildList {
        if (notifyOnEnter) add("notify")
        if (hasAppAction()) add("app")
        if (hasImageAction()) add("image")
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
    }

    fun delete(id: String) {
        val note = get(id)
        if (note != null && note.imagePath.isNotBlank()) {
            runCatching { File(note.imagePath).takeIf { it.exists() }?.delete() }
        }
        saveAll(list().filterNot { it.id == id })
        clearInside(id)
    }

    fun monitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITOR, false)

    fun setMonitoringEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR, on).apply()
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
        upsert(n.copy(lastTriggeredMs = atMs))
    }

    private fun saveAll(notes: List<LocationNote>) {
        val arr = JSONArray()
        notes.sortedByDescending { it.createdAtMs }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    private fun LocationNote.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("lat", lat)
        .put("lon", lon)
        .put("radiusM", radiusM.toDouble())
        .put("enabled", enabled)
        .put("notifyOnEnter", notifyOnEnter)
        .put("openAppPackage", openAppPackage)
        .put("openAppLabel", openAppLabel)
        .put("imagePath", imagePath)
        .put("createdAtMs", createdAtMs)
        .put("lastTriggeredMs", lastTriggeredMs)

    private fun JSONObject.toNote(): LocationNote = LocationNote(
        id = optString("id", UUID.randomUUID().toString()),
        title = optString("title", "Place"),
        body = optString("body", ""),
        lat = optDouble("lat", 0.0),
        lon = optDouble("lon", 0.0),
        radiusM = optDouble("radiusM", 60.0).toFloat().coerceIn(15f, 2000f),
        enabled = optBoolean("enabled", true),
        notifyOnEnter = optBoolean("notifyOnEnter", true),
        openAppPackage = optString("openAppPackage", ""),
        openAppLabel = optString("openAppLabel", ""),
        imagePath = optString("imagePath", ""),
        createdAtMs = optLong("createdAtMs", System.currentTimeMillis()),
        lastTriggeredMs = optLong("lastTriggeredMs", 0L),
    )

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

/** Start / stop PendingIntent location updates for place-note geofences. */
object LocationNoteWatcher {
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val appCtx = context.applicationContext
        val store = LocationNoteStore(appCtx)
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val pi = locationPendingIntent(appCtx)
        runCatching { lm.removeUpdates(pi) }
        if (!store.monitoringEnabled()) return
        if (!locationPermsOk(appCtx)) return
        val active = store.list().any { it.enabled }
        if (!active) return
        val minTime = 20_000L
        val minDist = 12f
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
            if (!lm.isProviderEnabled(p)) return@forEach
            runCatching {
                lm.requestLocationUpdates(p, minTime, minDist, pi)
            }
        }
        // Immediate evaluation from last fix
        readPlaceGps(appCtx)?.let { evaluate(appCtx, it) }
    }

    fun stop(context: Context) {
        val appCtx = context.applicationContext
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(locationPendingIntent(appCtx)) }
    }

    fun evaluate(context: Context, gps: GpsFix) {
        val appCtx = context.applicationContext
        val store = LocationNoteStore(appCtx)
        if (!store.monitoringEnabled()) return
        val now = System.currentTimeMillis()
        for (note in store.list()) {
            if (!note.enabled) continue
            val dist = distanceMeters(gps.lat, gps.lon, note.lat, note.lon)
            val wasInside = store.isInside(note.id)
            val enterR = note.radiusM.toDouble()
            val exitR = note.radiusM * 1.25
            val inside = if (wasInside) dist <= exitR else dist <= enterR
            if (inside && !wasInside) {
                store.setInside(note.id, true)
                val cool = note.lastTriggeredMs > 0L && now - note.lastTriggeredMs < MIN_RETRIGGER_MS
                if (!cool) {
                    store.markTriggered(note.id, now)
                    onEnterArea(appCtx, note, dist)
                }
            } else if (!inside && wasInside) {
                store.setInside(note.id, false)
            } else if (inside != wasInside) {
                store.setInside(note.id, inside)
            }
        }
    }

    private fun onEnterArea(context: Context, note: LocationNote, distM: Double) {
        if (note.notifyOnEnter || note.hasAppAction() || note.hasImageAction()) {
            notifyPlaceEnter(context, note, distM)
        }
        // Best-effort auto-open (may be blocked when backgrounded — notification actions remain).
        if (note.hasAppAction()) {
            openAppPackage(context, note.openAppPackage)
        }
        if (note.hasImageAction() && !note.hasAppAction()) {
            openNoteImage(context, note.imagePath)
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
        if (note.hasAppAction()) {
            append("\nApp: ")
            append(note.openAppLabel.ifBlank { note.openAppPackage })
        }
        if (note.hasImageAction()) append("\nImage attached")
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

    if (note.hasAppAction()) {
        val appPi = PendingIntent.getBroadcast(
            context,
            (note.id.hashCode() + 11) and 0xFFFF,
            Intent(context, LocationNoteReceiver::class.java)
                .setAction(ACTION_OPEN_APP)
                .putExtra(EXTRA_NOTE_ID, note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(0, "Open app", appPi)
    }
    if (note.hasImageAction()) {
        val imgPi = PendingIntent.getBroadcast(
            context,
            (note.id.hashCode() + 22) and 0xFFFF,
            Intent(context, LocationNoteReceiver::class.java)
                .setAction(ACTION_OPEN_IMAGE)
                .putExtra(EXTRA_NOTE_ID, note.id),
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
            Intent.ACTION_BOOT_COMPLETED -> {
                if (store.monitoringEnabled()) LocationNoteWatcher.sync(context)
            }
            ACTION_OPEN_APP -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                val note = store.get(id) ?: return
                openAppPackage(context, note.openAppPackage)
            }
            ACTION_OPEN_IMAGE -> {
                val id = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
                val note = store.get(id) ?: return
                openNoteImage(context, note.imagePath)
            }
            ACTION_OPEN_NOTE -> {
                // reserved — main activity handles open_app
            }
        }
    }
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
            openAppPackage = draft.openAppPackage.trim(),
            openAppLabel = draft.openAppLabel.trim(),
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
            // remove previous image if replacing
            val old = draft.imagePath
            if (old.isNotBlank() && old != path) {
                runCatching { File(old).delete() }
            }
            draft = draft.copy(imagePath = path)
            status = "Image attached"
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
    val sortedNotes = remember(notes, gps) {
        if (gps == null) notes.sortedByDescending { it.createdAtMs }
        else notes.sortedBy {
            distanceMeters(gps!!.lat, gps!!.lon, it.lat, it.lon)
        }
    }
    val mapMarkers = remember(notes) {
        notes.map { n ->
            WifiMapMarker(
                id = n.id,
                ssid = n.title,
                bssid = "r ${n.radiusM.toInt()}m",
                lat = n.lat,
                lon = n.lon,
                level = if (n.enabled) -50 else -90,
                distanceM = null,
                seenCount = 1,
                live = n.enabled,
            )
        }
    }

    if (showAppPicker) {
        val apps = remember { listLaunchableApps(appCtx) }
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
            title = { Text("Open app on enter", color = GrokifyColors.TextPrimary) },
            text = {
                Column {
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
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draft = draft.copy(
                                            openAppPackage = app.packageName,
                                            openAppLabel = app.label,
                                        )
                                        showAppPicker = false
                                        appQuery = ""
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(app.label, color = GrokifyColors.TextPrimary, fontSize = 14.sp)
                                Text(
                                    app.packageName,
                                    color = GrokifyColors.TextDim,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Cancel", color = GrokifyColors.GlowCyan)
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
                    onClearImage = {
                        val old = draft.imagePath
                        if (old.isNotBlank()) runCatching { File(old).delete() }
                        draft = draft.copy(imagePath = "")
                    },
                    onPickApp = { showAppPicker = true },
                    onClearApp = {
                        draft = draft.copy(openAppPackage = "", openAppLabel = "")
                    },
                    onSave = { saveDraft() },
                    onCancel = {
                        creating = false
                        editing = null
                    },
                )
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
                        IconButton(onClick = {
                            viewMode = if (viewMode == "map") "list" else "map"
                        }) {
                            Icon(
                                if (viewMode == "map") Icons.AutoMirrored.Filled.List else Icons.Default.Map,
                                contentDescription = null,
                                tint = if (viewMode == "map") GrokifyColors.GlowMint else GrokifyColors.GlowViolet,
                            )
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
                                    "On — enter radius → notify / open app / image"
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
                                store.setMonitoringEnabled(on)
                                if (on) {
                                    LocationNoteWatcher.sync(appCtx)
                                    status = "Area monitoring on"
                                } else {
                                    LocationNoteWatcher.stop(appCtx)
                                    status = "Area monitoring off"
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
                                "Tap “Note here” to pin a note at your GPS.\nOn enter: notify, open an app, or show an image.",
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
                                        if (note.hasAppAction()) openAppPackage(appCtx, note.openAppPackage)
                                        if (note.hasImageAction()) openNoteImage(appCtx, note.imagePath)
                                        if (!note.hasAppAction() && !note.hasImageAction()) {
                                            status = note.body.ifBlank { note.title }
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

@Composable
private fun PlaceNoteEditor(
    draft: LocationNote,
    onDraft: (LocationNote) -> Unit,
    gps: GpsFix?,
    onUseGps: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onPickApp: () -> Unit,
    onClearApp: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onDraft(draft.copy(title = it)) },
            label = { Text("Title") },
            singleLine = true,
            colors = placeFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.body,
            onValueChange = { onDraft(draft.copy(body = it)) },
            label = { Text("Note") },
            minLines = 3,
            maxLines = 6,
            colors = placeFieldColors(),
            modifier = Modifier.fillMaxWidth(),
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Text("Open app on enter", color = GrokifyColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            if (draft.openAppPackage.isNotBlank()) {
                Text(
                    draft.openAppLabel.ifBlank { draft.openAppPackage },
                    color = GrokifyColors.GlowMint,
                    fontSize = 13.sp,
                )
                Text(
                    draft.openAppPackage,
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text("None selected", color = GrokifyColors.TextDim, fontSize = 12.sp)
            }
            Row {
                TextButton(onClick = onPickApp) {
                    Text("Pick app", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
                }
                if (draft.openAppPackage.isNotBlank()) {
                    TextButton(onClick = onClearApp) {
                        Text("Clear", color = GrokifyColors.GlowRose, fontSize = 13.sp)
                    }
                }
            }
            OutlinedTextField(
                value = draft.openAppPackage,
                onValueChange = { onDraft(draft.copy(openAppPackage = it, openAppLabel = draft.openAppLabel)) },
                label = { Text("Package (optional manual)") },
                singleLine = true,
                colors = placeFieldColors(),
                modifier = Modifier.fillMaxWidth(),
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
                Icon(Icons.Default.Image, null, tint = GrokifyColors.GlowMint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open image on enter", color = GrokifyColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            if (draft.imagePath.isNotBlank() && File(draft.imagePath).exists()) {
                AsyncImage(
                    model = File(draft.imagePath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(6.dp))
            } else {
                Text("No image attached", color = GrokifyColors.TextDim, fontSize = 12.sp)
            }
            Row {
                TextButton(onClick = onPickImage) {
                    Text(if (draft.imagePath.isBlank()) "Pick image" else "Replace", color = GrokifyColors.GlowMint, fontSize = 13.sp)
                }
                if (draft.imagePath.isNotBlank()) {
                    TextButton(onClick = onClearImage) {
                        Text("Clear", color = GrokifyColors.GlowRose, fontSize = 13.sp)
                    }
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
                Text("Watch this place", color = GrokifyColors.TextPrimary, fontSize = 13.sp)
                Text("Included when area monitoring is on", color = GrokifyColors.TextDim, fontSize = 11.sp)
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
        Spacer(Modifier.height(24.dp))
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
        if (note.imagePath.isNotBlank() && File(note.imagePath).exists()) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = File(note.imagePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
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
            if (note.lastTriggeredMs > 0L) {
                Text(
                    "Last enter ${formatPlaceTime(note.lastTriggeredMs)}",
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
