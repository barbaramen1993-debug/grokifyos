package io.grokify.os.apps

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
    /**
     * Soft post-TTS mute after local drain finishes. Drain math covers the
     * AudioTrack tail; this hold covers room ring-out before we stream mic again.
     * Hard AudioRecord stop/start ("mix mute") was removed — it broke handoff.
     */
    private const val PLAYBACK_MUTE_HOLD_MS = 550L
    /** Extra cushion after estimated drain before treating playback as idle. */
    private const val PLAYBACK_DRAIN_PAD_MS = 200L
    /** How often to refresh elapsed status / check stalls / drain. */
    private const val WATCHDOG_MS = 200L
    /** No server progress while Thinking → surface “still working”. */
    private const val THINKING_STALL_MS = 8_000L
    /** Hard cancel stuck Thinking (not Build tools). Empty commits used to hang forever. */
    private const val THINKING_TIMEOUT_MS = 35_000L
    /**
     * Ignore server speech_started while assistant audio is still playing/queued.
     * Speaker bleed + short mute hold used to flush AudioTrack mid-utterance.
     */
    private const val BARGE_IN_GUARD_MS = 700L
    /**
     * After returning to Listening, keep ignoring weak speech_started and keep
     * the mic muted briefly. TTS room echo was starting a second empty turn
     * (weather answer → “couldn't look that up”).
     */
    private const val POST_SPEAK_COOLDOWN_MS = 1_400L
    /** Local RMS required to honor speech_started during post-speak cooldown. */
    private const val POST_SPEAK_BARGE_RMS = 0.07f
    /**
     * After response.done, if local speaker path is idle this long, force Listening.
     * Only applies once the server has finished generating (awaitingPlaybackDrain).
     */
    private const val SPEAKING_DRAIN_IDLE_MS = 3_500L
    /**
     * While the server is still generating (no response.done yet), allow long gaps
     * between PCM frames / pre-audio transcript. The old 2.8s timer fired during
     * normal lag → abandoned focus, wiped live captions, and cancelled TTS.
     */
    private const val SPEAKING_GENERATE_STALL_MS = 18_000L
    /** Ignore unstick while server events are still arriving. */
    private const val SPEAKING_EVENT_GRACE_MS = 4_000L
    /**
     * Local RMS (0..1) required to treat speech_started during Thinking as a real
     * barge-in (user still finishing after an early VAD stop), not room noise.
     */
    private const val THINKING_BARGE_RMS = 0.045f

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
    /** Bytes accepted into the playback queue but not yet written to AudioTrack. */
    private val queuedPcmBytes = AtomicInteger(0)
    /**
     * Wall-clock (uptimeMillis) when local speaker audio is expected to finish.
     * Extended on every successful PCM write; used for mute hold + barge-in guard.
     */
    private val playbackDrainUntilMs = AtomicLong(0L)
    /** True after response.done until local PCM has finished playing. */
    private val awaitingPlaybackDrain = AtomicBoolean(false)
    /** PCM bytes received for the current assistant response (binary or JSON delta). */
    private val responsePcmBytes = AtomicInteger(0)
    /** Most recent local mic RMS (even when muted) for diagnostics. */
    private val lastLocalRms = AtomicReference(0f)
    /** Outbound mic muted — we still read AudioRecord (keep hardware warm) but send nothing. */
    private val micMuted = AtomicBoolean(false)
    private val focusRequestRef = AtomicReference<AudioFocusRequest?>(null)
    /**
     * Wall-clock until which post-TTS cooldown applies (mute + ignore weak VAD).
     * Set on returnToListening; cleared by a strong barge-in or timeout.
     */
    private val postSpeakCooldownUntilMs = AtomicLong(0L)

    /**
     * True when we successfully routed capture to a Bluetooth headset mic (HFP/SCO).
     * Phone mic is used otherwise — even if A2DP headphones are connected for audio out.
     */
    private val btHeadsetMicActive = AtomicBoolean(false)
    /** True when a Bluetooth output (A2DP/SCO/BLE) is preferred for replies. */
    private val btOutputPreferred = AtomicBoolean(false)
    private val preferredInputDevice = AtomicReference<AudioDeviceInfo?>(null)
    private val preferredOutputDevice = AtomicReference<AudioDeviceInfo?>(null)
    private val headsetLabel = AtomicReference<String?>(null)
    private val scoConnected = AtomicBoolean(false)
    private val scoLatchRef = AtomicReference<CountDownLatch?>(null)
    private var scoReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    /** Avoid thrashing openBestMic on every device callback while already on that route. */
    private val lastRouteFingerprint = AtomicReference<String?>(null)

    private val clientRef = AtomicReference<GrokAssistantVoiceClient?>(null)
    private val micJob = AtomicReference<Job?>(null)
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private val audioRecordRef = AtomicReference<AudioRecord?>(null)
    private val running = AtomicBoolean(false)
    /** Prefer JSON transport (xAI docs default + cookbook). Binary is optional. */
    private val useBinary = AtomicBoolean(false)
    private val assistantCommittedThisResponse = AtomicBoolean(false)
    /**
     * True between response.created and response.done / cancelled.
     * Prevents sending response.cancel when the server has nothing to cancel
     * (which yields "Cancellation failed: no active response found" and used
     * to abort local TTS via the error handler).
     */
    private val serverResponseActive = AtomicBoolean(false)
    /** Dedup user transcript commits (server may send both .completed and .done). */
    private val lastUserCommitItemId = AtomicReference<String?>(null)
    private val lastUserCommitText = AtomicReference<String?>(null)
    private val lastUserCommitElapsedMs = AtomicLong(0L)

    /** Pending function outputs waiting for playback drain before response.create. */
    private val pendingResponseAfterTools = AtomicBoolean(false)
    private val inFlightTools = AtomicInteger(0)

    /** Single-thread PCM pump so WebSocket callbacks never race AudioTrack.write. */
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(1_024)
    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GrokVoicePlayback").apply { isDaemon = true }
    }
    private val playbackWorkerStarted = AtomicBoolean(false)

    /** Wall clock when current Thinking/ToolBusy phase began. */
    private val phaseStartedMs = AtomicLong(0L)
    /** Last WebSocket event received (for stall detection). */
    private val lastEventMs = AtomicLong(0L)
    private val lastEventType = AtomicReference<String?>(null)
    /** Short phase key for status: processing | thinking | web_search | x_search | build | … */
    private val phaseKey = AtomicReference("idle")
    private val deepThink = AtomicBoolean(false)

    private var appCtx: Context? = null
    private var conversationId: String? = null
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            tickWatchdog()
            mainHandler.postDelayed(this, WATCHDOG_MS)
        }
    }
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
                commitUserIfNeeded(app, seed)
            }
            return
        }

        deepThink.set(store.voiceDeepThink)
        setState(State.Connecting, Turn.Connecting, "Connecting Voice Agent…")
        GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)
        startWatchdog()

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
                // Resume server-side turns when we still have a conversation_id
                // for this local chat (xAI caches ~30 min). Also seed instructions
                // with local history so text→voice and cold starts keep context.
                val resumeId = store.voiceResumeId()
                client.connect(token, conversationId = resumeId)
                if (resumeId != null) {
                    conversationId = resumeId
                    Log.i(TAG, "resuming voice conversation_id=${resumeId.take(12)}…")
                }

                // Wait for real onOpen (socket object alone is not open yet).
                var waits = 0
                while (running.get() && !client.isOpen && waits < 80) {
                    Thread.sleep(50)
                    waits++
                }
                if (!client.isOpen) {
                    fail("Voice WebSocket connect timeout")
                    return@launch
                }

                val historyBlock = AssistantTranscript.formatHistoryForVoiceInstructions(
                    store.transcript(),
                )
                val instructions = buildString {
                    append(store.systemPrompt())
                    append("\n\n")
                    append(
                        GrokAssistantVoiceTools.voiceToolInstructions(
                            devMode = store.mode == AssistantMode.Dev,
                        ),
                    )
                    if (historyBlock.isNotBlank()) {
                        append("\n\n")
                        append(historyBlock)
                    }
                }
                val tools = GrokAssistantVoiceTools.sessionTools(
                    devMode = store.mode == AssistantMode.Dev,
                )
                // Default effort=none — xAI high reasoning is what made "Thinking…" feel frozen.
                val effort = if (store.voiceDeepThink) "high" else "none"
                // JSON transport = docs default + official cookbook path.
                // response.output_audio.delta carries base64 PCM; binary is optional/strict.
                client.sessionUpdate(
                    instructions = instructions,
                    voice = store.voiceId,
                    tools = tools,
                    sampleRate = GrokAssistantVoiceClient.SAMPLE_RATE,
                    useBinaryAudio = false,
                    reasoningEffort = effort,
                )
                useBinary.set(false)

                // Cookbook: wait for session.updated before mic / response.create.
                // Sending audio or seed before config applies can drop first-turn TTS.
                waits = 0
                while (running.get() && !client.isSessionReady && waits < 50) {
                    Thread.sleep(100)
                    waits++
                }
                if (!client.isSessionReady) {
                    Log.w(TAG, "session.updated not seen — continuing (socket open)")
                }

                // Route mic/playback to Bluetooth headset when HFP/SCO is available.
                prepareAudioRoute(force = true)
                registerAudioRouteWatchers()
                ensurePlaybackTrack()

                setPhase("live", Turn.Listening, listeningStatusLine())
                setState(State.Live, Turn.Listening, statusLine.get())

                val seed = seedUserText?.trim().orEmpty()
                if (seed.isNotEmpty()) {
                    commitUserIfNeeded(app, seed)
                    setPhase("thinking", Turn.Thinking, phaseStatus("thinking"))
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
        commitUserIfNeeded(app, t)
        scope.launch { client.createUserText(t) }
    }

    private fun stopInternal(releaseMic: Boolean) {
        // Persist live captions before wiping state — ending a session used to
        // erase in-flight user/assistant bubbles that never got response.done.
        appCtx?.let { flushLiveCaptions(it) }
        running.set(false)
        stopWatchdog()
        pendingResponseAfterTools.set(false)
        inFlightTools.set(0)
        assistantCommittedThisResponse.set(false)
        lastUserCommitItemId.set(null)
        lastUserCommitText.set(null)
        lastUserCommitElapsedMs.set(0L)
        awaitingPlaybackDrain.set(false)
        serverResponseActive.set(false)
        micMuted.set(false)
        postSpeakCooldownUntilMs.set(0L)
        lastPlaybackActivityMs.set(0L)
        queuedPcmBytes.set(0)
        responsePcmBytes.set(0)
        playbackDrainUntilMs.set(0L)
        lastLocalRms.set(0f)
        phaseStartedMs.set(0L)
        phaseKey.set("idle")
        lastEventType.set(null)
        // Poison + drain the playback queue so the worker doesn't write after release.
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
        releaseAudioRoute()
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
     * Commit any non-empty live captions into the chat store so they survive
     * cancel / session end / idle unstick (UI used to "delete" unfinished bubbles).
     */
    private fun flushLiveCaptions(app: Context) {
        val user = partialUser.get()?.trim().orEmpty()
        if (user.isNotEmpty()) {
            commitUserIfNeeded(app, user)
        }
        val asst = partialAssistant.get()?.trim().orEmpty()
        if (asst.isNotEmpty()) {
            commitAssistantIfNeeded(app, asst)
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun setPhase(key: String, t: Turn? = null, line: String? = null) {
        phaseKey.set(key)
        phaseStartedMs.set(SystemClock.uptimeMillis())
        if (t != null) turn.set(t)
        if (line != null) statusLine.set(line)
        applyMicMutePolicy(clientRef.get())
        publish()
    }

    private fun phaseElapsedSec(): Int {
        val start = phaseStartedMs.get()
        if (start <= 0L) return 0
        return ((SystemClock.uptimeMillis() - start) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun phaseStatus(key: String = phaseKey.get(), elapsed: Int = phaseElapsedSec()): String {
        val base = when (key) {
            "processing" -> "Voice Agent · processing"
            "thinking" -> if (deepThink.get()) {
                "Voice Agent · deep think"
            } else {
                "Voice Agent · thinking"
            }
            "web_search" -> "Voice Agent · web search"
            "x_search" -> "Voice Agent · X search"
            "build" -> "Grok Build (host CLI)"
            "tool" -> "Voice Agent · tool"
            "speaking" -> {
                if (btOutputPreferred.get() || btHeadsetMicActive.get()) {
                    "Voice Agent · speaking (headset)"
                } else {
                    "Voice Agent · speaking"
                }
            }
            "live" -> listeningStatusLine()
            else -> "Voice Agent · $key"
        }
        return if (elapsed > 0 && key != "live" && key != "speaking") {
            "$base · ${elapsed}s"
        } else {
            base
        }
    }

    private fun listeningStatusLine(): String {
        if (btHeadsetMicActive.get()) {
            val name = headsetLabel.get()?.takeIf { it.isNotBlank() }
            return if (name != null) {
                "Voice Agent · $name mic"
            } else {
                "Voice Agent · headset mic"
            }
        }
        return "Voice Agent · listening"
    }

    private fun noteServerEvent(type: String) {
        lastEventMs.set(SystemClock.uptimeMillis())
        lastEventType.set(type)
    }

    private fun tickWatchdog() {
        if (!running.get()) return
        tryFinishPlaybackDrain()
        applyMicMutePolicy(clientRef.get())
        // Keep the waveform alive between sparse PCM deltas / mic frames.
        tickLevelAnimation()

        val t = turn.get()
        val key = phaseKey.get()
        when (t) {
            Turn.Thinking -> {
                val elapsed = phaseElapsedSec()
                val lastEv = lastEventMs.get()
                val sinceEvent = if (lastEv > 0L) SystemClock.uptimeMillis() - lastEv else elapsed * 1000L
                // Refresh elapsed label so UI doesn't look frozen.
                val line = when {
                    elapsed >= THINKING_TIMEOUT_MS / 1000 -> null // handled below
                    sinceEvent >= THINKING_STALL_MS && elapsed >= 3 ->
                        "${phaseStatus(key, elapsed)} · still working…"
                    else -> phaseStatus(key, elapsed)
                }
                if (line != null && statusLine.get() != line) {
                    statusLine.set(line)
                    applyMicMutePolicy(clientRef.get())
                    publish()
                }
                if (elapsed * 1000L >= THINKING_TIMEOUT_MS) {
                    Log.w(
                        TAG,
                        "Thinking timeout ${elapsed}s lastEvent=${lastEventType.get()}",
                    )
                    cancelActiveResponse("thinking timeout")
                    // Persist any partials then reopen mic (do not leave ghost bubbles).
                    returnToListening(reason = "thinking timeout")
                    statusLine.set(
                        if (btHeadsetMicActive.get()) {
                            "Timed out · headset mic listening"
                        } else {
                            "Timed out · Voice Agent listening"
                        },
                    )
                    publish()
                    mainHandler.post {
                        listeners.forEach {
                            it.onError("Voice Agent timed out after ${elapsed}s (still connected)")
                        }
                    }
                }
            }
            Turn.GrokSpeaking -> {
                if (statusLine.get() != "Voice Agent · speaking") {
                    statusLine.set("Voice Agent · speaking")
                    publish()
                }
                // Text-only / server-cancelled replies used to sit here forever:
                // turn=GrokSpeaking with zero PCM and no drain flag.
                maybeUnstickSpeaking()
            }
            Turn.ToolBusy -> {
                val line = phaseStatus(key, phaseElapsedSec())
                if (statusLine.get() != line) {
                    statusLine.set(line)
                    publish()
                }
            }
            else -> Unit
        }
    }

    /**
     * Soft half-duplex: keep AudioRecord running always; only stop *sending*
     * frames while the assistant response is live or the speaker is hot.
     *
     * Before response.created, Thinking stays open so a short VAD pause + continue
     * still streams (early cutoff fix). Once the server starts a response
     * ([serverResponseActive]), mute immediately — speaker echo into an open mic
     * is the classic "text arrives, audio cancelled mid-stream" path (docs + prior
     * 9b3ff8a). Soft mute only; never AudioRecord stop/start around TTS.
     */
    private fun isMicSendAllowed(): Boolean {
        when (turn.get()) {
            Turn.Listening, Turn.UserSpeaking -> Unit
            Turn.Thinking -> {
                // Open only until the server commits to a response.
                if (serverResponseActive.get()) return false
            }
            Turn.GrokSpeaking, Turn.ToolBusy,
            Turn.Idle, Turn.Error, Turn.Connecting,
            -> return false
        }
        if (serverResponseActive.get()) return false
        if (awaitingPlaybackDrain.get()) return false
        if (hasLocalPlaybackRemaining()) return false
        // Soft hold after last PCM + post-speak cooldown so speaker ring-out
        // is not VAD'd into a phantom second user turn.
        val now = SystemClock.uptimeMillis()
        val lastPlay = lastPlaybackActivityMs.get()
        if (lastPlay > 0L && now - lastPlay < PLAYBACK_MUTE_HOLD_MS) return false
        if (now < postSpeakCooldownUntilMs.get()) return false
        return true
    }

    private fun inPostSpeakCooldown(): Boolean =
        SystemClock.uptimeMillis() < postSpeakCooldownUntilMs.get()

    private fun pcmBytesToMs(bytes: Int): Long {
        if (bytes <= 0) return 0L
        val rate = GrokAssistantVoiceClient.SAMPLE_RATE
        return (bytes.toLong() * 1000L) / (rate * 2L)
    }

    /** Approximate milliseconds of assistant audio still queued or in the speaker path. */
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

    /**
     * True when local speaker path still has (or just had) assistant PCM.
     * Does **not** treat transcript-only GrokSpeaking as active playback —
     * that false positive blocked barge-in recovery and left the UI stuck.
     */
    private fun isPlaybackActive(): Boolean {
        if (queuedPcmBytes.get() > 0) return true
        if (playbackRemainingMs() > 0L) return true
        if (awaitingPlaybackDrain.get() && hasLocalPlaybackRemaining()) return true
        val lastPlay = lastPlaybackActivityMs.get()
        if (lastPlay > 0L) {
            val age = SystemClock.uptimeMillis() - lastPlay
            if (age < BARGE_IN_GUARD_MS) return true
        }
        return false
    }

    /**
     * True only when we're confident the *user* is trying to interrupt —
     * not speaker bleed while Grok is still talking.
     *
     * During Thinking: honor only if local RMS looks like real speech so a
     * late finish after an early VAD stop can restart the turn; ignore hiss.
     */
    private fun shouldHonorBargeIn(): Boolean {
        if (isPlaybackActive()) return false
        when (turn.get()) {
            Turn.GrokSpeaking, Turn.ToolBusy,
            Turn.Idle, Turn.Error, Turn.Connecting,
            -> return false
            Turn.Thinking -> return lastLocalRms.get() >= THINKING_BARGE_RMS
            Turn.Listening, Turn.UserSpeaking -> {
                // Right after Grok finishes, only accept a clear barge-in.
                // Weak VAD on TTS echo used to open a second empty turn.
                if (inPostSpeakCooldown()) {
                    return lastLocalRms.get() >= POST_SPEAK_BARGE_RMS
                }
                return true
            }
        }
        return false
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
        // Server is done generating; keep UI in "speaking" until local buffer drains.
        awaitingPlaybackDrain.set(true)
        val hadAudio = responsePcmBytes.get() > 0 || hasLocalPlaybackRemaining()
        if (hadAudio && (turn.get() == Turn.GrokSpeaking || hasLocalPlaybackRemaining())) {
            setPhase("speaking", Turn.GrokSpeaking, phaseStatus("speaking"))
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
        returnToListening(reason = "playback drained (pcm=${responsePcmBytes.get()})")
    }

    /** Complete assistant turn and re-open the mic (after hold). */
    private fun returnToListening(reason: String) {
        Log.d(TAG, "returnToListening: $reason")
        awaitingPlaybackDrain.set(false)
        // Keep lastPlaybackActivityMs so PLAYBACK_MUTE_HOLD still applies; only
        // clear the queue/drain deadline. Zeroing lastPlay made mute lift too
        // early and let speaker ring-out become a phantom user turn.
        queuedPcmBytes.set(0)
        playbackDrainUntilMs.set(0L)
        if (lastPlaybackActivityMs.get() <= 0L) {
            lastPlaybackActivityMs.set(SystemClock.uptimeMillis())
        }
        if (state.get() == State.ToolBusy) {
            applyMicMutePolicy(clientRef.get())
            publish()
            return
        }
        // Keep unfinished assistant text in the chat (cancel / stall / end-of-turn).
        appCtx?.let { ctx ->
            val asst = partialAssistant.get()?.trim().orEmpty()
            if (asst.isNotEmpty()) commitAssistantIfNeeded(ctx, asst)
        }
        partialAssistant.set(null)
        // Cooldown: mute outbound + ignore weak VAD while the room settles.
        postSpeakCooldownUntilMs.set(SystemClock.uptimeMillis() + POST_SPEAK_COOLDOWN_MS)
        // Drop any residual server-side input so echo can't auto-commit a turn.
        clientRef.get()?.clearInputAudioBuffer()
        // Speaker path is idle — release focus so music apps can resume.
        abandonPlaybackFocus()
        setPhase("live", Turn.Listening, listeningStatusLine())
        setState(State.Live, Turn.Listening, statusLine.get())
    }

    /**
     * Unstick GrokSpeaking only when truly stalled.
     *
     * Important: do **not** force Listening a few seconds after the first
     * transcript delta. Audio often lags text; the old short timer dropped
     * focus + wiped the live bubble mid-reply so speech never played.
     *
     * Special case: full transcript already committed (transcript.done) and
     * still zero PCM after a short wait — server cancelled TTS or we missed
     * audio frames. Don't sit in Speaking forever.
     */
    private fun maybeUnstickSpeaking() {
        if (turn.get() != Turn.GrokSpeaking) return
        if (queuedPcmBytes.get() > 0) return
        if (playbackRemainingMs() > 0L) return
        val now = SystemClock.uptimeMillis()
        // Server still streaming events → keep waiting for PCM / done.
        val lastEv = lastEventMs.get()
        if (lastEv > 0L && now - lastEv < SPEAKING_EVENT_GRACE_MS) return

        val lastPlay = lastPlaybackActivityMs.get()
        val idleFromPlay = if (lastPlay > 0L) now - lastPlay else Long.MAX_VALUE
        val idleFromPhase = now - phaseStartedMs.get()
        val noPcm = responsePcmBytes.get() == 0

        if (awaitingPlaybackDrain.get()) {
            // response.done already received — local drain should finish soon.
            // Zero-PCM text-only replies finish immediately via tryFinishPlaybackDrain.
            val idle = if (noPcm) idleFromPhase else idleFromPlay
            if (noPcm || idle >= SPEAKING_DRAIN_IDLE_MS) {
                tryFinishPlaybackDrain()
            }
            if (turn.get() != Turn.GrokSpeaking) return
            if (idle < SPEAKING_DRAIN_IDLE_MS) return
            Log.w(
                TAG,
                "unstick GrokSpeaking after drain idle=${idle}ms pcm=${responsePcmBytes.get()} " +
                    "lastEvent=${lastEventType.get()}",
            )
            returnToListening(reason = "speaking drain stuck")
            return
        }

        // Transcript already committed, still no PCM, response still "active" or
        // silent — recover faster than the full generate stall.
        if (noPcm &&
            assistantCommittedThisResponse.get() &&
            idleFromPhase >= SPEAKING_DRAIN_IDLE_MS
        ) {
            Log.w(
                TAG,
                "unstick GrokSpeaking text-only (no PCM) idle=${idleFromPhase}ms " +
                    "lastEvent=${lastEventType.get()}",
            )
            if (serverResponseActive.get()) {
                cancelActiveResponse("text-only no PCM")
            }
            returnToListening(reason = "text-only no PCM")
            return
        }

        // Still generating: only unstick after a long stall with no events/PCM.
        val idle = if (noPcm) idleFromPhase else idleFromPlay
        if (idle < SPEAKING_GENERATE_STALL_MS) return
        Log.w(
            TAG,
            "unstick GrokSpeaking generate-stall idle=${idle}ms pcm=${responsePcmBytes.get()} " +
                "lastEvent=${lastEventType.get()}",
        )
        // Cancel only if the server still has a live response (avoids cancel-error → focus loss).
        cancelActiveResponse("speaking generate stall")
        flushPlayback("speaking generate stall")
        returnToListening(reason = "speaking generate stall")
    }

    /**
     * Send response.cancel only when we believe the server has an active response.
     * xAI auto-cancels on barge-in; a second cancel returns a hard error that used
     * to tear down local playback mid-sentence.
     */
    private fun cancelActiveResponse(reason: String): Boolean {
        if (!serverResponseActive.compareAndSet(true, false)) {
            Log.d(TAG, "skip response.cancel ($reason) — no active response")
            return false
        }
        Log.d(TAG, "response.cancel ($reason)")
        return clientRef.get()?.cancelResponse() == true
    }

    /**
     * Edge-triggered mute/unmute. Stop streaming immediately on mute.
     *
     * Only clear the server input buffer once Grok is already speaking / tools /
     * drain — never on speech_stopped→Thinking (that wiped the just-finished
     * utterance and stuck the UI on "thinking").
     */
    private fun applyMicMutePolicy(client: GrokAssistantVoiceClient?) {
        val wantMute = !isMicSendAllowed()
        val wasMuted = micMuted.getAndSet(wantMute)
        if (wantMute && !wasMuted) {
            val t = turn.get()
            val safeToClear = t == Turn.GrokSpeaking ||
                t == Turn.ToolBusy ||
                awaitingPlaybackDrain.get() ||
                hasLocalPlaybackRemaining()
            if (safeToClear) {
                client?.clearInputAudioBuffer()
            }
            Log.d(TAG, "mic muted (turn=$t clear=$safeToClear)")
        } else if (!wantMute && wasMuted) {
            // Clear again on unmute so nothing accumulated server-side while muted.
            client?.clearInputAudioBuffer()
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

    /**
     * Waveform used to freeze mid-utterance: mic levels are skipped while Grok
     * speaks, and TTS deltas can arrive in large infrequent chunks. Nudge the
     * rolling bars from the watchdog so the UI keeps moving whenever a turn is live.
     */
    private fun tickLevelAnimation() {
        val t = turn.get()
        when (t) {
            Turn.UserSpeaking, Turn.GrokSpeaking, Turn.Thinking, Turn.ToolBusy -> Unit
            Turn.Listening -> {
                // Soft ambient pulse while waiting — never a flat line.
                val ambient = 0.04f + 0.03f * ((SystemClock.uptimeMillis() / 180L) % 5) / 4f
                val cur = level.get()
                if (cur < 0.06f) noteLevel(max(cur * 0.9f, ambient))
                return
            }
            else -> return
        }
        val cur = level.get()
        val target = when (t) {
            Turn.UserSpeaking -> max(cur, lastLocalRms.get()).coerceAtLeast(0.10f)
            Turn.GrokSpeaking -> {
                // Prefer live speaker energy; fall back to a gentle speak pulse.
                if (hasLocalPlaybackRemaining() || serverResponseActive.get()) {
                    max(cur * 0.92f, 0.14f + 0.08f * ((SystemClock.uptimeMillis() / 90L) % 4) / 3f)
                } else {
                    cur * 0.85f
                }
            }
            Turn.Thinking, Turn.ToolBusy ->
                max(cur * 0.88f, 0.06f + 0.04f * ((SystemClock.uptimeMillis() / 220L) % 4) / 3f)
            else -> cur * 0.9f
        }
        noteLevel(target.coerceIn(0f, 1f))
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

    /**
     * Commit a user line once per utterance. Server may emit both
     * `input_audio_transcription.completed` and `.done` (same item_id / text).
     * Seed / sendText also go through here so a later caption of the same line
     * does not create a second bubble.
     *
     * Different item_ids from VAD-split pauses are **merged** by the store into
     * one user bubble (see [AssistantTranscript.shouldMergeUserUtterance]).
     */
    private fun commitUserIfNeeded(
        app: Context,
        text: String?,
        itemId: String? = null,
    ): Boolean {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return false
        val id = itemId?.trim().orEmpty()
        if (id.isNotEmpty() && id == lastUserCommitItemId.get()) {
            return false
        }
        val prevText = lastUserCommitText.get()
        val prevAt = lastUserCommitElapsedMs.get()
        val now = SystemClock.elapsedRealtime()
        if (prevText != null &&
            prevText.equals(body, ignoreCase = true) &&
            now - prevAt < 5_000L
        ) {
            return false
        }
        // Superseding cumulative ASR for the same item (partial → fuller) already
        // handled by store merge; still skip exact re-commit of the last body.
        if (id.isNotEmpty()) lastUserCommitItemId.set(id)
        partialUser.set(null)
        val store = GrokAssistantStore(app)
        val before = store.transcript()
        val beforeLast = before.lastOrNull()
        val beforeSize = before.size
        val beforeText = beforeLast?.text
        val msg = store.appendMessage("user", body)
        val after = store.transcript()
        val afterSize = after.size
        val afterLast = after.lastOrNull()
        // Track the *merged* body so the next exact twin is skipped correctly.
        val committedBody = afterLast?.text?.takeIf {
            afterLast.role.equals("user", ignoreCase = true)
        } ?: body
        lastUserCommitText.set(committedBody)
        lastUserCommitElapsedMs.set(now)
        val changed = afterSize > beforeSize ||
            (afterSize == beforeSize && afterLast?.text != beforeText)
        if (changed) {
            notifyTranscript("user", msg.text)
        } else {
            publish()
        }
        return changed
    }

    private fun extractUserItemId(event: JSONObject): String? {
        val direct = event.optString("item_id", "").trim()
        if (direct.isNotEmpty()) return direct
        val item = event.optJSONObject("item") ?: return null
        return item.optString("id", "").trim().ifEmpty { null }
    }

    private fun handleEvent(app: Context, event: JSONObject) {
        if (!running.get()) return
        val type = event.optString("type")
        noteServerEvent(type)
        when (type) {
            "conversation.created" -> {
                val id = event.optJSONObject("conversation")
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                conversationId = id
                if (id != null) {
                    // Persist for session resumption across reconnect / stop-start.
                    GrokAssistantStore(app).setVoiceResumeId(id)
                    Log.d(TAG, "voice conversation_id saved (${id.take(12)}…)")
                }
            }
            "error" -> {
                val msg = event.optString("error", "")
                    .ifBlank { event.optJSONObject("error")?.optString("message").orEmpty() }
                    .ifBlank { event.optJSONObject("error")?.toString().orEmpty() }
                    .ifBlank { event.optString("message", "voice_error") }
                // Spurious cancel races are common (server already auto-cancelled or
                // response.done already fired). Surfacing them aborted local TTS via
                // returnToListening → abandonAudioFocus while the message stayed.
                if (GrokAssistantVoiceClient.isBenignRealtimeCancelError(msg)) {
                    Log.d(TAG, "ignoring benign cancel error: $msg")
                    serverResponseActive.set(false)
                    return
                }
                Log.e(TAG, "server error: $msg")
                mainHandler.post { listeners.forEach { it.onError(msg) } }
                // Don't leave the UI stuck on Thinking after a soft server error.
                // Persist any live captions so a server fault doesn't erase the bubble.
                // If local speaker is still draining, keep playing — only unstick when idle.
                val speakingWithAudio = turn.get() == Turn.GrokSpeaking &&
                    (hasLocalPlaybackRemaining() || awaitingPlaybackDrain.get())
                if (speakingWithAudio) {
                    Log.w(TAG, "server error during TTS — keep playing: ${msg.take(80)}")
                    statusLine.set(msg.take(80))
                    publish()
                } else if (turn.get() == Turn.Thinking || turn.get() == Turn.GrokSpeaking) {
                    returnToListening(reason = "server error: ${msg.take(80)}")
                } else {
                    flushLiveCaptions(app)
                    statusLine.set(msg.take(120))
                    publish()
                }
            }
            "response.cancelled",
            "response.canceled",
            -> {
                Log.d(TAG, "response cancelled — return to listening")
                serverResponseActive.set(false)
                flushPlayback("response cancelled")
                // returnToListening commits any partial assistant text first.
                responsePcmBytes.set(0)
                awaitingPlaybackDrain.set(false)
                if (state.get() != State.ToolBusy) {
                    returnToListening(reason = "response cancelled")
                } else {
                    appCtx?.let { flushLiveCaptions(it) }
                    partialAssistant.set(null)
                    publish()
                }
            }
            "input_audio_buffer.speech_started" -> {
                // While Grok is speaking / playback active: almost always speaker echo.
                // Do NOT flush local TTS (that was the mid-sentence glitch path).
                // Clear only when playback is active so residual echo is not committed.
                if (!shouldHonorBargeIn()) {
                    Log.d(
                        TAG,
                        "ignore speech_started " +
                            "(muted=${micMuted.get()} turn=${turn.get()} " +
                            "rms=${lastLocalRms.get()} remMs=${playbackRemainingMs()} " +
                            "pcm=${responsePcmBytes.get()})",
                    )
                    if (isPlaybackActive() || turn.get() == Turn.GrokSpeaking) {
                        clientRef.get()?.clearInputAudioBuffer()
                    }
                    return
                }
                val priorTurn = turn.get()
                // Continuation after an early VAD stop (Thinking) vs a brand-new turn
                // after Listening. Continuations must merge into the same user bubble.
                val continuingUserTurn = priorTurn == Turn.Thinking ||
                    priorTurn == Turn.UserSpeaking
                // Real barge-in / mid-thought resume.
                // Save what Grok already said so the chat bubble doesn't vanish —
                // but only if this is a true interrupt of an assistant reply, not a
                // VAD-split of the same user utterance (no assistant text yet).
                val asstSoFar = partialAssistant.get()?.trim().orEmpty()
                if (asstSoFar.isNotEmpty() && !continuingUserTurn) {
                    commitAssistantIfNeeded(app, asstSoFar)
                } else if (asstSoFar.isNotEmpty() && continuingUserTurn) {
                    // Premature response to a partial user turn — drop partial text;
                    // the cancelled response should not become a half bubble.
                    Log.d(TAG, "drop partial assistant on user-turn continue")
                    partialAssistant.set(null)
                    assistantCommittedThisResponse.set(false)
                }
                // Server often auto-cancels on speech_started; only cancel if still active.
                cancelActiveResponse(if (continuingUserTurn) "user-turn continue" else "barge-in")
                flushPlayback(if (continuingUserTurn) "user-turn continue" else "barge-in")
                // Keep live caption if continuing so the UI doesn't flash empty.
                if (!continuingUserTurn) {
                    partialUser.set("")
                }
                partialAssistant.set(null)
                assistantCommittedThisResponse.set(false)
                responsePcmBytes.set(0)
                awaitingPlaybackDrain.set(false)
                postSpeakCooldownUntilMs.set(0L)
                // Only open a *new* user-commit chain after Listening (fresh turn).
                // During Thinking/UserSpeaking, keep last commit so store merge +
                // exact-text dedup fold VAD fragments into one message.
                if (!continuingUserTurn) {
                    lastUserCommitItemId.set(null)
                    lastUserCommitText.set(null)
                    lastUserCommitElapsedMs.set(0L)
                } else {
                    // Different server item_id for the next segment — clear only item
                    // so exact-text dedup still works across completed/done pairs.
                    lastUserCommitItemId.set(null)
                }
                setPhase("hearing", Turn.UserSpeaking, "Hearing you…")
            }
            "input_audio_buffer.speech_stopped" -> {
                // Show processing, but keep mic open (see isMicSendAllowed) so a
                // mid-thought pause + continue still streams. NEVER clear the
                // input buffer here — server_vad commits it for the response.
                setPhase("processing", Turn.Thinking, phaseStatus("processing"))
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
                    commitUserIfNeeded(app, text, itemId = extractUserItemId(event))
                    if (turn.get() == Turn.UserSpeaking || turn.get() == Turn.Thinking) {
                        setPhase("thinking", Turn.Thinking, phaseStatus("thinking"))
                    } else {
                        publish()
                    }
                }
            }
            "response.created" -> {
                assistantCommittedThisResponse.set(false)
                responsePcmBytes.set(0)
                partialAssistant.set("")
                serverResponseActive.set(true)
                // New assistant turn cancels post-speak cooldown (we're responding).
                postSpeakCooldownUntilMs.set(0L)
                // Mute immediately — any open-mic frames after this are almost
                // always speaker bleed and will VAD-cancel the TTS mid-stream
                // (symptom: chat text lands, AudioTrack never plays / stuck Speaking).
                // Don't demote GrokSpeaking → Thinking if we're already mid-reply
                // (prevents listen/speak animation flicker on multi-part tools).
                if (state.get() != State.ToolBusy) {
                    val t = turn.get()
                    if (t == Turn.GrokSpeaking || hasLocalPlaybackRemaining()) {
                        applyMicMutePolicy(clientRef.get())
                        publish()
                    } else {
                        setPhase("thinking", Turn.Thinking, phaseStatus("thinking"))
                    }
                } else {
                    applyMicMutePolicy(clientRef.get())
                }
            }
            "response.output_item.added",
            "response.output_item.done",
            -> {
                val item = event.optJSONObject("item")
                val itemType = item?.optString("type").orEmpty()
                val name = item?.optString("name").orEmpty()
                    .ifBlank { event.optString("name", "") }
                val toolKey = toolPhaseKey(itemType, name)
                if (toolKey != null && state.get() != State.ToolBusy) {
                    // Server-side tools (web_search / x_search). Keep Speaking if
                    // audio is already out — flipping to Thinking mid-TTS was the
                    // janky listen↔speak animation.
                    val speaking = turn.get() == Turn.GrokSpeaking ||
                        hasLocalPlaybackRemaining() ||
                        responsePcmBytes.get() > 0
                    if (speaking) {
                        statusLine.set(phaseStatus(toolKey))
                        publish()
                    } else {
                        setPhase(toolKey, Turn.Thinking, phaseStatus(toolKey))
                    }
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
                    if (turn.get() != Turn.GrokSpeaking && state.get() != State.ToolBusy) {
                        setPhase("speaking", Turn.GrokSpeaking, phaseStatus("speaking"))
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
                    if (turn.get() == Turn.Thinking && state.get() != State.ToolBusy) {
                        // Text-only progress still counts as activity
                        statusLine.set(phaseStatus("thinking"))
                    }
                    publish()
                }
            }
            "response.output_audio.delta",
            "response.audio.delta",
            -> {
                // Docs default JSON transport: base64 PCM16 LE @ session rate.
                val b64 = event.optString("delta", "")
                    .ifBlank { event.optString("audio", "") }
                if (b64.isNotBlank()) {
                    runCatching {
                        val pcm = GrokAssistantVoiceClient.base64ToPcm16(b64)
                        if (responsePcmBytes.get() == 0) {
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
            -> {
                val text = extractTranscript(event)
                    .ifBlank { partialAssistant.get().orEmpty() }
                commitAssistantIfNeeded(app, text)
            }
            "response.function_call_arguments.done" -> {
                val call = GrokAssistantVoiceTools.parseFunctionCallEvent(event) ?: return
                inFlightTools.incrementAndGet()
                val isBuild = call.name == GrokAssistantVoiceTools.TOOL_PROMPT_BUILD
                val key = if (isBuild) "build" else "tool"
                setPhase(key, Turn.ToolBusy, phaseStatus(key))
                setState(State.ToolBusy, Turn.ToolBusy, statusLine.get())
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
                        // Docs: wait until current TTS drains before response.create,
                        // otherwise the next turn stomps the rest of the sentence.
                        waitForPlaybackDrain(maxWaitMs = 45_000L)
                        if (running.get() && pendingResponseAfterTools.getAndSet(false)) {
                            clientRef.get()?.responseCreate()
                            setState(State.Live, Turn.Thinking, null)
                            setPhase("thinking", Turn.Thinking, phaseStatus("thinking"))
                        }
                    }
                }
            }
            "response.done" -> {
                serverResponseActive.set(false)
                val leftover = partialAssistant.get()
                commitAssistantIfNeeded(app, leftover)
                Log.d(
                    TAG,
                    "response.done pcmBytes=${responsePcmBytes.get()} " +
                        "queued=${queuedPcmBytes.get()} remMs=${playbackRemainingMs()}",
                )
                // Drain local TTS (or finish immediately if this was text-only / cancelled audio).
                markResponseAudioFinished()
            }
            "session.updated",
            "session.created",
            -> {
                if (state.get() == State.Connecting) {
                    setPhase("live", Turn.Listening, listeningStatusLine())
                    setState(State.Live, Turn.Listening, statusLine.get())
                }
            }
            else -> {
                // Keep lastEvent for stall diagnostics; ignore noise.
                Log.d(TAG, "voice event: $type")
            }
        }
    }

    /** Map server item / function names to a short UI phase key, or null if not a tool. */
    private fun toolPhaseKey(itemType: String, name: String): String? {
        val n = name.lowercase()
        val t = itemType.lowercase()
        return when {
            n.contains("web_search") || t.contains("web_search") -> "web_search"
            n.contains("x_search") || t.contains("x_search") -> "x_search"
            n == GrokAssistantVoiceTools.TOOL_PROMPT_BUILD || n.contains("grok_build") -> "build"
            // Only treat explicit function_call items as tools (not message/audio items).
            t == "function_call" || t == "function" || t == "custom_tool_call" -> "tool"
            else -> null
        }
    }

    private fun delaySoft(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    /** Block (IO thread) until local TTS has finished or [maxWaitMs] elapses. */
    private fun waitForPlaybackDrain(maxWaitMs: Long) {
        val deadline = SystemClock.uptimeMillis() + maxWaitMs
        while (running.get() && SystemClock.uptimeMillis() < deadline) {
            val stillPlaying = queuedPcmBytes.get() > 0 ||
                playbackRemainingMs() > 0L ||
                (turn.get() == Turn.GrokSpeaking && awaitingPlaybackDrain.get())
            if (!stillPlaying) break
            delaySoft(40)
        }
        // Final pad so the last samples leave the speaker before we unmute / create.
        delaySoft(PLAYBACK_MUTE_HOLD_MS.coerceAtMost(400L))
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
                if (pcm.isEmpty()) {
                    // Poison pill from stop, or empty frame — keep worker alive for reuse
                    if (!running.get()) continue
                    continue
                }
                writePcmBlocking(pcm)
            }
        }
    }

    private fun speechAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            // USAGE_MEDIA + focus ducks Spotify so replies are audible (USAGE_ASSISTANT
            // / VOICE_COMMUNICATION often routed quietly or lost focus to music apps).
            // When a BT headset mic is active we still use MEDIA; communicationDevice /
            // setPreferredDevice own the physical route.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun hasBluetoothConnectPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun deviceProductName(info: AudioDeviceInfo?): String? {
        if (info == null) return null
        val raw = try {
            info.productName?.toString()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        return raw.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun isBluetoothInputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isBluetoothOutputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                type == AudioDeviceInfo.TYPE_BLE_BROADCAST)

    private fun findBluetoothInput(am: AudioManager): AudioDeviceInfo? {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // Prefer classic SCO (HFP boom mic) over BLE headset when both exist.
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: inputs.firstOrNull { isBluetoothInputType(it.type) }
    }

    private fun findBluetoothOutput(am: AudioManager): AudioDeviceInfo? {
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Prefer A2DP for higher-quality reply audio; fall back to SCO/BLE.
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: outputs.firstOrNull { isBluetoothOutputType(it.type) }
    }

    private fun findCommunicationBluetooth(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            am.availableCommunicationDevices.firstOrNull { isBluetoothInputType(it.type) }
                ?: am.availableCommunicationDevices.firstOrNull { isBluetoothOutputType(it.type) }
        } catch (_: Exception) {
            null
        }
    }

    private fun routeFingerprint(
        input: AudioDeviceInfo?,
        output: AudioDeviceInfo?,
        sco: Boolean,
    ): String =
        "in=${input?.id ?: -1}/${input?.type ?: -1};out=${output?.id ?: -1}/${output?.type ?: -1};sco=$sco"

    /**
     * Detect Bluetooth headset/A2DP. Mic may use HFP/SCO when a headset profile is
     * actually connected; **playback never pins to a device** — forced preferred
     * output + MODE_IN_COMMUNICATION was silencing TTS (buds in case, phantom SCO).
     *
     * @return true if the active route changed (mic should be reopened).
     */
    private fun prepareAudioRoute(force: Boolean = false): Boolean {
        val ctx = appCtx ?: return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (!hasBluetoothConnectPermission(ctx)) {
            Log.i(TAG, "audio route: no BLUETOOTH_CONNECT — phone mic + speaker")
            return applyPhoneRoute(am, force)
        }

        val btIn = findBluetoothInput(am)
        val btOut = findBluetoothOutput(am)
        val commBt = findCommunicationBluetooth(am)
        val headsetProfileConnected = isBluetoothHeadsetProfileConnected(ctx)
        // Only grab HFP when the headset profile is really connected — bonded-only
        // SCO nodes on Samsung/Pixel were stealing the route and muting the speaker.
        val wantHeadsetMic = headsetProfileConnected && (btIn != null || commBt != null)
        val wantBtOut = btOut != null && (
            btOut.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    btOut.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                headsetProfileConnected
            )
        val fp = routeFingerprint(
            if (wantHeadsetMic) (btIn ?: commBt) else null,
            if (wantBtOut) btOut else null,
            wantHeadsetMic,
        )
        if (!force && fp == lastRouteFingerprint.get()) return false

        if (wantHeadsetMic) {
            val ok = activateBluetoothHeadsetRoute(am, btIn, btOut, commBt)
            if (ok) {
                lastRouteFingerprint.set(fp)
                Log.i(
                    TAG,
                    "audio route: headset mic label=${headsetLabel.get()} " +
                        "in=${preferredInputDevice.get()?.type} " +
                        "(playback unpinned — system routes media)",
                )
                return true
            }
            Log.w(TAG, "audio route: BT mic present but SCO/comm failed — phone path")
        }

        // A2DP headphones: phone mic, leave media route alone (system → buds).
        // Do not setCommunicationDevice / MODE_IN_COMMUNICATION — that silences TTS.
        if (wantBtOut) {
            val out = btOut
            val changed = !btOutputPreferred.get() ||
                btHeadsetMicActive.get() ||
                headsetLabel.get() != deviceProductName(out)
            clearBluetoothSco(am)
            preferredInputDevice.set(null)
            // Remember label for status only — never pin AudioTrack to this device.
            preferredOutputDevice.set(null)
            btHeadsetMicActive.set(false)
            btOutputPreferred.set(true)
            headsetLabel.set(deviceProductName(out))
            setSpeakerphone(false, forceModeNormal = true)
            lastRouteFingerprint.set(fp)
            Log.i(TAG, "audio route: phone mic + system BT out (${headsetLabel.get()})")
            return changed || force
        }

        return applyPhoneRoute(am, force)
    }

    /**
     * True when HFP/HEADSET profile is actually connected (not merely bonded).
     * Bonded-only devices still appear in [AudioManager.getDevices] and must not
     * force communication routing.
     */
    private fun isBluetoothHeadsetProfileConnected(ctx: Context): Boolean {
        return try {
            val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = mgr?.adapter ?: return false
            if (!adapter.isEnabled) return false
            val headset = adapter.getProfileConnectionState(
                android.bluetooth.BluetoothProfile.HEADSET,
            )
            if (headset == android.bluetooth.BluetoothProfile.STATE_CONNECTED) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val a2dp = adapter.getProfileConnectionState(
                    android.bluetooth.BluetoothProfile.A2DP,
                )
                // A2DP alone is not enough for mic routing, but we only call this
                // when choosing HFP — require HEADSET. Keep this branch for clarity.
                if (a2dp == android.bluetooth.BluetoothProfile.STATE_CONNECTED &&
                    headset == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                ) {
                    return true
                }
            }
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "BT profile check: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "BT profile check failed: ${e.message}")
            false
        }
    }

    private fun applyPhoneRoute(am: AudioManager, force: Boolean): Boolean {
        val wasBt = btHeadsetMicActive.get() || btOutputPreferred.get()
        clearBluetoothSco(am)
        preferredInputDevice.set(null)
        preferredOutputDevice.set(null)
        btHeadsetMicActive.set(false)
        btOutputPreferred.set(false)
        headsetLabel.set(null)
        lastRouteFingerprint.set(routeFingerprint(null, null, false))
        setSpeakerphone(true, forceModeNormal = true)
        return wasBt || force
    }

    private fun activateBluetoothHeadsetRoute(
        am: AudioManager,
        btIn: AudioDeviceInfo?,
        btOut: AudioDeviceInfo?,
        commBt: AudioDeviceInfo?,
    ): Boolean {
        // API 31+: setCommunicationDevice switches HFP for *capture*. Playback uses
        // USAGE_MEDIA and must stay unpinned so A2DP/speaker still work.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = commBt ?: btIn
            if (target != null) {
                val set = try {
                    am.setCommunicationDevice(target)
                } catch (e: Exception) {
                    Log.w(TAG, "setCommunicationDevice: ${e.message}")
                    false
                }
                if (set) {
                    preferredInputDevice.set(btIn ?: target)
                    // Do not pin output — media AudioTrack + A2DP stays audible.
                    preferredOutputDevice.set(null)
                    btHeadsetMicActive.set(true)
                    btOutputPreferred.set(btOut != null)
                    headsetLabel.set(
                        deviceProductName(btIn) ?: deviceProductName(btOut)
                            ?: deviceProductName(target),
                    )
                    // Keep MODE_NORMAL so USAGE_MEDIA TTS is not forced onto SCO
                    // (MODE_IN_COMMUNICATION + media was a common silent path).
                    setSpeakerphone(false, forceModeNormal = true)
                    return true
                }
            }
        }

        // Legacy SCO path (pre-S, or setCommunicationDevice refused).
        return startBluetoothScoAndWait(am, btIn, btOut)
    }

    private fun startBluetoothScoAndWait(
        am: AudioManager,
        btIn: AudioDeviceInfo?,
        btOut: AudioDeviceInfo?,
    ): Boolean {
        val latch = CountDownLatch(1)
        scoLatchRef.set(latch)
        scoConnected.set(false)
        runCatching {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
        }.onFailure {
            Log.w(TAG, "startBluetoothSco: ${it.message}")
            scoLatchRef.compareAndSet(latch, null)
            return false
        }
        // SCO connects asynchronously; wait briefly (or until receiver fires).
        val connected = scoConnected.get() || latch.await(2_000L, TimeUnit.MILLISECONDS) ||
            run {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn
            }
        scoLatchRef.compareAndSet(latch, null)
        if (!connected) {
            Log.w(TAG, "Bluetooth SCO did not connect in time")
            clearBluetoothSco(am)
            return false
        }
        preferredInputDevice.set(btIn ?: findBluetoothInput(am))
        // Leave playback unpinned — SCO-only preferred out often silences TTS.
        preferredOutputDevice.set(null)
        btHeadsetMicActive.set(true)
        btOutputPreferred.set(btOut != null)
        headsetLabel.set(
            deviceProductName(preferredInputDevice.get())
                ?: deviceProductName(btOut),
        )
        setSpeakerphone(false, forceModeNormal = false)
        return true
    }

    private fun clearBluetoothSco(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        }
        runCatching {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
            }
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        }
        scoConnected.set(false)
        scoLatchRef.getAndSet(null)
        runCatching {
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private fun registerAudioRouteWatchers() {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        if (scoReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            scoConnected.set(true)
                            scoLatchRef.get()?.countDown()
                            Log.i(TAG, "Bluetooth SCO connected")
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            scoConnected.set(false)
                            Log.i(TAG, "Bluetooth SCO disconnected")
                            if (running.get()) {
                                scope.launch { onAudioDevicesChanged() }
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    ctx.registerReceiver(receiver, filter)
                }
                scoReceiver = receiver
            }.onFailure { Log.w(TAG, "SCO receiver: ${it.message}") }
        }

        if (audioDeviceCallback == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (!running.get()) return
                    if (addedDevices.any {
                            isBluetoothInputType(it.type) || isBluetoothOutputType(it.type)
                        }
                    ) {
                        scope.launch { onAudioDevicesChanged() }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (!running.get()) return
                    if (removedDevices.any {
                            isBluetoothInputType(it.type) || isBluetoothOutputType(it.type)
                        }
                    ) {
                        scope.launch { onAudioDevicesChanged() }
                    }
                }
            }
            runCatching {
                am.registerAudioDeviceCallback(cb, mainHandler)
                audioDeviceCallback = cb
            }.onFailure { Log.w(TAG, "AudioDeviceCallback: ${it.message}") }
        }
    }

    private fun onAudioDevicesChanged() {
        if (!running.get()) return
        val changed = prepareAudioRoute(force = false)
        if (!changed) return
        // Rebuild playback path so setPreferredDevice sticks; reopen mic for new input.
        releasePlaybackTrackOnly()
        ensurePlaybackTrack()
        val client = clientRef.get()
        if (client != null && running.get()) {
            startMicCapture(client)
        }
        if (turn.get() == Turn.Listening || turn.get() == Turn.Idle) {
            setPhase("live", Turn.Listening, listeningStatusLine())
        } else {
            // Keep phase; refresh status suffix only when on live.
            publish()
        }
        Log.i(
            TAG,
            "audio route changed → headsetMic=${btHeadsetMicActive.get()} " +
                "btOut=${btOutputPreferred.get()} label=${headsetLabel.get()}",
        )
    }

    private fun releasePlaybackTrackOnly() {
        runCatching {
            audioTrackRef.getAndSet(null)?.let {
                it.pause()
                it.flush()
                it.release()
            }
        }
    }

    private fun releaseAudioRoute() {
        val ctx = appCtx
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        scoReceiver?.let { recv ->
            runCatching { ctx?.unregisterReceiver(recv) }
            scoReceiver = null
        }
        audioDeviceCallback?.let { cb ->
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { am.unregisterAudioDeviceCallback(cb) }
            }
            audioDeviceCallback = null
        }
        if (am != null) clearBluetoothSco(am)
        preferredInputDevice.set(null)
        preferredOutputDevice.set(null)
        btHeadsetMicActive.set(false)
        btOutputPreferred.set(false)
        headsetLabel.set(null)
        lastRouteFingerprint.set(null)
        scoConnected.set(false)
    }

    /**
     * @param forceModeNormal when true, reset MODE_NORMAL (phone speaker path).
     *   When false, leave MODE_IN_COMMUNICATION alone for active HFP/SCO.
     */
    private fun setSpeakerphone(on: Boolean, forceModeNormal: Boolean = true) {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Never force speaker while a BT headset route is intentional — that steals
        // A2DP/SCO and sends TTS to the phone loudspeaker with the case closed.
        if (on && (btHeadsetMicActive.get() || btOutputPreferred.get())) {
            runCatching {
                @Suppress("DEPRECATION")
                if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false
            }
            return
        }
        runCatching {
            if (forceModeNormal && am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
            @Suppress("DEPRECATION")
            if (am.isSpeakerphoneOn != on) {
                am.isSpeakerphoneOn = on
            }
        }
    }

    private fun requestPlaybackFocus() {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // Phone-only: re-assert speaker so replies beat call mode / quiet routing.
        // A2DP / headset: leave speaker off; system routes USAGE_MEDIA to buds.
        if (!btHeadsetMicActive.get() && !btOutputPreferred.get()) {
            setSpeakerphone(true, forceModeNormal = true)
        } else {
            setSpeakerphone(false, forceModeNormal = false)
        }
        // STREAM_MUSIC volume can be 0 while ring/call is up — voice uses MEDIA.
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0 && cur == 0) {
                Log.w(TAG, "STREAM_MUSIC volume is 0 — TTS will be silent until raised")
            }
        }
        if (focusRequestRef.get() != null) return
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
        // Only clear speaker when not on BT; keep HFP/SCO alive for the next user turn.
        if (!btHeadsetMicActive.get() && !btOutputPreferred.get()) {
            setSpeakerphone(false)
        }
    }

    private fun ensurePlaybackTrack() {
        val existing = audioTrackRef.get()
        if (existing != null) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            // Never re-pin output — setPreferredDevice(SCO/A2DP) was a common silence path.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { existing.setPreferredDevice(null) }
            }
            return
        }
        requestPlaybackFocus()
        val rate = GrokAssistantVoiceClient.SAMPLE_RATE
        // ~500ms min floor so MODE_STREAM rarely underruns mid-sentence.
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
            // 4× min (~2s) gives write() room without dropping frames on jitter.
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Unpinned track: system picks speaker (speakerphone) or A2DP for USAGE_MEDIA.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { track.setPreferredDevice(null) }
        }
        runCatching { track.setVolume(1f) }
        track.play()
        audioTrackRef.set(track)
        Log.d(
            TAG,
            "AudioTrack started rate=$rate buf=${minBuf * 4} " +
                "btOut=${btOutputPreferred.get()} headsetMic=${btHeadsetMicActive.get()}",
        )
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty() || !running.get()) return
        val total = responsePcmBytes.addAndGet(pcm.size)
        ensurePlaybackWorker()
        ensurePlaybackTrack()
        requestPlaybackFocus()
        // Count queued bytes before offer so mute/drain math stays ahead of the speaker.
        queuedPcmBytes.addAndGet(pcm.size)
        lastPlaybackActivityMs.set(SystemClock.uptimeMillis())
        // First audio of the reply — ensure Speaking + mute (also set on response.created).
        if (state.get() != State.ToolBusy) {
            if (turn.get() != Turn.GrokSpeaking) {
                setPhase("speaking", Turn.GrokSpeaking, phaseStatus("speaking"))
            } else {
                applyMicMutePolicy(clientRef.get())
            }
        } else {
            applyMicMutePolicy(clientRef.get())
        }
        if (total == pcm.size) {
            Log.d(TAG, "playPcm first frame size=${pcm.size} focus=${focusRequestRef.get() != null}")
        }
        noteLevel(pcmRms(pcm))
        // Never write from the WebSocket thread — only the playback worker touches AudioTrack.
        if (!playbackQueue.offer(pcm)) {
            Log.w(TAG, "playback queue full — dropping ${pcm.size}b frame")
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
                    // Rare with WRITE_BLOCKING; yield briefly.
                    delaySoft(5)
                    continue
                }
                offset += written
                writtenTotal += written
            }
            if (writtenTotal > 0) {
                extendPlaybackDeadline(writtenTotal)
                // Drive waveform from audio actually leaving the speaker — not only
                // when deltas arrive on the socket (those can be large/bursty).
                noteLevel(max(pcmRms(pcm), 0.12f))
            }
        } catch (e: Exception) {
            Log.w(TAG, "writePcm: ${e.message}")
        } finally {
            queuedPcmBytes.updateAndGet { (it - pcm.size).coerceAtLeast(0) }
            applyMicMutePolicy(clientRef.get())
            tryFinishPlaybackDrain()
        }
    }

    private data class OpenedMic(
        val record: AudioRecord,
        val captureRate: Int,
        val sourceName: String,
    )

    /**
     * Open the mic at a device-native rate when possible (often 48 kHz on Samsung),
     * then we downsample to [GrokAssistantVoiceClient.SAMPLE_RATE] ourselves.
     * Relying on HAL 24 kHz resampling is a common source of garbled ASR.
     *
     * With a Bluetooth headset mic (HFP/SCO), prefer VOICE_COMMUNICATION + 16 kHz
     * (wideband SCO) and pin [AudioRecord.setPreferredDevice] to the SCO input.
     */
    private fun openBestMic(): OpenedMic? {
        // Refresh route in case headphones connected between session start and mic open.
        prepareAudioRoute(force = false)
        val preferBt = btHeadsetMicActive.get()
        val preferred = preferredInputDevice.get()

        // Phone path: UNPROCESSED → VOICE_RECOGNITION → MIC (avoid VOICE_COMMUNICATION
        // earpiece side-effects). BT path: VOICE_COMMUNICATION first (HFP/SCO).
        val sources = buildList {
            if (preferBt) {
                add(MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION")
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
                add(MediaRecorder.AudioSource.MIC to "MIC")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
                }
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
                add(MediaRecorder.AudioSource.MIC to "MIC")
            }
        }
        // BT SCO is typically 16 kHz (mSBC) or 8 kHz (CVSD); try those first on headset.
        val rates = if (preferBt) {
            intArrayOf(16_000, 8_000, 24_000, 48_000, 44_100, 32_000)
        } else {
            intArrayOf(48_000, 44_100, 32_000, 24_000, 16_000)
        }
        for ((source, name) in sources) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) continue
                val buf = minBuf.coerceAtLeast(rate / 10 * 2) * 2
                val record = try {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        buf,
                    )
                } catch (_: Exception) {
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { record.release() }
                    continue
                }
                if (preferred != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pinned = runCatching { record.setPreferredDevice(preferred) }
                        .getOrDefault(false)
                    if (!pinned && preferBt) {
                        // Preferred SCO pin failed — still usable if comm device is set.
                        Log.w(TAG, "mic setPreferredDevice failed type=${preferred.type}")
                    } else {
                        Log.d(TAG, "mic preferredDevice type=${preferred.type} ok=$pinned")
                    }
                }
                Log.i(
                    TAG,
                    "mic open source=$name rate=$rate minBuf=$minBuf " +
                        "bt=${preferBt} route=${headsetLabel.get() ?: "phone"}",
                )
                return OpenedMic(record, rate, name)
            }
        }
        return null
    }

    private fun attachMicEffects(sessionId: Int): List<AudioEffect> = buildList {
        fun tryEnable(factory: () -> AudioEffect?): AudioEffect? {
            val effect = try {
                factory()
            } catch (_: Exception) {
                null
            } ?: return null
            return try {
                effect.enabled = true
                effect
            } catch (_: Exception) {
                runCatching { effect.release() }
                null
            }
        }
        // AGC first — quiet speakers were under-driving ASR/VAD.
        if (AutomaticGainControl.isAvailable()) {
            tryEnable { AutomaticGainControl.create(sessionId) }?.let(::add)
        }
        if (NoiseSuppressor.isAvailable()) {
            tryEnable { NoiseSuppressor.create(sessionId) }?.let(::add)
        }
        // AEC is best-effort; often only hooks on VOICE_COMMUNICATION. Soft mute
        // remains the primary half-duplex echo strategy.
        if (AcousticEchoCanceler.isAvailable()) {
            tryEnable { AcousticEchoCanceler.create(sessionId) }?.let(::add)
        }
        Log.d(TAG, "mic effects enabled=${size}")
    }

    private fun startMicCapture(client: GrokAssistantVoiceClient) {
        micJob.getAndSet(null)?.cancel()
        val job = scope.launch {
            val targetRate = GrokAssistantVoiceClient.SAMPLE_RATE
            val opened = try {
                openBestMic()
            } catch (e: SecurityException) {
                fail("Microphone permission denied")
                return@launch
            } catch (e: Exception) {
                fail(e.message ?: "mic_open_failed")
                return@launch
            }
            if (opened == null) {
                fail("Microphone not available")
                return@launch
            }
            val record = opened.record
            val captureRate = opened.captureRate
            // Effects are best-effort on phone mics; SCO often rejects NS/AEC — ignore failures.
            val effects = if (btHeadsetMicActive.get()) {
                emptyList()
            } else {
                attachMicEffects(record.audioSessionId)
            }
            audioRecordRef.set(record)
            record.startRecording()
            Log.i(
                TAG,
                "mic capturing source=${opened.sourceName} rate=$captureRate " +
                    "headset=${btHeadsetMicActive.get()} label=${headsetLabel.get()}",
            )
            // Read ~40ms at capture rate; emit 20ms @ target (xAI cookbook frame size).
            val readBytes = (captureRate * 40 / 1000 * 2).coerceAtLeast(640)
            val frame = ByteArray(readBytes)
            val sendFrameBytes =
                (targetRate * GrokAssistantVoiceClient.SEND_FRAME_MS / 1000 * 2)
                    .coerceAtLeast(640)
            val pending = ByteArrayOutputStream(sendFrameBytes * 4)
            try {
                while (isActive && running.get()) {
                    val n = record.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    // Keep PCM16 sample alignment (drop a trailing odd byte if any).
                    val even = (n / 2) * 2
                    if (even < 2) continue
                    val atTarget = if (captureRate == targetRate) {
                        frame.copyOf(even)
                    } else {
                        GrokAssistantVoiceClient.resamplePcm16Mono(
                            frame,
                            even,
                            captureRate,
                            targetRate,
                        )
                    }
                    val boosted = GrokAssistantVoiceClient.softGainPcm16(atTarget)
                    val rms = pcmRms(boosted, boosted.size)
                    lastLocalRms.set(rms)
                    // Re-evaluate mute each frame (covers post-TTS hold → unmute).
                    applyMicMutePolicy(client)
                    val muted = micMuted.get() || !isMicSendAllowed()
                    val t = turn.get()
                    // Always drive the waveform from mic energy. During GrokSpeaking
                    // we still sample (soft) so the strip never freezes when TTS
                    // deltas are sparse; playPcm/writePcm also push speaker RMS.
                    when (t) {
                        Turn.UserSpeaking -> noteLevel(max(rms, 0.10f))
                        Turn.Listening -> noteLevel(max(rms * 0.65f, if (rms > 0.02f) 0.05f else 0f))
                        Turn.Thinking -> noteLevel(max(rms * 0.7f, 0.05f))
                        Turn.GrokSpeaking -> {
                            // Speaker path owns loudness; keep a faint mic-reactive floor.
                            if (rms > 0.04f) noteLevel(max(level.get(), rms * 0.35f))
                        }
                        Turn.ToolBusy -> noteLevel(max(rms * 0.4f, 0.05f))
                        else -> if (!muted) noteLevel(rms * 0.08f)
                    }
                    // Soft half-duplex: always read mic; only skip *sending* during
                    // Grok TTS / tools / brief post-playback hold. Never stop().
                    if (muted || hasLocalPlaybackRemaining()) {
                        pending.reset()
                        continue
                    }
                    pending.write(boosted)
                    var blob = pending.toByteArray()
                    var offset = 0
                    while (blob.size - offset >= sendFrameBytes) {
                        val chunk = blob.copyOfRange(offset, offset + sendFrameBytes)
                        offset += sendFrameBytes
                        if (useBinary.get()) {
                            client.sendBinary(chunk)
                        } else {
                            client.appendInputAudioBase64(
                                GrokAssistantVoiceClient.pcm16ToBase64(chunk),
                            )
                        }
                    }
                    pending.reset()
                    if (offset < blob.size) {
                        pending.write(blob, offset, blob.size - offset)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "mic loop: ${e.message}")
            } finally {
                effects.forEach { runCatching { it.release() } }
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
