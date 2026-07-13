package io.grokify.os.apps

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.grokify.os.GrokifyApp
import io.grokify.os.ui.theme.GrokifyColors
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight Mapbox marker for a Wi‑Fi AP (or stored sighting). */
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
)

/**
 * Mapbox GL JS map in a WebView — public token only, no native Maps SDK / secret download token.
 * Dots = Wi‑Fi APs with GPS; color by signal; stacked pins spiderfy in a spiral.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WifiMapView(
    markers: List<WifiMapMarker>,
    userGps: GpsFix?,
    selectedId: String? = null,
    onMarkerSelected: (String) -> Unit = {},
    /** When false, map is edge-to-edge (fullscreen) without rounded chrome. */
    framed: Boolean = true,
    /** Bump when container size changes so Mapbox can remeasure. */
    resizeKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as GrokifyApp).tokenStore }
    val vaultToken by store.mapboxAccessTokenFlow.collectAsState(initial = null)
    val token = vaultToken?.trim().orEmpty()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val onSelectLatest = rememberUpdatedState(onMarkerSelected)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val hasToken = token.isNotEmpty()

    // Rebuild the WebView when the vault token changes.
    DisposableEffect(token) {
        mapReady = false
        loadError = null
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(markers, userGps, mapReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect
        pushMarkers(wv, markers, userGps, selectedId)
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
        // Let Compose finish layout, then tell Mapbox the container size changed.
        kotlinx.coroutines.delay(80)
        wv.evaluateJavascript("window.resizeWifiMap && window.resizeWifiMap();", null)
    }

    val chrome = if (framed) {
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Box(
        modifier
            .then(chrome)
            .background(GrokifyColors.Panel),
    ) {
        if (!hasToken) {
            Text(
                "Add a Mapbox token in Settings to enable maps",
                color = GrokifyColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        } else {
            key(token) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(AndroidColor.parseColor("#0B1220"))
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mediaPlaybackRequiresUserGesture = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            overScrollMode = WebView.OVER_SCROLL_NEVER
                            webChromeClient = WebChromeClient()
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onApSelected(id: String) {
                                        mainHandler.post {
                                            onSelectLatest.value(id)
                                        }
                                    }
                                },
                                "GrokifyWifiMap",
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url != null && url != "about:blank") {
                                        mapReady = true
                                        pushMarkers(view ?: return, markers, userGps, selectedId)
                                    }
                                }

                                @Deprecated("Deprecated in Java")
                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?,
                                ) {
                                    loadError = description ?: "Map failed to load"
                                }
                            }
                            loadDataWithBaseURL(
                                "https://api.mapbox.com",
                                buildMapHtml(token),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                            webView = this
                        }
                    },
                    update = { /* markers / selection via LaunchedEffect */ },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (markers.isEmpty()) {
                Text(
                    "No GPS-tagged APs yet — scan with location on",
                    color = GrokifyColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(GrokifyColors.Panel.copy(alpha = 0.92f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            loadError?.let { err ->
                Text(
                    err,
                    color = GrokifyColors.GlowRose,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                )
            }
        }
    }
}

private fun pushMarkers(
    webView: WebView,
    markers: List<WifiMapMarker>,
    userGps: GpsFix?,
    selectedId: String?,
) {
    val features = JSONArray()
    markers.forEach { m ->
        val props = JSONObject()
            .put("id", m.id)
            .put("ssid", m.ssid)
            .put("bssid", m.bssid)
            .put("live", m.live)
            .put("seen", m.seenCount)
        m.level?.let { props.put("level", it) }
        m.distanceM?.let { props.put("dist", it) }
        val color = when {
            m.level == null -> "#64748B"
            m.level >= -55 -> "#34D399"
            m.level >= -70 -> "#22D3EE"
            m.level >= -80 -> "#FBBF24"
            else -> "#FB7185"
        }
        props.put("color", color)
        val radius = when {
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

    val userJson = if (userGps != null) {
        JSONObject()
            .put("lat", userGps.lat)
            .put("lon", userGps.lon)
            .put("acc", userGps.accuracyM.toDouble())
            .toString()
    } else {
        "null"
    }
    val selJs = selectedId?.let { JSONObject.quote(it) } ?: "null"

    val js = "window.setWifiData && window.setWifiData($fc, $userJson, $selJs);"
    webView.evaluateJavascript(js, null)
}

private fun buildMapHtml(accessToken: String): String {
    val tokenEsc = accessToken.replace("\\", "\\\\").replace("'", "\\'")
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
<link href="https://api.mapbox.com/mapbox-gl-js/v3.9.0/mapbox-gl.css" rel="stylesheet"/>
<script src="https://api.mapbox.com/mapbox-gl-js/v3.9.0/mapbox-gl.js"></script>
<style>
  html, body, #map { margin:0; padding:0; width:100%; height:100%; background:#0B1220; }
  .mapboxgl-popup-content {
    background: #121A2B; color: #E8EEF7; border-radius: 10px;
    padding: 10px 12px; font: 12px/1.35 system-ui, sans-serif;
    box-shadow: 0 8px 24px rgba(0,0,0,.45); border: 1px solid #243049;
  }
  .mapboxgl-popup-tip { border-top-color: #121A2B !important; }
  .mapboxgl-ctrl-logo { opacity: .55; }
  .mapboxgl-ctrl-attrib { background: rgba(11,18,32,.75) !important; color: #94A3B8 !important; }
  .mapboxgl-ctrl-attrib a { color: #22D3EE !important; }
  .t { font-weight: 650; color: #F1F5F9; margin-bottom: 2px; }
  .m { color: #94A3B8; font-family: ui-monospace, monospace; font-size: 11px; }
  .s { color: #22D3EE; margin-top: 4px; }
</style>
</head>
<body>
<div id="map"></div>
<script>
mapboxgl.accessToken = '$tokenEsc';
const map = new mapboxgl.Map({
  container: 'map',
  style: 'mapbox://styles/mapbox/dark-v11',
  center: [-98.0, 39.5],
  zoom: 3,
  attributionControl: true,
  logoPosition: 'bottom-left'
});
map.addControl(new mapboxgl.NavigationControl({ showCompass: false }), 'top-right');

let popup = new mapboxgl.Popup({ closeButton: true, maxWidth: '240px' });
let ready = false;
let pending = null;
let selectedId = null;
/** id -> display [lon, lat] after spiderfy */
let displayById = {};
/** true GPS [lon, lat] for each id */
let originById = {};
let lastFcRaw = null;
let lastUser = null;
let suppressFit = false;

const CLUSTER_M = 14;       // meters — same cluster if closer
const SPIRAL_BASE_M = 10;   // first ring radius
const SPIRAL_STEP_M = 7;    // grow per index
const GOLDEN = 2.399963229728653; // ~137.5° in rad

function emptyFc() {
  return { type: 'FeatureCollection', features: [] };
}

function haversineM(lon1, lat1, lon2, lat2) {
  const R = 6371000;
  const toR = Math.PI / 180;
  const dLat = (lat2 - lat1) * toR;
  const dLon = (lon2 - lon1) * toR;
  const a = Math.sin(dLat/2)**2 +
    Math.cos(lat1*toR) * Math.cos(lat2*toR) * Math.sin(dLon/2)**2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
}

function offsetMeters(lon, lat, eastM, northM) {
  const dLat = northM / 111320;
  const dLon = eastM / (111320 * Math.cos(lat * Math.PI / 180) || 1e-6);
  return [lon + dLon, lat + dLat];
}

/**
 * Spiral spiderfy: group nearly-identical pins, fan them out so every AP is tappable.
 * Keeps origin coords in properties for spokes + popup "true" location.
 */
function spiderfyFeatures(features) {
  const items = (features || []).map((f, idx) => {
    const c = f.geometry && f.geometry.coordinates;
    return {
      f: f,
      idx: idx,
      lon: c ? c[0] : 0,
      lat: c ? c[1] : 0,
      id: (f.properties && f.properties.id) || String(idx),
    };
  });
  const used = new Array(items.length).fill(false);
  const outPoints = [];
  const spokes = [];
  displayById = {};
  originById = {};

  for (let i = 0; i < items.length; i++) {
    if (used[i]) continue;
    const group = [items[i]];
    used[i] = true;
    for (let j = i + 1; j < items.length; j++) {
      if (used[j]) continue;
      if (haversineM(items[i].lon, items[i].lat, items[j].lon, items[j].lat) <= CLUSTER_M) {
        group.push(items[j]);
        used[j] = true;
      }
    }

    // Cluster center = mean of members (stable when scan GPS is shared)
    let cLon = 0, cLat = 0;
    group.forEach(g => { cLon += g.lon; cLat += g.lat; });
    cLon /= group.length;
    cLat /= group.length;

    // Stronger signal first so ring index is stable-ish
    group.sort((a, b) => {
      const la = a.f.properties && a.f.properties.level != null ? a.f.properties.level : -999;
      const lb = b.f.properties && b.f.properties.level != null ? b.f.properties.level : -999;
      return lb - la;
    });

    group.forEach((g, k) => {
      const props = Object.assign({}, g.f.properties || {});
      props.originLon = g.lon;
      props.originLat = g.lat;
      props.clusterSize = group.length;
      props.clusterIndex = k;

      let dLon = g.lon, dLat = g.lat;
      if (group.length > 1) {
        // Archimedean spiral — radius grows so later pins sit further out
        const r = SPIRAL_BASE_M + k * SPIRAL_STEP_M;
        const ang = k * GOLDEN;
        const east = Math.cos(ang) * r;
        const north = Math.sin(ang) * r;
        const off = offsetMeters(cLon, cLat, east, north);
        dLon = off[0];
        dLat = off[1];
        spokes.push({
          type: 'Feature',
          geometry: {
            type: 'LineString',
            coordinates: [[cLon, cLat], [dLon, dLat]]
          },
          properties: { id: g.id, color: props.color || '#64748B' }
        });
      }

      displayById[g.id] = [dLon, dLat];
      originById[g.id] = [g.lon, g.lat];

      outPoints.push({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [dLon, dLat] },
        properties: props
      });
    });
  }

  return {
    points: { type: 'FeatureCollection', features: outPoints },
    spokes: { type: 'FeatureCollection', features: spokes }
  };
}

map.on('load', () => {
  map.addSource('aps', { type: 'geojson', data: emptyFc() });
  map.addSource('spokes', { type: 'geojson', data: emptyFc() });
  map.addSource('user', { type: 'geojson', data: emptyFc() });
  map.addSource('selected', { type: 'geojson', data: emptyFc() });

  map.addLayer({
    id: 'ap-spokes',
    type: 'line',
    source: 'spokes',
    paint: {
      'line-color': ['get', 'color'],
      'line-width': 1.5,
      'line-opacity': 0.45,
      'line-dasharray': [1.5, 1.5]
    }
  });

  map.addLayer({
    id: 'aps-halo',
    type: 'circle',
    source: 'aps',
    paint: {
      'circle-radius': ['+', ['get', 'radius'], 6],
      'circle-color': ['get', 'color'],
      'circle-opacity': 0.22,
      'circle-blur': 0.6
    }
  });
  map.addLayer({
    id: 'aps-dots',
    type: 'circle',
    source: 'aps',
    paint: {
      'circle-radius': ['get', 'radius'],
      'circle-color': ['get', 'color'],
      'circle-stroke-width': 1.5,
      'circle-stroke-color': '#0B1220',
      'circle-opacity': 0.95
    }
  });

  // Selection ring (above dots)
  map.addLayer({
    id: 'selected-ring',
    type: 'circle',
    source: 'selected',
    paint: {
      'circle-radius': 18,
      'circle-color': 'transparent',
      'circle-stroke-width': 3,
      'circle-stroke-color': '#F8FAFC',
      'circle-opacity': 1
    }
  });
  map.addLayer({
    id: 'selected-pulse',
    type: 'circle',
    source: 'selected',
    paint: {
      'circle-radius': 26,
      'circle-color': '#22D3EE',
      'circle-opacity': 0.18,
      'circle-blur': 0.4
    }
  });

  map.addLayer({
    id: 'user-acc',
    type: 'circle',
    source: 'user',
    paint: {
      'circle-radius': [
        'interpolate', ['linear'], ['zoom'],
        12, 18, 16, 40, 18, 70
      ],
      'circle-color': '#22D3EE',
      'circle-opacity': 0.12,
      'circle-stroke-width': 1,
      'circle-stroke-color': '#22D3EE',
      'circle-stroke-opacity': 0.35
    }
  });
  map.addLayer({
    id: 'user-dot',
    type: 'circle',
    source: 'user',
    paint: {
      'circle-radius': 7,
      'circle-color': '#22D3EE',
      'circle-stroke-width': 2,
      'circle-stroke-color': '#041016'
    }
  });

  function showPopupFor(id) {
    if (!id || !displayById[id]) return;
    const coords = displayById[id];
    // find feature props
    const feats = (lastFcRaw && lastFcRaw.features) || [];
    let p = {};
    for (let i = 0; i < feats.length; i++) {
      const pr = feats[i].properties || {};
      if (pr.id === id) { p = pr; break; }
    }
    // After spiderfy props live on source — try source
    const src = map.getSource('aps');
    if (src && src._data && src._data.features) {
      for (const f of src._data.features) {
        if (f.properties && f.properties.id === id) { p = f.properties; break; }
      }
    }
    const level = p.level != null ? (p.level + ' dBm') : '—';
    const dist = p.dist != null ? ('≈ ' + Number(p.dist).toFixed(0) + ' m') : '';
    const live = p.live === true || p.live === 'true' ? 'live' : 'stored';
    const stacked = p.clusterSize > 1 ? (' · spread ' + (Number(p.clusterIndex)+1) + '/' + p.clusterSize) : '';
    const html =
      '<div class="t">' + esc(p.ssid || '(hidden)') + '</div>' +
      '<div class="m">' + esc(p.bssid || '') + '</div>' +
      '<div class="s">' + esc(level) + (dist ? ' · ' + esc(dist) : '') +
      ' · seen ' + esc(String(p.seen || 1)) + '× · ' + live + stacked + '</div>';
    popup.setLngLat(coords).setHTML(html).addTo(map);
  }

  function paintSelected(id, fly) {
    selectedId = id || null;
    const us = map.getSource('selected');
    if (!us) return;
    if (!id || !displayById[id]) {
      us.setData(emptyFc());
      if (!id) popup.remove();
      return;
    }
    const coords = displayById[id];
    us.setData({
      type: 'FeatureCollection',
      features: [{
        type: 'Feature',
        geometry: { type: 'Point', coordinates: coords },
        properties: { id: id }
      }]
    });
    showPopupFor(id);
    if (fly) {
      map.easeTo({
        center: coords,
        zoom: Math.max(map.getZoom(), 16.5),
        duration: 500
      });
    }
  }

  map.on('click', 'aps-dots', (e) => {
    const f = e.features && e.features[0];
    if (!f) return;
    const id = (f.properties && f.properties.id) || '';
    if (!id) return;
    paintSelected(id, true);
    try { GrokifyWifiMap.onApSelected(String(id)); } catch (err) {}
  });
  map.on('mouseenter', 'aps-dots', () => { map.getCanvas().style.cursor = 'pointer'; });
  map.on('mouseleave', 'aps-dots', () => { map.getCanvas().style.cursor = ''; });

  ready = true;
  if (pending) {
    applyData(pending.fc, pending.user, pending.sel);
    pending = null;
  }
});

function esc(s) {
  return String(s)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;');
}

function applyData(fc, user, sel) {
  lastFcRaw = fc;
  lastUser = user;
  const spider = spiderfyFeatures((fc && fc.features) || []);
  const src = map.getSource('aps');
  if (src) src.setData(spider.points);
  const sp = map.getSource('spokes');
  if (sp) sp.setData(spider.spokes);

  const us = map.getSource('user');
  if (us) {
    if (user && typeof user.lat === 'number' && typeof user.lon === 'number') {
      us.setData({
        type: 'FeatureCollection',
        features: [{
          type: 'Feature',
          geometry: { type: 'Point', coordinates: [user.lon, user.lat] },
          properties: { acc: user.acc || 0 }
        }]
      });
    } else {
      us.setData(emptyFc());
    }
  }

  if (!suppressFit) fit(spider.points, user);
  // restore / apply selection without full re-fit when possible
  const want = sel != null ? sel : selectedId;
  if (want && displayById[want]) {
    paintSelectedLocal(want, false);
  } else if (selectedId && !displayById[selectedId]) {
    paintSelectedLocal(null, false);
  }
}

function paintSelectedLocal(id, fly) {
  selectedId = id || null;
  const us = map.getSource('selected');
  if (!us) return;
  if (!id || !displayById[id]) {
    us.setData(emptyFc());
    return;
  }
  const coords = displayById[id];
  us.setData({
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      geometry: { type: 'Point', coordinates: coords },
      properties: { id: id }
    }]
  });
  // popup
  const src = map.getSource('aps');
  let p = {};
  if (src && src._data && src._data.features) {
    for (const f of src._data.features) {
      if (f.properties && f.properties.id === id) { p = f.properties; break; }
    }
  }
  const level = p.level != null ? (p.level + ' dBm') : '—';
  const dist = p.dist != null ? ('≈ ' + Number(p.dist).toFixed(0) + ' m') : '';
  const live = p.live === true || p.live === 'true' ? 'live' : 'stored';
  const stacked = p.clusterSize > 1 ? (' · spread ' + (Number(p.clusterIndex)+1) + '/' + p.clusterSize) : '';
  const html =
    '<div class="t">' + esc(p.ssid || '(hidden)') + '</div>' +
    '<div class="m">' + esc(p.bssid || '') + '</div>' +
    '<div class="s">' + esc(level) + (dist ? ' · ' + esc(dist) : '') +
    ' · seen ' + esc(String(p.seen || 1)) + '× · ' + live + stacked + '</div>';
  popup.setLngLat(coords).setHTML(html).addTo(map);
  if (fly) {
    map.easeTo({
      center: coords,
      zoom: Math.max(map.getZoom(), 16.5),
      duration: 500
    });
  }
}

function fit(fc, user) {
  const bounds = new mapboxgl.LngLatBounds();
  let n = 0;
  (fc && fc.features || []).forEach(f => {
    const c = f.geometry && f.geometry.coordinates;
    if (c && c.length >= 2) { bounds.extend(c); n++; }
  });
  if (user && typeof user.lat === 'number') {
    bounds.extend([user.lon, user.lat]);
    n++;
  }
  if (n === 0) return;
  if (n === 1) {
    const c = bounds.getCenter();
    map.easeTo({ center: [c.lng, c.lat], zoom: 16, duration: 600 });
  } else {
    map.fitBounds(bounds, { padding: 48, maxZoom: 17, duration: 700 });
  }
}

window.setWifiData = function(fc, user, sel) {
  if (!ready) { pending = { fc: fc, user: user, sel: sel }; return; }
  applyData(fc, user, sel);
};

window.selectWifiAp = function(id) {
  if (!ready) return;
  if (!id) {
    paintSelectedLocal(null, false);
    popup.remove();
    return;
  }
  // If data not applied yet, stash
  if (!displayById[id] && lastFcRaw) {
    applyData(lastFcRaw, lastUser, id);
  }
  paintSelectedLocal(id, true);
};

window.resizeWifiMap = function() {
  try { map.resize(); } catch (e) {}
};
window.addEventListener('resize', function() {
  try { map.resize(); } catch (e) {}
});
</script>
</body>
</html>
""".trimIndent()
}
