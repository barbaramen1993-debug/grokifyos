package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

/**
 * Background “Okay Grok” listener.
 *
 * **Media-safe path:** [GrokAssistantWakeListenEngine] uses [android.media.AudioRecord]
 * with **no audio focus** so Spotify and other players keep playing. Short speech
 * snippets are transcribed via xAI STT and matched for the wake phrase.
 *
 * This is the third-party equivalent of “Okay Google”: Google’s DSP hotword still
 * requires system/assistant privileges; we cannot use that HAL from a normal app.
 *
 * When a wake phrase is heard, expands the floating overlay and either:
 * - sends remainder text as the query, or
 * - starts Voice Agent / overlay free-listen for the command.
 */
class GrokAssistantWakeService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var engine: GrokAssistantWakeListenEngine? = null
    private var running = false

    /** After wake with no remainder — next utterance is the command. */
    private var awaitingCommand = false

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
                // Soft pause while overlay / voice uses the mic
                stopEngine()
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (running) startEngine()
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
            return START_STICKY
        }
        running = true
        startAsForeground(status = "Listening for “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}”")
        startEngine()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        stopEngine()
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        stopEngine()
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startEngine() {
        if (!running) return
        if (!hasMicPermission()) {
            startAsForeground(status = "Mic permission needed")
            return
        }
        if (engine?.isRunning == true) return
        stopEngine()
        val eng = GrokAssistantWakeListenEngine(
            appCtx = applicationContext,
            onUtteranceText = { text ->
                mainHandler.post { handleHeard(listOf(text)) }
            },
            onStatus = { status ->
                mainHandler.post {
                    if (running) startAsForeground(status = status)
                }
            },
        )
        engine = eng
        eng.start()
    }

    private fun stopEngine() {
        engine?.stop()
        engine = null
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
    }

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
                val m = GrokAssistantWake.match(cmd)
                val text = when {
                    m == null -> cmd
                    m.remainder.isNotBlank() -> m.remainder
                    else -> ""
                }
                if (text.isNotBlank()) {
                    onCommand(text)
                    return
                }
            }
            startAsForeground(
                status = "Didn't catch a request — say ${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY} again",
            )
            return
        }

        var hit: GrokAssistantWake.Match? = null
        for (c in candidates) {
            hit = GrokAssistantWake.match(c)
            if (hit != null) break
        }
        if (hit == null) {
            // Non-wake speech — ignore (media keeps playing; no focus taken).
            return
        }

        Log.i(TAG, "wake: phrase='${hit.phrase}' rem='${hit.remainder}' raw='${hit.raw}'")

        if (hit.remainder.isNotBlank() && !GrokAssistantWake.isWakeOnly(hit)) {
            activateUi(listen = false)
            onCommand(hit.remainder)
        } else {
            awaitingCommand = false
            // Stop passive listen while Voice / overlay owns the mic.
            stopEngine()
            GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
            val useVoice = store.voiceRealtimeEnabled &&
                !io.grokify.os.apps.plugin.HostApiKeyStore
                    .getValue(this, io.grokify.os.data.ApiKeyIds.SPACEXAI)
                    .isNullOrBlank()
            if (useVoice) {
                activateUi(listen = false)
                GrokAssistantVoiceSession.start(this, seedUserText = null, openMic = true)
                startAsForeground(status = "Yes? Voice Agent live…")
                GrokAssistantMic.quietFor(2_500L)
                mainHandler.postDelayed({ if (running) startEngine() }, 4_000L)
            } else {
                activateUi(listen = true)
                startAsForeground(status = "Yes? Overlay listening…")
                GrokAssistantMic.quietFor(2_500L)
                mainHandler.postDelayed({ if (running) startEngine() }, 4_000L)
            }
        }
    }

    /**
     * Show ephemeral floating overlay when permitted; otherwise open the full app.
     */
    private fun activateUi(listen: Boolean = false) {
        if (GrokAssistantOverlayService.canDrawOverlays(this)) {
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
        io.grokify.os.widgets.WidgetNav.openPlugin(
            io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
        )
    }

    private fun onCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        startAsForeground(status = "Heard: ${trimmed.take(48)}")
        activateUi(listen = false)
        stopEngine()
        GrokAssistantMic.quietFor(1_500L)
        val store = GrokAssistantStore(applicationContext)
        val useVoice = store.voiceRealtimeEnabled &&
            !io.grokify.os.apps.plugin.HostApiKeyStore
                .getValue(applicationContext, io.grokify.os.data.ApiKeyIds.SPACEXAI)
                .isNullOrBlank()
        if (useVoice) {
            GrokAssistantVoiceSession.start(
                applicationContext,
                seedUserText = trimmed,
                openMic = true,
            )
            GrokAssistantMic.quietFor(4_000L)
            mainHandler.post {
                GrokAssistantOverlayService.bumpTranscript(applicationContext)
                startAsForeground(status = "Voice Agent · ${trimmed.take(36)}")
            }
            mainHandler.postDelayed({ if (running) startEngine() }, 6_000L)
            return
        }
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
                if (running) startEngine()
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
