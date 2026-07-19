package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.grokify.os.GrokifyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Background “Okay Grok” listener.
 *
 * Uses [SpeechRecognizer] in a restart loop (not a dedicated DSP hotword engine).
 * When a wake phrase is heard, expands the floating overlay and either:
 * - sends remainder text as the query, or
 * - starts a short command-listen window for the next utterance.
 */
class GrokAssistantWakeService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speech: SpeechRecognizer? = null
    private var running = false
    private var listening = false
    /** After wake with no remainder — next final result is the command. */
    private var awaitingCommand = false
    private var restartScheduled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                // Soft pause while overlay uses the mic
                cancelListening()
                return START_STICKY
            }
            ACTION_RESUME -> {
                scheduleRestart(delayMs = 400L)
                return START_STICKY
            }
        }
        val store = GrokAssistantStore(this)
        if (!store.enabled || !store.wakeWordEnabled) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        if (!hasMicPermission()) {
            startAsForeground(status = "Mic permission needed")
            scheduleRestart(delayMs = 8_000L)
            return START_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startAsForeground(status = "Speech recognition unavailable")
            return START_STICKY
        }
        running = true
        startAsForeground(status = "Listening for “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}”")
        scheduleRestart(delayMs = 200L)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        destroySpeech()
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        destroySpeech()
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startAsForeground(status: String) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            io.grokify.os.widgets.WidgetNav.openPluginIntent(
                this,
                io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            2,
            Intent(this, GrokAssistantWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, GrokifyApp.CHANNEL_ASSISTANT)
            .setContentTitle("${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY} listening")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= 34) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground: ${e.message}")
            runCatching { startForeground(NOTIF_ID, n) }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        if (restartScheduled) return
        restartScheduled = true
        mainHandler.postDelayed({
            restartScheduled = false
            if (!running) return@postDelayed
            tryStartListening()
        }, delayMs)
    }

    private fun tryStartListening() {
        if (!running) return
        val store = GrokAssistantStore(this)
        if (!store.enabled || !store.wakeWordEnabled) {
            stopSelfSafely()
            return
        }
        if (!hasMicPermission()) {
            startAsForeground(status = "Mic permission needed")
            scheduleRestart(delayMs = 8_000L)
            return
        }
        if (GrokAssistantMic.isQuietNow()) {
            scheduleRestart(delayMs = 600L)
            return
        }
        if (GrokAssistantSession.isBusy) {
            scheduleRestart(delayMs = 1_200L)
            return
        }
        if (GrokAssistantMic.current() == GrokAssistantMic.Owner.Overlay) {
            scheduleRestart(delayMs = 900L)
            return
        }
        if (!GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Wake)) {
            scheduleRestart(delayMs = 900L)
            return
        }
        if (listening) return
        val sr = ensureSpeech() ?: run {
            GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
            scheduleRestart(delayMs = 3_000L)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Prefer longer sessions when possible (OEM-dependent).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        runCatching {
            sr.startListening(intent)
            listening = true
            val label = if (awaitingCommand) {
                "Say your request…"
            } else {
                "Listening for “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}”"
            }
            startAsForeground(status = label)
        }.onFailure {
            Log.w(TAG, "startListening: ${it.message}")
            listening = false
            GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
            scheduleRestart(delayMs = 2_000L)
        }
    }

    private fun cancelListening() {
        listening = false
        runCatching {
            speech?.stopListening()
            speech?.cancel()
        }
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
    }

    private fun destroySpeech() {
        cancelListening()
        runCatching { speech?.destroy() }
        speech = null
    }

    private fun ensureSpeech(): SpeechRecognizer? {
        if (speech != null) return speech
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
                // Benign timeouts / no-match — just re-arm.
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT,
                    -> 350L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1_500L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 8_000L
                    else -> 900L
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    startAsForeground(status = "Mic permission needed")
                }
                // Don't drop awaitingCommand on a single timeout — user may still speak.
                scheduleRestart(delayMs = delay)
            }

            override fun onResults(results: Bundle?) {
                listening = false
                GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                handleHeard(texts)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Optional: early wake on partial — only when not awaiting command
                if (awaitingCommand) return
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val joined = texts.firstOrNull() ?: return
                val m = GrokAssistantWake.match(joined) ?: return
                // Don't act on partial alone if remainder empty (wait for final).
                if (m.remainder.isNotBlank()) {
                    // Let final results handle to avoid double-fire.
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech = sr
        return sr
    }

    private fun handleHeard(candidates: List<String>) {
        if (!running) return
        val store = GrokAssistantStore(this)
        if (!store.enabled || !store.wakeWordEnabled) {
            stopSelfSafely()
            return
        }

        if (awaitingCommand) {
            val cmd = candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            awaitingCommand = false
            if (cmd.isNotBlank()) {
                // Avoid re-triggering on wake phrase alone as the command.
                val m = GrokAssistantWake.match(cmd)
                val text = when {
                    m == null -> cmd
                    m.remainder.isNotBlank() -> m.remainder
                    else -> ""
                }
                if (text.isNotBlank()) {
                    onCommand(text)
                    scheduleRestart(delayMs = 1_500L)
                    return
                }
            }
            startAsForeground(
                status = "Didn't catch a request — say ${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY} again",
            )
            scheduleRestart(delayMs = 800L)
            return
        }

        // Normal wake scan — try each recognition alternative (STT n-best).
        var hit: GrokAssistantWake.Match? = null
        for (c in candidates) {
            hit = GrokAssistantWake.match(c)
            if (hit != null) break
        }
        if (hit == null) {
            scheduleRestart(delayMs = 250L)
            return
        }

        Log.i(TAG, "wake: phrase='${hit.phrase}' rem='${hit.remainder}' raw='${hit.raw}'")

        if (hit.remainder.isNotBlank() && !GrokAssistantWake.isWakeOnly(hit)) {
            // Phrase + command in one utterance — show overlay and send.
            activateUi(listen = false)
            onCommand(hit.remainder)
            scheduleRestart(delayMs = 1_500L)
        } else {
            // Wake alone — expand overlay and let it own the mic for the request.
            awaitingCommand = false
            activateUi(listen = true)
            startAsForeground(status = "Yes? Overlay listening…")
            // Stay paused long enough for overlay hold/auto listen to grab the mic.
            GrokAssistantMic.quietFor(400L)
            scheduleRestart(delayMs = 2_500L)
        }
    }

    /**
     * Always show the floating overlay when permitted; otherwise open the full app.
     * Does not require the Setup “Overlay enabled” toggle — wake should be visible.
     */
    private fun activateUi(listen: Boolean = false) {
        if (GrokAssistantOverlayService.canDrawOverlays(this)) {
            // Remember preference so boot/sync keeps overlay available after wake use.
            val store = GrokAssistantStore(this)
            if (!store.overlayEnabled) {
                store.overlayEnabled = true
            }
            if (listen) {
                GrokAssistantOverlayService.startListeningForCommand(this)
            } else {
                GrokAssistantOverlayService.start(this, expand = true)
            }
            GrokAssistantOverlayService.bumpTranscript(this)
        } else {
            openFullAssistant()
        }
    }

    private fun openFullAssistant() {
        val intent = io.grokify.os.widgets.WidgetNav.openPluginIntent(
            this,
            io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
        )
        // PendingIntent is more reliable from a background FGS than startActivity alone.
        val pi = PendingIntent.getActivity(
            this,
            77,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { pi.send() }
            .onFailure {
                runCatching {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        // Ensure Compose nav picks up the plugin even if activity was already resumed.
        io.grokify.os.widgets.WidgetNav.openPlugin(
            io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
        )
    }

    private fun onCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        startAsForeground(status = "Heard: ${trimmed.take(48)}")
        activateUi(listen = false)
        // Quiet while we process + speak so TTS doesn't re-trigger wake.
        GrokAssistantMic.quietFor(1_500L)
        scope.launch(Dispatchers.IO) {
            val result = GrokAssistantSession.send(applicationContext, trimmed)
            val quietMs = if (GrokAssistantStore(applicationContext).speakReplies) 5_000L else 900L
            GrokAssistantMic.quietFor(quietMs)
            mainHandler.post {
                GrokAssistantOverlayService.bumpTranscript(applicationContext)
                startAsForeground(
                    status = if (result.ok) {
                        "Listening for “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}”"
                    } else {
                        (result.errorText ?: "Error").take(60)
                    },
                )
            }
        }
    }

    companion object {
        private const val TAG = "GrokAssistantWake"
        private const val NOTIF_ID = 42043

        const val ACTION_START = "io.grokify.os.ASSISTANT_WAKE_START"
        const val ACTION_STOP = "io.grokify.os.ASSISTANT_WAKE_STOP"
        const val ACTION_PAUSE = "io.grokify.os.ASSISTANT_WAKE_PAUSE"
        const val ACTION_RESUME = "io.grokify.os.ASSISTANT_WAKE_RESUME"

        @Volatile
        var instance: GrokAssistantWakeService? = null

        fun start(ctx: Context) {
            val app = ctx.applicationContext
            val store = GrokAssistantStore(app)
            if (!store.enabled || !store.wakeWordEnabled) return
            val i = Intent(app, GrokAssistantWakeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(app, i)
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, GrokAssistantWakeService::class.java).setAction(ACTION_STOP)
            runCatching { app.startService(i) }
            runCatching { app.stopService(Intent(app, GrokAssistantWakeService::class.java)) }
        }

        fun pause(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, GrokAssistantWakeService::class.java).setAction(ACTION_PAUSE)
            runCatching { app.startService(i) }
        }

        fun resume(ctx: Context) {
            val app = ctx.applicationContext
            val store = GrokAssistantStore(app)
            if (!store.enabled || !store.wakeWordEnabled) return
            val i = Intent(app, GrokAssistantWakeService::class.java).setAction(ACTION_RESUME)
            runCatching { ContextCompat.startForegroundService(app, i) }
        }

        /** Start or stop based on store prefs (safe from Setup toggles / boot). */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val store = GrokAssistantStore(app)
            if (store.enabled && store.wakeWordEnabled) start(app) else stop(app)
        }
    }
}
