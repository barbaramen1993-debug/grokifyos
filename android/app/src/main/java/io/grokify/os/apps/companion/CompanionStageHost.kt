package io.grokify.os.apps.companion

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-thread dispatcher from native tools → the active Companion WebView stage.
 *
 * [CompanionLive2dStage] attaches/detaches the live WebView. Body tools call
 * [playGesture] / [setHands] / [getBodyState] without holding a Compose reference.
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

    /**
     * Evaluate JS on the stage WebView and wait for a JSON-encoded result.
     * Must not be called on the main thread (blocks). Safe from tool worker / IO.
     */
    fun evalForResult(js: String, timeoutMs: Long = 900): String? {
        val h = mainHandler ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { Log.w(TAG, "evalForResult on main thread — refuse to block UI") }
            return null
        }
        val latch = CountDownLatch(1)
        val box = AtomicReference<String?>(null)
        h.post {
            val wv = webView
            if (wv == null) {
                latch.countDown()
                return@post
            }
            runCatching {
                wv.evaluateJavascript(js) { value ->
                    box.set(value)
                    latch.countDown()
                }
            }.onFailure { e ->
                runCatching { Log.w(TAG, "evalForResult failed: ${e.message}") }
                latch.countDown()
            }
        }
        return try {
            if (!latch.await(timeoutMs.coerceIn(100, 5000), TimeUnit.MILLISECONDS)) {
                runCatching { Log.w(TAG, "evalForResult timeout ${timeoutMs}ms") }
                null
            } else {
                box.get()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Read live VR controllers, bones, look, gesture, and control schema from the stage.
     * Returns a JSON object the voice agent can use to plan absolute set_hands targets.
     */
    fun getBodyState(timeoutMs: Long = 900): JSONObject {
        if (!isAttached()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "stage_not_attached")
                .put("hint", "Open Companion with a VRM loaded to observe the avatar.")
        }
        val raw = evalForResult(
            "(function(){try{" +
                "if(!window.CompanionStage||typeof window.CompanionStage.exportBodyState!=='function')" +
                "return {ok:false,error:'no_exportBodyState'};" +
                "return window.CompanionStage.exportBodyState();" +
                "}catch(e){return {ok:false,error:String(e&&e.message||e)};}})()",
            timeoutMs,
        )
        if (raw.isNullOrBlank() || raw == "null") {
            return JSONObject()
                .put("ok", false)
                .put("error", "empty_result")
                .put("stage", true)
        }
        return parseJsJson(raw)
    }

    /** Decode WebView evaluateJavascript payload (object or double-encoded string). */
    internal fun parseJsJson(raw: String): JSONObject {
        val trimmed = raw.trim()
        // Direct object/array JSON from WebView.
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return runCatching { JSONObject(trimmed) }.getOrElse {
                JSONObject().put("ok", false).put("error", "parse_failed").put("raw", trimmed.take(240))
            }
        }
        // JSON-encoded string containing JSON.
        return runCatching {
            val tok = JSONTokener(trimmed).nextValue()
            when (tok) {
                is String -> JSONObject(tok)
                is JSONObject -> tok
                else -> JSONObject().put("ok", false).put("error", "unexpected_type")
            }
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "parse_failed").put("raw", trimmed.take(240))
        }
    }

    fun playGesture(name: String, intensity: Double = 1.0, side: String = "") {
        val n = JSONObject.quote(name.trim().ifBlank { "wave" })
        // Do not silently default empty side to "right" — JS maps asymmetric → both.
        val s = JSONObject.quote(side.trim())
        val i = intensity.coerceIn(0.2, 1.5)
        runCatching {
            android.util.Log.i(
                TAG,
                "playGesture name=${name.trim()} side=${side.trim().ifBlank { "(empty)" }} intensity=$i",
            )
        }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Sys,
                "stage",
                "playGesture ${name.trim()} side=${side.trim().ifBlank { "—" }}",
                "intensity=$i",
            )
        }
        eval(
            "window.CompanionStage && window.CompanionStage.playGesture($n, " +
                "{intensity:$i, side:$s});",
        )
    }

    fun setHands(payload: JSONObject) {
        val json = JSONObject.quote(payload.toString())
        eval("window.CompanionStage && window.CompanionStage.setHands($json);")
    }

    /**
     * AI movement agent keyframe plan: { look?, frames:[{at_ms,left?,right?,hold_sec?}] }.
     * Stage schedules frames with measured rest resolution — no scripted gesture table.
     */
    fun playAiMotion(payload: JSONObject) {
        val json = JSONObject.quote(payload.toString())
        runCatching {
            Log.i(TAG, "playAiMotion frames=${payload.optJSONArray("frames")?.length() ?: 0}")
        }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Sys,
                "stage",
                "playAiMotion",
                payload.toString().take(1_500),
            )
        }
        eval("window.CompanionStage && window.CompanionStage.playAiMotion($json);")
    }

    /**
     * Play a motion template / VRMA-backed pose for the loaded VRM.
     * Stage prefers portable VRMA clips when mapped, else joint-XYZ templates.
     */
    fun playTemplate(name: String, intensity: Double = 1.0, side: String = ""): Boolean {
        val n = JSONObject.quote(name.trim().ifBlank { "wave" })
        val s = JSONObject.quote(side.trim())
        val i = intensity.coerceIn(0.2, 1.5)
        runCatching {
            Log.i(TAG, "playTemplate name=${name.trim()} side=${side.trim().ifBlank { "(empty)" }}")
        }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Sys,
                "stage",
                "playTemplate ${name.trim()} side=${side.trim().ifBlank { "—" }}",
                "intensity=$i",
            )
        }
        if (!isAttached()) return false
        eval(
            "window.CompanionStage && window.CompanionStage.playTemplate($n, " +
                "{intensity:$i, side:$s});",
        )
        return true
    }

    /**
     * Play a bundled VRMA clip by id (goodbye, clapping, thinking, …).
     * Retargets to any loaded VRM humanoid.
     */
    fun playVrma(id: String, loop: Boolean = false): Boolean {
        val n = JSONObject.quote(id.trim().ifBlank { "goodbye" })
        runCatching { Log.i(TAG, "playVrma id=${id.trim()} loop=$loop") }
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Sys,
                "stage",
                "playVrma ${id.trim()}",
                "loop=$loop",
            )
        }
        if (!isAttached()) return false
        eval(
            "window.CompanionStage && window.CompanionStage.playVrma($n, {loop:${if (loop) "true" else "false"}});",
        )
        return true
    }

    fun stopVrma() {
        eval("window.CompanionStage && window.CompanionStage.stopVrma && window.CompanionStage.stopVrma();")
    }

    /** Catalog of joint-XYZ templates for the current avatar. */
    fun getMotionLibrary(timeoutMs: Long = 1_200): JSONObject {
        if (!isAttached()) {
            return JSONObject().put("ok", false).put("error", "stage_not_attached")
        }
        val raw = evalForResult(
            "(function(){try{" +
                "if(!window.CompanionStage||typeof window.CompanionStage.exportMotionLibrary!=='function')" +
                "return {ok:false,error:'no_exportMotionLibrary'};" +
                "return window.CompanionStage.exportMotionLibrary();" +
                "}catch(e){return {ok:false,error:String(e&&e.message||e)};}})()",
            timeoutMs,
        )
        if (raw.isNullOrBlank() || raw == "null") {
            return JSONObject().put("ok", false).put("error", "empty_result")
        }
        return parseJsJson(raw)
    }

    fun setLook(x: Double, y: Double) {
        eval(
            "window.CompanionStage && window.CompanionStage.setLook(" +
                "{x:${x.coerceIn(-1.0, 1.0)}, y:${y.coerceIn(-1.0, 1.0)}});",
        )
    }

    /** Full look payload (x/y, direction, hold_sec) → stage setLook. */
    fun setLookPayload(payload: JSONObject) {
        val json = JSONObject.quote(payload.toString())
        eval("window.CompanionStage && window.CompanionStage.setLook($json);")
    }

    fun resetBody() {
        eval("window.CompanionStage && window.CompanionStage.resetBody();")
    }

    fun playMotion(name: String) {
        val n = JSONObject.quote(name)
        eval("window.CompanionStage && window.CompanionStage.playMotion($n);")
    }

    /** Toggle bone/joint/controller wireframe on the VRM stage. */
    fun setDebugSkeleton(enabled: Boolean) {
        val flag = if (enabled) "true" else "false"
        eval("window.CompanionStage && window.CompanionStage.setDebugSkeleton($flag);")
    }

    /** Push full custom joint label map (JSON object string) into the stage. */
    fun setJointLabels(jsonObject: String) {
        val payload = jsonObject.trim().ifBlank { "{}" }
        val quoted = JSONObject.quote(payload)
        eval(
            "window.CompanionStage && window.CompanionStage.setJointLabels(" +
                "JSON.parse($quoted));",
        )
    }

    /** Set one joint's display name (empty clears custom → default humanoid label). */
    fun setJointLabel(key: String, label: String) {
        val k = JSONObject.quote(key.trim())
        val v = JSONObject.quote(label.trim())
        eval("window.CompanionStage && window.CompanionStage.setJointLabel($k, $v);")
    }

    /**
     * Enter in-app Companion World (maps bundled under assets/companion/world/).
     * Same stick/jump + VRM; no external Godot package.
     */
    fun enterWorld(mapId: String = "proto_arena"): Boolean {
        if (!isAttached()) return false
        val id = JSONObject.quote(mapId.trim().ifBlank { "proto_arena" })
        eval(
            "window.CompanionStage && window.CompanionStage.enterWorld && " +
                "window.CompanionStage.enterWorld($id);",
        )
        return true
    }

    fun loadMap(mapId: String): Boolean {
        if (!isAttached()) return false
        val id = JSONObject.quote(mapId.trim().ifBlank { "proto_arena" })
        eval(
            "window.CompanionStage && window.CompanionStage.loadMap && " +
                "window.CompanionStage.loadMap($id);",
        )
        return true
    }

    fun nextMap(): Boolean {
        if (!isAttached()) return false
        eval(
            "window.CompanionStage && window.CompanionStage.nextMap && " +
                "window.CompanionStage.nextMap();",
        )
        return true
    }

    fun leaveWorld(): Boolean {
        if (!isAttached()) return false
        eval(
            "window.CompanionStage && window.CompanionStage.leaveWorld && " +
                "window.CompanionStage.leaveWorld();",
        )
        return true
    }
}
