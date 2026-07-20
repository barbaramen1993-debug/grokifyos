package io.grokify.os.apps.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.grokify.os.apps.GrokAssistantMic
import io.grokify.os.apps.GrokAssistantVoiceClient
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Lean Companion Voice Agent session (PTT / continuous server_vad).
 *
 * Independent of [io.grokify.os.apps.GrokAssistantVoiceSession] so both panes
 * can exist without sharing that singleton. Uses [GrokAssistantVoiceClient]
 * for WebSocket + token mint; empty tools; lip-sync via [CompanionAmplitude].
 */
object CompanionVoiceSession {
    private const val TAG = "CompanionVoiceSession"
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Turn { Idle, Connecting, Listening, Thinking, Speaking, Error }

    data class Snapshot(
        val turn: Turn,
        val statusLine: String?,
        val partialUser: String?,
        val partialAssistant: String?,
        val mouth: Float,
        val level: Float,
    )

    interface Listener {
        fun onSnapshot(snap: Snapshot)
        fun onTranscriptCommitted(role: String, text: String)
        fun onError(message: String)
    }

    private const val LEVEL_PUBLISH_MS = 40L
    private const val WATCHDOG_MS = 250L
    private const val THINKING_TIMEOUT_MS = 35_000L
    private const val PLAYBACK_MUTE_HOLD_MS = 900L
    private const val PLAYBACK_DRAIN_PAD_MS = 220L
    /** Ignore residual speaker / room echo right after TTS frames. */
    private const val BARGE_IN_GUARD_MS = 700L
    private const val SPEAKING_IDLE_TIMEOUT_MS = 2_800L
    /** Post-TTS window where only loud speech counts as barge-in. */
    private const val POST_SPEAK_COOLDOWN_MS = 1_200L
    private const val POST_SPEAK_BARGE_RMS = 0.07f
    /** During Thinking, ignore soft ambient (Spotify bleed) as barge-in. */
    private const val THINKING_BARGE_RMS = 0.06f
    /** After connect / return-to-listen: hold send so residual ambient is not turn #1. */
    private const val LISTEN_OPEN_SETTLE_MS = 450L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerRef = AtomicReference<Listener?>(null)
    private val turn = AtomicReference(Turn.Idle)
    private val statusLine = AtomicReference<String?>(null)
    private val partialUser = AtomicReference<String?>(null)
    private val partialAssistant = AtomicReference<String?>(null)
    private val level = AtomicReference(0f)
    private val mouth = AtomicReference(0f)
    private val lastLevelPublishMs = AtomicLong(0L)
    private val lastPlaybackActivityMs = AtomicLong(0L)
    private val queuedPcmBytes = AtomicInteger(0)
    private val playbackDrainUntilMs = AtomicLong(0L)
    private val awaitingPlaybackDrain = AtomicBoolean(false)
    private val responsePcmBytes = AtomicInteger(0)
    private val lastLocalRms = AtomicReference(0f)
    private val micMuted = AtomicBoolean(false)
    private val phaseStartedMs = AtomicLong(0L)
    private val lastEventMs = AtomicLong(0L)
    private val lastEventType = AtomicReference<String?>(null)

    private val mouthSmoother = CompanionAmplitude.MouthSmoother()
    private val focusRequestRef = AtomicReference<AudioFocusRequest?>(null)
    private val clientRef = AtomicReference<GrokAssistantVoiceClient?>(null)
    private val micJob = AtomicReference<Job?>(null)
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private val audioRecordRef = AtomicReference<AudioRecord?>(null)
    private val running = AtomicBoolean(false)
    /** Bumps on every start/stop so in-flight start coroutines cannot resurrect a dead session. */
    private val startGeneration = AtomicInteger(0)
    /**
     * JSON base64 PCM (docs default / cookbook). Binary transport is optional and
     * strict — it was dropping Companion output audio on some devices/sessions.
     */
    private val useBinary = AtomicBoolean(false)
    private val assistantCommittedThisResponse = AtomicBoolean(false)
    /**
     * True between response.created and response.done / cancelled.
     * Prevents sending response.cancel when the server has nothing to cancel
     * (which yields "Cancellation failed: no active response found").
     */
    private val serverResponseActive = AtomicBoolean(false)
    private val lastUserCommitItemId = AtomicReference<String?>(null)
    private val lastUserCommitText = AtomicReference<String?>(null)
    private val lastUserCommitElapsedMs = AtomicLong(0L)
    private val postSpeakCooldownUntilMs = AtomicLong(0L)
    /** Wall-clock until which mic frames must not be sent (settle / post-connect). */
    private val listenOpenUntilMs = AtomicLong(0L)
    /** Session voice for HostAiClient TTS fallback when realtime audio is missing. */
    private val sessionVoiceId = AtomicReference("eve")
    private val sessionPreferDeviceTts = AtomicBoolean(false)
    private val firstAudioLogged = AtomicBoolean(false)

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(512)
    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CompanionVoicePlayback").apply { isDaemon = true }
    }
    private val playbackWorkerStarted = AtomicBoolean(false)

    private var appCtx: Context? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            tickWatchdog()
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    fun isActive(): Boolean {
        val t = turn.get()
        return running.get() && t != Turn.Idle && t != Turn.Error
    }

    /** Current turn for UI (e.g. restart-while-Connecting). */
    fun currentTurn(): Turn = turn.get()

    fun start(
        ctx: Context,
        instructions: String,
        voiceId: String,
        listener: Listener,
    ) {
        val app = ctx.applicationContext
        appCtx = app
        listenerRef.set(listener)

        val apiKey = HostApiKeyStore.getValue(app, ApiKeyIds.SPACEXAI)
        if (apiKey.isNullOrBlank()) {
            fail("Add SpaceXAI API key (vault) for Companion voice")
            return
        }
        if (running.get()) {
            Log.d(TAG, "start ignored — already running (turn=${turn.get()})")
            publish()
            return
        }

        val gen = startGeneration.incrementAndGet()
        running.set(true)

        mouthSmoother.reset()
        mouth.set(0f)
        level.set(0f)
        postSpeakCooldownUntilMs.set(0L)
        listenOpenUntilMs.set(0L)
        firstAudioLogged.set(false)
        val storePrefs = runCatching { CompanionStore(app) }.getOrNull()
        sessionVoiceId.set(voiceId.ifBlank { storePrefs?.voiceId ?: "eve" })
        sessionPreferDeviceTts.set(storePrefs?.preferDeviceTts == true)
        setTurn(Turn.Connecting, "Connecting Companion voice…")
        // Grab focus early so Spotify ducks before the first reply (not mid-PCM).
        requestPlaybackFocus()
        // Preempt wake loop so Companion can open the mic on first start.
        if (!GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)) {
            GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
            GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)
        }
        startWatchdog()

        val voice = sessionVoiceId.get().ifBlank { "eve" }
        val system = instructions.ifBlank { CompanionPrompts.DEFAULT_SYSTEM }

        scope.launch {
            try {
                fun stillThisStart(): Boolean =
                    running.get() && startGeneration.get() == gen

                if (!stillThisStart()) return@launch

                setTurn(Turn.Connecting, "Minting voice session…")
                val token = GrokAssistantVoiceClient.mintAuthToken(apiKey)
                if (!stillThisStart()) return@launch
                if (token.isBlank()) {
                    fail("Could not mint voice session token")
                    return@launch
                }

                setTurn(Turn.Connecting, "Opening voice socket…")
                val client = GrokAssistantVoiceClient(
                    onEvent = { event -> handleEvent(event) },
                    onBinaryAudio = { pcm -> playPcm(pcm) },
                    onState = { connected, detail ->
                        if (!connected && running.get() && startGeneration.get() == gen) {
                            val msg = detail ?: "disconnected"
                            val listener = listenerRef.get()
                            setTurn(Turn.Error, msg)
                            mainHandler.post { listener?.onError(msg) }
                            stopInternal()
                        }
                    },
                )
                if (!stillThisStart()) {
                    client.disconnect()
                    return@launch
                }
                clientRef.set(client)
                client.connect(token)

                // isConnected is true as soon as the WS object is created — wait for
                // onOpen via session.updated after sessionUpdate (isSessionReady).
                var waits = 0
                while (stillThisStart() && !client.isConnected && waits < 50) {
                    Thread.sleep(100)
                    waits++
                }
                if (!stillThisStart()) return@launch
                if (!client.isConnected) {
                    fail("Voice WebSocket connect timeout")
                    return@launch
                }

                setTurn(Turn.Connecting, "Configuring Companion voice…")
                // JSON transport = docs default + official cookbook (audible output).
                val updated = client.sessionUpdate(
                    instructions = system,
                    voice = voice,
                    tools = JSONArray(),
                    sampleRate = GrokAssistantVoiceClient.SAMPLE_RATE,
                    useBinaryAudio = false,
                    reasoningEffort = "none",
                )
                if (!updated) {
                    Log.w(TAG, "sessionUpdate send failed — will still wait briefly")
                }
                useBinary.set(false)

                // Cookbook: wait for session.updated before mic — audio sent earlier
                // can drop the first TTS reply entirely.
                waits = 0
                while (stillThisStart() && !client.isSessionReady && waits < 50) {
                    Thread.sleep(100)
                    waits++
                }
                if (!stillThisStart()) return@launch
                if (!client.isSessionReady) {
                    Log.w(TAG, "session.updated not seen — continuing (socket open)")
                }

                // Drop any pre-ready mic/ambient that never should have been committed.
                client.clearInputAudioBuffer()
                ensurePlaybackTrack()
                primePlaybackTrack()
                if (!stillThisStart()) return@launch
                // Settle before first listen so open-room / Spotify is not "user turn #1".
                listenOpenUntilMs.set(SystemClock.uptimeMillis() + LISTEN_OPEN_SETTLE_MS)
                markPhase(Turn.Listening, "Companion · listening")
                startMicCapture(client)
            } catch (e: Exception) {
                if (startGeneration.get() != gen) return@launch
                Log.e(TAG, "start failed", e)
                fail(e.message ?: "voice_start_failed")
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    /** Cancel in-flight response and clear local playback. */
    fun interrupt() {
        if (!running.get()) return
        cancelActiveResponse("interrupt")
        flushPlayback("interrupt")
        partialAssistant.set(null)
        responsePcmBytes.set(0)
        awaitingPlaybackDrain.set(false)
        mouthSmoother.reset()
        mouth.set(0f)
        markPhase(Turn.Listening, "Companion · listening")
    }

    /**
     * Send response.cancel only when we believe the server has an active response.
     * xAI auto-cancels on barge-in; a second cancel returns a hard error that used
     * to surface as a user-facing failure.
     */
    private fun cancelActiveResponse(reason: String): Boolean {
        if (!serverResponseActive.compareAndSet(true, false)) {
            Log.d(TAG, "skip response.cancel ($reason) — no active response")
            return false
        }
        Log.d(TAG, "response.cancel ($reason)")
        return clientRef.get()?.cancelResponse() == true
    }

    private fun stopInternal(clearListener: Boolean = true) {
        // Invalidate any in-flight start() so a late mint/connect cannot restart us.
        startGeneration.incrementAndGet()
        running.set(false)
        stopWatchdog()
        assistantCommittedThisResponse.set(false)
        serverResponseActive.set(false)
        lastUserCommitItemId.set(null)
        lastUserCommitText.set(null)
        lastUserCommitElapsedMs.set(0L)
        postSpeakCooldownUntilMs.set(0L)
        listenOpenUntilMs.set(0L)
        firstAudioLogged.set(false)
        awaitingPlaybackDrain.set(false)
        micMuted.set(false)
        lastPlaybackActivityMs.set(0L)
        queuedPcmBytes.set(0)
        responsePcmBytes.set(0)
        playbackDrainUntilMs.set(0L)
        lastLocalRms.set(0f)
        phaseStartedMs.set(0L)
        lastEventType.set(null)
        playbackQueue.clear()
        playbackQueue.offer(ByteArray(0))
        micJob.getAndSet(null)?.cancel()
        runCatching { audioRecordRef.getAndSet(null)?.release() }
        runCatching {
            audioTrackRef.getAndSet(null)?.let {
                it.pause()
                it.flush()
                it.release()
            }
        }
        abandonPlaybackFocus()
        clientRef.getAndSet(null)?.disconnect()
        GrokAssistantMic.release(GrokAssistantMic.Owner.Voice)
        partialUser.set(null)
        partialAssistant.set(null)
        level.set(0f)
        mouthSmoother.reset()
        mouth.set(0f)
        // Error path publishes Turn.Error separately; normal stop → Idle.
        if (turn.get() != Turn.Error) {
            setTurn(Turn.Idle, null)
        } else {
            // Drop to Idle after resources are gone so isActive() is false.
            turn.set(Turn.Idle)
            statusLine.set(null)
        }
        if (clearListener) {
            listenerRef.set(null)
            appCtx = null
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun markPhase(t: Turn, line: String?) {
        phaseStartedMs.set(SystemClock.uptimeMillis())
        turn.set(t)
        if (line != null) statusLine.set(line)
        applyMicMutePolicy(clientRef.get())
        publish()
    }

    private fun setTurn(t: Turn, line: String?) {
        turn.set(t)
        statusLine.set(line)
        applyMicMutePolicy(clientRef.get())
        publish()
    }

    private fun phaseElapsedMs(): Long {
        val start = phaseStartedMs.get()
        if (start <= 0L) return 0L
        return SystemClock.uptimeMillis() - start
    }

    private fun noteServerEvent(type: String) {
        lastEventMs.set(SystemClock.uptimeMillis())
        lastEventType.set(type)
    }

    private fun tickWatchdog() {
        if (!running.get()) return
        tryFinishPlaybackDrain()
        applyMicMutePolicy(clientRef.get())

        when (turn.get()) {
            Turn.Thinking -> {
                val elapsed = phaseElapsedMs()
                val sec = (elapsed / 1000L).toInt()
                val line = if (sec > 0) "Companion · thinking · ${sec}s" else "Companion · thinking"
                if (statusLine.get() != line) {
                    statusLine.set(line)
                    publish()
                }
                if (elapsed >= THINKING_TIMEOUT_MS) {
                    Log.w(TAG, "Thinking timeout ${sec}s lastEvent=${lastEventType.get()}")
                    cancelActiveResponse("thinking timeout")
                    commitPartialAssistantOnInterrupt("thinking timeout")
                    markPhase(Turn.Listening, "Timed out · Companion listening")
                    mainHandler.post {
                        listenerRef.get()?.onError(
                            "Companion voice timed out after ${sec}s (still connected)",
                        )
                    }
                }
            }
            Turn.Speaking -> {
                if (statusLine.get() != "Companion · speaking") {
                    statusLine.set("Companion · speaking")
                    publish()
                }
                maybeUnstickSpeaking()
            }
            else -> Unit
        }
    }

    private fun isMicSendAllowed(): Boolean {
        // Strict half-duplex: only stream mic while Listening. Thinking used to
        // still send ambient (Spotify bleed) *before* response.created, which
        // cancelled the first assistant turn — second turn often "worked" after
        // the room/buffer settled. Interrupt button covers barge-in.
        if (turn.get() != Turn.Listening) return false
        if (serverResponseActive.get()) return false
        if (awaitingPlaybackDrain.get()) return false
        if (hasLocalPlaybackRemaining()) return false
        val lastPlay = lastPlaybackActivityMs.get()
        if (lastPlay > 0L) {
            val age = SystemClock.uptimeMillis() - lastPlay
            if (age < PLAYBACK_MUTE_HOLD_MS) return false
        }
        if (SystemClock.uptimeMillis() < postSpeakCooldownUntilMs.get()) return false
        // Brief settle after socket ready so open-mic ambient is not the first "turn".
        if (SystemClock.uptimeMillis() < listenOpenUntilMs.get()) return false
        return true
    }

    private fun inPostSpeakCooldown(): Boolean =
        SystemClock.uptimeMillis() < postSpeakCooldownUntilMs.get()

    private fun pcmBytesToMs(bytes: Int): Long {
        if (bytes <= 0) return 0L
        val rate = GrokAssistantVoiceClient.SAMPLE_RATE
        return (bytes.toLong() * 1000L) / (rate * 2L)
    }

    private fun playbackRemainingMs(): Long {
        val now = SystemClock.uptimeMillis()
        val until = playbackDrainUntilMs.get()
        val hardwareLeft = (until - now).coerceAtLeast(0L)
        val queuedLeft = pcmBytesToMs(queuedPcmBytes.get())
        val left = max(hardwareLeft, queuedLeft)
        return if (left > 0L) left + PLAYBACK_DRAIN_PAD_MS else 0L
    }

    private fun hasLocalPlaybackRemaining(): Boolean =
        queuedPcmBytes.get() > 0 || playbackRemainingMs() > 0L

    private fun isPlaybackActive(): Boolean {
        if (queuedPcmBytes.get() > 0) return true
        if (playbackRemainingMs() > 0L) return true
        val lastPlay = lastPlaybackActivityMs.get()
        if (lastPlay > 0L) {
            val age = SystemClock.uptimeMillis() - lastPlay
            if (age < BARGE_IN_GUARD_MS) return true
        }
        return false
    }

    private fun shouldHonorBargeIn(): Boolean {
        if (isPlaybackActive()) return false
        // Never barge while the server is still generating a reply (mic is muted
        // anyway; this blocks residual speech_started races).
        if (serverResponseActive.get()) return false
        return when (turn.get()) {
            Turn.Speaking, Turn.Idle, Turn.Error, Turn.Connecting -> false
            Turn.Thinking -> lastLocalRms.get() >= THINKING_BARGE_RMS
            Turn.Listening -> {
                if (inPostSpeakCooldown()) {
                    lastLocalRms.get() >= POST_SPEAK_BARGE_RMS
                } else {
                    true
                }
            }
        }
    }

    private fun flushPlayback(reason: String) {
        Log.d(TAG, "flushPlayback: $reason")
        playbackQueue.clear()
        queuedPcmBytes.set(0)
        playbackDrainUntilMs.set(0L)
        awaitingPlaybackDrain.set(false)
        lastPlaybackActivityMs.set(0L)
        runCatching {
            audioTrackRef.get()?.let {
                it.pause()
                it.flush()
            }
        }
    }

    private fun extendPlaybackDeadline(writtenBytes: Int) {
        if (writtenBytes <= 0) return
        val addMs = pcmBytesToMs(writtenBytes) + PLAYBACK_DRAIN_PAD_MS
        val now = SystemClock.uptimeMillis()
        while (true) {
            val prev = playbackDrainUntilMs.get()
            val base = max(now, prev)
            if (playbackDrainUntilMs.compareAndSet(prev, base + addMs)) break
        }
        lastPlaybackActivityMs.set(now)
    }

    private fun markResponseAudioFinished() {
        awaitingPlaybackDrain.set(true)
        val hadAudio = responsePcmBytes.get() > 0 || hasLocalPlaybackRemaining()
        if (hadAudio && (turn.get() == Turn.Speaking || hasLocalPlaybackRemaining())) {
            markPhase(Turn.Speaking, "Companion · speaking")
        }
        applyMicMutePolicy(clientRef.get())
        tryFinishPlaybackDrain()
    }

    private fun tryFinishPlaybackDrain() {
        if (!running.get()) return
        if (!awaitingPlaybackDrain.get()) return
        if (queuedPcmBytes.get() > 0) return
        if (playbackRemainingMs() > 0L) return
        if (!awaitingPlaybackDrain.compareAndSet(true, false)) return
        returnToListening("playback drained")
    }

    private fun returnToListening(reason: String) {
        Log.d(TAG, "returnToListening: $reason")
        awaitingPlaybackDrain.set(false)
        partialAssistant.set(null)
        // Brief cooldown so speaker ring-out does not open a phantom user turn.
        val now = SystemClock.uptimeMillis()
        postSpeakCooldownUntilMs.set(now + POST_SPEAK_COOLDOWN_MS)
        // Keep focus a bit longer so turn 2 TTS is not racing Spotify unduck.
        // (Full abandon happens on stop; mid-session we re-request on playPcm.)
        clientRef.get()?.clearInputAudioBuffer()
        mouthSmoother.reset()
        mouth.set(0f)
        markPhase(Turn.Listening, "Companion · listening")
    }

    /**
     * Persist whatever the assistant already said so chat history does not lose
     * mid-cancelled replies (the live partial strip alone is ephemeral).
     */
    private fun commitPartialAssistantOnInterrupt(reason: String) {
        val body = partialAssistant.get()?.trim().orEmpty()
        if (body.isEmpty()) return
        Log.d(TAG, "commit partial assistant on $reason (${body.length} chars)")
        commitAssistantIfNeeded(body)
    }

    /**
     * When realtime returns transcript/text but zero PCM frames (or audio was
     * dropped), speak the reply via HostAiClient so the user still hears it.
     */
    private fun maybeSpeakTextFallback(text: String?) {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return
        if (responsePcmBytes.get() > 0 || hasLocalPlaybackRemaining()) return
        val ctx = appCtx ?: return
        if (!running.get()) return
        Log.w(TAG, "no realtime audio — TTS fallback (${body.length} chars)")
        scope.launch {
            try {
                markPhase(Turn.Speaking, "Companion · speaking (TTS)")
                requestPlaybackFocus()
                val opts = JSONObject()
                    .put("voice_id", sessionVoiceId.get())
                    .put("prefer_device", sessionPreferDeviceTts.get())
                    .put("language", "en")
                    .put("wait", true)
                    .toString()
                val raw = HostAiClient.speak(ctx, body, opts)
                val ok = runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
                if (!ok) {
                    val err = runCatching {
                        JSONObject(raw).optString("error", "speak_failed")
                    }.getOrDefault("speak_failed")
                    Log.w(TAG, "TTS fallback failed: $err")
                    mainHandler.post {
                        listenerRef.get()?.onError("TTS: ${err.take(100)}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TTS fallback error: ${e.message}")
            } finally {
                if (running.get()) {
                    returnToListening("tts fallback done")
                }
            }
        }
    }

    private fun maybeUnstickSpeaking() {
        if (turn.get() != Turn.Speaking) return
        // Server still generating (transcript-only so far) — do not abort; wait for
        // response.done / audio / cancel. Unstick used to wipe partials before commit.
        if (serverResponseActive.get()) return
        if (queuedPcmBytes.get() > 0) return
        if (playbackRemainingMs() > 0L) return
        val now = SystemClock.uptimeMillis()
        val lastPlay = lastPlaybackActivityMs.get()
        val idleFromPlay = if (lastPlay > 0L) now - lastPlay else Long.MAX_VALUE
        val idleFromPhase = now - phaseStartedMs.get()
        val idle = if (responsePcmBytes.get() == 0) idleFromPhase else idleFromPlay
        if (idle < SPEAKING_IDLE_TIMEOUT_MS) return
        if (awaitingPlaybackDrain.get()) {
            tryFinishPlaybackDrain()
            if (turn.get() != Turn.Speaking) return
        }
        Log.w(TAG, "unstick Speaking idle=${idle}ms pcm=${responsePcmBytes.get()}")
        commitPartialAssistantOnInterrupt("speaking idle timeout")
        returnToListening("speaking idle timeout")
    }

    private fun applyMicMutePolicy(client: GrokAssistantVoiceClient?) {
        val wantMute = !isMicSendAllowed()
        val wasMuted = micMuted.getAndSet(wantMute)
        if (wantMute && !wasMuted) {
            val t = turn.get()
            // Clear server input on Thinking/Speaking so ambient already buffered
            // cannot cancel the in-flight first reply.
            val safeToClear = t == Turn.Speaking ||
                t == Turn.Thinking ||
                awaitingPlaybackDrain.get() ||
                hasLocalPlaybackRemaining() ||
                serverResponseActive.get()
            if (safeToClear) client?.clearInputAudioBuffer()
            Log.d(TAG, "mic muted (turn=$t clear=$safeToClear)")
        } else if (!wantMute && wasMuted) {
            Log.d(TAG, "mic unmuted (turn=${turn.get()})")
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        val listener = listenerRef.get()
        setTurn(Turn.Error, msg)
        mainHandler.post {
            listener?.onError(msg)
            listener?.onSnapshot(
                Snapshot(
                    turn = Turn.Error,
                    statusLine = msg,
                    partialUser = null,
                    partialAssistant = null,
                    mouth = 0f,
                    level = 0f,
                ),
            )
        }
        // Keep listener long enough for the Error snapshot/onError above; clear after stop.
        stopInternal(clearListener = false)
        listenerRef.set(null)
        appCtx = null
    }

    private fun snapshot(): Snapshot = Snapshot(
        turn = turn.get(),
        statusLine = statusLine.get(),
        partialUser = partialUser.get(),
        partialAssistant = partialAssistant.get(),
        mouth = mouth.get(),
        level = level.get(),
    )

    private fun publish() {
        val snap = snapshot()
        mainHandler.post {
            listenerRef.get()?.onSnapshot(snap)
        }
    }

    private fun notifyTranscript(role: String, text: String) {
        mainHandler.post {
            listenerRef.get()?.onTranscriptCommitted(role, text)
        }
    }

    private fun noteLevel(sample: Float, force: Boolean = false) {
        val clamped = sample.coerceIn(0f, 1f)
        val prev = level.get()
        val smoothed = if (clamped >= prev) {
            prev * 0.25f + clamped * 0.75f
        } else {
            prev * 0.82f + clamped * 0.18f
        }
        level.set(smoothed)
        val now = SystemClock.uptimeMillis()
        val last = lastLevelPublishMs.get()
        if (force || now - last >= LEVEL_PUBLISH_MS) {
            if (lastLevelPublishMs.compareAndSet(last, now) || force) {
                lastLevelPublishMs.set(now)
                publish()
            }
        }
    }

    private fun noteMouthFromPcm(pcm: ByteArray) {
        val rms = CompanionAmplitude.rmsPcm16Bytes(pcm)
        mouth.set(mouthSmoother.next(rms))
        noteLevel(min(1f, rms * 3.5f))
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

    private fun extractUserItemId(event: JSONObject): String? {
        val direct = event.optString("item_id", "").trim()
        if (direct.isNotEmpty()) return direct
        val item = event.optJSONObject("item") ?: return null
        return item.optString("id", "").trim().ifEmpty { null }
    }

    private fun commitAssistantIfNeeded(text: String?) {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return
        if (!assistantCommittedThisResponse.compareAndSet(false, true)) return
        partialAssistant.set(null)
        notifyTranscript("assistant", body)
        publish()
    }

    private fun commitUserIfNeeded(text: String?, itemId: String? = null): Boolean {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return false
        val id = itemId?.trim().orEmpty()
        if (id.isNotEmpty() && id == lastUserCommitItemId.get()) return false
        val prevText = lastUserCommitText.get()
        val prevAt = lastUserCommitElapsedMs.get()
        val now = SystemClock.elapsedRealtime()
        if (prevText != null &&
            prevText.equals(body, ignoreCase = true) &&
            now - prevAt < 5_000L
        ) {
            return false
        }
        if (id.isNotEmpty()) lastUserCommitItemId.set(id)
        lastUserCommitText.set(body)
        lastUserCommitElapsedMs.set(now)
        partialUser.set(null)
        notifyTranscript("user", body)
        publish()
        return true
    }

    private fun handleEvent(event: JSONObject) {
        if (!running.get()) return
        val type = event.optString("type")
        noteServerEvent(type)
        when (type) {
            "error" -> {
                val msg = event.optString("error", "")
                    .ifBlank { event.optJSONObject("error")?.optString("message").orEmpty() }
                    .ifBlank { event.optJSONObject("error")?.toString().orEmpty() }
                    .ifBlank { event.optString("message", "voice_error") }
                // Spurious cancel races are common (server already auto-cancelled).
                // Surfacing them aborted the UI (voiceActive=false) while the WS stayed live.
                if (GrokAssistantVoiceClient.isBenignRealtimeCancelError(msg)) {
                    Log.d(TAG, "ignoring benign cancel error: $msg")
                    serverResponseActive.set(false)
                    return
                }
                Log.e(TAG, "server error: $msg")
                mainHandler.post { listenerRef.get()?.onError(msg) }
                val speakingWithAudio = turn.get() == Turn.Speaking &&
                    (hasLocalPlaybackRemaining() || awaitingPlaybackDrain.get())
                if (speakingWithAudio) {
                    Log.w(TAG, "server error during TTS — keep playing: ${msg.take(80)}")
                    statusLine.set(msg.take(80))
                    publish()
                } else if (turn.get() == Turn.Thinking || turn.get() == Turn.Speaking) {
                    returnToListening("server error: ${msg.take(80)}")
                } else {
                    statusLine.set(msg.take(120))
                    publish()
                }
            }
            "response.cancelled",
            "response.canceled",
            -> {
                serverResponseActive.set(false)
                // Keep whatever text already streamed so chat review is not empty.
                commitPartialAssistantOnInterrupt("response cancelled")
                flushPlayback("response cancelled")
                partialAssistant.set(null)
                responsePcmBytes.set(0)
                firstAudioLogged.set(false)
                awaitingPlaybackDrain.set(false)
                returnToListening("response cancelled")
            }
            "input_audio_buffer.speech_started" -> {
                if (!shouldHonorBargeIn()) {
                    Log.d(
                        TAG,
                        "ignore speech_started turn=${turn.get()} " +
                            "serverActive=${serverResponseActive.get()} " +
                            "rms=${lastLocalRms.get()} remMs=${playbackRemainingMs()}",
                    )
                    if (isPlaybackActive() ||
                        turn.get() == Turn.Speaking ||
                        serverResponseActive.get()
                    ) {
                        clientRef.get()?.clearInputAudioBuffer()
                    }
                    return
                }
                val priorTurn = turn.get()
                // Thinking + speech_started usually means premature VAD split of the
                // *user* turn (they paused mid-thought). Drop half assistant text.
                // Listening + speech_started is a true interrupt of a finished reply.
                val continuingUserTurn = priorTurn == Turn.Thinking
                val asstSoFar = partialAssistant.get()?.trim().orEmpty()
                if (asstSoFar.isNotEmpty() && !continuingUserTurn) {
                    commitPartialAssistantOnInterrupt("barge-in")
                } else if (asstSoFar.isNotEmpty() && continuingUserTurn) {
                    Log.d(TAG, "drop partial assistant on user-turn continue")
                    partialAssistant.set(null)
                    assistantCommittedThisResponse.set(false)
                }
                // Server often auto-cancels on speech_started; only cancel if still active.
                cancelActiveResponse(if (continuingUserTurn) "user-turn continue" else "barge-in")
                flushPlayback(if (continuingUserTurn) "user-turn continue" else "barge-in")
                if (!continuingUserTurn) {
                    partialUser.set("")
                }
                partialAssistant.set(null)
                assistantCommittedThisResponse.set(false)
                responsePcmBytes.set(0)
                firstAudioLogged.set(false)
                awaitingPlaybackDrain.set(false)
                if (!continuingUserTurn) {
                    lastUserCommitItemId.set(null)
                    lastUserCommitText.set(null)
                    lastUserCommitElapsedMs.set(0L)
                } else {
                    lastUserCommitItemId.set(null)
                }
                markPhase(Turn.Listening, "Hearing you…")
            }
            "input_audio_buffer.speech_stopped" -> {
                // Mute + clear immediately so ambient after the user stops talking
                // cannot open a cancel race before response.created.
                markPhase(Turn.Thinking, "Companion · thinking")
                clientRef.get()?.clearInputAudioBuffer()
            }
            "conversation.item.input_audio_transcription.updated" -> {
                val text = extractTranscript(event)
                if (text.isNotEmpty()) {
                    partialUser.set(text)
                    if (turn.get() == Turn.Listening) {
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
                    commitUserIfNeeded(text, itemId = extractUserItemId(event))
                    if (turn.get() == Turn.Listening || turn.get() == Turn.Thinking) {
                        markPhase(Turn.Thinking, "Companion · thinking")
                    } else {
                        publish()
                    }
                }
            }
            "response.created",
            "response.output_item.added",
            -> {
                if (type == "response.created") {
                    assistantCommittedThisResponse.set(false)
                    responsePcmBytes.set(0)
                    firstAudioLogged.set(false)
                    partialAssistant.set("")
                    serverResponseActive.set(true)
                    // Mute mic immediately so ambient audio cannot cancel this turn.
                    applyMicMutePolicy(clientRef.get())
                }
                if (turn.get() != Turn.Speaking) {
                    markPhase(Turn.Thinking, "Companion · thinking")
                } else {
                    publish()
                }
            }
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta",
            -> {
                val d = event.optString("delta", "")
                if (d.isNotEmpty()) {
                    partialAssistant.updateAndGet { (it ?: "") + d }
                    // Only flip to Speaking once PCM is flowing — transcript can arrive
                    // seconds before audio; premature Speaking + idle unstick wiped chat.
                    if (responsePcmBytes.get() > 0 && turn.get() != Turn.Speaking) {
                        markPhase(Turn.Speaking, "Companion · speaking")
                    } else if (turn.get() != Turn.Speaking) {
                        if (turn.get() != Turn.Thinking) {
                            markPhase(Turn.Thinking, "Companion · thinking")
                        } else {
                            publish()
                        }
                    } else {
                        publish()
                    }
                }
            }
            "response.output_text.delta",
            "response.text.delta",
            -> {
                val d = event.optString("delta", "")
                if (d.isNotEmpty()) {
                    partialAssistant.updateAndGet { (it ?: "") + d }
                    if (turn.get() != Turn.Speaking && turn.get() != Turn.Thinking) {
                        markPhase(Turn.Thinking, "Companion · thinking")
                    } else {
                        publish()
                    }
                }
            }
            "response.output_audio.delta",
            "response.audio.delta",
            -> {
                val b64 = event.optString("delta", "")
                    .ifBlank { event.optString("audio", "") }
                if (b64.isNotBlank()) {
                    runCatching {
                        val pcm = GrokAssistantVoiceClient.base64ToPcm16(b64)
                        if (firstAudioLogged.compareAndSet(false, true)) {
                            Log.d(TAG, "first output_audio.delta bytes=${pcm.size}")
                        }
                        playPcm(pcm)
                    }.onFailure { e ->
                        Log.w(TAG, "output_audio.delta decode failed: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "output_audio.delta empty payload")
                }
            }
            "response.output_audio.done",
            "response.audio.done",
            -> {
                Log.d(TAG, "output_audio.done pcmBytes=${responsePcmBytes.get()}")
            }
            "response.output_audio_transcript.done",
            "response.audio_transcript.done",
            "response.output_text.done",
            "response.text.done",
            -> {
                val text = extractTranscript(event)
                    .ifBlank { partialAssistant.get().orEmpty() }
                commitAssistantIfNeeded(text)
            }
            "response.done" -> {
                serverResponseActive.set(false)
                val leftover = partialAssistant.get()
                    ?.trim()
                    .orEmpty()
                    .ifBlank {
                        // Some payloads nest final text only on response.done.
                        extractTranscript(event)
                    }
                commitAssistantIfNeeded(leftover)
                Log.d(
                    TAG,
                    "response.done pcmBytes=${responsePcmBytes.get()} " +
                        "queued=${queuedPcmBytes.get()} textLen=${leftover.length}",
                )
                // Transcript-only replies: speak via host TTS so voice is never silent.
                if (leftover.isNotEmpty() &&
                    responsePcmBytes.get() == 0 &&
                    !hasLocalPlaybackRemaining()
                ) {
                    maybeSpeakTextFallback(leftover)
                } else {
                    markResponseAudioFinished()
                }
            }
            "session.updated",
            "session.created",
            -> {
                if (turn.get() == Turn.Connecting) {
                    markPhase(Turn.Listening, "Companion · listening")
                }
            }
            else -> Log.d(TAG, "voice event: $type")
        }
    }

    private fun ensurePlaybackWorker() {
        if (!playbackWorkerStarted.compareAndSet(false, true)) return
        playbackExecutor.execute {
            while (true) {
                val pcm = try {
                    playbackQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (pcm.isEmpty()) continue
                writePcmBlocking(pcm)
            }
        }
    }

    private fun speechAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun requestPlaybackFocus() {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (focusRequestRef.get() != null) return
        // Keep device out of call routing (earpiece) while Companion speaks.
        runCatching {
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
        }
        val attrs = speechAudioAttributes()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { /* keep playing voice reply */ }
            .build()
        val result = am.requestAudioFocus(req)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequestRef.set(req)
            return
        }
        Log.w(TAG, "audio focus denied ($result) — trying exclusive transient")
        val exclusive = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        if (am.requestAudioFocus(exclusive) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequestRef.set(exclusive)
        } else {
            Log.w(TAG, "audio focus still denied — playback may be silent under Spotify")
        }
    }

    private fun abandonPlaybackFocus() {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusRequestRef.getAndSet(null)?.let { req ->
            runCatching { am.abandonAudioFocusRequest(req) }
        }
    }

    private fun ensurePlaybackTrack() {
        val existing = audioTrackRef.get()
        if (existing != null) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return
        }
        requestPlaybackFocus()
        val rate = GrokAssistantVoiceClient.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(rate / 2 * 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(speechAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        runCatching { track.setVolume(1f) }
        track.play()
        audioTrackRef.set(track)
        Log.d(TAG, "AudioTrack started rate=$rate buf=${minBuf * 4}")
    }

    /** Write a short silence burst so the first real PCM is not lost to cold start. */
    private fun primePlaybackTrack() {
        val track = audioTrackRef.get() ?: return
        val rate = GrokAssistantVoiceClient.SAMPLE_RATE
        // ~40ms of zeros at 24k mono PCM16.
        val silence = ByteArray((rate / 25) * 2)
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            if (Build.VERSION.SDK_INT >= 23) {
                track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(silence, 0, silence.size)
            }
        }.onFailure { e ->
            Log.w(TAG, "primePlaybackTrack: ${e.message}")
        }
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty() || !running.get()) return
        responsePcmBytes.addAndGet(pcm.size)
        ensurePlaybackWorker()
        ensurePlaybackTrack()
        requestPlaybackFocus()
        queuedPcmBytes.addAndGet(pcm.size)
        lastPlaybackActivityMs.set(SystemClock.uptimeMillis())
        if (turn.get() != Turn.Speaking) {
            markPhase(Turn.Speaking, "Companion · speaking")
        } else {
            applyMicMutePolicy(clientRef.get())
        }
        noteMouthFromPcm(pcm)
        if (!playbackQueue.offer(pcm)) {
            Log.w(TAG, "playback queue full — dropping ${pcm.size}b")
            queuedPcmBytes.updateAndGet { (it - pcm.size).coerceAtLeast(0) }
        }
    }

    private fun writePcmBlocking(pcm: ByteArray) {
        if (!running.get()) {
            queuedPcmBytes.updateAndGet { (it - pcm.size).coerceAtLeast(0) }
            return
        }
        ensurePlaybackTrack()
        val track = audioTrackRef.get() ?: run {
            queuedPcmBytes.updateAndGet { (it - pcm.size).coerceAtLeast(0) }
            return
        }
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            var offset = 0
            var writtenTotal = 0
            while (offset < pcm.size && running.get()) {
                val written = if (Build.VERSION.SDK_INT >= 23) {
                    track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                } else {
                    track.write(pcm, offset, pcm.size - offset)
                }
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write error $written")
                    break
                }
                if (written == 0) {
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                offset += written
                writtenTotal += written
            }
            if (writtenTotal > 0) extendPlaybackDeadline(writtenTotal)
        } catch (e: Exception) {
            Log.w(TAG, "writePcm: ${e.message}")
        } finally {
            queuedPcmBytes.updateAndGet { (it - pcm.size).coerceAtLeast(0) }
            applyMicMutePolicy(clientRef.get())
            tryFinishPlaybackDrain()
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
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
            val frame = ByteArray((rate / 10) * 2)
            try {
                while (isActive && running.get()) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    val chunk = if (n == frame.size) frame else frame.copyOf(n)
                    val rms = CompanionAmplitude.rmsPcm16Bytes(chunk)
                    lastLocalRms.set(rms)
                    applyMicMutePolicy(client)
                    val muted = micMuted.get() || !isMicSendAllowed()
                    val t = turn.get()
                    if (!muted && (t == Turn.Listening || t == Turn.Thinking)) {
                        noteLevel(
                            when (t) {
                                Turn.Thinking -> max(rms * 0.7f, 0.04f)
                                else -> rms * 0.55f
                            },
                        )
                    } else if (t != Turn.Speaking) {
                        noteLevel(rms * 0.08f)
                    }
                    if (muted) continue
                    if (hasLocalPlaybackRemaining()) continue
                    // Soft gain so quiet mics still hit server VAD / ASR.
                    val boosted = GrokAssistantVoiceClient.softGainPcm16(chunk)
                    if (useBinary.get()) {
                        client.sendBinary(boosted)
                    } else {
                        client.appendInputAudioBase64(
                            GrokAssistantVoiceClient.pcm16ToBase64(boosted),
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
