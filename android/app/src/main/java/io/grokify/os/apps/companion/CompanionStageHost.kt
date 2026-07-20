package io.grokify.os.apps.companion

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject

/**
 * Main-thread dispatcher from native tools → the active Companion WebView stage.
 *
 * [CompanionLive2dStage] attaches/detaches the live WebView. Body tools call
 * [playGesture] / [setHands] / etc. without holding a Compose reference.
 */
object CompanionStageHost {
    private const val TAG = "CompanionStageHost"

    /** Null in pure JVM unit tests (no Android main looper). */
    private val mainHandler: Handler? = try {
        Handler(Looper.getMainLooper())
    } catch (_: Throwable) {
        null
    }

    @Volatile
    private var webView: WebView? = null

    fun attach(wv: WebView) {
        val h = mainHandler
        if (h == null) {
            webView = wv
            return
        }
        h.post { webView = wv }
    }

    fun detach(wv: WebView) {
        val h = mainHandler
        if (h == null) {
            if (webView === wv) webView = null
            return
        }
        h.post {
            if (webView === wv) webView = null
        }
    }

    fun isAttached(): Boolean = webView != null

    private fun eval(js: String) {
        val run = Runnable {
            val wv = webView
            if (wv == null) {
                runCatching { Log.w(TAG, "stage not attached — drop js: ${js.take(80)}") }
                return@Runnable
            }
            runCatching {
                wv.evaluateJavascript(js, null)
            }.onFailure { e ->
                runCatching { Log.w(TAG, "evaluateJavascript failed: ${e.message}") }
            }
        }
        val h = mainHandler
        if (h == null) {
            // Unit tests / no looper: tools still return ok; stage is device-only.
            return
        }
        h.post(run)
    }

    fun playGesture(name: String, intensity: Double = 1.0, side: String = "right") {
        val n = JSONObject.quote(name.trim().ifBlank { "wave" })
        val s = JSONObject.quote(side.trim().ifBlank { "right" })
        val i = intensity.coerceIn(0.2, 1.5)
        eval(
            "window.CompanionStage && window.CompanionStage.playGesture($n, " +
                "{intensity:$i, side:$s});",
        )
    }

    fun setHands(payload: JSONObject) {
        val json = JSONObject.quote(payload.toString())
        eval("window.CompanionStage && window.CompanionStage.setHands($json);")
    }

    fun setLook(x: Double, y: Double) {
        eval(
            "window.CompanionStage && window.CompanionStage.setLook(" +
                "{x:${x.coerceIn(-1.0, 1.0)}, y:${y.coerceIn(-1.0, 1.0)}});",
        )
    }

    fun resetBody() {
        eval("window.CompanionStage && window.CompanionStage.resetBody();")
    }

    fun playMotion(name: String) {
        val n = JSONObject.quote(name)
        eval("window.CompanionStage && window.CompanionStage.playMotion($n);")
    }
}
