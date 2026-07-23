package io.grokify.os.apps.companion

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CompanionAvatarState {
    Idle,
    Listening,
    Thinking,
    Speaking,
}

/**
 * Offline VRM stage hosted in a WebView (`assets/companion/index.html`).
 *
 * Uses Three.js + @pixiv/three-vrm (vendored). JS bridge name: [GrokifyCompanion].
 * Host → page via `window.CompanionStage.*` (loadModel / setState / setMouth).
 * Do not Compose-clip the WebView; it blanks the surface on many OEMs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CompanionLive2dStage(
    modelSource: String,
    userModelPath: String,
    avatarState: CompanionAvatarState,
    mouth: Float,
    debugSkeleton: Boolean = false,
    onReady: () -> Unit = {},
    onModelLoaded: (String) -> Unit = {},
    onModelError: (String) -> Unit = {},
    onAvatarTapped: () -> Unit = {},
    onJointPicked: (JSONObject) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    var webView by remember { mutableStateOf<WebView?>(null) }
    var stageReady by remember { mutableStateOf(false) }
    var lastPushedMouth by remember { mutableFloatStateOf(Float.NaN) }
    var lastMouthPushMs by remember { mutableLongStateOf(0L) }
    // Prefer real filesystem path — WebView XHR to android_asset often fails for ~11MB VRMs.
    var bundledVrmPath by remember { mutableStateOf<String?>(null) }

    val onReadyLatest = rememberUpdatedState(onReady)
    val onModelLoadedLatest = rememberUpdatedState(onModelLoaded)
    val onModelErrorLatest = rememberUpdatedState(onModelError)
    val onAvatarTappedLatest = rememberUpdatedState(onAvatarTapped)
    val onJointPickedLatest = rememberUpdatedState(onJointPicked)
    val modelSourceLatest = rememberUpdatedState(modelSource)
    val userModelPathLatest = rememberUpdatedState(userModelPath)
    val avatarStateLatest = rememberUpdatedState(avatarState)
    val mouthLatest = rememberUpdatedState(mouth)
    val bundledVrmPathLatest = rememberUpdatedState(bundledVrmPath)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(Unit) {
        bundledVrmPath = withContext(Dispatchers.IO) {
            val path = CompanionModelAssets.ensureBundledVrmFile(appContext)
            // Materialize portable VRMA clips for the stage player.
            runCatching { CompanionModelAssets.ensureBundledAnimations(appContext) }
            path
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                CompanionStageHost.detach(wv)
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.loadUrl("about:blank")
                wv.removeJavascriptInterface("GrokifyCompanion")
                wv.destroy()
            }
            webView = null
            stageReady = false
        }
    }

    // Model pack: reload when source/path changes after the stage is ready.
    LaunchedEffect(modelSource, userModelPath, bundledVrmPath, stageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!stageReady) return@LaunchedEffect
        pushLoadModel(wv, modelSource, userModelPath, bundledVrmPath)
    }

    LaunchedEffect(avatarState, stageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!stageReady) return@LaunchedEffect
        pushState(wv, avatarState)
    }

    // Throttle mouth: push on meaningful delta or ~50 fps so lips track PCM envelope.
    LaunchedEffect(mouth, stageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!stageReady) return@LaunchedEffect
        val clamped = mouth.coerceIn(0f, 1f)
        val now = SystemClock.uptimeMillis()
        val delta = if (lastPushedMouth.isNaN()) 1f else abs(clamped - lastPushedMouth)
        val elapsed = now - lastMouthPushMs
        // Push often — lips desync when small envelope edges are dropped.
        if (!lastPushedMouth.isNaN() && delta <= 0.008f && elapsed < 12L) {
            return@LaunchedEffect
        }
        lastPushedMouth = clamped
        lastMouthPushMs = now
        pushMouth(wv, clamped)
    }

    // Skeleton / joint / VR-controller wireframe.
    LaunchedEffect(debugSkeleton, stageReady) {
        if (!stageReady) return@LaunchedEffect
        CompanionStageHost.setDebugSkeleton(debugSkeleton)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // Default layer type — HARDWARE blanks WebView surfaces on some OEMs.
                setLayerType(View.LAYER_TYPE_NONE, null)
                isFocusable = true
                isFocusableInTouchMode = true
                // Keep multi-touch orbit (rotate / pinch / pan) inside the WebView;
                // parents must not steal the gesture mid-drag.
                setOnTouchListener { v, _ ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Never reuse a stale Live2D stage after OTA — assets live in the APK.
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                // Offline stage: no remote network (bundled assets + optional local user pack).
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.userAgentString = settings.userAgentString + " GrokifyCompanion/2"
                clearCache(true)
                clearHistory()
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
                            msg.contains("failed", ignoreCase = true)
                        ) {
                            android.util.Log.w(TAG, "JS: $msg")
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                addJavascriptInterface(
                    CompanionJsBridge(
                        ctx = appContext,
                        onReady = {
                            mainHandler.post {
                                // Mark ready only — LaunchedEffect(modelSource, …, stageReady)
                                // is the single load entrypoint (avoids stacked dual loads).
                                stageReady = true
                                val wv = webView ?: return@post
                                pushState(wv, avatarStateLatest.value)
                                val m = mouthLatest.value.coerceIn(0f, 1f)
                                lastPushedMouth = m
                                lastMouthPushMs = SystemClock.uptimeMillis()
                                pushMouth(wv, m)
                                // Restore user joint names before/with skeleton debug.
                                val labels = CompanionStore(appContext).jointLabelsJson
                                if (labels.isNotBlank()) {
                                    CompanionStageHost.setJointLabels(labels)
                                }
                                onReadyLatest.value()
                            }
                        },
                        onModelLoaded = { detail ->
                            android.util.Log.d(TAG, "model loaded: $detail")
                            mainHandler.post {
                                // Re-apply host state/mouth: install used to force idle and
                                // left Listening/Speaking stuck until the next turn change.
                                val wv = webView
                                if (wv != null) {
                                    pushState(wv, avatarStateLatest.value)
                                    val m = mouthLatest.value.coerceIn(0f, 1f)
                                    lastPushedMouth = m
                                    lastMouthPushMs = SystemClock.uptimeMillis()
                                    pushMouth(wv, m)
                                    val labels = CompanionStore(appContext).jointLabelsJson
                                    if (labels.isNotBlank()) {
                                        CompanionStageHost.setJointLabels(labels)
                                    }
                                }
                                onModelLoadedLatest.value(detail)
                            }
                        },
                        onError = { message ->
                            mainHandler.post {
                                val msg = message.take(240).ifBlank { "VRM model failed" }
                                android.util.Log.w(TAG, "stage error: $msg")
                                onModelErrorLatest.value(msg)
                            }
                        },
                        onAvatarTapped = {
                            mainHandler.post { onAvatarTappedLatest.value() }
                        },
                        onJointPicked = { json ->
                            mainHandler.post { onJointPickedLatest.value(json) }
                        },
                        resolveBundledPath = { bundledVrmPathLatest.value },
                    ),
                    "GrokifyCompanion",
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        // Stay on the offline asset stage; ignore navigations.
                        val url = request?.url?.toString().orEmpty()
                        return !url.startsWith("file:///android_asset/companion") &&
                            !url.startsWith("file://${appContext.filesDir}") &&
                            !url.startsWith("file://${appContext.filesDir.absolutePath}")
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val raw = request?.url?.toString().orEmpty()
                        if (raw.isBlank()) return super.shouldInterceptRequest(view, request)
                        interceptCompanionResource(appContext, raw)?.let { return it }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        val url = request?.url?.toString().orEmpty()
                        android.util.Log.w(
                            TAG,
                            "resource error isMain=${request?.isForMainFrame} $url :: ${error?.description}",
                        )
                        if (request?.isForMainFrame == true) {
                            mainHandler.post {
                                onModelErrorLatest.value(
                                    error?.description?.toString()
                                        ?: "Companion stage failed to load",
                                )
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
                        android.util.Log.w(TAG, "resource error code=$errorCode $failingUrl :: $description")
                        mainHandler.post {
                            onModelErrorLatest.value(
                                description ?: "Companion stage failed to load",
                            )
                        }
                    }
                }

                // Cache-bust query so WebView cannot keep a pre-VRM stage document.
                loadUrl(ASSET_URL)
                webView = this
                CompanionStageHost.attach(this)
            }
        },
        update = { view ->
            webView = view
            CompanionStageHost.attach(view)
            if (view.layoutParams == null ||
                view.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                view.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT
            ) {
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
    )
}

private const val TAG = "CompanionVrm"
// Version bump forces a fresh document after Live2D → VRM migrations.
private const val ASSET_URL =
    "file:///android_asset/companion/index.html?stage=vrm9&v=248"

/**
 * Host bridge for the offline VRM stage.
 *
 * Critical: Android WebView `fetch`/`XHR` to `file://` almost always fails with
 * "Failed to fetch" (even with shouldInterceptRequest). The stage therefore
 * reads VRM bytes via [openVrm]/[readVrmBase64]/[closeVrm] and parses them in JS.
 */
private class CompanionJsBridge(
    private val ctx: android.content.Context,
    private val onReady: () -> Unit,
    private val onModelLoaded: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAvatarTapped: () -> Unit,
    private val onJointPicked: (JSONObject) -> Unit,
    private val resolveBundledPath: () -> String?,
) {
    private val lock = Any()
    private var openFile: RandomAccessFile? = null
    private var openLength: Long = 0L
    private var openLabel: String = ""

    @JavascriptInterface
    fun onReady() {
        onReady.invoke()
    }

    @JavascriptInterface
    fun onModelLoaded(detail: String?) {
        onModelLoaded.invoke(detail.orEmpty())
    }

    @JavascriptInterface
    fun onError(message: String?) {
        onError.invoke(message?.ifBlank { null } ?: "VRM model failed")
    }

    @JavascriptInterface
    fun onAvatarTapped() {
        onAvatarTapped.invoke()
    }

    /**
     * Stage tapped a debug joint/controller. Payload:
     * `{id,name,default_name,custom,local,world}`
     */
    @JavascriptInterface
    fun onJointPicked(json: String?) {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 4_000) return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        onJointPicked.invoke(obj)
    }

    /**
     * Stage → AI debug overlay. Used for joint motion / pick samples so
     * coords can be copied from the phone UI.
     */
    @JavascriptInterface
    fun onDebugLog(kind: String?, summary: String?, detail: String?) {
        val k = kind?.trim().orEmpty().ifBlank { "stage" }.take(32)
        val s = summary?.trim().orEmpty().take(240)
        if (s.isEmpty()) return
        val d = detail?.trim().orEmpty().take(8_000)
        CompanionDebugLog.append(CompanionDebugLog.Dir.Sys, k, s, d)
    }

    /** Persist custom joint labels JSON object from the stage. */
    @JavascriptInterface
    fun saveJointLabels(json: String?) {
        val raw = json?.trim().orEmpty()
        if (raw.length > 8_000) return
        runCatching {
            // Accept only object-shaped JSON.
            if (raw.isNotEmpty() && !raw.startsWith("{")) return@runCatching
            CompanionStore(ctx).jointLabelsJson = raw
        }
    }

    /** Sync read of saved joint labels for stage boot / debug rebuild. */
    @JavascriptInterface
    fun getJointLabels(): String {
        return runCatching { CompanionStore(ctx).jointLabelsJson }.getOrDefault("")
    }

    /** Persist last camera/orbit framing (called from OrbitControls end). */
    @JavascriptInterface
    fun saveOrbit(json: String?) {
        val raw = json?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 2_000) return
        runCatching {
            CompanionStore(ctx).lastOrbitJson = raw
        }
    }

    @JavascriptInterface
    fun clearOrbit() {
        runCatching { CompanionStore(ctx).clearLastOrbit() }
    }

    /** Sync read for JS restore after model install. */
    @JavascriptInterface
    fun getSavedOrbit(): String {
        return runCatching { CompanionStore(ctx).lastOrbitJson }.getOrDefault("")
    }

    /**
     * Open a VRM or VRMA for chunked base64 reads.
     * @param path absolute filesystem path, `bundled`, empty (= bundled),
     *   `anim:<id>` / `vrma:<id>` for a bundled animation clip
     * @return byte length, or negative on error:
     *   -1 unreadable / outside app storage / resolve failed
     *   -2 missing or empty
     *   -3 too large
     *   -4 I/O exception
     *   -5 not a glTF/VRM binary
     */
    @JavascriptInterface
    fun openVrm(path: String?): Int {
        synchronized(lock) {
            closeVrmLocked()
            return try {
                val file = resolveReadableVrm(path)
                    ?: run {
                        android.util.Log.w(
                            TAG,
                            "openVrm: unreadable path=$path filesDir=${ctx.filesDir.absolutePath}",
                        )
                        return -1
                    }
                if (!file.isFile || file.length() < 64L) {
                    android.util.Log.w(TAG, "openVrm: missing/empty ${file.absolutePath}")
                    return -2
                }
                // Soft size cap — absurd files will OOM the WebView base64 path.
                if (file.length() > 80L * 1024L * 1024L) {
                    android.util.Log.w(TAG, "openVrm: too large ${file.length()}")
                    return -3
                }
                if (!CompanionModelAssets.looksLikeGltfBinary(file)) {
                    android.util.Log.w(TAG, "openVrm: bad magic ${file.absolutePath}")
                    return -5
                }
                // Prefer FileInputStream path via RandomAccessFile for seekable chunks.
                val raf = RandomAccessFile(file, "r")
                openFile = raf
                openLength = file.length()
                openLabel = file.name
                android.util.Log.i(TAG, "openVrm ${file.absolutePath} (${openLength} bytes)")
                // Length fits Int for our size cap (80MB).
                openLength.toInt()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "openVrm failed path=$path", e)
                closeVrmLocked()
                -4
            }
        }
    }

    /** JSON list of bundled VRMA clip ids for the stage catalog. */
    @JavascriptInterface
    fun listVrmaClips(): String {
        runCatching { CompanionModelAssets.ensureBundledAnimations(ctx) }
        return org.json.JSONArray(CompanionModelAssets.BUNDLED_VRMA_IDS).toString()
    }

    @JavascriptInterface
    fun vrmLabel(): String = synchronized(lock) { openLabel }

    @JavascriptInterface
    fun readVrmBase64(offset: Int, length: Int): String {
        synchronized(lock) {
            val raf = openFile ?: return ""
            if (offset < 0 || length <= 0) return ""
            if (offset.toLong() >= openLength) return ""
            val toRead = minOf(length.toLong(), openLength - offset.toLong(), 512L * 1024L).toInt()
            return try {
                val buf = ByteArray(toRead)
                raf.seek(offset.toLong())
                var got = 0
                while (got < toRead) {
                    val n = raf.read(buf, got, toRead - got)
                    if (n < 0) break
                    got += n
                }
                if (got <= 0) return ""
                Base64.encodeToString(
                    if (got == toRead) buf else buf.copyOf(got),
                    Base64.NO_WRAP,
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "readVrmBase64 failed off=$offset: ${e.message}")
                ""
            }
        }
    }

    @JavascriptInterface
    fun closeVrm() {
        synchronized(lock) { closeVrmLocked() }
    }

    private fun closeVrmLocked() {
        try {
            openFile?.close()
        } catch (_: Exception) {
        }
        openFile = null
        openLength = 0L
        openLabel = ""
    }

    /**
     * Resolve a path the stage may open. Handles:
     * - blank / "bundled" → extracted Seed-san
     * - anim:<id> / vrma:<id> → bundled VRMA clip
     * - file:// or absolute paths under app files/cache
     * - /data/user/N vs /data/data symlink mismatch via canonical roots
     * - re-extract when a stale bundled path is missing
     */
    private fun resolveReadableVrm(path: String?): File? {
        val raw = path?.trim().orEmpty()

        fun accept(f: File?): File? {
            if (f == null) return null
            if (!f.isFile || f.length() < 64L) return null
            if (!isUnderAppStorage(f)) {
                android.util.Log.w(
                    TAG,
                    "reject outside app storage: abs=${f.absolutePath} " +
                        "canon=${runCatching { f.canonicalPath }.getOrNull()} " +
                        "files=${ctx.filesDir.absolutePath}",
                )
                return null
            }
            return f
        }

        // Bundled alias → always materialize from assets (authoritative).
        if (raw.isBlank() || raw.equals("bundled", ignoreCase = true)) {
            // Prefer already-extracted path from Compose, then force ensure.
            accept(resolveBundledPath()?.let { File(it) })?.let { return it }
            return accept(CompanionModelAssets.ensureBundledVrmFile(ctx)?.let { File(it) })
        }

        // Bundled VRMA: anim:goodbye | vrma:clapping | goodbye.vrma (known id)
        val animId = when {
            raw.startsWith("anim:", ignoreCase = true) -> raw.substring(5)
            raw.startsWith("vrma:", ignoreCase = true) -> raw.substring(5)
            raw.endsWith(".vrma", ignoreCase = true) && !raw.contains("/") ->
                raw.removeSuffix(".vrma").removeSuffix(".VRMA")
            else -> null
        }
        if (animId != null) {
            accept(CompanionModelAssets.ensureAnimationFile(ctx, animId)?.let { File(it) })
                ?.let { return it }
        }

        val abs = when {
            raw.startsWith("file://") -> {
                // file:///data/... → /data/...
                raw.removePrefix("file://")
                    .removePrefix("localhost")
                    .let { if (it.startsWith("/")) it else "/$it" }
            }
            else -> raw
        }
        val candidate = File(abs)
        accept(candidate)?.let { return it }

        // Stale/missing bundled extract path — re-materialize from APK assets.
        val looksBundled =
            abs.contains("/companion/bundled/") ||
                abs.endsWith("Seed-san.vrm", ignoreCase = true) ||
                abs.contains("Seed-san", ignoreCase = true)
        if (looksBundled) {
            android.util.Log.i(TAG, "re-extracting bundled VRM after failed open of $abs")
            return accept(CompanionModelAssets.ensureBundledVrmFile(ctx)?.let { File(it) })
        }

        // Absolute path under animations dir by basename.
        if (abs.contains("/companion/animations/") || abs.endsWith(".vrma", ignoreCase = true)) {
            val base = File(abs).nameWithoutExtension
            accept(CompanionModelAssets.ensureAnimationFile(ctx, base)?.let { File(it) })
                ?.let { return it }
        }

        android.util.Log.w(
            TAG,
            "resolveReadableVrm failed path=$abs exists=${candidate.exists()} " +
                "isFile=${candidate.isFile} len=${candidate.length()} " +
                "under=${isUnderAppStorage(candidate)}",
        )
        return null
    }

    /**
     * True if [file] lives under the app's private files/cache trees.
     * Compares both absolute and canonical forms so `/data/user/0/...` and
     * `/data/data/...` (symlink) both match.
     */
    private fun isUnderAppStorage(file: File): Boolean {
        val filePaths = buildList {
            add(file.absolutePath)
            try {
                add(file.canonicalPath)
            } catch (_: Exception) {
            }
        }
        val roots = buildList {
            val dirs = listOfNotNull(
                ctx.filesDir,
                ctx.cacheDir,
                ctx.noBackupFilesDir,
            )
            for (dir in dirs) {
                add(dir.absolutePath)
                try {
                    add(dir.canonicalPath)
                } catch (_: Exception) {
                }
            }
        }
        return filePaths.any { path ->
            roots.any { root ->
                val r = root.trimEnd('/')
                path == r || path.startsWith("$r/")
            }
        }
    }
}

private fun CompanionAvatarState.toJsState(): String = when (this) {
    CompanionAvatarState.Idle -> "idle"
    CompanionAvatarState.Listening -> "listening"
    CompanionAvatarState.Thinking -> "thinking"
    CompanionAvatarState.Speaking -> "speaking"
}

/**
 * Serve companion assets (HTML/JS/CSS) with explicit MIME types.
 * VRM binaries are no longer loaded via URL — see [CompanionJsBridge.openVrm].
 */
private fun interceptCompanionResource(
    ctx: android.content.Context,
    rawUrl: String,
): WebResourceResponse? {
    val url = rawUrl.substringBefore('#').substringBefore('?')
    return try {
        when {
            url.startsWith("file:///android_asset/companion/") -> {
                val assetPath = url.removePrefix("file:///android_asset/")
                // Multi‑MB VRMs must not go through intercept+fetch (fails on OEMs).
                // Small world map GLBs under companion/world/ are fine and needed offline.
                val isWorldGlb =
                    assetPath.endsWith(".glb", ignoreCase = true) &&
                        assetPath.contains("/world/")
                if (assetPath.endsWith(".vrm", ignoreCase = true) ||
                    (assetPath.endsWith(".glb", ignoreCase = true) && !isWorldGlb)
                ) {
                    return null
                }
                val stream = ctx.assets.open(assetPath)
                WebResourceResponse(mimeForPath(assetPath), charsetForPath(assetPath), stream)
            }
            url.startsWith("file://") -> {
                val path = url.removePrefix("file://").let { p ->
                    if (p.startsWith("/")) p else "/$p"
                }.removePrefix("/localhost")
                val filesRoot = ctx.filesDir.absolutePath
                if (!path.startsWith(filesRoot)) return null
                val file = File(path)
                if (!file.isFile) return null
                if (file.name.endsWith(".vrm", ignoreCase = true) ||
                    file.name.endsWith(".glb", ignoreCase = true)
                ) {
                    return null
                }
                WebResourceResponse(
                    mimeForPath(file.name),
                    charsetForPath(file.name),
                    FileInputStream(file),
                )
            }
            else -> null
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "intercept failed for $url: ${e.message}")
        null
    }
}

private fun mimeForPath(path: String): String {
    val p = path.lowercase()
    return when {
        p.endsWith(".html") || p.endsWith(".htm") -> "text/html"
        p.endsWith(".js") -> "application/javascript"
        p.endsWith(".css") -> "text/css"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".vrm") || p.endsWith(".glb") || p.endsWith(".vrma") -> "model/gltf-binary"
        p.endsWith(".gltf") -> "model/gltf+json"
        p.endsWith(".png") -> "image/png"
        p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
        p.endsWith(".webp") -> "image/webp"
        p.endsWith(".wasm") -> "application/wasm"
        else -> "application/octet-stream"
    }
}

private fun charsetForPath(path: String): String? {
    val p = path.lowercase()
    return when {
        p.endsWith(".html") || p.endsWith(".js") || p.endsWith(".css") ||
            p.endsWith(".json") || p.endsWith(".gltf") -> "utf-8"
        else -> null
    }
}

private fun pushLoadModel(
    webView: WebView,
    source: String,
    path: String,
    // Readiness gate in Compose LaunchedEffect (re-run after extract); bridge re-extracts.
    @Suppress("UNUSED_PARAMETER") bundledPath: String?,
) {
    val rawSrc = source.ifBlank { CompanionStore.SOURCE_BUNDLED }
    // Stage reads bytes via Kotlin bridge only (no file:// fetch).
    // User source without a path used to pass source=user path=bundled and load
    // Seed-san while the store still claimed "user".
    val useUser = rawSrc == CompanionStore.SOURCE_USER && path.isNotBlank()
    val effectiveSource = if (useUser) CompanionStore.SOURCE_USER else CompanionStore.SOURCE_BUNDLED
    // For bundled, pass alias "bundled" so the bridge materializes Seed-san
    // (avoids stale absolute paths / symlink mismatches).
    val effectivePath = if (useUser) path else "bundled"
    val sourceJs = JSONObject.quote(effectiveSource)
    val pathJs = JSONObject.quote(effectivePath)
    webView.evaluateJavascript(
        "window.CompanionStage && window.CompanionStage.loadModel($sourceJs, $pathJs);",
        null,
    )
}

private fun pushState(webView: WebView, state: CompanionAvatarState) {
    val stateJs = JSONObject.quote(state.toJsState())
    webView.evaluateJavascript(
        "window.CompanionStage && window.CompanionStage.setState($stateJs);",
        null,
    )
}

private fun pushMouth(webView: WebView, mouth: Float) {
    val v = mouth.coerceIn(0f, 1f)
    webView.evaluateJavascript(
        "window.CompanionStage && window.CompanionStage.setMouth($v);",
        null,
    )
}
