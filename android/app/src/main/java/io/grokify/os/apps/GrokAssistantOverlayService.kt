package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.grokify.os.GrokifyApp
import io.grokify.os.ui.theme.GrokifyColors
import io.grokify.os.ui.theme.GrokifyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Ephemeral floating Grok Assistant over other apps.
 *
 * Shown only while a session is active (wake / assist / manual Show).
 * No always-on bubble, no chat history — just status + current turn + input.
 * Closing or expanding to the full app stops this service.
 */
class GrokAssistantOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private val draftState = mutableStateOf("")
    private val busyState = mutableStateOf(false)
    private val listeningState = mutableStateOf(false)
    private val partialState = mutableStateOf<String?>(null)
    private val statusState = mutableStateOf<String?>(null)
    /** Current-session lines only (not full chat history). */
    private val turnUserState = mutableStateOf<String?>(null)
    private val turnReplyState = mutableStateOf<String?>(null)
    private val sessionTick = mutableStateOf(0)

    // Live Voice Agent state (shared session with the full app).
    private val voiceLiveState = mutableStateOf(GrokAssistantVoiceSession.isLive)
    private val voiceTurnState = mutableStateOf(GrokAssistantVoiceSession.Turn.Idle)
    private val voiceLevelState = mutableFloatStateOf(0f)
    private val voiceBarsState = mutableStateOf(FloatArray(28))
    private val voiceStatusState = mutableStateOf<String?>(null)
    private val voiceMicMutedState = mutableStateOf(false)
    private val voicePartialUserState = mutableStateOf<String?>(null)
    private val voicePartialAsstState = mutableStateOf<String?>(null)

    private var speech: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Auto-listen (wake / assist) vs manual hold-to-talk. */
    private var autoListenActive = false
    /** When expanding to the full app, keep the Voice Agent session alive. */
    private var preserveVoiceOnDestroy = false

    private val voiceListener = object : GrokAssistantVoiceSession.Listener {
        override fun onSnapshot(snap: GrokAssistantVoiceSession.Snapshot) {
            voiceLiveState.value = snap.state == GrokAssistantVoiceSession.State.Live ||
                snap.state == GrokAssistantVoiceSession.State.Connecting ||
                snap.state == GrokAssistantVoiceSession.State.ToolBusy
            voiceTurnState.value = snap.turn
            voiceLevelState.floatValue = snap.level
            voiceBarsState.value = snap.bars
            voiceStatusState.value = snap.statusLine
            voiceMicMutedState.value = snap.micMuted
            voicePartialUserState.value = snap.partialUser
            voicePartialAsstState.value = snap.partialAssistant
            if (snap.partialUser?.isNotBlank() == true) {
                turnUserState.value = snap.partialUser
            }
            if (snap.partialAssistant?.isNotBlank() == true) {
                turnReplyState.value = snap.partialAssistant?.take(400)
            }
            if (voiceLiveState.value) {
                statusState.value = snap.statusLine
            }
        }

        override fun onTranscriptCommitted(role: String, text: String) {
            val body = text.trim()
            if (body.isEmpty()) return
            when (role) {
                "user" -> turnUserState.value = body
                "assistant" -> turnReplyState.value = body.take(400)
            }
        }

        override fun onError(message: String) {
            voiceLiveState.value = false
            statusState.value = message.take(80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        GrokAssistantVoiceSession.addListener(voiceListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_BUMP -> {
                sessionTick.value = sessionTick.value + 1
            }
            ACTION_LISTEN -> {
                pendingStartListen = true
                statusState.value = "Listening…"
                sessionTick.value = sessionTick.value + 1
            }
            ACTION_AUTO_SEND -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotBlank()) {
                    pendingAutoSend = text
                    sessionTick.value = sessionTick.value + 1
                }
            }
            else -> {
                // START / SHOW
            }
        }
        val autoText = intent?.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (intent?.action == ACTION_START && autoText.isNotBlank()) {
            pendingAutoSend = autoText
        }
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            pendingStartListen = true
            statusState.value = "Listening…"
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            statusState.value = "Overlay permission required"
            return START_STICKY
        }
        if (composeView == null) {
            attachOverlay()
        } else {
            // Ensure window is visible (may have been hidden for screen capture).
            showAfterScreenCapture()
        }
        if (pendingStartListen) {
            scheduleAutoListen(delayMs = 500L)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        GrokAssistantVoiceSession.removeListener(voiceListener)
        if (!preserveVoiceOnDestroy) {
            runCatching { GrokAssistantVoiceSession.stop() }
        }
        preserveVoiceOnDestroy = false
        detachOverlay()
        destroySpeech()
        super.onDestroy()
    }

    private fun startAsForeground() {
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
            1,
            Intent(this, GrokAssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, GrokifyApp.CHANNEL_ASSISTANT)
            .setContentTitle("Grok Assistant")
            .setContentText("Quick overlay session · tap to open full app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Dismiss", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            runCatching { startForeground(NOTIF_ID, n) }
        }
    }

    private fun attachOverlay() {
        if (composeView != null) return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val owner = OverlayLifecycleOwner().also {
            it.onCreate()
            it.onStart()
            lifecycleOwner = it
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // Always focusable panel while visible (ephemeral session, not a bubble).
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val bottomPad = (24 * density).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = bottomPad
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = lp
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                GrokifyTheme {
                    OverlayRoot()
                }
            }
        }
        composeView = view
        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            statusState.value = "Could not show overlay: ${e.message}"
            composeView = null
        }
    }

    /** Hide the floating window so MediaProjection does not capture our chrome. */
    fun hideForScreenCapture() {
        val wm = windowManager ?: return
        val view = composeView ?: return
        runCatching { wm.removeView(view) }
    }

    /** Restore floating window after screen capture / crop. */
    fun showAfterScreenCapture(expand: Boolean = true) {
        val wm = windowManager ?: return
        val view = composeView
        val lp = layoutParams
        if (view == null || lp == null) {
            attachOverlay()
            return
        }
        if (view.parent == null) {
            val density = resources.displayMetrics.density
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.x = 0
            lp.y = (24 * density).toInt()
            runCatching { wm.addView(view, lp) }
                .onFailure { e ->
                    Log.e(TAG, "re-add overlay failed", e)
                    composeView = null
                    attachOverlay()
                }
        }
        sessionTick.value = sessionTick.value + 1
    }

    private fun detachOverlay() {
        val wm = windowManager
        val view = composeView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        composeView = null
        layoutParams = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }

    private fun stopSelfSafely() {
        mainHandler.removeCallbacksAndMessages(null)
        autoListenActive = false
        runCatching { GrokAssistantVoiceSession.stop() }
        detachOverlay()
        destroySpeech(resumeWake = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun destroySpeech(resumeWake: Boolean = true) {
        runCatching {
            speech?.stopListening()
            speech?.cancel()
            speech?.destroy()
        }
        speech = null
        listeningState.value = false
        autoListenActive = false
        GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
        if (resumeWake) {
            GrokAssistantWakeService.resume(this)
        }
    }

    private fun ensureSpeech(): SpeechRecognizer? {
        if (speech != null) return speech
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusState.value = "Speech recognition unavailable"
            return null
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listeningState.value = true
                partialState.value = null
                statusState.value = "Listening… say your request"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listeningState.value = false
            }

            override fun onError(error: Int) {
                listeningState.value = false
                partialState.value = null
                val wasAuto = autoListenActive
                autoListenActive = false
                GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
                // Soft errors during auto-listen: one quick retry, then idle (keep overlay).
                val soft = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_CLIENT
                if (wasAuto && soft) {
                    statusState.value = "Didn't catch that — tap mic or type"
                    GrokAssistantWakeService.resume(this@GrokAssistantOverlayService)
                    return
                }
                GrokAssistantWakeService.resume(this@GrokAssistantOverlayService)
                statusState.value = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic busy — try again"
                    else -> "Mic error ($error)"
                }
            }

            override fun onResults(results: Bundle?) {
                listeningState.value = false
                autoListenActive = false
                GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
                // Don't resume wake until send completes (busy path resumes later).
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull()?.trim().orEmpty()
                partialState.value = null
                if (best.isNotBlank()) {
                    draftState.value = best
                    statusState.value = null
                    pendingAutoSend = best
                    sessionTick.value = sessionTick.value + 1
                } else {
                    statusState.value = "No speech text"
                    GrokAssistantWakeService.resume(this@GrokAssistantOverlayService)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partialState.value = texts?.firstOrNull()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech = sr
        return sr
    }

    @Volatile
    private var pendingAutoSend: String? = null

    @Volatile
    private var pendingStartListen: Boolean = false

    private var listenRetryCount = 0
    private val autoListenKick = Runnable {
        if (listeningState.value || busyState.value) {
            pendingStartListen = false
            return@Runnable
        }
        pendingStartListen = false
        startListening(auto = true, retry = 0)
    }

    private fun scheduleAutoListen(delayMs: Long = 400L) {
        pendingStartListen = true
        mainHandler.removeCallbacks(autoListenKick)
        mainHandler.postDelayed(autoListenKick, delayMs)
    }

    /**
     * @param auto true for wake/assist free-listen until result; false for hold-to-talk.
     */
    private fun startListening(auto: Boolean = false, retry: Int = 0) {
        if (busyState.value || GrokAssistantSession.isBusy) {
            statusState.value = "Busy…"
            return
        }
        autoListenActive = auto
        listenRetryCount = retry
        // Fully free the wake recognizer before we grab the mic.
        GrokAssistantWakeService.pause(this)
        GrokAssistantMic.release(GrokAssistantMic.Owner.Wake)
        GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Overlay)
        // Fresh recognizer avoids OEM "busy" after wake loop.
        runCatching {
            speech?.cancel()
            speech?.destroy()
        }
        speech = null
        val sr = ensureSpeech() ?: run {
            autoListenActive = false
            GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
            GrokAssistantWakeService.resume(this)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        }
        runCatching {
            sr.startListening(intent)
            listeningState.value = true
            listenRetryCount = 0
            statusState.value = if (auto) "Listening… say your request" else "Listening…"
            Log.i(TAG, "overlay listening (auto=$auto)")
        }.onFailure {
            Log.w(TAG, "startListening: ${it.message}")
            statusState.value = it.message ?: "Could not start mic"
            listeningState.value = false
            autoListenActive = false
            GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
            if (auto && retry < 2) {
                statusState.value = "Mic starting…"
                mainHandler.postDelayed({
                    if (!listeningState.value && !busyState.value) {
                        startListening(auto = true, retry = retry + 1)
                    }
                }, 700L)
            } else {
                GrokAssistantWakeService.resume(this)
            }
        }
    }

    private fun stopListening() {
        autoListenActive = false
        runCatching { speech?.stopListening() }
        listeningState.value = false
        GrokAssistantMic.release(GrokAssistantMic.Owner.Overlay)
        GrokAssistantWakeService.resume(this)
    }

    @Composable
    private fun OverlayRoot() {
        val draft by draftState
        val busy by busyState
        val listening by listeningState
        val partial by partialState
        val status by statusState
        val turnUser by turnUserState
        val turnReply by turnReplyState
        val tick by sessionTick
        val voiceLive by voiceLiveState
        val voiceTurn by voiceTurnState
        val voiceLevel by voiceLevelState
        val voiceBars by voiceBarsState
        val voiceStatus by voiceStatusState
        val voiceMicMuted by voiceMicMutedState
        val liveUser by voicePartialUserState
        val liveAsst by voicePartialAsstState
        val store = remember { GrokAssistantStore(applicationContext) }
        val enabled = store.enabled
        val hasXaiKey = remember {
            !HostApiKeyStore.getValue(applicationContext, ApiKeyIds.SPACEXAI).isNullOrBlank()
        }
        val voiceRealtime = store.voiceRealtimeEnabled
        val scope = rememberCoroutineScope()

        fun sendText(text: String) {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || !enabled || busyState.value || GrokAssistantSession.isBusy) return
            draftState.value = ""
            turnUserState.value = trimmed
            turnReplyState.value = null
            val useVoice = store.voiceRealtimeEnabled &&
                !HostApiKeyStore.getValue(applicationContext, ApiKeyIds.SPACEXAI).isNullOrBlank()
            if (useVoice) {
                // Realtime path: stream speech; overlay shows seed text + live status.
                destroySpeech(resumeWake = false)
                GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)
                busyState.value = true
                statusState.value = "Voice Agent…"
                if (GrokAssistantVoiceSession.isLive) {
                    GrokAssistantVoiceSession.sendText(trimmed)
                } else {
                    GrokAssistantVoiceSession.start(
                        applicationContext,
                        seedUserText = trimmed,
                        openMic = true,
                    )
                }
                // Free busy flag after a beat so UI can show listening; session is independent.
                scope.launch {
                    kotlinx.coroutines.delay(600)
                    busyState.value = false
                    statusState.value = "Voice live · tap ✕ to close"
                    GrokAssistantMic.quietFor(3_000L)
                }
                return
            }
            busyState.value = true
            statusState.value = "Thinking…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    GrokAssistantSession.send(applicationContext, trimmed)
                }
                GrokAssistantMic.quietFor(
                    if (store.speakReplies) 5_000L else 800L,
                )
                busyState.value = false
                if (result.ok) {
                    turnReplyState.value = result.replyText?.take(400)
                    statusState.value = null
                } else {
                    turnReplyState.value = null
                    statusState.value = (result.errorText ?: "Error").take(80)
                }
                GrokAssistantWakeService.resume(this@GrokAssistantOverlayService)
            }
        }

        fun toggleVoiceLive() {
            if (!enabled || !voiceRealtime || !hasXaiKey) return
            if (GrokAssistantVoiceSession.isLive) {
                GrokAssistantVoiceSession.stop()
                voiceLiveState.value = false
                statusState.value = null
                GrokAssistantWakeService.resume(this@GrokAssistantOverlayService)
            } else {
                destroySpeech(resumeWake = false)
                GrokAssistantMic.tryAcquire(GrokAssistantMic.Owner.Voice)
                statusState.value = "Connecting Voice…"
                GrokAssistantVoiceSession.start(
                    applicationContext,
                    seedUserText = null,
                    openMic = true,
                )
                GrokAssistantMic.quietFor(3_000L)
            }
        }

        LaunchedEffect(tick) {
            // Auto-listen is scheduled from the service (debounced) — only handle auto-send here.
            val auto = pendingAutoSend
            if (auto != null) {
                pendingAutoSend = null
                if (enabled && !busyState.value && !GrokAssistantSession.isBusy) {
                    sendText(auto)
                } else if (GrokAssistantSession.isBusy) {
                    draftState.value = ""
                    statusState.value = "Working…"
                } else {
                    draftState.value = auto
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { destroySpeech(resumeWake = true) }
        }

        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .width(if (voiceLive) 300.dp else 280.dp)
                .clip(shape)
                .background(GrokifyColors.VoidElevated.copy(alpha = 0.97f))
                .border(1.dp, GrokifyColors.GlowViolet.copy(alpha = 0.45f), shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (voiceLive) "Grok · live" else "Grok",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { openFullAssistantApp() },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = "Open full app",
                        tint = GrokifyColors.GlowCyan,
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(
                    onClick = { stopSelfSafely() },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss overlay",
                        tint = GrokifyColors.GlowAmber,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            // Same sound-reactive strip as the full app (compact for the bubble).
            if (voiceLive) {
                VoiceReactiveStrip(
                    turn = voiceTurn,
                    level = voiceLevel,
                    bars = voiceBars,
                    status = voiceStatus,
                    compact = true,
                    micMuted = voiceMicMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                )
            }

            // Current turn only — no history list. Prefer live captions while streaming.
            val displayUser = liveUser?.takeIf { it.isNotBlank() } ?: turnUser
            val displayReply = liveAsst?.takeIf { it.isNotBlank() } ?: turnReply
            if (!displayUser.isNullOrBlank()) {
                Text(
                    displayUser,
                    color = GrokifyColors.TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GrokifyColors.GlowViolet.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            if (!displayReply.isNullOrBlank()) {
                Text(
                    displayReply,
                    color = GrokifyColors.TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GrokifyColors.PanelSoft)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(Modifier.height(4.dp))
            } else if (
                displayUser.isNullOrBlank() &&
                !listening &&
                !voiceLive &&
                status.isNullOrBlank() &&
                !busy
            ) {
                Text(
                    if (!enabled) "Enable assistant in Setup."
                    else "Say something or type…",
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (!voiceLive && (!status.isNullOrBlank() || !partial.isNullOrBlank())) {
                Text(
                    partial?.let { "…$it" } ?: status.orEmpty(),
                    color = if (listening) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draftState.value = it.take(2000) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 72.dp),
                    placeholder = {
                        Text(
                            when {
                                !enabled -> "Off"
                                voiceLive -> "Talk or type…"
                                else -> "Message…"
                            },
                            fontSize = 12.sp,
                            color = GrokifyColors.TextDim,
                        )
                    },
                    enabled = enabled && !busy && !voiceLive,
                    maxLines = 2,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = GrokifyColors.TextPrimary,
                        unfocusedTextColor = GrokifyColors.TextPrimary,
                        focusedBorderColor = GrokifyColors.GlowViolet,
                        unfocusedBorderColor = GrokifyColors.PanelBorder,
                        cursorColor = GrokifyColors.GlowViolet,
                        focusedContainerColor = GrokifyColors.PanelSoft,
                        unfocusedContainerColor = GrokifyColors.PanelSoft,
                        disabledTextColor = GrokifyColors.TextDim,
                    ),
                )
                IconButton(
                    onClick = {
                        if (!enabled || busy || voiceLive) return@IconButton
                        val q = draftState.value.trim()
                        GrokAssistantScreenLookActivity.start(
                            this@GrokAssistantOverlayService,
                            query = q,
                            hideOverlayFirst = true,
                        )
                    },
                    enabled = enabled && !busy && !voiceLive,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.ScreenshotMonitor,
                        contentDescription = "Look at my screen",
                        tint = if (enabled && !busy && !voiceLive) {
                            GrokifyColors.GlowCyan
                        } else {
                            GrokifyColors.TextDim
                        },
                        modifier = Modifier.size(19.dp),
                    )
                }
                // Realtime Voice toggle when enabled; otherwise classic STT listen.
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                voiceLive -> GrokifyColors.GlowMint.copy(alpha = 0.35f)
                                listening -> GrokifyColors.GlowMint.copy(alpha = 0.35f)
                                else -> GrokifyColors.PanelSoft
                            },
                        )
                        .border(
                            1.dp,
                            when {
                                voiceLive -> GrokifyColors.GlowMint
                                listening -> GrokifyColors.GlowMint
                                else -> GrokifyColors.PanelBorder
                            },
                            CircleShape,
                        )
                        .pointerInput(enabled, busy, voiceRealtime, hasXaiKey, voiceLive) {
                            detectTapGestures(
                                onTap = {
                                    if (!enabled || busy) return@detectTapGestures
                                    if (voiceRealtime && hasXaiKey) {
                                        toggleVoiceLive()
                                    } else {
                                        if (listening) stopListening() else startListening(auto = true)
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            voiceLive -> Icons.Default.MicOff
                            else -> Icons.Default.Mic
                        },
                        contentDescription = if (voiceLive) "Stop voice" else "Listen",
                        tint = when {
                            voiceLive || listening -> GrokifyColors.GlowMint
                            else -> GrokifyColors.TextPrimary
                        },
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = { sendText(draftState.value) },
                    enabled = enabled && !busy && draft.isNotBlank() && !voiceLive,
                    modifier = Modifier.size(36.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = GrokifyColors.GlowViolet,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (enabled && draft.isNotBlank() && !voiceLive) {
                                GrokifyColors.GlowViolet
                            } else {
                                GrokifyColors.TextDim
                            },
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }

    /** Open full Assistant and dismiss this overlay so it does not stay on top. */
    private fun openFullAssistantApp() {
        // Hand off live Voice Agent to the full app — don't tear the session down.
        preserveVoiceOnDestroy = true
        val intent = io.grokify.os.widgets.WidgetNav.openPluginIntent(
            this,
            io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
        )
        io.grokify.os.widgets.WidgetNav.openPlugin(
            io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
        )
        val pi = PendingIntent.getActivity(
            this,
            98,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { pi.send() }
            .onFailure {
                runCatching {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { e ->
                    Log.w(TAG, "open full assistant failed: ${e.message}")
                }
            }
        // Always hide overlay when handing off to the full app.
        stopSelfSafely()
    }

    /** Minimal Lifecycle + SavedState host so ComposeView works outside an Activity. */
    private class OverlayLifecycleOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        init {
            savedStateController.performRestore(null)
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun onCreate() {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun onStart() {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    companion object {
        private const val TAG = "GrokAssistantOverlay"
        private const val NOTIF_ID = 42042

        const val ACTION_START = "io.grokify.os.ASSISTANT_OVERLAY_START"
        const val ACTION_STOP = "io.grokify.os.ASSISTANT_OVERLAY_STOP"
        const val ACTION_EXPAND = "io.grokify.os.ASSISTANT_OVERLAY_EXPAND"
        const val ACTION_COLLAPSE = "io.grokify.os.ASSISTANT_OVERLAY_COLLAPSE"
        const val ACTION_LISTEN = "io.grokify.os.ASSISTANT_OVERLAY_LISTEN"
        const val ACTION_AUTO_SEND = "io.grokify.os.ASSISTANT_OVERLAY_AUTO_SEND"
        const val ACTION_BUMP = "io.grokify.os.ASSISTANT_OVERLAY_BUMP"
        const val EXTRA_EXPAND = "expand"
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_TEXT = "text"
        const val EXTRA_OPEN_ASSISTANT = "open_grok_assistant"

        fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

        fun openOverlayPermissionSettings(ctx: Context) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }

        @Volatile
        var instance: GrokAssistantOverlayService? = null

        fun start(ctx: Context, expand: Boolean = true, listen: Boolean = false, autoText: String? = null) {
            val app = ctx.applicationContext
            if (!Settings.canDrawOverlays(app)) {
                openOverlayPermissionSettings(app)
                return
            }
            val i = Intent(app, GrokAssistantOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_EXPAND, true) // always a panel while shown
                putExtra(EXTRA_LISTEN, listen)
                if (!autoText.isNullOrBlank()) putExtra(EXTRA_TEXT, autoText)
            }
            ContextCompat.startForegroundService(app, i)
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, GrokAssistantOverlayService::class.java).setAction(ACTION_STOP)
            runCatching { app.startService(i) }
            runCatching { app.stopService(Intent(app, GrokAssistantOverlayService::class.java)) }
        }

        /** Show ephemeral overlay and free-listen for the next command. */
        fun startListeningForCommand(ctx: Context) {
            val app = ctx.applicationContext
            if (!Settings.canDrawOverlays(app)) {
                openOverlayPermissionSettings(app)
                return
            }
            val i = Intent(app, GrokAssistantOverlayService::class.java).apply {
                action = ACTION_LISTEN
                putExtra(EXTRA_EXPAND, true)
                putExtra(EXTRA_LISTEN, true)
            }
            ContextCompat.startForegroundService(app, i)
        }

        fun enqueueAutoSend(ctx: Context, text: String) {
            val app = ctx.applicationContext
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            if (!Settings.canDrawOverlays(app)) return
            val i = Intent(app, GrokAssistantOverlayService::class.java).apply {
                action = ACTION_AUTO_SEND
                putExtra(EXTRA_EXPAND, true)
                putExtra(EXTRA_TEXT, trimmed)
            }
            ContextCompat.startForegroundService(app, i)
        }

        fun bumpTranscript(ctx: Context) {
            val svc = instance ?: return
            svc.sessionTick.value = svc.sessionTick.value + 1
        }

        fun hideForCapture(ctx: Context) {
            instance?.hideForScreenCapture()
        }

        fun showAfterCapture(ctx: Context, expand: Boolean = true) {
            val svc = instance
            if (svc != null) {
                svc.showAfterScreenCapture(expand = expand)
            }
            // Do not auto-restart a permanent overlay after capture if user dismissed it.
        }

        fun isRunning(): Boolean = instance != null
    }
}
