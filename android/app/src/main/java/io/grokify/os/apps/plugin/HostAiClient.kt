package io.grokify.os.apps.plugin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import io.grokify.os.BuildConfig
import io.grokify.os.GrokifyApp
import io.grokify.os.chat.BridgeClient
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.data.GrokifyApi
import io.grokify.os.data.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Host AI for marketplace scripts.
 *
 * **Text / research** — same path as Chat: device token → host API → bridge →
 * Grok Build headless CLI. Does **not** call api.x.ai for completions.
 *
 * **Speak / DJ banter** — prefers xAI Voice TTS (`POST /v1/tts`) when an
 * xAI API key is stored; falls back to on-device TTS.
 *
 * Plugin / Live DJ turns reuse dedicated [system_chat_sessions] rows whose
 * titles start with [INTERNAL_SESSION_TITLE_PREFIX] so main Chat history can
 * hide them. Keep that prefix if you add new app-scoped session titles.
 */
/** Leading mark on host sessions owned by plugins / Spotify Live DJ (not user Chat). */
const val INTERNAL_SESSION_TITLE_PREFIX = "·"

/** True when [title] is a marketplace / Live DJ bridge session (hide from Chat history). */
fun isInternalAppSessionTitle(title: String?): Boolean {
    val t = title?.trim().orEmpty()
    if (t.isEmpty()) return false
    // Middle-dot (U+00B7) is canonical; bullet is a common paste/lookalike.
    return t.startsWith(INTERNAL_SESSION_TITLE_PREFIX) || t.startsWith("•")
}

object HostAiClient {
    private const val TAG = "HostAiClient"
    private const val COMPLETE_TIMEOUT_SEC = 300L
    private const val PLUGIN_SESSION_TITLE = INTERNAL_SESSION_TITLE_PREFIX + " Plugin AI"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val ttsRef = AtomicReference<TextToSpeech?>(null)
    private val ttsReady = AtomicReference(false)
    private val mediaPlayer = AtomicReference<MediaPlayer?>(null)
    private val focusRequestRef = AtomicReference<AudioFocusRequest?>(null)

    /** Speech stream attrs — must duck/interrupt Spotify so banter is audible. */
    private fun speechAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /**
     * Request transient audio focus so TTS is heard over (or instead of) Spotify.
     * Spotify often keeps focus while paused; without this, MediaPlayer/device TTS is silent.
     *
     * @param talkover when true, **skip focus entirely** — Spotify treats even MAY_DUCK as
     *                 a reason to pause on many devices. Live DJ ducks Spotify volume via API
     *                 and plays the mic on top without stealing focus.
     */
    private fun requestSpeechFocus(ctx: Context, talkover: Boolean = false): AudioFocusRequest? {
        if (talkover) {
            // Critical: do not call requestAudioFocus during talkover. Spotify (and other
            // streamers) often pause on any focus loss, including TRANSIENT_MAY_DUCK.
            abandonSpeechFocus(ctx)
            Log.i(TAG, "talkover: skipping audio focus so Spotify keeps playing")
            return null
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        abandonSpeechFocus(ctx)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { /* keep playing banter */ }
            .build()
        val result = am.requestAudioFocus(req)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequestRef.set(req)
            return req
        }
        Log.w(TAG, "audio focus denied ($result) — trying exclusive transient")
        val exclusive = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAudioAttributes())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        val r2 = am.requestAudioFocus(exclusive)
        if (r2 == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequestRef.set(exclusive)
            return exclusive
        }
        Log.w(TAG, "audio focus still denied ($r2)")
        return null
    }

    private fun abandonSpeechFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusRequestRef.getAndSet(null)?.let { req ->
            runCatching { am.abandonAudioFocusRequest(req) }
        }
    }

    private fun tokenStore(ctx: Context): TokenStore {
        val app = ctx.applicationContext
        return if (app is GrokifyApp) app.tokenStore else TokenStore(app)
    }

    private fun deviceToken(ctx: Context): String? = runBlocking {
        tokenStore(ctx).tokenFlow.first()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun preferredModel(ctx: Context): String? = runBlocking {
        tokenStore(ctx).modelFlow.first()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Run a one-shot Grok Build agent turn via the host bridge (same as Chat).
     *
     * optionsJson (all optional):
     * - system: instruction note injected as agent notes
     * - model: e.g. "gb:grok-4.5" (default: host preference / server default)
     * - session_title: override plugin session title
     */
    fun complete(ctx: Context, prompt: String, optionsJson: String?): String {
        val appCtx = ctx.applicationContext
        val token = deviceToken(appCtx)
        if (token.isNullOrBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "not_signed_in")
                .put(
                    "hint",
                    "Save your device token on Home — plugins use the same host API / bridge as Chat.",
                )
                .toString()
        }

        val opts = runCatching {
            if (optionsJson.isNullOrBlank()) JSONObject() else JSONObject(optionsJson)
        }.getOrElse { JSONObject() }

        val system = opts.optString("system", "").trim()
        val sessionTitle = opts.optString("session_title", PLUGIN_SESSION_TITLE)
            .ifBlank { PLUGIN_SESSION_TITLE }

        val api = GrokifyApi { token }

        return try {
            // Resolve model + short-lived WS token (same endpoints as Chat).
            val modelsJson = api.models()
            if (modelsJson.optBoolean("ok", true) == false &&
                modelsJson.has("error") &&
                modelsJson.optString("ws_token").isBlank()
            ) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", modelsJson.optString("error", "models_failed"))
                    .put("hint", "Host API rejected the request — check device token and server.")
                    .toString()
            }

            var wsToken = modelsJson.optString("ws_token", "").trim()
            if (wsToken.isBlank()) {
                val me = api.me()
                wsToken = me.optString("ws_token", "").trim()
            }
            if (wsToken.isBlank()) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "missing_ws_token")
                    .put("hint", "Could not mint a bridge token — is the host signed in?")
                    .toString()
            }

            val selected = modelsJson.optString("selected", "").trim()
            val defaultModel = modelsJson.optString("default_model", "gb:grok-4.5")
                .ifBlank { "gb:grok-4.5" }
            val optModel = opts.optString("model", "").trim()
            val model = normalizeModel(
                when {
                    optModel.isNotBlank() -> optModel
                    !preferredModel(appCtx).isNullOrBlank() -> preferredModel(appCtx)!!
                    selected.isNotBlank() -> selected
                    else -> defaultModel
                },
            )

            // Dedicated plugin session so Chat history stays clean.
            val sessionId = resolvePluginSession(api, sessionTitle)
            if (sessionId.isBlank()) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "session_create_failed")
                    .put("hint", "Host could not create a plugin chat session.")
                    .toString()
            }

            // Persist user turn (mirrors Chat) so bridge/history stay consistent.
            runCatching {
                api.createMessage(sessionId, "user", prompt)
            }

            val notes = mutableListOf<String>()
            if (system.isNotEmpty()) {
                notes += system
            }
            notes +=
                "You are running inside a GrokifyOS marketplace plugin (not the main Chat UI). " +
                    "Prefer a concise, complete answer. If the user asked for JSON only, reply with JSON only."

            val textRef = AtomicReference("")
            val errRef = AtomicReference<String?>(null)
            val done = CountDownLatch(1)
            val opened = CountDownLatch(1)
            val finished = AtomicBoolean(false)
            val promptSent = AtomicBoolean(false)

            fun finishOk(text: String) {
                if (finished.compareAndSet(false, true)) {
                    textRef.set(text)
                    done.countDown()
                }
            }

            fun finishErr(msg: String) {
                if (finished.compareAndSet(false, true)) {
                    errRef.set(msg)
                    done.countDown()
                }
            }

            val bridge = BridgeClient(
                onEvent = { evt ->
                    when (evt.optString("type")) {
                        "chunk", "text_delta" -> {
                            val c = evt.optString("content")
                            if (c.isNotEmpty()) {
                                textRef.updateAndGet { prev -> prev + c }
                            }
                        }
                        "text_replace" -> {
                            textRef.set(evt.optString("content"))
                        }
                        "done" -> {
                            val final = evt.optString("content").ifBlank { textRef.get() }
                            if (evt.optBoolean("error", false)) {
                                finishErr(evt.optString("content").ifBlank { "agent_error" })
                            } else {
                                finishOk(final)
                            }
                        }
                        "error", "agent_error" -> {
                            finishErr(
                                evt.optString("content")
                                    .ifBlank { evt.optString("error", "bridge_error") },
                            )
                        }
                        "interrupted", "bridge_stopping" -> {
                            val content = evt.optString("content")
                            if (content.isNotEmpty() && content.length > textRef.get().length) {
                                textRef.set(content)
                            }
                            val survive = evt.optBoolean("agents_survive", false) ||
                                evt.optString("reason") == "worker_restart"
                            if (!survive && textRef.get().isBlank()) {
                                finishErr("stream_interrupted")
                            } else if (!survive) {
                                finishOk(textRef.get())
                            }
                        }
                        "no_agent" -> {
                            if (textRef.get().isBlank()) finishErr("no_agent")
                            else finishOk(textRef.get())
                        }
                        else -> { /* thinking/tools ignored for plugin complete */ }
                    }
                },
                onState = { connected, detail ->
                    if (connected) {
                        opened.countDown()
                    } else if (promptSent.get() && !finished.get()) {
                        // Drop after send: keep partial text if any; otherwise surface detail
                        if (textRef.get().isNotBlank()) {
                            finishOk(textRef.get())
                        } else if (!detail.isNullOrBlank()) {
                            finishErr(detail)
                        }
                    } else if (!promptSent.get() && !finished.get() && !detail.isNullOrBlank()) {
                        finishErr(detail)
                        opened.countDown()
                    }
                },
            )

            try {
                bridge.connect(wsToken, BuildConfig.WS_URL)
                if (!opened.await(15, TimeUnit.SECONDS)) {
                    return JSONObject()
                        .put("ok", false)
                        .put("error", "bridge_connect_timeout")
                        .put("hint", "Could not open host WebSocket — is the bridge up?")
                        .toString()
                }
                if (finished.get()) {
                    return JSONObject()
                        .put("ok", false)
                        .put("error", errRef.get() ?: "bridge_failed")
                        .toString()
                }

                val sent = bridge.sendPrompt(
                    prompt = prompt,
                    sessionId = sessionId,
                    model = model,
                    notes = notes,
                    history = emptyList(),
                )
                promptSent.set(true)
                if (!sent) {
                    finishErr("send_failed")
                }

                val completed = done.await(COMPLETE_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!completed) {
                    return JSONObject()
                        .put("ok", false)
                        .put("error", "timeout")
                        .put(
                            "hint",
                            "Grok Build agent did not finish within ${COMPLETE_TIMEOUT_SEC}s. " +
                                "Check bridge / grok login on the server.",
                        )
                        .put("partial", textRef.get().take(2000))
                        .toString()
                }

                val err = errRef.get()
                val text = textRef.get()
                if (err != null && text.isBlank()) {
                    return JSONObject()
                        .put("ok", false)
                        .put("error", err)
                        .put(
                            "hint",
                            "Host bridge / Grok Build failed. Same stack as Chat — " +
                                "check bridge status and `grok login` on the server.",
                        )
                        .toString()
                }

                JSONObject()
                    .put("ok", true)
                    .put("text", text)
                    .put("model", model)
                    .put("provider", "grok-build")
                    .put("session_id", sessionId)
                    .toString()
            } finally {
                bridge.disconnect(notify = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "complete failed", e)
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "ai_failed")
                .toString()
        }
    }

    /**
     * Speak [text] for DJ banter.
     *
     * optionsJson (optional):
     * - voice_id: xAI voice (default "eve")
     * - language: default "en"
     * - prefer_device: if true, skip xAI TTS
     * - wait: if true, block until audio finishes (for between-track DJ)
     * - talkover: if true, duck under live music (no exclusive focus / hard pause)
     */
    fun speak(ctx: Context, text: String?, optionsJson: String? = null): String {
        val msg = text?.trim().orEmpty()
        if (msg.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "empty").toString()
        }
        val opts = runCatching {
            if (optionsJson.isNullOrBlank()) JSONObject() else JSONObject(optionsJson)
        }.getOrElse { JSONObject() }

        val preferDevice = opts.optBoolean("prefer_device", false)
        val wait = opts.optBoolean("wait", false)
        val talkover = opts.optBoolean("talkover", false)
        val xaiKey = HostApiKeyStore.getValue(ctx, ApiKeyIds.XAI)

        if (!preferDevice && !xaiKey.isNullOrBlank()) {
            val voice = opts.optString("voice_id", "eve").ifBlank { "eve" }
            val language = opts.optString("language", "en").ifBlank { "en" }
            val ttsResult = speakXaiTts(
                ctx = ctx.applicationContext,
                apiKey = xaiKey,
                text = msg.take(2000),
                voiceId = voice,
                language = language,
                wait = wait,
                talkover = talkover,
            )
            if (ttsResult.optBoolean("ok")) return ttsResult.toString()
            // Fall through to device TTS with error note
            val device = speakDevice(ctx.applicationContext, msg, wait, talkover)
            if (device.optBoolean("ok")) {
                return device
                    .put("fallback_from", "xai_tts")
                    .put("xai_error", ttsResult.optString("error"))
                    .toString()
            }
            return ttsResult.toString()
        }

        val device = speakDevice(ctx.applicationContext, msg, wait, talkover)
        if (!device.optBoolean("ok") && xaiKey.isNullOrBlank()) {
            return device
                .put(
                    "hint",
                    "Add an xAI API key in Settings for Grok Voice TTS (DJ banter). " +
                        "Research already uses host Grok Build — no xAI key needed for that.",
                )
                .toString()
        }
        return device.toString()
    }

    private fun speakXaiTts(
        ctx: Context,
        apiKey: String,
        text: String,
        voiceId: String,
        language: String,
        wait: Boolean,
        talkover: Boolean = false,
    ): JSONObject {
        return try {
            val body = JSONObject()
                .put("text", text)
                .put("voice_id", voiceId)
                .put("language", language)
            val req = Request.Builder()
                .url("https://api.x.ai/v1/tts")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (!resp.isSuccessful || bytes == null || bytes.isEmpty()) {
                    val errBody = try {
                        bytes?.toString(Charsets.UTF_8)?.take(400).orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    return@use JSONObject()
                        .put("ok", false)
                        .put("error", "xai_tts_http_${resp.code}")
                        .put("body", errBody)
                }
                // Reject JSON error bodies that slipped through with 200
                val looksJson = bytes.size < 512 &&
                    bytes.firstOrNull()?.toInt()?.toChar() == '{'
                if (looksJson) {
                    val errBody = bytes.toString(Charsets.UTF_8).take(400)
                    return@use JSONObject()
                        .put("ok", false)
                        .put("error", "xai_tts_not_audio")
                        .put("body", errBody)
                }
                val out = File(ctx.cacheDir, "plugin-tts-${UUID.randomUUID()}.mp3")
                out.writeBytes(bytes)
                playAudioFile(ctx, out, wait, talkover)
                JSONObject()
                    .put("ok", true)
                    .put("mode", "xai_tts")
                    .put("voice_id", voiceId)
                    .put("bytes", bytes.size)
                    .put("waited", wait)
                    .put(
                        "note",
                        "Grok Voice TTS (xAI). Research/completions use host Grok Build, not this key.",
                    )
            }
        } catch (e: Exception) {
            Log.e(TAG, "speakXaiTts failed", e)
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "xai_tts_failed")
        }
    }

    private fun playAudioFile(ctx: Context, file: File, wait: Boolean, talkover: Boolean = false) {
        val done = CountDownLatch(1)
        val focusHeld = AtomicBoolean(false)
        try {
            mediaPlayer.getAndSet(null)?.run {
                runCatching {
                    stop()
                    release()
                }
            }
            val focus = requestSpeechFocus(ctx, talkover = talkover)
            focusHeld.set(focus != null)
            if (focus == null && !talkover) {
                Log.w(TAG, "playing TTS without audio focus — may be silent over Spotify")
            }
            val attrs = speechAudioAttributes()
            val mp = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setVolume(1f, 1f)
                setDataSource(file.absolutePath)
                setOnCompletionListener { player ->
                    runCatching { player.release() }
                    mediaPlayer.compareAndSet(player, null)
                    file.delete()
                    if (focusHeld.getAndSet(false)) abandonSpeechFocus(ctx)
                    done.countDown()
                }
                setOnErrorListener { player, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    runCatching { player.release() }
                    mediaPlayer.compareAndSet(player, null)
                    file.delete()
                    if (focusHeld.getAndSet(false)) abandonSpeechFocus(ctx)
                    done.countDown()
                    true
                }
                prepare()
                start()
            }
            mediaPlayer.set(mp)
            if (wait) {
                // Cap wait so a hung MediaPlayer can't freeze the caller forever
                val ok = done.await(90, TimeUnit.SECONDS)
                if (!ok) {
                    Log.w(TAG, "playAudioFile wait timed out")
                    runCatching {
                        mp.stop()
                        mp.release()
                    }
                    mediaPlayer.compareAndSet(mp, null)
                    file.delete()
                    if (focusHeld.getAndSet(false)) abandonSpeechFocus(ctx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "playAudioFile failed", e)
            file.delete()
            if (focusHeld.getAndSet(false)) abandonSpeechFocus(ctx)
            done.countDown()
            throw e
        }
    }

    private fun speakDevice(ctx: Context, msg: String, wait: Boolean, talkover: Boolean = false): JSONObject {
        return try {
            ensureTts(ctx)
            val tts = ttsRef.get()
            if (tts == null || !ttsReady.get()) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "tts_not_ready")
            }
            val focus = requestSpeechFocus(ctx, talkover = talkover)
            if (focus == null && !talkover) {
                Log.w(TAG, "device TTS without audio focus — may be silent over Spotify")
            }
            runCatching {
                tts.setAudioAttributes(speechAudioAttributes())
            }
            val utteranceId = "grokify-plugin-${UUID.randomUUID()}"
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        abandonSpeechFocus(ctx)
                        done.countDown()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        abandonSpeechFocus(ctx)
                        done.countDown()
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        abandonSpeechFocus(ctx)
                        done.countDown()
                    }
                },
            )
            val code = tts.speak(msg.take(800), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (code != TextToSpeech.SUCCESS) {
                abandonSpeechFocus(ctx)
                return JSONObject().put("ok", false).put("error", "tts_speak_code_$code")
            }
            if (wait) {
                // Estimate fallback if listener never fires
                val estMs = (msg.split(Regex("\\s+")).size * 320L + 1200L).coerceIn(1500L, 45000L)
                val finished = done.await(estMs + 2000L, TimeUnit.MILLISECONDS)
                if (!finished) abandonSpeechFocus(ctx)
            } else {
                // Non-blocking: abandon after estimated duration so Spotify can resume later
                // (caller that uses wait:false typically doesn't chain next track)
            }
            JSONObject()
                .put("ok", true)
                .put("mode", "device_tts")
                .put("waited", wait)
                .put("focus", focus != null)
                .put(
                    "note",
                    "On-device TTS. Add xAI API key for Grok Voice between tracks.",
                )
        } catch (e: Exception) {
            abandonSpeechFocus(ctx)
            JSONObject().put("ok", false).put("error", e.message ?: "speak_failed")
        }
    }

    private fun ensureTts(ctx: Context) {
        if (ttsRef.get() != null) return
        synchronized(this) {
            if (ttsRef.get() != null) return
            val latch = CountDownLatch(1)
            val tts = TextToSpeech(ctx.applicationContext) { status ->
                ttsReady.set(status == TextToSpeech.SUCCESS)
                if (status == TextToSpeech.SUCCESS) {
                    ttsRef.get()?.let { engine ->
                        engine.language = Locale.getDefault()
                        runCatching {
                            engine.setAudioAttributes(speechAudioAttributes())
                        }
                    }
                }
                latch.countDown()
            }
            ttsRef.set(tts)
            latch.await(3, TimeUnit.SECONDS)
        }
    }

    private fun normalizeModel(raw: String): String {
        val m = raw.trim()
        if (m.isEmpty()) return "gb:grok-4.5"
        // Strip accidental OpenAI-style ids plugins might still pass
        if (m == "grok-3" || m == "grok-2" || m.startsWith("grok-") && !m.startsWith("gb:")) {
            // Prefer host default rather than inventing a bad CLI id
            return if (m.startsWith("grok-")) "gb:$m" else "gb:grok-4.5"
        }
        if (m.startsWith("grok:") && !m.startsWith("gb:")) {
            return "gb:" + m.removePrefix("grok:")
        }
        return m
    }

    /**
     * Reuse an existing plugin/DJ session when possible.
     * Matches exact title first, then other internal sessions that share the same title
     * (never reuses a normal user Chat session).
     */
    private fun resolvePluginSession(api: GrokifyApi, title: String): String {
        val want = title.trim().ifBlank { PLUGIN_SESSION_TITLE }
        runCatching {
            val list = api.listChatSessions()
            val arr = list.optJSONArray("sessions") ?: list.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val t = s.optString("title", "").trim()
                    // Only match internal app sessions — never a user-visible Chat.
                    if (!isInternalAppSessionTitle(t)) continue
                    if (t == want || t.startsWith(want)) {
                        val id = s.optString("id", "").ifBlank {
                            s.optString("session_id", "")
                        }
                        if (id.isNotBlank()) return id
                    }
                }
            }
        }
        val created = api.createChatSession(want)
        return created.optString("id", "").ifBlank {
            created.optString("session_id", "")
        }.ifBlank {
            created.optJSONObject("session")?.optString("id").orEmpty()
        }
    }
}
