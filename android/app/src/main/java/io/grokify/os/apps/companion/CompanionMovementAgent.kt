package io.grokify.os.apps.companion

import android.content.Context
import android.util.Log
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.INTERNAL_SESSION_TITLE_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Movement agent for Companion VRM control.
 *
 * Primary path: **joint-XYZ motion templates** rebuilt for the loaded avatar
 * (shoulders / rest wrists / arm reach / camera). Voice agent picks a template;
 * the stage computes absolute wrist keyframes from live joint positions.
 *
 * Secondary path: host **bridge CLI** plans novel poses when no template matches.
 *
 * Flow:
 * 1. Match intent → named template (wave_right, point_left, …)
 * 2. Else observe joints + bridge CLI plan
 * 3. Apply frames via stage two-bone IK
 */
object CompanionMovementAgent {
    private const val TAG = "CompanionMoveAgent"
    private const val SESSION_TITLE = INTERNAL_SESSION_TITLE_PREFIX + " Companion Movement"
    private const val COMPLETE_TIMEOUT_HINT = 90

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val appCtxRef = AtomicReference<Context?>(null)
    private val generation = AtomicInteger(0)
    private val busy = AtomicBoolean(false)
    private var runJob: Job? = null

    /** Known motion ids: VRMA-backed + joint-XYZ templates (stage picks layer). */
    val TEMPLATE_IDS: List<String> = listOf(
        "rest", "wave", "wave_left", "wave_right", "wave_both",
        "point", "point_left", "point_right",
        "nod", "shake_head", "shrug", "think", "thinking", "clap", "clapping",
        "cheer", "bow", "lean_in", "hands_on_hips", "crossed_arms",
        "hello", "goodbye", "yes", "no", "celebrate", "jump",
        "angry", "sad", "sleepy", "surprised", "blush", "lookaround", "relax",
    )

    data class Result(
        val ok: Boolean,
        val mode: String = "template",
        val framesApplied: Int = 0,
        val error: String? = null,
        val intent: String = "",
        val planNote: String = "",
        val templateId: String = "",
    )

    fun attach(ctx: Context) {
        appCtxRef.set(ctx.applicationContext)
    }

    fun isBusy(): Boolean = busy.get()

    /** Cancel any in-flight plan + application. */
    fun cancel() {
        generation.incrementAndGet()
        runJob?.cancel()
        runJob = null
        busy.set(false)
    }

    /**
     * True when [text] likely asks for body motion (wave, look, pose, …).
     * Used to kick the movement agent in tandem with voice/text replies.
     */
    fun wantsMotion(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.isEmpty()) return false
        val verbs = Regex(
            """\b(wave|waving|gesture|gestur|point|pointing|nod|nodding|shake|shrug|""" +
                """clap|clapping|cheer|bow|lean|dance|pose|raise|lift|move|moving|look|looking|""" +
                """turn|tilt|hands?|arms?|elbow|shoulder|wrist|hips?|head|body|""" +
                """hello|hi there|goodbye|bye|celebrate|jump|angry|sad|sleepy|surprised|blush|""" +
                """cross(?:ed)? arms|hands on hips|think|thinking|relax|""" +
                """reset (?:body|pose)|stand|sit|stretch|reach|beckon|thumbs?\s*up)\b""",
        )
        return verbs.containsMatchIn(t)
    }

    /**
     * Fire-and-forget motion turn. Safe from any thread.
     * No-ops if no [attach] context yet.
     */
    fun requestAsync(
        intent: String,
        userText: String = "",
        source: String = "async",
        onDone: ((Result) -> Unit)? = null,
    ) {
        val ctx = appCtxRef.get()
        if (ctx == null) {
            runCatching { Log.w(TAG, "requestAsync without attach — drop intent=${intent.take(80)}") }
            onDone?.invoke(Result(ok = false, error = "no_context", intent = intent))
            return
        }
        val gen = generation.incrementAndGet()
        runJob?.cancel()
        runJob = scope.launch {
            val r = run(ctx, intent, userText, source, gen)
            if (generation.get() == gen) onDone?.invoke(r)
        }
    }

    /**
     * Blocking / coroutine entry: plan via bridge CLI and apply frames.
     */
    suspend fun run(
        ctx: Context,
        intent: String,
        userText: String = "",
        source: String = "run",
        expectedGen: Int = generation.get(),
    ): Result = mutex.withLock {
        if (expectedGen != generation.get()) {
            return Result(ok = false, error = "cancelled", intent = intent)
        }
        busy.set(true)
        try {
            if (CompanionDebugLog.enabled) {
                CompanionDebugLog.append(
                    CompanionDebugLog.Dir.Sys,
                    "move→",
                    "source=$source ${intent.take(120)}",
                    userText.take(500),
                )
            }
            runCatching { Log.i(TAG, "run source=$source intent=${intent.take(100)}") }

            if (!CompanionStageHost.isAttached()) {
                return Result(ok = false, error = "stage_not_attached", intent = intent)
            }

            // 1) Prefer joint-XYZ template (instant, measured for this VRM).
            val matched = matchTemplate(intent, userText)
            if (matched != null) {
                val (templateId, side) = matched
                runCatching {
                    Log.i(TAG, "template hit id=$templateId side=$side source=$source")
                }
                if (CompanionDebugLog.enabled) {
                    CompanionDebugLog.append(
                        CompanionDebugLog.Dir.Sys,
                        "move→",
                        "template=$templateId side=$side",
                        intent.take(200),
                    )
                }
                val ok = CompanionStageHost.playTemplate(templateId, 1.0, side)
                return Result(
                    ok = ok,
                    mode = "joint_xyz_template",
                    framesApplied = if (ok) 1 else 0,
                    intent = intent,
                    planNote = "template:$templateId",
                    templateId = templateId,
                    error = if (ok) null else "play_template_failed",
                )
            }

            // 2) Novel motion → bridge planner from live joints.
            val body = CompanionStageHost.getBodyState(1_200)
            if (!body.optBoolean("ok", false)) {
                return Result(
                    ok = false,
                    error = body.optString("error", "body_state_failed"),
                    intent = intent,
                )
            }
            if (body.optBoolean("fallback", false) || !body.optBoolean("loaded", true)) {
                return Result(ok = false, error = "no_vrm_skeleton", intent = intent)
            }

            val compact = compactBodyState(body)
            val planText = callBridgePlanner(ctx, intent, userText, compact)
            if (planText == null) {
                return fallbackScripted(intent, userText)
            }

            val plan = parsePlan(planText)
            if (plan == null) {
                runCatching { Log.w(TAG, "parse failed; raw=${planText.take(300)}") }
                if (CompanionDebugLog.enabled) {
                    CompanionDebugLog.append(
                        CompanionDebugLog.Dir.In,
                        "move←",
                        "parse_failed",
                        planText.take(3_000),
                    )
                }
                return fallbackScripted(intent, userText)
            }

            if (CompanionDebugLog.enabled) {
                CompanionDebugLog.append(
                    CompanionDebugLog.Dir.In,
                    "move←",
                    "plan frames=${plan.optJSONArray("frames")?.length() ?: 0}",
                    plan.toString().take(4_000),
                )
            }

            val applied = applyPlan(plan, expectedGen)
            Result(
                ok = applied > 0,
                mode = "ai_bridge",
                framesApplied = applied,
                intent = intent,
                planNote = plan.optString("note", plan.optString("intent", "")),
                error = if (applied == 0) "no_frames_applied" else null,
            )
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "run failed", e) }
            Result(ok = false, error = e.message ?: "move_failed", intent = intent)
        } finally {
            if (generation.get() == expectedGen) busy.set(false)
        }
    }

    /**
     * Map natural-language intent → (templateId, side).
     * Returns null when the motion is too novel for the joint-XYZ catalog.
     */
    fun matchTemplate(intent: String, userText: String = ""): Pair<String, String>? {
        val blob = "$intent $userText".lowercase().trim()
        if (blob.isEmpty()) return null

        val side = CompanionBodyTools.inferWaveSideFromUserText(userText)
            ?: CompanionBodyTools.inferWaveSideFromUserText(intent)
            ?: when {
                Regex("""\b(left)\b""").containsMatchIn(blob) &&
                    !Regex("""\bright\b""").containsMatchIn(blob) -> "left"
                Regex("""\b(right)\b""").containsMatchIn(blob) &&
                    !Regex("""\bleft\b""").containsMatchIn(blob) -> "right"
                Regex("""\bboth\b""").containsMatchIn(blob) -> "both"
                else -> ""
            }

        // Explicit template id in intent (body_pose path).
        for (id in TEMPLATE_IDS.sortedByDescending { it.length }) {
            if (blob == id || blob.contains("template:$id") || blob.contains("pose:$id")) {
                val s = when {
                    id.contains("left") -> "left"
                    id.contains("right") -> "right"
                    id.contains("both") -> "both"
                    else -> side.ifBlank { "right" }
                }
                return id to s
            }
        }

        if (Regex("""\b(reset|rest|idle|stand down|hands down)\b""").containsMatchIn(blob)) {
            return "rest" to ""
        }
        if (Regex("""\b(cross(?:ed)?\s*arms|fold(?:ed)?\s*arms)\b""").containsMatchIn(blob)) {
            return "crossed_arms" to ""
        }
        if (Regex("""\b(hands?\s*on\s*hips|akimbo)\b""").containsMatchIn(blob)) {
            return "hands_on_hips" to ""
        }
        if (Regex("""\b(shake\s*head|nope|disagree)\b""").containsMatchIn(blob) ||
            Regex("""\b(say\s+no|head\s+no)\b""").containsMatchIn(blob)
        ) {
            return "shake_head" to ""
        }
        if (Regex("""\b(nod|nodding|agree|yes)\b""").containsMatchIn(blob)) {
            return "nod" to ""
        }
        if (Regex("""\b(shrug|dunno|don't know|idk)\b""").containsMatchIn(blob)) {
            return "shrug" to ""
        }
        if (Regex("""\b(think|thinking|ponder|hmm)\b""").containsMatchIn(blob)) {
            return "think" to "right"
        }
        if (Regex("""\b(clap|applaud)\b""").containsMatchIn(blob)) {
            return "clap" to ""
        }
        if (Regex("""\b(cheer|celebrate|hooray|yay)\b""").containsMatchIn(blob)) {
            return "cheer" to "both"
        }
        if (Regex("""\b(bow|curtsy)\b""").containsMatchIn(blob)) {
            return "bow" to ""
        }
        if (Regex("""\b(lean\s*in|lean closer)\b""").containsMatchIn(blob)) {
            return "lean_in" to ""
        }
        if (Regex("""\b(point|pointing)\b""").containsMatchIn(blob)) {
            val s = side.ifBlank { "right" }
            return "point" to s
        }
        if (Regex("""\b(wave|waving|hello|hi there|greet|goodbye|bye)\b""").containsMatchIn(blob)) {
            val s = side.ifBlank { "right" }
            return "wave" to s
        }
        if (Regex("""\b(jump|leap)\b""").containsMatchIn(blob)) {
            return "jump" to ""
        }
        if (Regex("""\b(angry|mad|furious)\b""").containsMatchIn(blob)) {
            return "angry" to ""
        }
        if (Regex("""\b(sad|cry|upset)\b""").containsMatchIn(blob)) {
            return "sad" to ""
        }
        if (Regex("""\b(sleepy|sleep|tired|yawn)\b""").containsMatchIn(blob)) {
            return "sleepy" to ""
        }
        if (Regex("""\b(surprised|shock|whoa)\b""").containsMatchIn(blob)) {
            return "surprised" to ""
        }
        if (Regex("""\b(blush|shy|embarrassed)\b""").containsMatchIn(blob)) {
            return "blush" to ""
        }
        if (Regex("""\b(look\s*around|lookaround)\b""").containsMatchIn(blob)) {
            return "lookaround" to ""
        }
        if (Regex("""\b(relax|chill)\b""").containsMatchIn(blob)) {
            return "relax" to ""
        }
        return null
    }

    /** Prefer template; last resort old scripted gesture path. */
    private fun fallbackScripted(intent: String, userText: String): Result {
        val matched = matchTemplate(intent, userText)
        if (matched != null) {
            val (templateId, side) = matched
            runCatching { Log.w(TAG, "fallback template id=$templateId side=$side") }
            val ok = CompanionStageHost.playTemplate(templateId, 1.0, side)
            return Result(
                ok = ok,
                mode = "template_fallback",
                framesApplied = if (ok) 1 else 0,
                intent = intent,
                planNote = "bridge_unavailable_template_$templateId",
                templateId = templateId,
            )
        }
        val g = extractGestureName(intent, userText)
        if (g != null) {
            val side = CompanionBodyTools.inferWaveSideFromUserText(userText)
                ?: CompanionBodyTools.inferWaveSideFromUserText(intent)
                ?: "both"
            runCatching { Log.w(TAG, "fallback scripted gesture=$g side=$side") }
            CompanionStageHost.playGesture(g, 1.0, side)
            return Result(
                ok = true,
                mode = "scripted_fallback",
                framesApplied = 1,
                intent = intent,
                planNote = "bridge_unavailable_scripted_$g",
            )
        }
        return Result(ok = false, error = "bridge_plan_failed", intent = intent)
    }

    private fun extractGestureName(intent: String, userText: String): String? {
        return matchTemplate(intent, userText)?.first
    }

    private fun callBridgePlanner(
        ctx: Context,
        intent: String,
        userText: String,
        bodyCompact: JSONObject,
    ): String? {
        val system = movementSystemPrompt()
        val prompt = buildString {
            appendLine("MOTION INTENT: $intent")
            if (userText.isNotBlank()) {
                appendLine("USER SAID: ${userText.trim().take(500)}")
            }
            appendLine()
            appendLine("LIVE BODY STATE (measured for THIS VRM — use these numbers only):")
            appendLine(bodyCompact.toString())
            appendLine()
            appendLine(
                "Reply with ONLY a single JSON object motion plan (no markdown fences, no prose).",
            )
        }
        val options = JSONObject()
            .put("system", system)
            .put("session_title", SESSION_TITLE)
            .toString()

        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Out,
                "move-plan",
                "bridge complete",
                "INTENT:\n$intent\n\nBODY:\n${bodyCompact.toString().take(2_500)}",
            )
        }

        val raw = HostAiClient.complete(ctx, prompt, options)
        val env = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!env.optBoolean("ok", false)) {
            runCatching { Log.w(TAG, "bridge complete fail: ${env.optString("error")}") }
            if (CompanionDebugLog.enabled) {
                CompanionDebugLog.append(
                    CompanionDebugLog.Dir.Sys,
                    "move-err",
                    env.optString("error", "complete_failed"),
                    env.optString("hint", "").take(500),
                )
            }
            return null
        }
        return env.optString("text", "").trim().ifBlank { null }
    }

    internal fun movementSystemPrompt(): String = """
        You are the Companion MOVEMENT agent for novel poses only.
        Named moves (wave/point/nod/…) already use joint-XYZ templates on-device.
        You plan freeform motion from measured joint positions for THIS VRM.

        CONTROL MODEL:
        - hips-local wrist targets {x,y,z}; two-bone IK solves elbow
        - axes: x = right+, y = up+, z = hips forward+ (use camera_hips_local for viewer)
        - stay within arm_reach.max_reach of that side's shoulder (prefer ≤ 0.88 × reach)
        - after hold_sec, wrists spring to measured soft hang rest
        - look: x=-1 left..1 right, y=-1 down..1 up

        OUTPUT: one JSON object only:
        {
          "ok": true,
          "intent": "short_label",
          "look": { "x": 0, "y": 0.05, "hold_sec": 2.5 },
          "frames": [
            { "at_ms": 0, "left": {"x":0,"y":0,"z":0}, "right": {"x":0,"y":0,"z":0}, "hold_sec": 0.45 }
          ]
        }

        Rules:
        - Use ONLY numbers from the live body snapshot (vr.rest, joints, shoulders, arm_reach, camera).
        - 3–10 frames; "rest": true returns a hand to hang rest.
        - JSON only — no markdown or prose.
    """.trimIndent()

    /**
     * Slim body JSON for the planner (token budget). Keeps measured geometry,
     * drops dense named_joints maps and bone worlds.
     */
    internal fun compactBodyState(full: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("ok", true)
        out.put("space", full.optString("space", "hips_local"))
        out.put("axes", full.opt("axes"))
        out.put("loaded", full.optBoolean("loaded", true))
        out.put("look", full.opt("look"))
        out.put("vr", full.opt("vr"))
        out.put("joints", full.opt("joints"))
        out.put("arm_reach", full.opt("arm_reach"))
        // Keep only local positions for key bones (no world/euler noise).
        val bonesIn = full.optJSONObject("bones")
        if (bonesIn != null) {
            val keep = listOf(
                "hips", "spine", "chest", "neck", "head",
                "leftUpperArm", "leftLowerArm", "leftHand",
                "rightUpperArm", "rightLowerArm", "rightHand",
            )
            val bonesOut = JSONObject()
            for (k in keep) {
                val b = bonesIn.optJSONObject(k) ?: continue
                bonesOut.put(
                    k,
                    JSONObject()
                        .put("id", k)
                        .put("name", b.optString("name", k))
                        .put("local", b.opt("local")),
                )
            }
            out.put("bones", bonesOut)
        }
        val env = full.optJSONObject("environment")
        if (env != null) {
            out.put(
                "environment",
                JSONObject()
                    .put("camera_hips_local", env.opt("camera_hips_local"))
                    .put("camera_relative", env.opt("camera_relative"))
                    .put("approx_avatar_height", env.opt("approx_avatar_height")),
            )
        }
        // Drop precomputed scripted examples — AI must reason from measurements.
        return out
    }

    /** Extract first JSON object from model text (strips fences / prose). */
    internal fun parsePlan(raw: String): JSONObject? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        // Direct object
        extractJsonObject(t)?.let { return normalizePlan(it) }
        // Fenced ```json ... ```
        val fence = Regex(
            """```(?:json)?\s*([\s\S]*?)```""",
            RegexOption.IGNORE_CASE,
        ).find(t)
        if (fence != null) {
            extractJsonObject(fence.groupValues[1].trim())?.let { return normalizePlan(it) }
        }
        // First { ... } span
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) {
            extractJsonObject(t.substring(start, end + 1))?.let { return normalizePlan(it) }
        }
        return null
    }

    private fun extractJsonObject(s: String): JSONObject? =
        runCatching { JSONObject(s) }.getOrNull()

    private fun normalizePlan(plan: JSONObject): JSONObject? {
        // Single-pose shorthand → one frame
        if (!plan.has("frames")) {
            val frames = JSONArray()
            val frame = JSONObject()
            frame.put("at_ms", 0)
            if (plan.has("left")) frame.put("left", plan.get("left"))
            if (plan.has("right")) frame.put("right", plan.get("right"))
            if (plan.has("hold_sec")) frame.put("hold_sec", plan.get("hold_sec"))
            if (frame.has("left") || frame.has("right")) {
                frames.put(frame)
                plan.put("frames", frames)
            }
        }
        val frames = plan.optJSONArray("frames") ?: return null
        if (frames.length() == 0) return null
        if (!plan.has("ok")) plan.put("ok", true)
        return plan
    }

    private suspend fun applyPlan(plan: JSONObject, expectedGen: Int): Int {
        // Prefer stage-side scheduler for tight timing when available.
        if (CompanionStageHost.isAttached()) {
            val payload = JSONObject()
                .put("frames", plan.optJSONArray("frames"))
            if (plan.has("look")) payload.put("look", plan.get("look"))
            CompanionStageHost.playAiMotion(payload)
            // Also apply look immediately if present (stage handles it too).
            plan.optJSONObject("look")?.let { CompanionStageHost.setLookPayload(it) }

            val frames = plan.optJSONArray("frames") ?: return 0
            // Wait roughly until last frame + hold so busy flag covers the motion.
            var maxAt = 0L
            for (i in 0 until frames.length()) {
                val f = frames.optJSONObject(i) ?: continue
                val at = f.optLong("at_ms", f.optLong("t_ms", 0L)).coerceAtLeast(0L)
                val holdMs = ((f.optDouble("hold_sec", 0.35) * 1000).toLong()).coerceIn(50L, 5_000L)
                maxAt = maxOf(maxAt, at + holdMs)
            }
            val wait = (maxAt + 200L).coerceIn(200L, 12_000L)
            var waited = 0L
            while (waited < wait && generation.get() == expectedGen) {
                delay(50)
                waited += 50
            }
            return frames.length()
        }

        // Kotlin fallback applicator
        val frames = plan.optJSONArray("frames") ?: return 0
        plan.optJSONObject("look")?.let { CompanionStageHost.setLookPayload(it) }
        var applied = 0
        var lastAt = 0L
        val ordered = (0 until frames.length())
            .mapNotNull { frames.optJSONObject(it) }
            .sortedBy { it.optLong("at_ms", it.optLong("t_ms", 0L)) }
        for (f in ordered) {
            if (generation.get() != expectedGen) break
            val at = f.optLong("at_ms", f.optLong("t_ms", 0L)).coerceAtLeast(0L)
            val gap = (at - lastAt).coerceAtLeast(0L)
            if (gap > 0) delay(gap)
            lastAt = at
            val hands = JSONObject()
            resolveHand(f.opt("left"), "left")?.let { hands.put("left", it) }
            resolveHand(f.opt("right"), "right")?.let { hands.put("right", it) }
            if (f.has("hold_sec")) hands.put("hold_sec", f.optDouble("hold_sec", 0.4))
            else hands.put("hold_sec", 0.4)
            if (hands.has("left") || hands.has("right")) {
                CompanionStageHost.setHands(hands)
                applied++
            }
            f.optJSONObject("look")?.let { CompanionStageHost.setLookPayload(it) }
        }
        return applied
    }

    private fun resolveHand(raw: Any?, side: String): JSONObject? {
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is String && raw.equals("rest", ignoreCase = true)) {
            return restHand(side)
        }
        if (raw !is JSONObject) return null
        if (raw.optBoolean("rest", false)) return restHand(side)
        if (!raw.has("x") && !raw.has("y") && !raw.has("z")) return null
        return JSONObject()
            .put("x", raw.optDouble("x", 0.0))
            .put("y", raw.optDouble("y", 0.0))
            .put("z", raw.optDouble("z", 0.0))
    }

    private fun restHand(side: String): JSONObject {
        val body = CompanionStageHost.getBodyState(500)
        val rest = body.optJSONObject("vr")?.optJSONObject("rest")
        val h = rest?.optJSONObject(side)
        return if (h != null) {
            JSONObject()
                .put("x", h.optDouble("x", 0.0))
                .put("y", h.optDouble("y", 0.0))
                .put("z", h.optDouble("z", 0.0))
        } else {
            JSONObject().put("x", 0.0).put("y", 0.0).put("z", 0.0)
        }
    }
}
