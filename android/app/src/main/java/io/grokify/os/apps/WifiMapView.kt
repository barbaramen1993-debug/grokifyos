package io.grokify.os.apps

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.grokify.os.GrokifyApp
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.ui.theme.GrokifyColors
import java.net.URLEncoder
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight map marker for a Wi‑Fi AP / Bluetooth device / place note. */
data class WifiMapMarker(
    val id: String,
    val ssid: String,
    val bssid: String,
    val lat: Double,
    val lon: Double,
    val level: Int? = null,
    val distanceM: Double? = null,
    val seenCount: Int = 1,
    val live: Boolean = true,
    /** Optional geofence / accuracy ring in meters (place notes, etc.). */
    val radiusM: Double? = null,
)

/**
 * Raster map in a WebView (Leaflet from app assets — no CDN, no WebGL).
 *
 * Token resolution (Settings → Mapbox card **or** API vault id `mapbox_access_token`):
 * - Public `pk.…` → Mapbox dark raster tiles
 * - Missing / invalid / secret `sk.…` → free Carto dark tiles (map still works)
 *
 * Do not Compose-clip the WebView; it blanks the surface on many OEMs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WifiMapView(
    markers: List<WifiMapMarker>,
    userGps: GpsFix?,
    selectedId: String? = null,
    onMarkerSelected: (String) -> Unit = {},
    /** Empty-state chip; null hides it. */
    emptyHint: String? = "No GPS-tagged pins yet — scan with location on",
    /** Tap empty map (not a pin) — used to place a pin in editors. */
    onMapTapped: ((lat: Double, lon: Double) -> Unit)? = null,
    /** When false, skip fitBounds on each data push (smoother live radius tweaks). */
    autoFit: Boolean = true,
    /** When false, map is edge-to-edge (fullscreen) without rounded chrome. */
    framed: Boolean = true,
    /** Bump when container size changes so the map can remeasure. */
    resizeKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as GrokifyApp).tokenStore }
    val vaultToken by store.mapboxAccessTokenFlow.collectAsState(initial = null)
    val apiVault by store.apiKeyVaultFlow.collectAsState(initial = emptyMap())

    val rawToken = remember(vaultToken, apiVault) {
        vaultToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: apiVault[ApiKeyIds.MAPBOX]?.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""
    }
    val tokenInfo = remember(rawToken) { normalizeMapboxToken(rawToken) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var basemapLabel by remember { mutableStateOf("Loading map…") }
    var loadError by remember { mutableStateOf<String?>(null) }
    val onSelectLatest = rememberUpdatedState(onMarkerSelected)
    val onMapTappedLatest = rememberUpdatedState(onMapTapped)
    val markersLatest = rememberUpdatedState(markers)
    val userGpsLatest = rememberUpdatedState(userGps)
    val selectedIdLatest = rememberUpdatedState(selectedId)
    val tokenInfoLatest = rememberUpdatedState(tokenInfo)
    val autoFitLatest = rememberUpdatedState(autoFit)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                loadUrl("about:blank")
                removeJavascriptInterface("GrokifyWifiMap")
                destroy()
            }
            webView = null
            mapReady = false
        }
    }

    // Push basemap when token changes (no WebView recreate — avoids blank flashes).
    LaunchedEffect(tokenInfo, mapReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        applyBasemap(wv, tokenInfo)
    }

    LaunchedEffect(markers, userGps, mapReady, autoFit) {
        val wv = webView ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        pushMarkers(wv, markers, userGps, selectedId, autoFit)
    }

    LaunchedEffect(selectedId, mapReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        val idJs = selectedId?.let { JSONObject.quote(it) } ?: "null"
        wv.evaluateJavascript("window.selectWifiAp && window.selectWifiAp($idJs);", null)
    }

    LaunchedEffect(resizeKey, mapReady, framed) {
        val wv = webView ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        repeat(5) { i ->
            delay(if (i == 0) 40L else 100L)
            wv.evaluateJavascript("window.resizeWifiMap && window.resizeWifiMap();", null)
        }
    }

    val chrome = if (framed) {
        Modifier.border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Box(
        modifier
            .then(chrome)
            .background(
                GrokifyColors.Panel,
                if (framed) RoundedCornerShape(12.dp) else RoundedCornerShape(0.dp),
            ),
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.parseColor("#0B1220"))
                    // Default layer type — HARDWARE blanks WebView surfaces on some OEMs.
                    setLayerType(View.LAYER_TYPE_NONE, null)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.loadsImagesAutomatically = true
                    settings.blockNetworkImage = false
                    settings.blockNetworkLoads = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.userAgentString = settings.userAgentString + " GrokifyOSMap/3"
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message().orEmpty()
                            if (msg.contains("error", ignoreCase = true) ||
                                msg.contains("failed", ignoreCase = true) ||
                                msg.contains("unauthorized", ignoreCase = true) ||
                                msg.contains("401")
                            ) {
                                android.util.Log.w("WifiMapView", "JS: $msg")
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onApSelected(id: String) {
                                mainHandler.post { onSelectLatest.value(id) }
                            }

                            @JavascriptInterface
                            fun onMapTapped(lat: Double, lon: Double) {
                                mainHandler.post {
                                    onMapTappedLatest.value?.invoke(lat, lon)
                                }
                            }

                            @JavascriptInterface
                            fun onMapReady() {
                                mainHandler.post {
                                    mapReady = true
                                    loadError = null
                                    val wv = webView ?: return@post
                                    applyBasemap(wv, tokenInfoLatest.value)
                                    pushMarkers(
                                        wv,
                                        markersLatest.value,
                                        userGpsLatest.value,
                                        selectedIdLatest.value,
                                        autoFitLatest.value,
                                    )
                                    wv.evaluateJavascript(
                                        "window.resizeWifiMap && window.resizeWifiMap();",
                                        null,
                                    )
                                }
                            }

                            @JavascriptInterface
                            fun onBasemap(label: String?) {
                                mainHandler.post {
                                    basemapLabel = label?.take(80) ?: "Map ready"
                                }
                            }

                            @JavascriptInterface
                            fun onMapError(message: String?) {
                                mainHandler.post {
                                    loadError = message?.take(160) ?: "Map failed to load"
                                    basemapLabel = "Map error"
                                }
                            }
                        },
                        "GrokifyWifiMap",
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.post {
                                view.evaluateJavascript(
                                    "window.resizeWifiMap && window.resizeWifiMap();",
                                    null,
                                )
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                mainHandler.post {
                                    loadError = error?.description?.toString()
                                        ?: "Map failed to load"
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?,
                        ) {
                            mainHandler.post {
                                loadError = description ?: "Map failed to load"
                            }
                        }
                    }

                    fun tryLoad() {
                        if (width <= 0 || height <= 0) return
                        if (tag == "loaded") return
                        tag = "loaded"
                        // Bundle Leaflet in assets so maps work without CDN access.
                        loadDataWithBaseURL(
                            "file:///android_asset/map/",
                            buildMapHtml(),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }

                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        tryLoad()
                        if (tag == "loaded") {
                            evaluateJavascript(
                                "window.resizeWifiMap && window.resizeWifiMap();",
                                null,
                            )
                        }
                    }
                    post { tryLoad() }
                    postDelayed({ tryLoad() }, 200)
                    postDelayed({ tryLoad() }, 600)
                    postDelayed({ tryLoad() }, 1500)
                    // Last resort: load even at 0 size and let JS ResizeObserver fix it.
                    postDelayed({
                        if (tag != "loaded") {
                            tag = "loaded"
                            loadDataWithBaseURL(
                                "file:///android_asset/map/",
                                buildMapHtml(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    }, 2500)

                    webView = this
                }
            },
            update = { view ->
                webView = view
                // Ensure the WebView actually fills the Compose slot.
                if (view.layoutParams == null ||
                    view.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                    view.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT
                ) {
                    view.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                if (mapReady) {
                    pushMarkers(view, markers, userGps, selectedId, autoFit)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Status chip (outside WebView so blank surfaces still show diagnostics).
        Text(
            buildString {
                append(basemapLabel)
                if (tokenInfo.kind == MapboxTokenKind.PUBLIC) append(" · token ok")
                when (tokenInfo.kind) {
                    MapboxTokenKind.SECRET -> append(" · use pk. not sk.")
                    MapboxTokenKind.EMPTY -> append(" · free basemap")
                    MapboxTokenKind.INVALID -> append(" · token invalid")
                    MapboxTokenKind.PUBLIC -> Unit
                }
            },
            color = when {
                loadError != null -> GrokifyColors.GlowRose
                tokenInfo.kind == MapboxTokenKind.SECRET ||
                    tokenInfo.kind == MapboxTokenKind.INVALID -> GrokifyColors.GlowAmber
                else -> GrokifyColors.TextMuted
            },
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(
                    GrokifyColors.Panel.copy(alpha = 0.92f),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )

        if (markers.isEmpty() && !emptyHint.isNullOrBlank()) {
            Text(
                emptyHint,
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(10.dp)
                    .background(
                        GrokifyColors.Panel.copy(alpha = 0.92f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        loadError?.let { err ->
            Text(
                err,
                color = GrokifyColors.GlowRose,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(
                        GrokifyColors.Panel.copy(alpha = 0.94f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private enum class MapboxTokenKind { EMPTY, PUBLIC, SECRET, INVALID }

private data class MapboxTokenInfo(
    val kind: MapboxTokenKind,
    /** Cleaned token for Mapbox requests, or empty. */
    val token: String,
)

/**
 * Normalize what users paste into Settings.
 * Accepts only public `pk.` tokens for client-side Mapbox tiles.
 */
private fun normalizeMapboxToken(raw: String): MapboxTokenInfo {
    var t = raw.trim()
    if (t.isEmpty()) return MapboxTokenInfo(MapboxTokenKind.EMPTY, "")
    // Strip common paste junk.
    if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\''))) {
        t = t.substring(1, t.length - 1).trim()
    }
    if (t.startsWith("Bearer ", ignoreCase = true)) t = t.substring(7).trim()
    if (t.startsWith("access_token=", ignoreCase = true)) t = t.substring(13).trim()
    t = t.replace(Regex("\\s+"), "")
    if (t.isEmpty()) return MapboxTokenInfo(MapboxTokenKind.EMPTY, "")
    return when {
        t.startsWith("pk.") && t.length > 20 -> MapboxTokenInfo(MapboxTokenKind.PUBLIC, t)
        t.startsWith("sk.") -> MapboxTokenInfo(MapboxTokenKind.SECRET, "")
        t.startsWith("pk.") -> MapboxTokenInfo(MapboxTokenKind.INVALID, "")
        else -> MapboxTokenInfo(MapboxTokenKind.INVALID, "")
    }
}

private fun applyBasemap(webView: WebView, info: MapboxTokenInfo) {
    val encoded = if (info.token.isNotEmpty()) {
        URLEncoder.encode(info.token, Charsets.UTF_8.name())
            // URLEncoder turns pk.xxx into ok form; keep dots unescaped for Mapbox.
            .replace("%2E", ".")
            .replace("+", "%20")
    } else {
        ""
    }
    val kind = when (info.kind) {
        MapboxTokenKind.PUBLIC -> "mapbox"
        else -> "carto"
    }
    val tokenJs = JSONObject.quote(encoded)
    val kindJs = JSONObject.quote(kind)
    webView.evaluateJavascript(
        "window.setBasemap && window.setBasemap($kindJs, $tokenJs);",
        null,
    )
}

private fun pushMarkers(
    webView: WebView,
    markers: List<WifiMapMarker>,
    userGps: GpsFix?,
    selectedId: String?,
    autoFit: Boolean = true,
) {
    val features = JSONArray()
    markers.forEach { m ->
        if (!m.lat.isFinite() || !m.lon.isFinite()) return@forEach
        val props = JSONObject()
            .put("id", m.id)
            .put("ssid", m.ssid)
            .put("bssid", m.bssid)
            .put("live", m.live)
            .put("seen", m.seenCount)
        m.level?.let { props.put("level", it) }
        m.distanceM?.let { props.put("dist", it) }
        m.radiusM?.takeIf { it.isFinite() && it > 0 }?.let { props.put("radiusM", it) }
        val color = when {
            m.radiusM != null && m.live -> "#A78BFA"
            m.radiusM != null -> "#64748B"
            m.level == null -> "#64748B"
            m.level >= -55 -> "#34D399"
            m.level >= -70 -> "#22D3EE"
            m.level >= -80 -> "#FBBF24"
            else -> "#FB7185"
        }
        props.put("color", color)
        val radius = when {
            m.radiusM != null -> 10
            m.level == null -> 7
            m.level >= -55 -> 11
            m.level >= -70 -> 9
            else -> 7
        }
        props.put("radius", radius)

        features.put(
            JSONObject()
                .put("type", "Feature")
                .put(
                    "geometry",
                    JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(m.lon).put(m.lat)),
                )
                .put("properties", props),
        )
    }
    val fc = JSONObject()
        .put("type", "FeatureCollection")
        .put("features", features)

    val userJson = if (userGps != null && userGps.lat.isFinite() && userGps.lon.isFinite()) {
        JSONObject()
            .put("lat", userGps.lat)
            .put("lon", userGps.lon)
            .put("acc", userGps.accuracyM.toDouble())
            .toString()
    } else {
        "null"
    }
    val selJs = selectedId?.let { JSONObject.quote(it) } ?: "null"
    val fitJs = if (autoFit) "true" else "false"

    val js = "window.setWifiData && window.setWifiData($fc, $userJson, $selJs, $fitJs);"
    webView.evaluateJavascript(js, null)
}

/**
 * Static HTML shell. Leaflet is loaded from [file:///android_asset/map/].
 * Basemap URL is set at runtime via [window.setBasemap] so token updates don't reload the page.
 */
private fun buildMapHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
<link rel="stylesheet" href="leaflet.css"/>
<style>
  html, body, #map { margin:0; padding:0; width:100%; height:100%; background:#0B1220; }
  .leaflet-container { background:#0B1220; font: 12px/1.35 system-ui, sans-serif; }
  .leaflet-control-zoom a {
    background:#121A2B !important; color:#E8EEF7 !important;
    border-color:#243049 !important; width:30px !important; height:30px !important;
    line-height:30px !important;
  }
  .leaflet-control-attribution {
    background: rgba(11,18,32,.8) !important; color:#94A3B8 !important;
    max-width: 70%;
  }
  .leaflet-control-attribution a { color:#22D3EE !important; }
  .popup-card { background: transparent; color: #E8EEF7; margin:0; min-width: 140px; }
  .leaflet-popup-content-wrapper {
    background: #121A2B; color: #E8EEF7; border-radius: 10px;
    border: 1px solid #243049; box-shadow: 0 8px 24px rgba(0,0,0,.45);
  }
  .leaflet-popup-tip { background: #121A2B; }
  .leaflet-popup-content { margin: 10px 12px; }
  .t { font-weight: 650; color: #F1F5F9; margin-bottom: 2px; }
  .m { color: #94A3B8; font-family: ui-monospace, monospace; font-size: 11px; }
  .s { color: #22D3EE; margin-top: 4px; }
  #boot {
    position:absolute; inset:0; display:flex; align-items:center; justify-content:center;
    color:#94A3B8; font: 13px/1.4 system-ui, sans-serif; pointer-events:none; z-index:1000;
    background:#0B1220;
  }
  .pin {
    border-radius: 50%;
    border: 2px solid #0B1220;
    box-shadow: 0 0 0 3px rgba(0,0,0,.25), 0 2px 8px rgba(0,0,0,.35);
  }
  .pin-halo {
    border-radius: 50%;
    opacity: 0.28;
    position: absolute;
    left: 50%; top: 50%;
    transform: translate(-50%, -50%);
  }
  .pin-wrap {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .pin-sel {
    box-shadow: 0 0 0 3px #22D3EE, 0 2px 10px rgba(0,0,0,.4) !important;
  }
  .user-dot {
    width: 14px; height: 14px; border-radius: 50%;
    background: #22D3EE; border: 2px solid #041016;
    box-shadow: 0 0 0 6px rgba(34,211,238,.18);
  }
</style>
</head>
<body>
<div id="map"></div>
<div id="boot">Loading map…</div>
<script src="leaflet.js"></script>
<script>
(function() {
function reportError(msg) {
  try { GrokifyWifiMap.onMapError(String(msg || 'Map error')); } catch (e) {}
  var b = document.getElementById('boot');
  if (b) { b.textContent = String(msg || 'Map failed'); b.style.color = '#FB7185'; b.style.display = 'flex'; }
}
function hideBoot() {
  var b = document.getElementById('boot');
  if (b) b.style.display = 'none';
}
function reportBasemap(label) {
  try { GrokifyWifiMap.onBasemap(String(label || '')); } catch (e) {}
}

if (typeof L === 'undefined') {
  reportError('Map library missing from app assets');
  return;
}

var ready = false;
var pending = null;
var selectedId = null;
var displayById = {};
var originById = {};
var lastFcRaw = null;
var lastUser = null;
var map = null;
var basemapLayer = null;
var markersLayer = null;
var spokesLayer = null;
var circlesLayer = null;
var userLayer = null;
var markerById = {};
var tileErrors = 0;
var lastFitKey = '';

var CLUSTER_M = 14;
var SPIRAL_BASE_M = 10;
var SPIRAL_STEP_M = 7;
var GOLDEN = 2.399963229728653;

function haversineM(lon1, lat1, lon2, lat2) {
  var R = 6371000;
  var toR = Math.PI / 180;
  var dLat = (lat2 - lat1) * toR;
  var dLon = (lon2 - lon1) * toR;
  var a = Math.sin(dLat/2)*Math.sin(dLat/2) +
    Math.cos(lat1*toR) * Math.cos(lat2*toR) * Math.sin(dLon/2)*Math.sin(dLon/2);
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

function offsetMeters(lon, lat, eastM, northM) {
  var dLat = northM / 111320;
  var dLon = eastM / (111320 * Math.cos(lat * Math.PI / 180) || 1e-6);
  return [lon + dLon, lat + dLat];
}

function spiderfyFeatures(features) {
  var items = (features || []).map(function(f, idx) {
    var c = f.geometry && f.geometry.coordinates;
    return {
      f: f, idx: idx,
      lon: c ? c[0] : 0, lat: c ? c[1] : 0,
      id: (f.properties && f.properties.id) || String(idx)
    };
  });
  var used = new Array(items.length).fill(false);
  var outPoints = [];
  var spokes = [];
  displayById = {};
  originById = {};

  for (var i = 0; i < items.length; i++) {
    if (used[i]) continue;
    var group = [items[i]];
    used[i] = true;
    for (var j = i + 1; j < items.length; j++) {
      if (used[j]) continue;
      if (haversineM(items[i].lon, items[i].lat, items[j].lon, items[j].lat) <= CLUSTER_M) {
        group.push(items[j]);
        used[j] = true;
      }
    }
    var cLon = 0, cLat = 0;
    group.forEach(function(g) { cLon += g.lon; cLat += g.lat; });
    cLon /= group.length;
    cLat /= group.length;
    group.sort(function(a, b) {
      var la = a.f.properties && a.f.properties.level != null ? a.f.properties.level : -999;
      var lb = b.f.properties && b.f.properties.level != null ? b.f.properties.level : -999;
      return lb - la;
    });
    group.forEach(function(g, k) {
      var props = Object.assign({}, g.f.properties || {});
      props.originLon = g.lon;
      props.originLat = g.lat;
      props.clusterSize = group.length;
      props.clusterIndex = k;
      var dLon = g.lon, dLat = g.lat;
      if (group.length > 1) {
        var r = SPIRAL_BASE_M + k * SPIRAL_STEP_M;
        var ang = k * GOLDEN;
        var off = offsetMeters(cLon, cLat, Math.cos(ang) * r, Math.sin(ang) * r);
        dLon = off[0];
        dLat = off[1];
        spokes.push({ from: [cLat, cLon], to: [dLat, dLon], color: props.color || '#64748B' });
      }
      displayById[g.id] = [dLon, dLat];
      originById[g.id] = [g.lon, g.lat];
      outPoints.push({ id: g.id, lat: dLat, lon: dLon, props: props });
    });
  }
  return { points: outPoints, spokes: spokes };
}

function esc(s) {
  return String(s)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;');
}

function popupHtml(p) {
  // Place-note style when a geofence radius is present.
  if (p.radiusM != null && p.radiusM !== '') {
    var r = Number(p.radiusM);
    var distP = p.dist != null ? (' · ≈ ' + Number(p.dist).toFixed(0) + ' m away') : '';
    var onOff = (p.live === true || p.live === 'true') ? 'watching' : 'paused';
    return '<div class="popup-card">' +
      '<div class="t">' + esc(p.ssid || 'Place') + '</div>' +
      '<div class="m">' + esc(p.bssid || '') + '</div>' +
      '<div class="s">radius ' + esc(String(Math.round(r))) + ' m · ' + onOff + distP + '</div></div>';
  }
  var level = p.level != null ? (p.level + ' dBm') : '—';
  var dist = p.dist != null ? ('≈ ' + Number(p.dist).toFixed(0) + ' m') : '';
  var live = (p.live === true || p.live === 'true') ? 'live' : 'stored';
  var stacked = p.clusterSize > 1
    ? (' · spread ' + (Number(p.clusterIndex)+1) + '/' + p.clusterSize) : '';
  return '<div class="popup-card">' +
    '<div class="t">' + esc(p.ssid || '(hidden)') + '</div>' +
    '<div class="m">' + esc(p.bssid || '') + '</div>' +
    '<div class="s">' + esc(level) + (dist ? ' · ' + esc(dist) : '') +
    ' · seen ' + esc(String(p.seen || 1)) + '× · ' + live + stacked + '</div></div>';
}

function pinIcon(color, radius, selected) {
  var r = Math.max(6, Number(radius) || 8);
  var outer = r * 2 + (selected ? 10 : 6);
  var halo = r * 2 + 8;
  var cls = selected ? 'pin pin-sel' : 'pin';
  var html =
    '<div class="pin-wrap" style="width:' + outer + 'px;height:' + outer + 'px">' +
    '<div class="pin-halo" style="width:' + halo + 'px;height:' + halo + 'px;background:' + color + '"></div>' +
    '<div class="' + cls + '" style="width:' + (r*2) + 'px;height:' + (r*2) + 'px;background:' + color + '"></div>' +
    '</div>';
  return L.divIcon({
    className: '',
    html: html,
    iconSize: [outer, outer],
    iconAnchor: [outer/2, outer/2],
    popupAnchor: [0, -r]
  });
}

function fitAll(points, user) {
  var bounds = [];
  (points || []).forEach(function(p) { bounds.push([p.lat, p.lon]); });
  if (user && typeof user.lat === 'number') bounds.push([user.lat, user.lon]);
  if (bounds.length === 0) return;
  if (bounds.length === 1) {
    map.setView(bounds[0], 16, { animate: true });
  } else {
    map.fitBounds(bounds, { padding: [48, 48], maxZoom: 17, animate: true });
  }
}

function paintSelected(id, fly) {
  selectedId = id || null;
  Object.keys(markerById).forEach(function(mid) {
    var entry = markerById[mid];
    if (!entry) return;
    entry.marker.setIcon(pinIcon(entry.color, entry.radius, mid === selectedId));
  });
  if (!id || !displayById[id]) return;
  var coords = displayById[id];
  var entry = markerById[id];
  if (entry) entry.marker.openPopup();
  if (fly) {
    map.setView([coords[1], coords[0]], Math.max(map.getZoom(), 16.5), { animate: true });
  }
}

function applyData(fc, user, sel, doFit) {
  lastFcRaw = fc;
  lastUser = user;
  var spider = spiderfyFeatures((fc && fc.features) || []);

  markersLayer.clearLayers();
  spokesLayer.clearLayers();
  if (circlesLayer) circlesLayer.clearLayers();
  markerById = {};

  spider.spokes.forEach(function(s) {
    L.polyline([s.from, s.to], {
      color: s.color, weight: 1.5, opacity: 0.45, dashArray: '4 4'
    }).addTo(spokesLayer);
  });

  // Geofence rings at true origin (not spiderfied display points).
  spider.points.forEach(function(p) {
    var rm = p.props.radiusM != null ? Number(p.props.radiusM) : NaN;
    if (!(rm > 0) || !circlesLayer) return;
    var o = originById[p.id] || [p.lon, p.lat];
    var color = p.props.color || '#A78BFA';
    L.circle([o[1], o[0]], {
      radius: rm,
      color: color,
      weight: 1.5,
      opacity: 0.55,
      fillColor: color,
      fillOpacity: 0.12,
      interactive: false
    }).addTo(circlesLayer);
  });

  spider.points.forEach(function(p) {
    var color = p.props.color || '#64748B';
    var radius = p.props.radius || 8;
    var m = L.marker([p.lat, p.lon], {
      icon: pinIcon(color, radius, p.id === selectedId),
      keyboard: false
    });
    m.bindPopup(popupHtml(p.props), { maxWidth: 240, closeButton: true });
    m.on('click', function() {
      paintSelected(p.id, true);
      try { GrokifyWifiMap.onApSelected(String(p.id)); } catch (err) {}
    });
    m.addTo(markersLayer);
    markerById[p.id] = { marker: m, color: color, radius: radius };
  });

  userLayer.clearLayers();
  if (user && typeof user.lat === 'number' && typeof user.lon === 'number') {
    var acc = Number(user.acc) || 0;
    if (acc > 0) {
      L.circle([user.lat, user.lon], {
        radius: acc, color: '#22D3EE', weight: 1, opacity: 0.35,
        fillColor: '#22D3EE', fillOpacity: 0.12
      }).addTo(userLayer);
    }
    L.marker([user.lat, user.lon], {
      icon: L.divIcon({
        className: '',
        html: '<div class="user-dot"></div>',
        iconSize: [14, 14],
        iconAnchor: [7, 7]
      }),
      interactive: false,
      keyboard: false
    }).addTo(userLayer);
  }

  // Fit only when pin set changes (or forced), so radius tweaks don't re-zoom.
  var fitKey = spider.points.map(function(p) {
    return p.id + ':' + p.lon.toFixed(5) + ',' + p.lat.toFixed(5);
  }).join('|') + '|' + (user && user.lat != null ? (user.lat.toFixed(4)+','+user.lon.toFixed(4)) : '');
  var shouldFit = doFit !== false && (fitKey !== lastFitKey || !lastFitKey);
  if (shouldFit) {
    lastFitKey = fitKey;
    fitAll(spider.points, user);
  }

  var want = sel != null ? sel : selectedId;
  if (want && displayById[want]) paintSelected(want, false);
  else if (selectedId && !displayById[selectedId]) selectedId = null;
}

function makeCartoLayer() {
  return L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; OSM &copy; CARTO',
    maxZoom: 20,
    subdomains: 'abcd',
    crossOrigin: true
  });
}

function makeOsmLayer() {
  return L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap',
    maxZoom: 19,
    crossOrigin: true
  });
}

function makeMapboxLayer(token) {
  // Official style-as-raster URL for Leaflet (512px tiles, zoomOffset -1).
  var url = 'https://api.mapbox.com/styles/v1/mapbox/dark-v11/tiles/{z}/{x}/{y}?access_token=' + token;
  return L.tileLayer(url, {
    attribution: '&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a> &copy; OSM',
    maxZoom: 22,
    tileSize: 512,
    zoomOffset: -1,
    crossOrigin: true
  });
}

function wireTileFallback(layer, label, onFail) {
  tileErrors = 0;
  layer.on('tileerror', function() {
    tileErrors++;
    if (tileErrors >= 4) {
      layer.off('tileerror');
      if (onFail) onFail();
    }
  });
  layer.on('load', function() {
    reportBasemap(label);
  });
}

function setBasemap(kind, token) {
  if (!map) return;
  if (basemapLayer) {
    try { map.removeLayer(basemapLayer); } catch (e) {}
    basemapLayer = null;
  }
  tileErrors = 0;

  function useCarto(reason) {
    basemapLayer = makeCartoLayer();
    wireTileFallback(basemapLayer, reason ? ('Carto · ' + reason) : 'Carto dark', function() {
      try { map.removeLayer(basemapLayer); } catch (e) {}
      basemapLayer = makeOsmLayer();
      basemapLayer.addTo(map);
      basemapLayer.bringToBack();
      reportBasemap('OSM (fallback)');
    });
    basemapLayer.addTo(map);
    basemapLayer.bringToBack();
    reportBasemap(reason ? ('Carto · ' + reason) : 'Carto dark');
  }

  if (kind === 'mapbox' && token) {
    basemapLayer = makeMapboxLayer(token);
    wireTileFallback(basemapLayer, 'Mapbox dark', function() {
      useCarto('Mapbox tiles failed');
    });
    basemapLayer.addTo(map);
    basemapLayer.bringToBack();
    reportBasemap('Mapbox dark');
  } else {
    useCarto(kind === 'mapbox' ? 'no token' : null);
  }
  try { map.invalidateSize(false); } catch (e) {}
}

function initWhenSized() {
  var el = document.getElementById('map');
  if (!el) { reportError('Map container missing'); return; }

  function start() {
    if (ready) return true;
    // Allow init even if size is still settling — invalidateSize later.
    try {
      map = L.map('map', {
        zoomControl: false,
        attributionControl: true,
        preferCanvas: true
      }).setView([39.5, -98.0], 3);
      L.control.zoom({ position: 'topright' }).addTo(map);

      // Default free basemap immediately so the box is never empty.
      setBasemap('carto', '');

      circlesLayer = L.layerGroup().addTo(map);
      spokesLayer = L.layerGroup().addTo(map);
      markersLayer = L.layerGroup().addTo(map);
      userLayer = L.layerGroup().addTo(map);

      map.on('click', function(e) {
        if (!e || !e.latlng) return;
        try {
          GrokifyWifiMap.onMapTapped(e.latlng.lat, e.latlng.lng);
        } catch (err) {}
      });

      ready = true;
      hideBoot();
      try { map.invalidateSize(false); } catch (e) {}
      try { GrokifyWifiMap.onMapReady(); } catch (err) {}
      if (pending) {
        applyData(pending.fc, pending.user, pending.sel, pending.fit);
        pending = null;
      }
      // Keep invalidating until the view has real size.
      var n = 0;
      var t = setInterval(function() {
        n++;
        try { map.invalidateSize(false); } catch (e) {}
        if (n > 40) clearInterval(t);
      }, 150);
      return true;
    } catch (err) {
      reportError(err && err.message ? err.message : err);
      return true;
    }
  }

  if (start()) return;
  var n = 0;
  var t = setInterval(function() {
    n++;
    if (start() || n > 40) clearInterval(t);
  }, 100);
  if (typeof ResizeObserver !== 'undefined') {
    var ro = new ResizeObserver(function() {
      if (!ready) start();
      else {
        try { map.invalidateSize(false); } catch (e) {}
      }
    });
    ro.observe(el);
  }
}

setTimeout(function() {
  if (!ready) reportError('Map timed out');
}, 15000);

window.setBasemap = setBasemap;

window.setWifiData = function(fc, user, sel, doFit) {
  if (!ready) { pending = { fc: fc, user: user, sel: sel, fit: doFit }; return; }
  applyData(fc, user, sel, doFit);
};

window.selectWifiAp = function(id) {
  if (!ready) return;
  if (!id) {
    selectedId = null;
    Object.keys(markerById).forEach(function(mid) {
      var entry = markerById[mid];
      if (entry) entry.marker.setIcon(pinIcon(entry.color, entry.radius, false));
    });
    map.closePopup();
    return;
  }
  if (!displayById[id] && lastFcRaw) applyData(lastFcRaw, lastUser, id);
  paintSelected(id, true);
};

window.resizeWifiMap = function() {
  try {
    if (map) {
      map.invalidateSize(false);
      setTimeout(function() {
        try { map.invalidateSize(false); } catch (e2) {}
      }, 80);
    }
  } catch (e) {}
};
window.addEventListener('resize', function() {
  try { if (map) map.invalidateSize(false); } catch (e) {}
});

initWhenSized();
})();
</script>
</body>
</html>
""".trimIndent()
