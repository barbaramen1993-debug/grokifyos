package io.grokify.os.apps.companion

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import org.json.JSONObject

enum class CompanionAvatarState {
    Idle,
    Listening,
    Thinking,
    Speaking,
}

/**
 * Offline Live2D stage hosted in a WebView (`assets/companion/index.html`).
 *
 * JS bridge name: [GrokifyCompanion]. Host → page via `window.CompanionStage.*`.
 * Do not Compose-clip the WebView; it blanks the surface on many OEMs.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CompanionLive2dStage(
    modelSource: String,
    userModelPath: String,
    avatarState: CompanionAvatarState,
    mouth: Float,
    onReady: () -> Unit = {},
    onModelError: (String) -> Unit = {},
    onAvatarTapped: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var stageReady by remember { mutableStateOf(false) }
    var lastPushedMouth by remember { mutableFloatStateOf(Float.NaN) }
    var lastMouthPushMs by remember { mutableLongStateOf(0L) }

    val onReadyLatest = rememberUpdatedState(onReady)
    val onModelErrorLatest = rememberUpdatedState(onModelError)
    val onAvatarTappedLatest = rememberUpdatedState(onAvatarTapped)
    val modelSourceLatest = rememberUpdatedState(modelSource)
    val userModelPathLatest = rememberUpdatedState(userModelPath)
    val avatarStateLatest = rememberUpdatedState(avatarState)
    val mouthLatest = rememberUpdatedState(mouth)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                loadUrl("about:blank")
                removeJavascriptInterface("GrokifyCompanion")
                destroy()
            }
            webView = null
            stageReady = false
        }
    }

    // Model pack: reload when source/path changes after the stage is ready.
    LaunchedEffect(modelSource, userModelPath, stageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!stageReady) return@LaunchedEffect
        pushLoadModel(wv, modelSource, userModelPath)
    }

    LaunchedEffect(avatarState, stageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!stageReady) return@LaunchedEffect
        pushState(wv, avatarState)
    }

    // Throttle mouth updates: push if delta > 0.03 or at most ~30 fps (every 33ms).
    LaunchedEffect(mouth, stageReady) {
        val wv = webView ?: return@LaunchedEffect
        if (!stageReady) return@LaunchedEffect
        val clamped = mouth.coerceIn(0f, 1f)
        val now = SystemClock.uptimeMillis()
        val delta = if (lastPushedMouth.isNaN()) 1f else abs(clamped - lastPushedMouth)
        val elapsed = now - lastMouthPushMs
        if (!lastPushedMouth.isNaN() && delta <= 0.03f && elapsed < 33L) {
            return@LaunchedEffect
        }
        lastPushedMouth = clamped
        lastMouthPushMs = now
        pushMouth(wv, clamped)
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

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
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
                settings.userAgentString = settings.userAgentString + " GrokifyCompanion/1"
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
                    object {
                        @JavascriptInterface
                        fun onReady() {
                            mainHandler.post {
                                stageReady = true
                                val wv = webView ?: return@post
                                // Re-apply host props (JS also auto-loads bundled on boot).
                                pushLoadModel(
                                    wv,
                                    modelSourceLatest.value,
                                    userModelPathLatest.value,
                                )
                                pushState(wv, avatarStateLatest.value)
                                val m = mouthLatest.value.coerceIn(0f, 1f)
                                lastPushedMouth = m
                                lastMouthPushMs = SystemClock.uptimeMillis()
                                pushMouth(wv, m)
                                onReadyLatest.value()
                            }
                        }

                        @JavascriptInterface
                        fun onModelLoaded(detail: String?) {
                            android.util.Log.d(TAG, "model loaded: ${detail.orEmpty()}")
                        }

                        @JavascriptInterface
                        fun onError(message: String?) {
                            mainHandler.post {
                                val msg = message?.take(240)?.ifBlank { null }
                                    ?: "Live2D model failed"
                                android.util.Log.w(TAG, "stage error: $msg")
                                // Parent should fall back to bundled when user pack fails.
                                onModelErrorLatest.value(msg)
                            }
                        }

                        @JavascriptInterface
                        fun onAvatarTapped() {
                            mainHandler.post { onAvatarTappedLatest.value() }
                        }
                    },
                    "GrokifyCompanion",
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        // Stay on the offline asset stage; ignore navigations.
                        val url = request?.url?.toString().orEmpty()
                        return !url.startsWith("file:///android_asset/companion")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
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
                        mainHandler.post {
                            onModelErrorLatest.value(
                                description ?: "Companion stage failed to load",
                            )
                        }
                    }
                }

                loadUrl(ASSET_URL)
                webView = this
            }
        },
        update = { view ->
            webView = view
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

private const val TAG = "CompanionLive2d"
private const val ASSET_URL = "file:///android_asset/companion/index.html"

private fun CompanionAvatarState.toJsState(): String = when (this) {
    CompanionAvatarState.Idle -> "idle"
    CompanionAvatarState.Listening -> "listening"
    CompanionAvatarState.Thinking -> "thinking"
    CompanionAvatarState.Speaking -> "speaking"
}

private fun pushLoadModel(webView: WebView, source: String, path: String) {
    val src = source.ifBlank { CompanionStore.SOURCE_BUNDLED }
    val sourceJs = JSONObject.quote(src)
    val pathJs = JSONObject.quote(path)
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
