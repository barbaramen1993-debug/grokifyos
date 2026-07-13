package io.grokify.os.apps.plugin

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.grokify.os.BuildConfig
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.ApiKeyPresets
import io.grokify.os.ui.theme.GrokifyColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sandboxed WebView host for remote marketplace scripts.
 *
 * JS bridge [GrokifyHost]:
 * - getInfo()
 * - toast(msg)
 * - getRequiredKeys() / getApiKeyStatus() / getApiKey(id) / saveApiKey(id, value) / hasApiKey(id)
 * - http(method, url, headersJson, body)
 * - aiComplete(prompt, optionsJson) — Grok Build via host bridge (same as Chat)
 * - speak(text, optionsJson?) — xAI Voice TTS when key present; else device TTS (wait:true blocks until done)
 * - spotifyAuthStatus() / spotifyLogin() / spotifyLogout() / spotifyApi(method, path, body)
 * - spotifyOpenUri(uri) — open spotify:… / open.spotify.com link in the Spotify app
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScriptPluginPane(
    plugin: PluginManifest,
    entryHtml: File?,
    connected: Boolean,
    tokenSaved: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var gateTick by remember { mutableStateOf(0) }

    val required = plugin.requiredKeys.filter { it.required }
    val missing = remember(plugin.id, gateTick) {
        HostApiKeyStore.missing(context, required)
    }
    val needsGate = missing.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    plugin.title,
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    if (needsGate) "Needs API keys · v${plugin.version}"
                    else "Remote script · v${plugin.version}",
                    color = GrokifyColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            if (!needsGate) {
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reload",
                        tint = GrokifyColors.TextPrimary,
                    )
                }
            }
        }

        when {
            needsGate -> PluginKeyGate(
                plugin = plugin,
                missing = missing,
                onSaved = { gateTick++ },
                onOpenSettings = onOpenSettings,
            )
            entryHtml == null || !entryHtml.isFile -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        loadError ?: "Package missing. Unload and reinstall from Marketplace.",
                        color = GrokifyColors.GlowRose,
                        fontSize = 14.sp,
                    )
                }
            }
            else -> {
                val htmlFile = entryHtml
                val allowedKeys = remember(plugin.id, plugin.requiredKeys) {
                    plugin.allowedKeyIds() + setOf(
                        // Host-managed OAuth tokens always usable by Spotify helpers
                        ApiKeyIds.SPOTIFY_ACCESS_TOKEN,
                        ApiKeyIds.SPOTIFY_REFRESH_TOKEN,
                        ApiKeyIds.SPOTIFY_TOKEN_EXPIRES_AT,
                    )
                }
                val bridge = remember(connected, tokenSaved, plugin.id) {
                    GrokifyHostBridge(
                        appContext = context.applicationContext,
                        connected = connected,
                        tokenSaved = tokenSaved,
                        pluginId = plugin.id,
                        requiredKeys = plugin.requiredKeys,
                        allowedKeyIds = allowedKeys,
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(AndroidColor.parseColor("#0B1016"))
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mediaPlaybackRequiresUserGesture = true
                                @Suppress("DEPRECATION")
                                allowFileAccessFromFileURLs = false
                                @Suppress("DEPRECATION")
                                allowUniversalAccessFromFileURLs = false
                            }
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    if (url.startsWith("file:")) {
                                        val allowed = htmlFile.parentFile?.canonicalPath.orEmpty()
                                        val path = request?.url?.path.orEmpty()
                                        return !(allowed.isNotEmpty() && path.startsWith(allowed))
                                    }
                                    return true
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?,
                                ) {
                                    loadError = description ?: "Load error"
                                }
                            }
                            addJavascriptInterface(bridge, "GrokifyHost")
                            loadUrl(htmlFile.toURI().toString())
                            webViewRef = this
                        }
                    },
                    update = { wv ->
                        bridge.connected = connected
                        bridge.tokenSaved = tokenSaved
                        webViewRef = wv
                    },
                )

                DisposableEffect(Unit) {
                    onDispose {
                        webViewRef?.apply {
                            stopLoading()
                            removeJavascriptInterface("GrokifyHost")
                            destroy()
                        }
                        webViewRef = null
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginKeyGate(
    plugin: PluginManifest,
    missing: List<PluginRequiredKey>,
    onSaved: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val drafts = remember(missing) {
        mutableStateMapOf<String, String>().apply {
            missing.forEach { put(it.id, "") }
        }
    }
    val visible = remember { mutableStateMapOf<String, Boolean>() }
    var flash by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GrokifyColors.Panel)
                .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, tint = GrokifyColors.GlowViolet, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "API keys required",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
            Text(
                "${plugin.title} needs host keys before it can run. Keys stay on this device and are only shared with this plugin.",
                color = GrokifyColors.TextMuted,
                fontSize = 13.sp,
            )
        }

        missing.forEach { key ->
            val vis = visible[key.id] == true
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GrokifyColors.Panel)
                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(key.label, color = GrokifyColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (key.description.isNotBlank()) {
                    Text(key.description, color = GrokifyColors.TextMuted, fontSize = 12.sp)
                }
                Text(key.id, color = GrokifyColors.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                OutlinedTextField(
                    value = drafts[key.id].orEmpty(),
                    onValueChange = { drafts[key.id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (vis) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible[key.id] = !vis }) {
                            Icon(
                                if (vis) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = GrokifyColors.TextMuted,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                )
            }
        }

        Button(
            onClick = {
                var any = false
                missing.forEach { key ->
                    val v = drafts[key.id].orEmpty().trim()
                    if (v.isNotEmpty()) {
                        HostApiKeyStore.save(
                            ctx,
                            key.id,
                            v,
                            key.label,
                            key.description,
                        )
                        any = true
                    }
                }
                if (any) {
                    flash = true
                    onSaved()
                }
            },
            enabled = missing.any { drafts[it.id].orEmpty().isNotBlank() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = GrokifyColors.GlowCyan,
                contentColor = Color(0xFF041016),
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                if (flash) "Saved — loading…" else "Save keys & continue",
                fontWeight = FontWeight.SemiBold,
            )
        }

        TextButton(onClick = onOpenSettings) {
            Text("Open Settings → API keys", color = GrokifyColors.TextMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

private class GrokifyHostBridge(
    private val appContext: android.content.Context,
    @Volatile var connected: Boolean,
    @Volatile var tokenSaved: Boolean,
    private val pluginId: String,
    private val requiredKeys: List<PluginRequiredKey>,
    private val allowedKeyIds: Set<String>,
) {
    @JavascriptInterface
    fun getInfo(): String {
        return JSONObject()
            .put("appName", "GrokifyOS")
            .put("packageName", appContext.packageName)
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("connected", connected)
            .put("tokenSaved", tokenSaved)
            .put("pluginId", pluginId)
            .toString()
    }

    @JavascriptInterface
    fun toast(message: String?) {
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(appContext, msg.take(120), Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun getRequiredKeys(): String {
        val arr = JSONArray()
        requiredKeys.forEach { k ->
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("label", k.label)
                    .put("description", k.description)
                    .put("required", k.required)
                    .put("present", HostApiKeyStore.has(appContext, k.id)),
            )
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun getApiKeyStatus(): String {
        val ids = requiredKeys.map { it.id }
        return HostApiKeyStore.statusJson(appContext, ids, includeInternal = false)
    }

    @JavascriptInterface
    fun hasApiKey(id: String?): String {
        val kid = id?.trim().orEmpty()
        if (kid.isEmpty() || kid !in allowedKeyIds) {
            return JSONObject().put("ok", false).put("present", false).put("error", "not_allowed").toString()
        }
        return JSONObject()
            .put("ok", true)
            .put("id", kid)
            .put("present", HostApiKeyStore.has(appContext, kid))
            .toString()
    }

    @JavascriptInterface
    fun getApiKey(id: String?): String {
        val kid = id?.trim().orEmpty()
        // Never hand out internal OAuth secrets to JS; use spotifyApi instead.
        if (kid.isEmpty() || kid !in allowedKeyIds || kid in ApiKeyIds.INTERNAL) {
            return JSONObject().put("ok", false).put("error", "not_allowed").toString()
        }
        val value = HostApiKeyStore.getValue(appContext, kid)
        return if (value.isNullOrBlank()) {
            JSONObject().put("ok", false).put("error", "missing").put("id", kid).toString()
        } else {
            JSONObject().put("ok", true).put("id", kid).put("value", value).toString()
        }
    }

    @JavascriptInterface
    fun saveApiKey(id: String?, value: String?): String {
        val kid = id?.trim().orEmpty()
        if (kid.isEmpty() || kid !in allowedKeyIds || kid in ApiKeyIds.INTERNAL) {
            return JSONObject().put("ok", false).put("error", "not_allowed").toString()
        }
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) {
            HostApiKeyStore.remove(appContext, kid)
            return JSONObject().put("ok", true).put("cleared", true).toString()
        }
        val meta = requiredKeys.firstOrNull { it.id == kid }
        val preset = ApiKeyPresets.byId(kid)
        HostApiKeyStore.save(
            appContext,
            kid,
            v,
            meta?.label ?: preset?.label,
            meta?.description ?: preset?.description,
        )
        return JSONObject().put("ok", true).put("id", kid).put("present", true).toString()
    }

    @JavascriptInterface
    fun http(method: String?, url: String?, headersJson: String?, body: String?): String {
        return HostHttpProxy.request(
            method.orEmpty(),
            url.orEmpty(),
            headersJson,
            body,
        )
    }

    @JavascriptInterface
    fun aiComplete(prompt: String?, optionsJson: String?): String {
        val p = prompt?.trim().orEmpty()
        if (p.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "empty_prompt").toString()
        }
        return HostAiClient.complete(appContext, p, optionsJson)
    }

    /**
     * Speak [text]. Pass optionsJson as JSON string or null.
     * Uses SpaceXAI Voice TTS when spacexai_api_key is set; else device TTS.
     * optionsJson may include voice_id, language, prefer_device, wait (block until done).
     */
    @JavascriptInterface
    fun speak(text: String?, optionsJson: String?): String =
        HostAiClient.speak(appContext, text, optionsJson)

    @JavascriptInterface
    fun spotifyAuthStatus(): String = SpotifyOAuth.authStatusJson(appContext)

    @JavascriptInterface
    fun spotifyLogin(): String = SpotifyOAuth.startLogin(appContext)

    @JavascriptInterface
    fun spotifyLogout(): String = SpotifyOAuth.logout(appContext)

    @JavascriptInterface
    fun spotifyApi(method: String?, path: String?, body: String?): String {
        return SpotifyOAuth.api(appContext, method.orEmpty().ifBlank { "GET" }, path.orEmpty(), body)
    }

    /** Open track/album/playlist in the Spotify Android app (fallback when Web API play fails). */
    @JavascriptInterface
    fun spotifyOpenUri(uri: String?): String = SpotifyOAuth.openContentUri(appContext, uri)
}
