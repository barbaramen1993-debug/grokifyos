package io.grokify.os.apps

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Passive always-on mic for “Okay Grok” **without audio focus**.
 *
 * Unlike [android.speech.SpeechRecognizer], plain [AudioRecord] does not duck or
 * pause Spotify/media — same idea as third-party assistants (Alexa app, etc.).
 * True Google DSP hotword still requires system/assistant privileges we don't have.
 *
 * Flow: capture → adaptive VAD → short utterance → cloud STT (xAI) → phrase match.
 */
class GrokAssistantWakeListenEngine(
    private val appCtx: Context,
    private val onUtteranceText: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>(null)
    private val recordRef = AtomicReference<AudioRecord?>(null)
    private val effectsRef = AtomicReference<List<AudioEffect>>(emptyList())

    /** In-flight STT count for light rate limiting. */
    private val sttInFlight = AtomicBoolean(false)
    @Volatile private var lastSttMs = 0L
    @Volatile private var sttWindowStartMs = 0L
    @Volatile private var sttWindowCount = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = thread(name = "GrokWakeListen", isDaemon = true) {
            runLoop()
        }
        worker.set(t)
    }

    fun stop() {
        running.set(false)
        stopMic()
        worker.getAndSet(null)?.interrupt()
    }

    val isRunning: Boolean get() = running.get()

    private fun runLoop() {
        val vad = GrokAssistantWakeVad()
        while (running.get()) {
            if (GrokAssistantMic.isQuietNow() ||
                GrokAssistantSession.isBusy ||
                GrokAssistantVoiceSession.isLive ||
                GrokAssistantOverlayService.isRunning()
            ) {
                stopMic()
                sleepQuiet(500L)
                continue
            }
            val owner = GrokAssistantMic.current()
            if (owner == GrokAssistantMic.Owner.Overlay || owner == GrokAssistantMic.Owner.Voice) {
                stopMic()
                sleepQuiet(600L)
                continue
            }
            if (!GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake)) {
                sleepQuiet(400L)
                continue
            }

            val record = ensureMic()
            if (record == null) {
                GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
                onStatus("Mic unavailable")
                sleepQuiet(3_000L)
                continue
            }

            val frame = ByteArray(vad.frameBytes)
            try {
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.startRecording()
                }
                onStatus("Listening for “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}” (media-safe)")
                while (running.get()) {
                    if (GrokAssistantMic.isQuietNow() ||
                        GrokAssistantSession.isBusy ||
                        GrokAssistantVoiceSession.isLive ||
                        GrokAssistantOverlayService.isRunning()
                    ) {
                        break
                    }
                    val n = try {
                        record.read(frame, 0, frame.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) {
                        if (n < 0) break
                        continue
                    }
                    val slice = if (n == frame.size) frame else frame.copyOf(n)
                    val music = isMusicLikelyPlaying()
                    val utter = vad.accept(slice, musicBoost = music) ?: continue
                    // Don't block the capture loop on network STT.
                    handleUtteranceAsync(utter)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "mic permission: ${e.message}")
                onStatus("Mic permission needed")
                running.set(false)
            } catch (e: Exception) {
                Log.w(TAG, "listen loop: ${e.message}")
            } finally {
                // Keep AudioRecord warm across quiet pauses; full stop on engine stop.
                if (!running.get()) stopMic()
                GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
            }
            sleepQuiet(200L)
        }
        stopMic()
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
    }

    private fun handleUtteranceAsync(utter: GrokAssistantWakeVad.Utterance) {
        // Too long → probably a full sentence / conversation; skip to save quota.
        if (utter.durationMs > GrokAssistantWakeVad.MAX_UTTER_MS) return
        if (utter.pcm.size < 2_000) return
        if (!sttInFlight.compareAndSet(false, true)) return

        val now = System.currentTimeMillis()
        if (now - lastSttMs < MIN_STT_GAP_MS) {
            sttInFlight.set(false)
            return
        }
        if (now - sttWindowStartMs > 60_000L) {
            sttWindowStartMs = now
            sttWindowCount = 0
        }
        if (sttWindowCount >= MAX_STT_PER_MIN) {
            sttInFlight.set(false)
            return
        }
        sttWindowCount++
        lastSttMs = now

        thread(name = "GrokWakeStt", isDaemon = true) {
            try {
                val token = io.grokify.os.apps.plugin.HostApiKeyStore
                    .getValue(appCtx, io.grokify.os.data.ApiKeyIds.SPACEXAI)
                    ?.trim()
                    .orEmpty()
                if (token.isBlank()) {
                    Log.w(TAG, "no SpaceXAI key — wake STT skipped (media-safe mode needs key)")
                    onStatus("Add SpaceXAI key for media-safe wake")
                    return@thread
                }
                val text = GrokAssistantWakeStt.transcribePcm(token, utter.pcm)
                    ?: return@thread
                Log.i(TAG, "wake stt (${utter.durationMs}ms): ${text.take(80)}")
                // Service applies wake-phrase match; non-matches are ignored (no focus/duck).
                if (text.isNotBlank()) onUtteranceText(text)
            } finally {
                sttInFlight.set(false)
            }
        }
    }

    private fun ensureMic(): AudioRecord? {
        recordRef.get()?.let { existing ->
            if (existing.state == AudioRecord.STATE_INITIALIZED) return existing
            stopMic()
        }
        val opened = openMic() ?: return null
        recordRef.set(opened)
        effectsRef.set(attachEffects(opened.audioSessionId))
        return opened
    }

    private fun stopMic() {
        effectsRef.getAndSet(emptyList()).forEach { e ->
            runCatching {
                e.enabled = false
                e.release()
            }
        }
        recordRef.getAndSet(null)?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
    }

    private fun openMic(): AudioRecord? {
        // Prefer VOICE_RECOGNITION / MIC — avoid VOICE_COMMUNICATION (can flip audio mode).
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        val rate = GrokAssistantWakeVad.SAMPLE_RATE
        for (source in sources) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) continue
            val buf = minBuf.coerceAtLeast(rate / 5 * 2) * 2
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
            Log.i(TAG, "wake mic open source=$source rate=$rate")
            return record
        }
        return null
    }

    private fun attachEffects(sessionId: Int): List<AudioEffect> = buildList {
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
        if (AutomaticGainControl.isAvailable()) {
            tryEnable { AutomaticGainControl.create(sessionId) }?.let(::add)
        }
        if (NoiseSuppressor.isAvailable()) {
            tryEnable { NoiseSuppressor.create(sessionId) }?.let(::add)
        }
        // Critical for media-safe wake: cancel speaker playback so VAD sees voice, not song.
        if (AcousticEchoCanceler.isAvailable()) {
            tryEnable { AcousticEchoCanceler.create(sessionId) }?.let(::add)
        }
    }

    private fun isMusicLikelyPlaying(): Boolean {
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            @Suppress("DEPRECATION")
            am.isMusicActive
        } catch (_: Exception) {
            false
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "GrokAssistantWakeEng"
        private const val MIN_STT_GAP_MS = 700L
        private const val MAX_STT_PER_MIN = 20
    }
}
