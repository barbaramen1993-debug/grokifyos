package io.grokify.os.apps

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.grokify.os.permission.AppPermissionId
import io.grokify.os.permission.PermissionHelper
import io.grokify.os.ui.theme.GrokifyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WifiAp(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val channel: Int,
    val capabilities: String,
    val seenAtMs: Long = System.currentTimeMillis(),
)

data class WifiScanSnapshot(
    val id: Long,
    val atMs: Long,
    val networks: List<WifiAp>,
)

/** Convert MHz → 2.4/5/6 GHz channel (best-effort). */
fun wifiChannel(freqMhz: Int): Int = when {
    freqMhz in 2412..2484 -> (freqMhz - 2407) / 5
    freqMhz in 5170..5825 -> (freqMhz - 5000) / 5
    freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
    else -> 0
}

@SuppressLint("MissingPermission")
fun readWifiResults(wm: WifiManager): List<WifiAp> {
    val now = System.currentTimeMillis()
    val boot = SystemClock.elapsedRealtime()
    return try {
        wm.scanResults
            .orEmpty()
            .map { r ->
                val ssid = r.ssidCompat().ifBlank { "(hidden)" }
                val ageMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // timestamp is microseconds since boot
                    val ageBoot = boot - (r.timestamp / 1000L)
                    now - ageBoot.coerceAtLeast(0L)
                } else {
                    now
                }
                WifiAp(
                    ssid = ssid,
                    bssid = r.BSSID ?: "??",
                    level = r.level,
                    frequency = r.frequency,
                    channel = wifiChannel(r.frequency),
                    capabilities = r.capabilities ?: "",
                    seenAtMs = ageMs,
                )
            }
            .sortedWith(compareByDescending<WifiAp> { it.level }.thenBy { it.ssid.lowercase() })
    } catch (_: SecurityException) {
        emptyList()
    }
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
    // Android 13+: NEARBY_WIFI_DEVICES with neverForLocation can scan without location,
    // but many OEMs still want fine location for full results — accept either.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        nearby || loc
    } else {
        loc
    }
}

@Composable
fun WifiScannerPane(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val wm = remember {
        appCtx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Tap Scan to discover nearby networks") }
    var results by remember { mutableStateOf<List<WifiAp>>(emptyList()) }
    var history by remember { mutableStateOf<List<WifiScanSnapshot>>(emptyList()) }
    var showHistory by remember { mutableStateOf(false) }
    var lastScanAt by remember { mutableStateOf(0L) }

    fun applyResults(list: List<WifiAp>, fromScan: Boolean) {
        results = list
        if (fromScan) {
            val snap = WifiScanSnapshot(
                id = System.currentTimeMillis(),
                atMs = System.currentTimeMillis(),
                networks = list,
            )
            history = (listOf(snap) + history).take(20)
            lastScanAt = snap.atMs
            status = if (list.isEmpty()) {
                "Scan finished — no networks (check location/Wi‑Fi permissions)"
            } else {
                "Found ${list.size} network${if (list.size == 1) "" else "s"}"
            }
        }
        scanning = false
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
                val list = readWifiResults(wm)
                if (ok || list.isNotEmpty()) {
                    applyResults(list, fromScan = true)
                } else {
                    scanning = false
                    status = "Scan throttled or failed — try again in a few seconds"
                    // Still show last known cache
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
        // Seed with last cached results if any
        if (wifiPermsOk(appCtx)) {
            val cached = readWifiResults(wm)
            if (cached.isNotEmpty()) {
                results = cached
                status = "Cached ${cached.size} network(s) — tap Scan to refresh"
            }
        }
        onDispose {
            try {
                appCtx.unregisterReceiver(receiver)
            } catch (_: Exception) {
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
        scanning = true
        status = "Scanning…"
        @Suppress("DEPRECATION")
        val started = try {
            wm.startScan()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            // Still try cached results; OEM may throttle startScan
            val cached = readWifiResults(wm)
            if (cached.isNotEmpty()) {
                applyResults(cached, fromScan = true)
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
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

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
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
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(
                    if (showHistory) "Live" else "History (${history.size})",
                    color = GrokifyColors.GlowMint,
                    fontSize = 13.sp,
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
                            "Allow Nearby Wi‑Fi and/or Location to scan networks."
                        } else {
                            "Allow Location to scan Wi‑Fi networks."
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
                            status = "Showing scan from ${formatTime(snap.atMs)}"
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
                    items(results, key = { it.bssid + it.ssid }) { ap ->
                        WifiApRow(ap)
                    }
                }
            }
        }
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
                    "${snap.networks.size} network${if (snap.networks.size == 1) "" else "s"}",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onOpen) {
                Text("View", color = GrokifyColors.GlowCyan)
            }
        }
    }
}

@Composable
private fun WifiApRow(ap: WifiAp) {
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
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
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
