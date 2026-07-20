package io.grokify.os.apps

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-level Voice Agent session for Grok Assistant.
 *
 * Streams mic PCM → xAI Realtime, plays assistant PCM, handles
 * `prompt_grok_build` (and other client functions) via [GrokAssistantVoiceTools].
 *
 * Threading: WebSocket callbacks may be off-main; UI listeners are posted to main.
 */
object GrokAssistantVoiceSession {
    private const val TAG = "GrokAssistantVoiceSession"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** UI-facing conversational turn (drives color + animation). */
    enum class Turn {
        Idle,
        Connecting,
        Listening,
        UserSpeaking,
        Thinking,
        GrokSpeaking,
        ToolBusy,
        Error,
    }

    enum class State {
        Idle,
        Connecting,
        Live,
        ToolBusy,
        Error,
    }

    data class Snapshot(
        val state: State,
        val turn: Turn,
        val statusLine: String?,
        val partialUser: String?,
        val partialAssistant: String?,
        /** Peak-smoothed level 0f..1f for reactive UI. */
        val level: Float,
        /** Rolling bar samples 0f..1f for waveform (newest last). */
        val bars: FloatArray,
        /** True while outbound mic is suppressed (Grok speaking / post-TTS hold). */
        val micMuted: Boolean = false,
    )

    interface Listener {
        fun onSnapshot(snap: Snapshot)
        fun onTranscriptCommitted(role: String, text: String) {}
        fun onError(message: String) {}
    }

    private const val BAR_COUNT = 28
    private const val LEVEL_PUBLISH_MS = 40L
    /** Keep mic muted briefly after last TTS chunk so the AudioTrack tail can't loop back. */
    private const val PLAYBACK_MUTE_HOLD_MS = 700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = ConcurrentLinkedQueue<Listener>()
    private val state = AtomicReference(State.Idle)
    private val turn = AtomicReference(Turn.Idle)
    private val statusLine = AtomicReference<String?>(null)
    private val partialUser = AtomicReference<String?>(null)
    private val partialAssistant = AtomicReference<String?>(null)
    private val level = AtomicReference(0f)
    private val barsLock = Any()
    private val bars = FloatArray(BAR_COUNT)
    private val lastLevelPublishMs = AtomicLong(0L)
    private val lastPlaybackActivityMs = AtomicLong(0L)
    /** Outbound mic muted — we still read AudioRecord (keep hardware warm) but send nothing. */
    private val micMuted = AtomicBoolean(false)

    private val clientRef = AtomicReference<GrokAssistantVoiceClient?>(null)
    private val micJob = AtomicReference<Job?>(null)
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private val audioRecordRef = AtomicReference<AudioRecord?>(null)
    private val running = AtomicBoolean(false)
    private val useBinary = AtomicBoolean(true)
    private val assistantCommittedThisResponse = AtomicBoolean(false)

    /** Pending function outputs waiting for playback drain before response.create. */
    private val pendingResponseAfterTools = AtomicBoolean(false)
    private val inFlightTools = AtomicInteger(0)

    private var appCtx: Context? = null
    private var conversationId: String? = null

    val isLive: Boolean
        get() {
            val s = state.get()
            return s == State.Live || s == State.Connecting || s == State.ToolBusy
        }

    fun snapshot(): Snapshot = Snapshot(
        state = state.get(),
        turn = turn.get(),
        statusLine = statusLine.get(),
        partialUser = partialUser.get(),
        partialAssistant = partialAssistant.get(),
        level = level.get(),
        bars = synchronized(barsLock) { bars.copyOf() },
        micMuted = micMuted.get() || !isMicSendAllowed(),
    )

    fun addListener(l: Listener) {
        listeners.add(l)
        mainHandler.post { l.onSnapshot(snapshot()) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * Start a live voice session. Requires SpaceXAI vault key + RECORD_AUDIO.
     * [seedUserText] is injected as a text turn after connect (wake remainder / typed kickoff).
     */
    fun start(
        ctx: Context,
        seedUserText: String? = null,
        openMic: Boolean = true,
    ) {
        val app = ctx.applicationContext
        appCtx = app
        val store = GrokAssistantStore(app)
        if (!store.enabled) {
            fail("Assistant is off — enable in Setup")
            return
        }
        if (!store.voiceRealtimeEnabled) {
            fail("Realtime Voice is off — enable in Setup")
            return
        }
        val apiKey = HostApiKeyStore.getValue(app, ApiKeyIds.SPACEXAI)
        if (apiKey.isNullOrBlank()) {
            fail("Add SpaceXAI API key (vault) for Realtime Voice")
            return
        }
        if (running.getAndSet(true)) {
            // Already running — inject text if provided
            val seed = seedUserText?.trim().orEmpty()
            if (seed.isNotEmpty()) {
                scope.launch { clientRef.get()?.createUserText(seed) }
                store.appendMessage("user", seed)
                notifyTranscript("user", seed)
            }
            return
        }

        setState(State.Connecting, Turn.Connecting, "Connecting Voice Agent…")
        GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)

        scope.launch {
            try {
                val token = GrokAssistantVoiceClient.mintAuthToken(apiKey)
                if (token.isBlank()) {
                    fail("Could not mint voice session token")
                    return@launch
                }
                val client = GrokAssistantVoiceClient(
                    onEvent = { event -> handleEvent(app, event) },
                    onBinaryAudio = { pcm -> playPcm(pcm) },
                    onState = { connected, detail ->
                        if (!connected && running.get()) {
                            val msg = detail ?: "disconnected"
                            setState(State.Error, Turn.Error, msg)
                            mainHandler.post {
                                listeners.forEach { it.onError(msg) }
                            }
                            stopInternal(releaseMic = true)
                        }
                    },
                )
                clientRef.set(client)
                client.connect(token)

                // Brief wait for socket open via session.update after small delay
                var waits = 0
                while (running.get() && !client.isConnected && waits < 50) {
                    Thread.sleep(100)
                    waits++
                }
                if (!client.isConnected) {
                    fail("Voice WebSocket connect timeout")
                    return@launch
                }

                val instructions = buildString {
                    append(store.systemPrompt())
                    append("\n\n")
                    append(
                        GrokAssistantVoiceTools.voiceToolInstructions(
                            devMode = store.mode == AssistantMode.Dev,
                        ),
                    )
                }
                val tools = GrokAssistantVoiceTools.sessionTools(
                    devMode = store.mode == AssistantMode.Dev,
                )
                client.sessionUpdate(
                    instructions = instructions,
                    voice = store.voiceId,
                    tools = tools,
                    sampleRate = GrokAssistantVoiceClient.SAMPLE_RATE,
                    useBinaryAudio = true,
                )
                useBinary.set(true)
                ensurePlaybackTrack()

                setState(State.Live, Turn.Listening, "Listening…")

                val seed = seedUserText?.trim().orEmpty()
                if (seed.isNotEmpty()) {
                    store.appendMessage("user", seed)
                    notifyTranscript("user", seed)
                    client.createUserText(seed)
                }

                if (openMic) {
                    startMicCapture(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                fail(e.message ?: "voice_start_failed")
            }
        }
    }

    fun stop() {
        stopInternal(releaseMic = true)
    }

    /** Inject a text user turn into an active session (or no-op if idle). */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val app = appCtx ?: return
        val client = clientRef.get() ?: return
        GrokAssistantStore(app).appendMessage("user", t)
        notifyTranscript("user", t)
        scope.launch { client.createUserText(t) }
    }

    private fun stopInternal(releaseMic: Boolean) {
        running.set(false)
        pendingResponseAfterTools.set(false)
        inFlightTools.set(0)
        assistantCommittedThisResponse.set(false)
        micMuted.set(false)
        lastPlaybackActivityMs.set(0L)
        micJob.getAndSet(null)?.cancel()
        runCatching { audioRecordRef.getAndSet(null)?.release() }
        runCatching {
            audioTrackRef.getAndSet(null)?.let {
                it.pause()
                it.flush()
                it.release()
            }
        }
        clientRef.getAndSet(null)?.disconnect()
        if (releaseMic) {
            GrokAssistantMic.release(GrokAssistantMic.Owner.Voice)
        }
        partialUser.set(null)
        partialAssistant.set(null)
        level.set(0f)
        synchronized(barsLock) { bars.fill(0f) }
        setState(State.Idle, Turn.Idle, null)
    }

    /**
     * Mic should not stream while Grok is talking (or still draining TTS),
     * so the model never hears itself.
     */
    private fun isMicSendAllowed(): Boolean {
        when (turn.get()) {
            Turn.GrokSpeaking, Turn.Thinking, Turn.ToolBusy, Turn.Idle, Turn.Error -> return false
            Turn.Connecting, Turn.Listening, Turn.UserSpeaking -> Unit
        }
        val lastPlay = lastPlaybackActivityMs.get()
        if (lastPlay > 0L) {
            val age = SystemClock.uptimeMillis() - lastPlay
            if (age < PLAYBACK_MUTE_HOLD_MS) return false
        }
        return true
    }

    /** Edge-triggered mute/unmute with buffer clear so residual echo never commits. */
    private fun applyMicMutePolicy(client: GrokAssistantVoiceClient?) {
        val wantMute = !isMicSendAllowed()
        val wasMuted = micMuted.getAndSet(wantMute)
        if (wantMute && !wasMuted) {
            client?.clearInputAudioBuffer()
            Log.d(TAG, "mic muted (turn=${turn.get()})")
        } else if (!wantMute && wasMuted) {
            Log.d(TAG, "mic unmuted (turn=${turn.get()})")
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        setState(State.Error, Turn.Error, msg)
        mainHandler.post {
            listeners.forEach { it.onError(msg) }
        }
        stopInternal(releaseMic = true)
    }

    private fun setState(s: State, t: Turn, line: String?) {
        state.set(s)
        turn.set(t)
        statusLine.set(line)
        applyMicMutePolicy(clientRef.get())
        publish()
    }

    private fun setTurn(t: Turn, line: String? = statusLine.get()) {
        turn.set(t)
        if (line != null) statusLine.set(line)
        applyMicMutePolicy(clientRef.get())
        publish()
    }

    private fun publish() {
        val snap = snapshot()
        mainHandler.post {
            listeners.forEach { it.onSnapshot(snap) }
        }
    }

    private fun notifyTranscript(role: String, text: String) {
        mainHandler.post {
            listeners.forEach { it.onTranscriptCommitted(role, text) }
        }
    }

    private fun noteLevel(sample: Float, force: Boolean = false) {
        val clamped = sample.coerceIn(0f, 1f)
        val prev = level.get()
        // Attack fast, release slower so the orb feels alive
        val smoothed = if (clamped >= prev) {
            prev * 0.25f + clamped * 0.75f
        } else {
            prev * 0.82f + clamped * 0.18f
        }
        level.set(smoothed)
        synchronized(barsLock) {
            System.arraycopy(bars, 1, bars, 0, bars.size - 1)
            bars[bars.size - 1] = smoothed
        }
        val now = SystemClock.uptimeMillis()
        val last = lastLevelPublishMs.get()
        if (force || now - last >= LEVEL_PUBLISH_MS) {
            if (lastLevelPublishMs.compareAndSet(last, now) || force) {
                lastLevelPublishMs.set(now)
                publish()
            }
        }
    }

    private fun pcmRms(pcm: ByteArray, len: Int = pcm.size): Float {
        if (len < 2) return 0f
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += (sample * sample).toDouble()
            n++
            i += 2
        }
        if (n == 0) return 0f
        val rms = sqrt(sum / n)
        // ~2000 typical speech RMS at 16-bit; map to 0..1 with headroom
        return min(1f, (rms / 4500.0).toFloat())
    }

    private fun extractTranscript(event: JSONObject): String {
        val direct = event.optString("transcript", "").trim()
        if (direct.isNotEmpty()) return direct
        val item = event.optJSONObject("item")
        if (item != null) {
            val t = item.optString("transcript", "").trim()
            if (t.isNotEmpty()) return t
            val content = item.optJSONArray("content")
            if (content != null) {
                val parts = ArrayList<String>()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val text = part.optString("transcript", "")
                        .ifBlank { part.optString("text", "") }
                        .trim()
                    if (text.isNotEmpty()) parts += text
                }
                if (parts.isNotEmpty()) return parts.joinToString(" ")
            }
        }
        return event.optString("text", "").trim()
    }

    private fun commitAssistantIfNeeded(app: Context, text: String?) {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return
        if (!assistantCommittedThisResponse.compareAndSet(false, true)) {
            // Already committed this response turn — avoid duplicates
            return
        }
        partialAssistant.set(null)
        GrokAssistantStore(app).appendMessage("assistant", body)
        notifyTranscript("assistant", body)
        publish()
    }

    private fun handleEvent(app: Context, event: JSONObject) {
        if (!running.get()) return
        when (event.optString("type")) {
            "conversation.created" -> {
                conversationId = event.optJSONObject("conversation")
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() }
            }
            "error" -> {
                val msg = event.optString("error", "")
                    .ifBlank { event.optJSONObject("error")?.optString("message").orEmpty() }
                    .ifBlank { event.optJSONObject("error")?.toString().orEmpty() }
                    .ifBlank { event.optString("message", "voice_error") }
                Log.e(TAG, "server error: $msg")
                mainHandler.post { listeners.forEach { it.onError(msg) } }
                statusLine.set(msg.take(120))
                publish()
            }
            "input_audio_buffer.speech_started" -> {
                // Barge-in: flush playback
                runCatching {
                    audioTrackRef.get()?.pause()
                    audioTrackRef.get()?.flush()
                }
                partialUser.set("")
                partialAssistant.set(null)
                assistantCommittedThisResponse.set(false)
                setTurn(Turn.UserSpeaking, "Hearing you…")
            }
            "input_audio_buffer.speech_stopped" -> {
                setTurn(Turn.Thinking, "Thinking…")
            }
            "conversation.item.input_audio_transcription.updated" -> {
                // Cumulative live caption while user speaks
                val text = extractTranscript(event)
                if (text.isNotEmpty()) {
                    partialUser.set(text)
                    if (turn.get() != Turn.UserSpeaking) {
                        turn.set(Turn.UserSpeaking)
                        statusLine.set("Hearing you…")
                    }
                    publish()
                }
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.done",
            -> {
                val text = extractTranscript(event)
                if (text.isNotEmpty()) {
                    partialUser.set(null)
                    GrokAssistantStore(app).appendMessage("user", text)
                    notifyTranscript("user", text)
                    if (turn.get() == Turn.UserSpeaking) {
                        setTurn(Turn.Thinking, "Thinking…")
                    } else {
                        publish()
                    }
                }
            }
            "response.created" -> {
                assistantCommittedThisResponse.set(false)
                partialAssistant.set("")
                if (state.get() != State.ToolBusy) {
                    setTurn(Turn.Thinking, "Grok is thinking…")
                }
            }
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta",
            -> {
                val d = event.optString("delta", "")
                if (d.isNotEmpty()) {
                    partialAssistant.updateAndGet { (it ?: "") + d }
                    if (turn.get() != Turn.GrokSpeaking && state.get() != State.ToolBusy) {
                        turn.set(Turn.GrokSpeaking)
                        statusLine.set("Grok speaking…")
                    }
                    publish()
                }
            }
            "response.output_text.delta",
            "response.text.delta",
            -> {
                val d = event.optString("delta", "")
                if (d.isNotEmpty()) {
                    partialAssistant.updateAndGet { (it ?: "") + d }
                    publish()
                }
            }
            "response.output_audio.delta",
            "response.audio.delta",
            -> {
                // JSON transport fallback
                val b64 = event.optString("delta", "")
                    .ifBlank { event.optString("audio", "") }
                if (b64.isNotBlank()) {
                    runCatching {
                        playPcm(GrokAssistantVoiceClient.base64ToPcm16(b64))
                    }
                }
            }
            "response.output_audio_transcript.done",
            "response.audio_transcript.done",
            -> {
                val text = extractTranscript(event)
                    .ifBlank { partialAssistant.get().orEmpty() }
                commitAssistantIfNeeded(app, text)
            }
            "response.function_call_arguments.done" -> {
                val call = GrokAssistantVoiceTools.parseFunctionCallEvent(event) ?: return
                inFlightTools.incrementAndGet()
                setState(State.ToolBusy, Turn.ToolBusy, "Grok Build…")
                scope.launch {
                    val store = GrokAssistantStore(app)
                    val result = try {
                        withContext(Dispatchers.IO) {
                            GrokAssistantVoiceTools.execute(app, call, store)
                        }
                    } catch (e: Exception) {
                        GrokAssistantVoiceTools.FunctionResult(
                            callId = call.callId,
                            outputJson = JSONObject()
                                .put("ok", false)
                                .put("error", e.message ?: "tool_failed")
                                .toString(),
                        )
                    }
                    val client = clientRef.get()
                    if (client != null && running.get()) {
                        client.sendFunctionOutput(result)
                    }
                    val left = inFlightTools.decrementAndGet()
                    if (left <= 0) {
                        inFlightTools.set(0)
                        pendingResponseAfterTools.set(true)
                        // Brief pause so any prior TTS finishes (docs recommendation)
                        delaySoft(350)
                        if (running.get() && pendingResponseAfterTools.getAndSet(false)) {
                            clientRef.get()?.responseCreate()
                            setState(State.Live, Turn.Thinking, "Thinking…")
                        }
                    }
                }
            }
            "response.done" -> {
                val leftover = partialAssistant.get()
                commitAssistantIfNeeded(app, leftover)
                partialAssistant.set(null)
                if (state.get() != State.ToolBusy) {
                    setState(State.Live, Turn.Listening, "Listening…")
                }
            }
            "response.output_item.done" -> {
                // Prefer transcript.done / response.done for commits.
                // Still surface partials if present for UI consistency.
                publish()
            }
            "session.updated",
            "session.created",
            -> {
                if (state.get() == State.Connecting) {
                    setState(State.Live, Turn.Listening, "Listening…")
                }
            }
        }
    }

    private fun delaySoft(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun ensurePlaybackTrack() {
        if (audioTrackRef.get() != null) return
        val rate = GrokAssistantVoiceClient.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(rate / 5 * 2)
        val usage = if (Build.VERSION.SDK_INT >= 29) {
            AudioAttributes.USAGE_ASSISTANT
        } else {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrackRef.set(track)
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        ensurePlaybackTrack()
        val track = audioTrackRef.get() ?: return
        lastPlaybackActivityMs.set(SystemClock.uptimeMillis())
        // Always mute outbound mic while Grok's audio is playing.
        applyMicMutePolicy(clientRef.get())
        val rms = pcmRms(pcm)
        if (state.get() != State.ToolBusy) {
            if (turn.get() != Turn.GrokSpeaking) {
                turn.set(Turn.GrokSpeaking)
                statusLine.set("Grok speaking…")
                applyMicMutePolicy(clientRef.get())
                publish()
            }
        }
        noteLevel(rms)
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
        } catch (e: Exception) {
            Log.w(TAG, "playPcm: ${e.message}")
        }
    }

    private fun startMicCapture(client: GrokAssistantVoiceClient) {
        micJob.getAndSet(null)?.cancel()
        val job = scope.launch {
            val rate = GrokAssistantVoiceClient.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(rate / 10 * 2)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (e: SecurityException) {
                fail("Microphone permission denied")
                return@launch
            } catch (e: Exception) {
                fail(e.message ?: "mic_open_failed")
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                fail("Microphone not available")
                return@launch
            }
            audioRecordRef.set(record)
            record.startRecording()
            // ~100ms frames
            val frame = ByteArray((rate / 10) * 2)
            try {
                while (isActive && running.get()) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    val chunk = if (n == frame.size) frame else frame.copyOf(n)
                    val rms = pcmRms(chunk, n)
                    // Re-evaluate mute each frame (covers post-TTS hold → unmute).
                    applyMicMutePolicy(client)
                    val muted = micMuted.get() || !isMicSendAllowed()
                    val t = turn.get()
                    if (!muted &&
                        (t == Turn.UserSpeaking || t == Turn.Listening || t == Turn.Connecting)
                    ) {
                        noteLevel(if (t == Turn.UserSpeaking) max(rms, 0.08f) else rms * 0.55f)
                    } else if (t != Turn.GrokSpeaking) {
                        // Soft idle when thinking / tools / muted listening
                        noteLevel(rms * 0.08f)
                    }
                    // Never stream mic while Grok is talking (or hold-after-TTS).
                    if (muted) continue
                    if (useBinary.get()) {
                        client.sendBinary(chunk)
                    } else {
                        client.appendInputAudioBase64(
                            GrokAssistantVoiceClient.pcm16ToBase64(chunk),
                        )
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "mic loop: ${e.message}")
            } finally {
                runCatching {
                    record.stop()
                    record.release()
                }
                audioRecordRef.compareAndSet(record, null)
            }
        }
        micJob.set(job)
    }
}
