package io.grokify.os.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * Floating mini Grok Assistant over other apps.
 *
 * Requires [Settings.canDrawOverlays]. Collapsed bubble ↔ expanded chat panel
 * with text send + hold-to-talk (SpeechRecognizer).
 */
class GrokAssistantOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private val expandedState = mutableStateOf(false)
    private val draftState = mutableStateOf("")
    private val busyState = mutableStateOf(false)
    private val listeningState = mutableStateOf(false)
    private val partialState = mutableStateOf<String?>(null)
    private val statusState = mutableStateOf<String?>(null)
    private val transcriptTick = mutableStateOf(0)

    private var speech: SpeechRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_COLLAPSE -> {
                expandedState.value = false
                applyFocusFlags(focusable = false)
            }
            ACTION_EXPAND -> {
                expandedState.value = true
                applyFocusFlags(focusable = true)
            }
            else -> {
                // START / SHOW
            }
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            statusState.value = "Overlay permission required"
            // Stay alive briefly so notification can open settings; no window without perm.
            return START_STICKY
        }
        if (composeView == null) {
            attachOverlay(expand = intent?.getBooleanExtra(EXTRA_EXPAND, true) == true)
        } else if (intent?.getBooleanExtra(EXTRA_EXPAND, false) == true) {
            expandedState.value = true
            applyFocusFlags(focusable = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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
            .setContentTitle("Grok Assistant overlay")
            .setContentText("Tap to open · floating mini chat active")
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

    private fun attachOverlay(expand: Boolean) {
        if (composeView != null) return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val owner = OverlayLifecycleOwner().also {
            it.onCreate()
            it.onStart()
            lifecycleOwner = it
        }
        expandedState.value = expand
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            if (expand) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val bottomPad = (24 * density).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Fixed bottom-center dock (bubble + expanded panel).
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

    private fun applyFocusFlags(focusable: Boolean) {
        val wm = windowManager ?: return
        val view = composeView ?: return
        val lp = layoutParams ?: return
        // Keep docked bottom-center whenever focus changes (expand/collapse).
        val density = resources.displayMetrics.density
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = 0
        lp.y = (24 * density).toInt()
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(view, lp) }
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
            attachOverlay(expand = expand)
            return
        }
        if (view.parent == null) {
            val density = resources.displayMetrics.density
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.x = 0
            lp.y = (24 * density).toInt()
            expandedState.value = expand
            applyFocusFlags(focusable = expand)
            runCatching { wm.addView(view, lp) }
                .onFailure { e ->
                    Log.e(TAG, "re-add overlay failed", e)
                    composeView = null
                    attachOverlay(expand = expand)
                }
        } else {
            expandedState.value = expand
            applyFocusFlags(focusable = expand)
        }
        transcriptTick.value = transcriptTick.value + 1
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
        detachOverlay()
        destroySpeech()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun destroySpeech() {
        runCatching {
            speech?.stopListening()
            speech?.cancel()
            speech?.destroy()
        }
        speech = null
        listeningState.value = false
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
                statusState.value = "Listening…"
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
                statusState.value = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed"
                    else -> "Mic error ($error)"
                }
            }

            override fun onResults(results: Bundle?) {
                listeningState.value = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull()?.trim().orEmpty()
                partialState.value = null
                if (best.isNotBlank()) {
                    draftState.value = best
                    statusState.value = null
                    // Auto-send after recognition for hands-free feel
                    // (user can still edit if we didn't auto-send — we auto-send).
                    // Fire via Compose side by flipping a one-shot? Simpler: set draft and flag.
                    pendingAutoSend = best
                    transcriptTick.value = transcriptTick.value + 1
                } else {
                    statusState.value = "No speech text"
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

    private fun startListening() {
        val sr = ensureSpeech() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        runCatching {
            sr.startListening(intent)
            listeningState.value = true
            statusState.value = "Listening…"
        }.onFailure {
            statusState.value = it.message ?: "Could not start mic"
            listeningState.value = false
        }
    }

    private fun stopListening() {
        runCatching { speech?.stopListening() }
        listeningState.value = false
    }

    @Composable
    private fun OverlayRoot() {
        val expanded by expandedState
        val draft by draftState
        val busy by busyState
        val listening by listeningState
        val partial by partialState
        val status by statusState
        val tick by transcriptTick
        val store = remember { GrokAssistantStore(applicationContext) }
        var speakReplies by remember { mutableStateOf(store.speakReplies) }
        var enabled by remember { mutableStateOf(store.enabled) }
        var transcript by remember { mutableStateOf(store.transcript()) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        fun reload() {
            transcript = store.transcript()
            speakReplies = store.speakReplies
            enabled = store.enabled
        }

        LaunchedEffect(tick) {
            reload()
            val auto = pendingAutoSend
            if (auto != null) {
                pendingAutoSend = null
                if (enabled && !busy && !GrokAssistantSession.isBusy) {
                    draftState.value = ""
                    busyState.value = true
                    statusState.value = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            GrokAssistantSession.send(applicationContext, auto)
                        }
                        busyState.value = false
                        reload()
                        transcriptTick.value = transcriptTick.value + 1
                    }
                } else {
                    draftState.value = auto
                }
            }
        }

        LaunchedEffect(transcript.size, busy) {
            if (transcript.isNotEmpty()) {
                listState.animateScrollToItem(transcript.lastIndex.coerceAtLeast(0))
            }
        }

        DisposableEffect(Unit) {
            onDispose { destroySpeech() }
        }

        if (!expanded) {
            Bubble(
                listening = listening,
                busy = busy,
                onClick = {
                    expandedState.value = true
                    applyFocusFlags(focusable = true)
                    reload()
                },
            )
            return
        }

        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .width(300.dp)
                .clip(shape)
                .background(GrokifyColors.VoidElevated.copy(alpha = 0.96f))
                .border(1.dp, GrokifyColors.GlowViolet.copy(alpha = 0.45f), shape)
                .padding(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Grok Assistant",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        expandedState.value = false
                        applyFocusFlags(focusable = false)
                        destroySpeech()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Collapse",
                        tint = GrokifyColors.TextMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        startActivity(
                            io.grokify.os.widgets.WidgetNav.openPluginIntent(
                                this@GrokAssistantOverlayService,
                                io.grokify.os.apps.plugin.BuiltinPluginCatalog.GROK_ASSISTANT,
                            ),
                        )
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = "Open app",
                        tint = GrokifyColors.GlowCyan,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { stopSelfSafely() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close overlay",
                        tint = GrokifyColors.GlowAmber,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                buildString {
                    append(if (enabled) store.mode.storageKey else "off")
                    append(" · ")
                    append(if (speakReplies) "speak" else "silent")
                },
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))

            val recent = transcript.takeLast(8)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (recent.isEmpty()) {
                    item {
                        Text(
                            if (!enabled) "Enable assistant in the app Setup tab."
                            else "Say something or type below.",
                            color = GrokifyColors.TextDim,
                            fontSize = 11.sp,
                        )
                    }
                }
                items(recent, key = { it.id }) { msg ->
                    MiniBubble(msg)
                }
            }

            if (!status.isNullOrBlank() || !partial.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    partial?.let { "…$it" } ?: status.orEmpty(),
                    color = if (listening) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))
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
                        .heightIn(min = 40.dp, max = 88.dp),
                    placeholder = {
                        Text(
                            if (!enabled) "Assistant off" else "Message…",
                            fontSize = 12.sp,
                            color = GrokifyColors.TextDim,
                        )
                    },
                    enabled = enabled && !busy,
                    maxLines = 3,
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
                // Look at screen (capture + crop)
                IconButton(
                    onClick = {
                        if (!enabled || busy) return@IconButton
                        val q = draftState.value.trim()
                        GrokAssistantScreenLookActivity.start(
                            this@GrokAssistantOverlayService,
                            query = q,
                            hideOverlayFirst = true,
                        )
                    },
                    enabled = enabled && !busy,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.ScreenshotMonitor,
                        contentDescription = "Look at my screen",
                        tint = if (enabled && !busy) GrokifyColors.GlowCyan else GrokifyColors.TextDim,
                        modifier = Modifier.size(22.dp),
                    )
                }
                // Hold to talk
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (listening) GrokifyColors.GlowMint.copy(alpha = 0.35f)
                            else GrokifyColors.PanelSoft,
                        )
                        .border(
                            1.dp,
                            if (listening) GrokifyColors.GlowMint else GrokifyColors.PanelBorder,
                            CircleShape,
                        )
                        .pointerInput(enabled, busy) {
                            detectTapGestures(
                                onPress = {
                                    if (!enabled || busy) return@detectTapGestures
                                    startListening()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        stopListening()
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Hold to talk",
                        tint = if (listening) GrokifyColors.GlowMint else GrokifyColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val text = draftState.value.trim()
                        if (text.isEmpty() || !enabled || busy || GrokAssistantSession.isBusy) return@IconButton
                        draftState.value = ""
                        busyState.value = true
                        statusState.value = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                GrokAssistantSession.send(applicationContext, text)
                            }
                            busyState.value = false
                            reload()
                        }
                    },
                    enabled = enabled && !busy && draft.isNotBlank(),
                    modifier = Modifier.size(40.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = GrokifyColors.GlowViolet,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (enabled && draft.isNotBlank()) GrokifyColors.GlowViolet
                            else GrokifyColors.TextDim,
                        )
                    }
                }
            }
            Text(
                "Hold mic · Look (screen crop) · Setup voice",
                color = GrokifyColors.TextDim,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    @Composable
    private fun Bubble(
        listening: Boolean,
        busy: Boolean,
        onClick: () -> Unit,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    when {
                        listening -> GrokifyColors.GlowMint.copy(alpha = 0.9f)
                        busy -> GrokifyColors.GlowViolet.copy(alpha = 0.85f)
                        else -> GrokifyColors.GlowViolet.copy(alpha = 0.92f)
                    },
                )
                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(
                    "G",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
        }
    }

    @Composable
    private fun MiniBubble(msg: AssistantChatMessage) {
        val isUser = msg.role == "user"
        val isErr = msg.role == "error"
        val bg = when {
            isErr -> GrokifyColors.GlowAmber.copy(alpha = 0.15f)
            isUser -> GrokifyColors.GlowViolet.copy(alpha = 0.22f)
            else -> GrokifyColors.PanelSoft
        }
        val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        Box(Modifier.fillMaxWidth(), contentAlignment = align) {
            Text(
                msg.text,
                color = if (isErr) GrokifyColors.GlowAmber else GrokifyColors.TextPrimary,
                fontSize = 11.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
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
        const val EXTRA_EXPAND = "expand"
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

        fun start(ctx: Context, expand: Boolean = true) {
            val app = ctx.applicationContext
            if (!Settings.canDrawOverlays(app)) {
                openOverlayPermissionSettings(app)
                return
            }
            val i = Intent(app, GrokAssistantOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_EXPAND, expand)
            }
            ContextCompat.startForegroundService(app, i)
        }

        fun stop(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, GrokAssistantOverlayService::class.java).setAction(ACTION_STOP)
            runCatching { app.startService(i) }
            // Also try stopService if not running as started with action
            runCatching { app.stopService(Intent(app, GrokAssistantOverlayService::class.java)) }
        }

        fun hideForCapture(ctx: Context) {
            instance?.hideForScreenCapture()
        }

        fun showAfterCapture(ctx: Context, expand: Boolean = true) {
            val svc = instance
            if (svc != null) {
                svc.showAfterScreenCapture(expand = expand)
            } else {
                val store = GrokAssistantStore(ctx)
                if (store.enabled && store.overlayEnabled && Settings.canDrawOverlays(ctx)) {
                    start(ctx, expand = expand)
                }
            }
        }

        fun isLikelyRunning(ctx: Context): Boolean {
            // Lightweight: preference + overlay permission; true running state is soft.
            val store = GrokAssistantStore(ctx)
            return store.overlayEnabled && Settings.canDrawOverlays(ctx)
        }
    }
}
