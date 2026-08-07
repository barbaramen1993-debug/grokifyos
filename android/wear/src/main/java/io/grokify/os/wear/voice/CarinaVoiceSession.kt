package io.grokify.os.wear.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.grokify.os.wear.data.WearPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Carina voice agent on Wear — xAI Voice Agent Realtime (Voice 2.0), voice=carina.
 */
object CarinaVoiceSession {
    private const val TAG = "CarinaVoiceSession"
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Turn { Idle, Connecting, Listening, Thinking, Speaking, Error }

    data class Snapshot(
        val turn: Turn,
        val statusLine: String?,
        val partialUser: String?,
        val partialAssistant: String?,
        val level: Float,
    )

    interface Listener {
        fun onSnapshot(snap: Snapshot)
        fun onTranscriptCommitted(role: String, text: String) {}
        fun onError(message: String) {}
    }

    private const val WATCHDOG_MS = 250L
    private const val THINKING_TIMEOUT_MS = 35_000L
    private const val CONNECTING_TIMEOUT_MS = 18_000L
    private const val PLAYBACK_MUTE_HOLD_MS = 700L
    private const val BARGE_IN_GUARD_MS = 650L
    private const val SPEAKING_IDLE_TIMEOUT_MS = 3_200L
    private const val LISTEN_OPEN_SETTLE_MS = 300L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerRef = AtomicReference<Listener?>(null)
    private val turn = AtomicReference(Turn.Idle)
    private val statusLine = AtomicReference<String?>(null)
    private val partialUser = AtomicReference<String?>(null)
    private val partialAssistant = AtomicReference<String?>(null)
    private val level = AtomicReference(0f)
    private val clientRef = AtomicReference<WearVoiceClient?>(null)
    private val micJob = AtomicReference<Job?>(null)
    private val audioTrackRef = AtomicReference<AudioTrack?>(null)
    private val audioRecordRef = AtomicReference<AudioRecord?>(null)
    private val running = AtomicBoolean(false)
    private val startGeneration = AtomicInteger(0)
    private val micMuted = AtomicBoolean(false)
    private val serverResponseActive = AtomicBoolean(false)
    private val phaseStartedMs = AtomicLong(0L)
    private val lastPlaybackActivityMs = AtomicLong(0L)
    private val playbackMuteUntilMs = AtomicLong(0L)
    private val listenOpenUntilMs = AtomicLong(0L)
    private val queuedPcmBytes = AtomicInteger(0)
    private val responsePcmBytes = AtomicInteger(0)

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(512)
    private val playbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarinaPlayback").apply { isDaemon = true }
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

    fun currentTurn(): Turn = turn.get()

    fun start(ctx: Context, listener: Listener) {
        val app = ctx.applicationContext
        appCtx = app
        listenerRef.set(listener)

        val apiKey = WearPrefs(app).spaceXaiApiKey
        if (apiKey.isBlank()) {
            fail("Add SpaceXAI API key (swipe up → Settings)")
            return
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail("Microphone permission required")
            return
        }
        if (running.get()) {
            publish()
            return
        }

        val gen = startGeneration.incrementAndGet()
        running.set(true)
        level.set(0f)
        partialUser.set(null)
        partialAssistant.set(null)
        serverResponseActive.set(false)
        responsePcmBytes.set(0)
        queuedPcmBytes.set(0)
        phaseStartedMs.set(SystemClock.uptimeMillis())
        setTurn(Turn.Connecting, "Connecting Carina…")
        startWatchdog()

        val instructions = buildString {
            append(CarinaTools.systemIdentity())
            append("\n\n")
            append(CarinaTools.toolInstructions())
            val snap = CarinaTools.currentSnapshot()
            append("\n\nLive snapshot at connect:\n")
            append("HR=${snap.heartRateBpm} steps=${snap.stepsToday} bat=${snap.batteryPct}% ")
            append("heading=${snap.headingDeg} weather=${snap.weatherTempC} ${snap.weatherLabel} ")
            append("media=${snap.mediaTitle}")
        }

        scope.launch {
            try {
                fun still(): Boolean = running.get() && startGeneration.get() == gen
                if (!still()) return@launch

                setTurn(Turn.Connecting, "Minting voice session…")
                val minted = WearVoiceClient.mintAuthToken(apiKey)
                val token = minted.ifBlank { apiKey }
                if (!still()) return@launch

                val prefs = WearPrefs(app)
                val resumeId = prefs.carinaConversationId
                val disconnectDetail = AtomicReference<String?>(null)
                val sessionCreatedSeen = AtomicBoolean(false)

                val client = WearVoiceClient(
                    onEvent = { event ->
                        if (event.optString("type") == "session.created") {
                            sessionCreatedSeen.set(true)
                        }
                        handleEvent(event)
                    },
                    onBinaryAudio = { pcm -> playPcm(pcm) },
                    onState = { connected, detail ->
                        if (!connected && running.get() && startGeneration.get() == gen) {
                            val msg = detail?.take(140) ?: "Voice socket closed"
                            disconnectDetail.set(msg)
                            if (turn.get() != Turn.Connecting) {
                                fail(msg)
                            }
                        }
                    },
                )
                clientRef.set(client)

                setTurn(Turn.Connecting, "Opening voice socket…")
                client.connect(authToken = token, conversationId = resumeId)
                var waits = 0
                while (still() && !client.isOpen && waits < 100) {
                    if (disconnectDetail.get() != null) break
                    Thread.sleep(50)
                    waits++
                }
                if (!still()) return@launch
                if (!client.isOpen) {
                    // Retry without resume + raw API key
                    prefs.clearCarinaConversationId()
                    client.connect(authToken = apiKey, conversationId = null)
                    waits = 0
                    while (still() && !client.isOpen && waits < 100) {
                        if (disconnectDetail.get() != null) break
                        Thread.sleep(50)
                        waits++
                    }
                }
                if (!still()) return@launch
                if (!client.isOpen) {
                    fail(disconnectDetail.get() ?: "Voice connect timeout")
                    return@launch
                }

                setTurn(Turn.Connecting, "Configuring Carina…")
                waits = 0
                while (still() && !sessionCreatedSeen.get() && waits < 40) {
                    Thread.sleep(50)
                    waits++
                }
                val tools = CarinaTools.sessionTools()
                fun sendConfig(): Boolean = client.sessionUpdate(
                    instructions = instructions,
                    voice = WearVoiceClient.VOICE_CARINA,
                    tools = tools,
                    sampleRate = WearVoiceClient.SAMPLE_RATE,
                    reasoningEffort = "none",
                )
                if (!sendConfig()) {
                    Thread.sleep(100)
                    if (still() && client.isOpen) sendConfig()
                }
                waits = 0
                while (still() && !client.isSessionReady && waits < 80) {
                    Thread.sleep(50)
                    waits++
                }
                if (!still()) return@launch
                if (!client.isSessionReady) {
                    sendConfig()
                    waits = 0
                    while (still() && !client.isSessionReady && waits < 40) {
                        Thread.sleep(50)
                        waits++
                    }
                }
                if (!still()) return@launch
                if (!client.isSessionReady) {
                    fail("Carina session did not configure — retry")
                    return@launch
                }

                client.clearInputAudioBuffer()
                ensurePlaybackTrack()
                listenOpenUntilMs.set(SystemClock.uptimeMillis() + LISTEN_OPEN_SETTLE_MS)
                markPhase(Turn.Listening, "Carina · listening")
                startMicCapture(client)
                Log.i(TAG, "Carina live")
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

    private fun stopInternal() {
        if (!running.getAndSet(false) && turn.get() == Turn.Idle) return
        startGeneration.incrementAndGet()
        mainHandler.removeCallbacks(watchdogRunnable)
        micJob.getAndSet(null)?.cancel()
        runCatching { audioRecordRef.getAndSet(null)?.release() }
        clientRef.getAndSet(null)?.disconnect()
        playbackQueue.clear()
        runCatching { audioTrackRef.getAndSet(null)?.release() }
        setTurn(Turn.Idle, null)
        partialUser.set(null)
        partialAssistant.set(null)
        level.set(0f)
        publish()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        val listener = listenerRef.get()
        setTurn(Turn.Error, message)
        mainHandler.post { listener?.onError(message) }
        stopInternal()
        // Keep error visible briefly via last status on Idle
        statusLine.set(message)
        setTurn(Turn.Idle, message)
        publish()
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun tickWatchdog() {
        if (!running.get()) return
        val now = SystemClock.uptimeMillis()
        when (turn.get()) {
            Turn.Connecting -> {
                if (now - phaseStartedMs.get() > CONNECTING_TIMEOUT_MS) {
                    fail("Connect timed out")
                }
            }
            Turn.Thinking -> {
                if (now - phaseStartedMs.get() > THINKING_TIMEOUT_MS) {
                    markPhase(Turn.Listening, "Carina · listening")
                    serverResponseActive.set(false)
                }
            }
            Turn.Speaking -> {
                val idle = now - lastPlaybackActivityMs.get() > SPEAKING_IDLE_TIMEOUT_MS &&
                    queuedPcmBytes.get() <= 0 &&
                    !serverResponseActive.get()
                if (idle) {
                    playbackMuteUntilMs.set(now + PLAYBACK_MUTE_HOLD_MS)
                    markPhase(Turn.Listening, "Carina · listening")
                }
            }
            else -> Unit
        }
    }

    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        when (type) {
            "input_audio_buffer.speech_started" -> {
                val now = SystemClock.uptimeMillis()
                if (now < playbackMuteUntilMs.get()) return
                if (turn.get() == Turn.Speaking &&
                    now - lastPlaybackActivityMs.get() < BARGE_IN_GUARD_MS
                ) {
                    return
                }
                if (serverResponseActive.get()) {
                    clientRef.get()?.cancelResponse()
                    serverResponseActive.set(false)
                    flushPlayback()
                }
                markPhase(Turn.Listening, "Carina · hearing you…")
                partialUser.set(null)
            }
            "input_audio_buffer.speech_stopped" -> {
                if (turn.get() != Turn.Speaking) {
                    markPhase(Turn.Thinking, "Carina · thinking…")
                }
            }
            "conversation.item.input_audio_transcription.completed",
            "conversation.item.input_audio_transcription.done",
            -> {
                val text = event.optString("transcript", "")
                    .ifBlank { event.optJSONObject("item")?.optString("transcript").orEmpty() }
                    .trim()
                if (text.isNotEmpty()) {
                    partialUser.set(text)
                    publish()
                    mainHandler.post {
                        listenerRef.get()?.onTranscriptCommitted("user", text)
                    }
                }
            }
            "conversation.item.input_audio_transcription.updated" -> {
                val text = event.optString("transcript", "").trim()
                if (text.isNotEmpty()) {
                    partialUser.set(text)
                    publish()
                }
            }
            "response.created" -> {
                serverResponseActive.set(true)
                responsePcmBytes.set(0)
                partialAssistant.set(null)
                markPhase(Turn.Thinking, "Carina · thinking…")
            }
            "response.output_audio.delta",
            "response.audio.delta",
            -> {
                val b64 = event.optString("delta", "")
                    .ifBlank { event.optString("audio", "") }
                if (b64.isNotEmpty()) {
                    playPcm(WearVoiceClient.base64ToPcm16(b64))
                }
            }
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta",
            -> {
                val d = event.optString("delta", "")
                if (d.isNotEmpty()) {
                    partialAssistant.set((partialAssistant.get().orEmpty() + d).take(800))
                    if (turn.get() != Turn.Speaking) {
                        markPhase(Turn.Speaking, "Carina · speaking")
                    } else {
                        publish()
                    }
                }
            }
            "response.output_audio_transcript.done",
            "response.audio_transcript.done",
            -> {
                val t = event.optString("transcript", "").trim()
                    .ifBlank { partialAssistant.get().orEmpty().trim() }
                if (t.isNotEmpty()) {
                    partialAssistant.set(t)
                    mainHandler.post {
                        listenerRef.get()?.onTranscriptCommitted("assistant", t)
                    }
                }
            }
            "response.function_call_arguments.done" -> {
                val call = CarinaTools.parseFunctionCall(event) ?: return
                scope.launch {
                    val ctx = appCtx ?: return@launch
                    val result = CarinaTools.execute(ctx, call)
                    clientRef.get()?.sendFunctionOutput(result.callId, result.outputJson)
                    markPhase(Turn.Thinking, "Carina · ${call.name}…")
                }
            }
            "response.done", "response.cancelled" -> {
                serverResponseActive.set(false)
                val leftover = partialAssistant.get()?.trim().orEmpty()
                if (leftover.isNotEmpty()) {
                    mainHandler.post {
                        listenerRef.get()?.onTranscriptCommitted("assistant", leftover)
                    }
                }
                if (queuedPcmBytes.get() <= 0) {
                    playbackMuteUntilMs.set(SystemClock.uptimeMillis() + PLAYBACK_MUTE_HOLD_MS)
                    markPhase(Turn.Listening, "Carina · listening")
                } else {
                    markPhase(Turn.Speaking, "Carina · speaking")
                }
            }
            "conversation.created" -> {
                val id = event.optJSONObject("conversation")?.optString("id").orEmpty()
                    .ifBlank { event.optString("conversation_id", "") }
                    .trim()
                if (id.isNotEmpty()) {
                    appCtx?.let { WearPrefs(it).carinaConversationId = id }
                }
            }
            "error" -> {
                val msg = event.optJSONObject("error")?.optString("message")
                    .orEmpty()
                    .ifBlank { event.optString("error", "error") }
                if (WearVoiceClient.isBenignCancelError(msg)) return
                Log.e(TAG, "server error: $msg")
                statusLine.set(msg.take(80))
                publish()
            }
            else -> Log.d(TAG, "event $type")
        }
    }

    private fun startMicCapture(client: WearVoiceClient) {
        micJob.getAndSet(null)?.cancel()
        val job = scope.launch {
            val rateCandidates = intArrayOf(24_000, 48_000, 16_000, 44_100)
            var record: AudioRecord? = null
            var usedRate = WearVoiceClient.SAMPLE_RATE
            for (rate in rateCandidates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) continue
                val bufSize = max(minBuf, rate / 50 * 2) * 2
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    usedRate = rate
                    break
                }
                rec.release()
            }
            if (record == null) {
                fail("Could not open microphone")
                return@launch
            }
            audioRecordRef.set(record)
            record.startRecording()
            val frameSamples = WearVoiceClient.SAMPLE_RATE * WearVoiceClient.SEND_FRAME_MS / 1000
            val readBuf = ByteArray(max(record.bufferSizeInFrames, frameSamples) * 2)
            val targetFrame = frameSamples * 2
            val accum = ArrayList<Byte>(targetFrame * 2)

            try {
                while (isActive && running.get()) {
                    val n = record.read(readBuf, 0, readBuf.size)
                    if (n <= 0) continue
                    val now = SystemClock.uptimeMillis()
                    val mute = micMuted.get() ||
                        now < playbackMuteUntilMs.get() ||
                        now < listenOpenUntilMs.get() ||
                        turn.get() == Turn.Speaking
                    val rms = pcmRms(readBuf, n)
                    level.set(rms)
                    publishLevelThrottled()

                    if (mute) {
                        accum.clear()
                        continue
                    }
                    val pcm = if (usedRate == WearVoiceClient.SAMPLE_RATE) {
                        readBuf.copyOf(n)
                    } else {
                        WearVoiceClient.resamplePcm16Mono(readBuf, n, usedRate, WearVoiceClient.SAMPLE_RATE)
                    }
                    for (b in pcm) accum.add(b)
                    while (accum.size >= targetFrame) {
                        val frame = ByteArray(targetFrame)
                        for (i in 0 until targetFrame) frame[i] = accum.removeAt(0)
                        client.appendInputAudioBase64(WearVoiceClient.pcm16ToBase64(frame))
                    }
                }
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

    private var lastLevelPublish = 0L
    private fun publishLevelThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLevelPublish < 50) return
        lastLevelPublish = now
        publish()
    }

    private fun pcmRms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        var n = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
            sum += (s * s).toDouble()
            n++
            i += 2
        }
        if (n == 0) return 0f
        return (sqrt(sum / n) / 32768.0).toFloat().coerceIn(0f, 1f)
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

    private fun ensurePlaybackTrack() {
        val existing = audioTrackRef.get()
        if (existing != null) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return
        }
        val rate = WearVoiceClient.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(rate / 2 * 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrackRef.set(track)
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty() || !running.get()) return
        responsePcmBytes.addAndGet(pcm.size)
        ensurePlaybackWorker()
        ensurePlaybackTrack()
        queuedPcmBytes.addAndGet(pcm.size)
        lastPlaybackActivityMs.set(SystemClock.uptimeMillis())
        if (turn.get() != Turn.Speaking) {
            markPhase(Turn.Speaking, "Carina · speaking")
        }
        playbackQueue.offer(pcm)
    }

    private fun writePcmBlocking(pcm: ByteArray) {
        val track = audioTrackRef.get() ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            var offset = 0
            while (offset < pcm.size) {
                val w = track.write(pcm, offset, pcm.size - offset)
                if (w <= 0) break
                offset += w
            }
            lastPlaybackActivityMs.set(SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "writePcm: ${e.message}")
        } finally {
            queuedPcmBytes.addAndGet(-pcm.size)
        }
    }

    private fun flushPlayback() {
        playbackQueue.clear()
        queuedPcmBytes.set(0)
        runCatching { audioTrackRef.get()?.pause() }
        runCatching { audioTrackRef.get()?.flush() }
        runCatching { audioTrackRef.get()?.play() }
    }

    private fun setTurn(t: Turn, status: String?) {
        turn.set(t)
        statusLine.set(status)
        phaseStartedMs.set(SystemClock.uptimeMillis())
        publish()
    }

    private fun markPhase(t: Turn, status: String?) {
        setTurn(t, status)
    }

    private fun publish() {
        val snap = Snapshot(
            turn = turn.get(),
            statusLine = statusLine.get(),
            partialUser = partialUser.get(),
            partialAssistant = partialAssistant.get(),
            level = level.get(),
        )
        mainHandler.post { listenerRef.get()?.onSnapshot(snap) }
    }
}
