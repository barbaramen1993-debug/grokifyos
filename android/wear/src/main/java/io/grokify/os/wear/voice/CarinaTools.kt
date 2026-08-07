package io.grokify.os.wear.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import io.grokify.os.wear.data.WearLocationNoteStore
import io.grokify.os.wear.data.WearSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Client-side tools for Carina on Wear: telemetry, apps, Spotify/media, location notes.
 */
object CarinaTools {
    private const val TAG = "CarinaTools"

    const val TOOL_READ_TELEMETRY = "read_watch_telemetry"
    const val TOOL_OPEN_APP = "open_app"
    const val TOOL_LIST_APPS = "list_apps"
    const val TOOL_SPOTIFY = "control_spotify"
    const val TOOL_LOCATION_NOTE = "take_location_note"
    const val TOOL_LIST_NOTES = "list_location_notes"

    private val snapshotRef = AtomicReference(WearSnapshot())

    fun updateSnapshot(snap: WearSnapshot) {
        snapshotRef.set(snap)
    }

    fun currentSnapshot(): WearSnapshot = snapshotRef.get()

    fun sessionTools(): JSONArray {
        val tools = JSONArray()
        tools.put(JSONObject().put("type", "web_search"))
        tools.put(JSONObject().put("type", "x_search"))
        tools.put(fn(
            TOOL_READ_TELEMETRY,
            "Read live Galaxy Watch telemetry: time, heart rate, steps, compass heading, " +
                "GPS, weather, battery, now-playing media, last message. Call when the user " +
                "asks about vitals, fitness, weather, location, battery, or what is playing.",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        ))
        tools.put(fn(
            TOOL_LIST_APPS,
            "List launchable apps installed on the watch (name + package). " +
                "Use before open_app when the user is vague about the app name.",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        ))
        tools.put(fn(
            TOOL_OPEN_APP,
            "Open an app on the watch by package name or display name match.",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "package_name",
                            JSONObject().put("type", "string")
                                .put("description", "Exact applicationId if known"),
                        )
                        .put(
                            "name",
                            JSONObject().put("type", "string")
                                .put("description", "Display name fragment, e.g. Spotify, Maps"),
                        ),
                ),
        ))
        tools.put(fn(
            TOOL_SPOTIFY,
            "Control Spotify / active media playback on the watch or paired phone media session. " +
                "Actions: play, pause, play_pause, next, previous, status.",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "action",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "description",
                                "play | pause | play_pause | next | previous | status",
                            ),
                    ),
                )
                .put("required", JSONArray().put("action")),
        ))
        tools.put(fn(
            TOOL_LOCATION_NOTE,
            "Save a short note tagged with the current GPS coordinates (or without if unavailable).",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "text",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Note body spoken by the user"),
                    ),
                )
                .put("required", JSONArray().put("text")),
        ))
        tools.put(fn(
            TOOL_LIST_NOTES,
            "List recent location notes saved on this watch.",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        ))
        return tools
    }

    fun toolInstructions(): String = buildString {
        append(
            "You are Carina, the voice assistant for Grokify Wear on the user's Galaxy Watch. " +
                "Speak naturally, warmly, and briefly — this is a small watch speaker. " +
                "You can read live watch sensors and notifications, open apps, control Spotify/media, " +
                "and take location notes. Prefer tools over guessing for vitals, weather, location, " +
                "or media status. ",
        )
        append(
            "When the user asks what you can see or how they are doing, call read_watch_telemetry. " +
                "For music: control_spotify. For reminders about places: take_location_note. " +
                "For launching something: list_apps if needed, then open_app. ",
        )
        append(
            "You also have web_search and x_search for live facts. Keep answers short for speech. " +
                "Never claim you did something unless a tool returned ok=true.",
        )
    }

    fun systemIdentity(): String =
        "You are Carina — soft, empathetic, capable on-wrist AI for GrokifyOS Wear. " +
            "You live on the watch as your own experience (not a phone clone). " +
            "Help with health telemetry, navigation context, music, apps, and place notes."

    data class FunctionCall(val name: String, val callId: String, val argumentsJson: String)
    data class FunctionResult(val callId: String, val outputJson: String)

    fun parseFunctionCall(event: JSONObject): FunctionCall? {
        val name = event.optString("name", "").trim()
        val callId = event.optString("call_id", "").trim()
            .ifBlank { event.optString("callId", "").trim() }
        if (name.isEmpty() || callId.isEmpty()) return null
        val args = when {
            event.has("arguments") && event.opt("arguments") is String ->
                event.optString("arguments", "{}")
            event.optJSONObject("arguments") != null ->
                event.optJSONObject("arguments")!!.toString()
            else -> "{}"
        }
        return FunctionCall(name, callId, args)
    }

    fun execute(ctx: Context, call: FunctionCall): FunctionResult {
        return try {
            when (call.name) {
                TOOL_READ_TELEMETRY -> ok(call, readTelemetryJson())
                TOOL_LIST_APPS -> ok(call, listAppsJson(ctx))
                TOOL_OPEN_APP -> ok(call, openAppJson(ctx, call.argumentsJson))
                TOOL_SPOTIFY -> ok(call, spotifyJson(ctx, call.argumentsJson))
                TOOL_LOCATION_NOTE -> ok(call, noteJson(ctx, call.argumentsJson))
                TOOL_LIST_NOTES -> ok(
                    call,
                    JSONObject()
                        .put("ok", true)
                        .put("notes", WearLocationNoteStore(ctx).recentSummary(8)),
                )
                else -> FunctionResult(
                    call.callId,
                    JSONObject().put("ok", false).put("error", "unknown_function")
                        .put("name", call.name).toString(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "tool ${call.name}", e)
            FunctionResult(
                call.callId,
                JSONObject().put("ok", false).put("error", e.message ?: "tool_failed").toString(),
            )
        }
    }

    private fun ok(call: FunctionCall, json: JSONObject) =
        FunctionResult(call.callId, json.toString())

    private fun readTelemetryJson(): JSONObject {
        val s = snapshotRef.get()
        return JSONObject()
            .put("ok", true)
            .put("time_ms", s.timeMillis)
            .put("heart_rate_bpm", s.heartRateBpm ?: JSONObject.NULL)
            .put("steps_today", s.stepsToday ?: JSONObject.NULL)
            .put("heading_deg", s.headingDeg ?: JSONObject.NULL)
            .put("latitude", s.latitude ?: JSONObject.NULL)
            .put("longitude", s.longitude ?: JSONObject.NULL)
            .put("location_accuracy_m", s.locationAccuracyM ?: JSONObject.NULL)
            .put("weather_temp_c", s.weatherTempC ?: JSONObject.NULL)
            .put("weather", s.weatherLabel ?: JSONObject.NULL)
            .put("battery_pct", s.batteryPct ?: JSONObject.NULL)
            .put("media_title", s.mediaTitle ?: JSONObject.NULL)
            .put("media_artist", s.mediaArtist ?: JSONObject.NULL)
            .put("last_notification", s.lastNotification ?: JSONObject.NULL)
            .put("sleep_hours", s.sleepHours ?: JSONObject.NULL)
            .put(
                "summary",
                buildString {
                    append("HR ${s.heartRateBpm?.toInt() ?: "—"} bpm · ")
                    append("${s.stepsToday ?: "—"} steps · ")
                    append("bat ${s.batteryPct ?: "—"}% · ")
                    s.headingDeg?.let { append(String.format(Locale.US, "heading %.0f° · ", it)) }
                    if (s.weatherTempC != null) {
                        append(String.format(Locale.US, "%.0f°C %s · ", s.weatherTempC, s.weatherLabel ?: ""))
                    }
                    if (s.hasLocation) {
                        append(String.format(Locale.US, "loc %.4f,%.4f · ", s.latitude, s.longitude))
                    }
                    s.mediaTitle?.let { append("♪ $it") }
                }.trim().trimEnd('·', ' '),
            )
    }

    private fun listAppsJson(ctx: Context): JSONObject {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val apps = JSONArray()
        resolved
            .mapNotNull { ri ->
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
                val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
                if (label.isEmpty() || pkg.isEmpty()) null
                else JSONObject().put("name", label).put("package", pkg)
            }
            .distinctBy { it.optString("package") }
            .sortedBy { it.optString("name").lowercase(Locale.US) }
            .take(60)
            .forEach { apps.put(it) }
        return JSONObject().put("ok", true).put("count", apps.length()).put("apps", apps)
    }

    private fun openAppJson(ctx: Context, argsJson: String): JSONObject {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val pkg = args.optString("package_name", "").trim()
        val name = args.optString("name", "").trim()
        val pm = ctx.packageManager
        val targetPkg = when {
            pkg.isNotEmpty() -> pkg
            name.isNotEmpty() -> findPackageByName(ctx, name)
            else -> null
        } ?: return JSONObject().put("ok", false).put("error", "app_not_found")
        val launch = pm.getLaunchIntentForPackage(targetPkg)
            ?: return JSONObject().put("ok", false).put("error", "no_launch_intent")
                .put("package", targetPkg)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return JSONObject().put("ok", true).put("package", targetPkg)
    }

    private fun findPackageByName(ctx: Context, name: String): String? {
        val q = name.lowercase(Locale.US)
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val scored = resolved.mapNotNull { ri ->
            val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
            if (label.isEmpty() || pkg.isEmpty()) return@mapNotNull null
            val l = label.lowercase(Locale.US)
            val p = pkg.lowercase(Locale.US)
            val score = when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.contains(q) -> 60
                p.contains(q.replace(" ", "")) -> 40
                else -> 0
            }
            if (score == 0) null else pkg to score
        }.sortedByDescending { it.second }
        return scored.firstOrNull()?.first
    }

    private fun spotifyJson(ctx: Context, argsJson: String): JSONObject {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val action = args.optString("action", "status").trim().lowercase(Locale.US)
        val snap = snapshotRef.get()
        if (action == "status") {
            return JSONObject()
                .put("ok", true)
                .put("title", snap.mediaTitle ?: JSONObject.NULL)
                .put("artist", snap.mediaArtist ?: JSONObject.NULL)
                .put("note", "Live title from notification bridge when available")
        }
        // Prefer MediaController when notification listener is enabled.
        val controlled = tryMediaController(ctx, action)
        if (controlled) {
            return JSONObject().put("ok", true).put("action", action).put("via", "media_session")
        }
        // Fallback: media key events (works for many Spotify/phone handoffs).
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return JSONObject().put("ok", false).put("error", "unknown_action")
                .put("action", action)
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return JSONObject().put("ok", true).put("action", action).put("via", "media_key")
    }

    private fun tryMediaController(ctx: Context, action: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 21) return false
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return false
            val sessions = try {
                msm.getActiveSessions(null)
            } catch (_: SecurityException) {
                return false
            }
            val ctrl = sessions.firstOrNull { c ->
                val pkg = c.packageName.orEmpty().lowercase(Locale.US)
                pkg.contains("spotify") || c.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull() ?: return false
            when (action) {
                "play" -> ctrl.transportControls.play()
                "pause" -> ctrl.transportControls.pause()
                "play_pause", "toggle" -> {
                    val st = ctrl.playbackState?.state
                    if (st == PlaybackState.STATE_PLAYING) ctrl.transportControls.pause()
                    else ctrl.transportControls.play()
                }
                "next" -> ctrl.transportControls.skipToNext()
                "previous", "prev" -> ctrl.transportControls.skipToPrevious()
                else -> return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "media controller: ${e.message}")
            false
        }
    }

    private fun noteJson(ctx: Context, argsJson: String): JSONObject {
        val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
        val text = args.optString("text", "").trim()
        if (text.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "missing_text")
        }
        val s = snapshotRef.get()
        val note = WearLocationNoteStore(ctx).add(text, s.latitude, s.longitude)
        return JSONObject()
            .put("ok", true)
            .put("id", note.id)
            .put("text", note.text)
            .put("latitude", note.latitude ?: JSONObject.NULL)
            .put("longitude", note.longitude ?: JSONObject.NULL)
    }

    private fun fn(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)
}
