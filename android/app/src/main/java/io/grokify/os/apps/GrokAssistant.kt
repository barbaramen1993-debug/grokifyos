package io.grokify.os.apps

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private enum class AssistantTab { Chat, History, Setup }

@Composable
fun GrokAssistantPane(onBack: () -> Unit) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val store = remember { GrokAssistantStore(appCtx) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(AssistantTab.Chat) }
    var enabled by remember { mutableStateOf(store.enabled) }
    var mode by remember { mutableStateOf(store.mode) }
    var voiceId by remember { mutableStateOf(store.voiceId) }
    var preferDeviceTts by remember { mutableStateOf(store.preferDeviceTts) }
    var speakReplies by remember { mutableStateOf(store.speakReplies) }
    var overlayEnabled by remember { mutableStateOf(store.overlayEnabled) }
    var wakeWordEnabled by remember { mutableStateOf(store.wakeWordEnabled) }
    var voiceRealtimeEnabled by remember { mutableStateOf(store.voiceRealtimeEnabled) }
    var voiceLive by remember { mutableStateOf(GrokAssistantVoiceSession.isLive) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var voiceTurn by remember {
        mutableStateOf(GrokAssistantVoiceSession.snapshot().turn)
    }
    var voiceLevel by remember { mutableFloatStateOf(0f) }
    var voiceBars by remember {
        mutableStateOf(FloatArray(28))
    }
    var partialUser by remember { mutableStateOf<String?>(null) }
    var partialAssistant by remember { mutableStateOf<String?>(null) }
    var canDrawOverlays by remember {
        mutableStateOf(GrokAssistantOverlayService.canDrawOverlays(appCtx))
    }
    var hasMic by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                appCtx,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var isDefaultAssistant by remember {
        mutableStateOf(GrokAssistantEntry.isAssistantRoleHeld(appCtx))
    }
    var templates by remember { mutableStateOf(store.templates()) }
    var transcript by remember { mutableStateOf(store.transcript()) }
    var sessions by remember { mutableStateOf(store.sessionMetas()) }
    var activeSessionId by remember { mutableStateOf(store.activeSessionId) }
    var sessionTitle by remember {
        mutableStateOf(store.activeConversation().title)
    }
    var hasXaiKey by remember {
        mutableStateOf(!HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank())
    }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var voicePreviewMsg by remember { mutableStateOf<String?>(null) }

    fun reloadSessions() {
        sessions = store.sessionMetas()
        activeSessionId = store.activeSessionId
        sessionTitle = store.activeConversation().title
        transcript = store.transcript()
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null || !store.enabled || busy || GrokAssistantSession.isBusy) return@rememberLauncherForActivityResult
        busy = true
        statusMsg = null
        val query = draft.trim()
        draft = ""
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    loadImageUriAsJpeg(appCtx, uri)
                }
                if (jpeg == null || jpeg.isEmpty()) {
                    statusMsg = "Could not read image"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    GrokAssistantSession.sendWithImage(appCtx, query, jpeg)
                }
            } finally {
                busy = false
                reloadSessions()
            }
        }
    }

    // Re-check overlay permission when returning from system settings.
    LaunchedEffect(tab) {
        if (tab == AssistantTab.Setup) {
            canDrawOverlays = GrokAssistantOverlayService.canDrawOverlays(appCtx)
            hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : GrokAssistantVoiceSession.Listener {
            override fun onSnapshot(snap: GrokAssistantVoiceSession.Snapshot) {
                voiceLive = snap.state == GrokAssistantVoiceSession.State.Live ||
                    snap.state == GrokAssistantVoiceSession.State.Connecting ||
                    snap.state == GrokAssistantVoiceSession.State.ToolBusy
                voiceStatus = snap.statusLine
                voiceTurn = snap.turn
                voiceLevel = snap.level
                voiceBars = snap.bars
                partialUser = snap.partialUser
                partialAssistant = snap.partialAssistant
                if (snap.state == GrokAssistantVoiceSession.State.Idle ||
                    snap.state == GrokAssistantVoiceSession.State.Error
                ) {
                    partialUser = null
                    partialAssistant = null
                    voiceLevel = 0f
                    reloadSessions()
                }
            }

            override fun onTranscriptCommitted(role: String, text: String) {
                // Commit path clears partials in the session; refresh store-backed list immediately.
                reloadSessions()
            }

            override fun onError(message: String) {
                statusMsg = message.take(120)
                voiceLive = false
                partialUser = null
                partialAssistant = null
            }
        }
        GrokAssistantVoiceSession.addListener(listener)
        onDispose {
            GrokAssistantVoiceSession.removeListener(listener)
            // Keep live session if overlay/wake owns it; only stop if pane-owned and leaving app.
            // Do not force-stop here — user may background the chat while talking.
        }
    }

    // Prompt editor
    var promptKind by remember { mutableStateOf(AssistantPromptKind.Core) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editLabel by remember { mutableStateOf("") }
    var editBlurb by remember { mutableStateOf("") }
    var editBody by remember { mutableStateOf("") }
    var addingCustom by remember { mutableStateOf(false) }

    fun reloadAll() {
        enabled = store.enabled
        mode = store.mode
        voiceId = store.voiceId
        preferDeviceTts = store.preferDeviceTts
        speakReplies = store.speakReplies
        overlayEnabled = store.overlayEnabled
        wakeWordEnabled = store.wakeWordEnabled
        voiceRealtimeEnabled = store.voiceRealtimeEnabled
        hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
        canDrawOverlays = GrokAssistantOverlayService.canDrawOverlays(appCtx)
        hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            appCtx,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        isDefaultAssistant = GrokAssistantEntry.isAssistantRoleHeld(appCtx)
        templates = store.templates()
        reloadSessions()
        hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
    }

    fun reloadTranscript() {
        reloadSessions()
    }

    fun sendMessage(text: String) {
        if (!store.enabled || busy || GrokAssistantSession.isBusy) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        busy = true
        statusMsg = null
        draft = ""
        store.appendMessage("user", trimmed)
        reloadTranscript()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GrokAssistantSession.send(appCtx, trimmed, userAlreadyAppended = true)
                }
            } finally {
                busy = false
                reloadTranscript()
            }
        }
    }

    fun previewVoice() {
        val v = GROK_VOICES.firstOrNull { it.id.equals(voiceId, ignoreCase = true) }
        val line = "Hi, I'm ${v?.label ?: "Grok"} — your Grok Assistant."
        voicePreviewMsg = "Playing ${v?.label ?: voiceId}…"
        scope.launch(Dispatchers.IO) {
            val speakOpts = JSONObject()
                .put("voice_id", store.voiceId)
                .put("prefer_device", store.preferDeviceTts)
                .put("language", "en")
                .toString()
            val raw = HostAiClient.speak(appCtx, line, speakOpts)
            val ok = runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                voicePreviewMsg = if (ok) "Played ${v?.label ?: voiceId}" else "Preview failed"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(GrokifyColors.Void)) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(GrokifyColors.VoidElevated)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GrokifyColors.TextPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Grok Assistant",
                    color = GrokifyColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    when {
                        !enabled -> "Off — enable in Setup"
                        tab == AssistantTab.Chat -> sessionTitle
                        else -> "${mode.storageKey} · ${if (speakReplies) "speak on" else "silent"}"
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tab == AssistantTab.Chat) {
                IconButton(
                    onClick = {
                        store.newSession()
                        reloadSessions()
                        draft = ""
                        statusMsg = "New chat"
                    },
                    enabled = !busy,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = GrokifyColors.GlowMint,
                    )
                }
                IconButton(
                    onClick = { tab = AssistantTab.History; reloadSessions() },
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "History",
                        tint = GrokifyColors.GlowCyan,
                    )
                }
                IconButton(
                    onClick = { showClearConfirm = true },
                    enabled = transcript.isNotEmpty() && !busy,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear chat",
                        tint = GrokifyColors.TextMuted,
                    )
                }
            }
        }

        // Tabs
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(AssistantTab.Chat, AssistantTab.History, AssistantTab.Setup).forEach { t ->
                val selected = tab == t
                FilterChip(
                    selected = selected,
                    onClick = {
                        tab = t
                        if (t == AssistantTab.Setup || t == AssistantTab.History) reloadAll()
                    },
                    label = {
                        Text(
                            t.name,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowViolet.copy(alpha = 0.28f),
                        selectedLabelColor = GrokifyColors.GlowViolet,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = GrokifyColors.GlowViolet,
                    ),
                )
            }
        }

        when (tab) {
            AssistantTab.Chat -> AssistantChatTab(
                enabled = enabled,
                transcript = transcript,
                draft = draft,
                onDraftChange = { draft = it },
                busy = busy,
                speakReplies = speakReplies,
                onSpeakRepliesChange = {
                    speakReplies = it
                    store.speakReplies = it
                },
                voiceRealtimeEnabled = voiceRealtimeEnabled,
                voiceLive = voiceLive,
                voiceStatus = voiceStatus,
                voiceTurn = voiceTurn,
                voiceLevel = voiceLevel,
                voiceBars = voiceBars,
                partialUser = partialUser,
                partialAssistant = partialAssistant,
                hasXaiKey = hasXaiKey,
                onToggleVoiceLive = {
                    if (!store.enabled) {
                        statusMsg = "Turn on Enabled first"
                        return@AssistantChatTab
                    }
                    if (!store.voiceRealtimeEnabled) {
                        statusMsg = "Enable Realtime Voice in Setup"
                        return@AssistantChatTab
                    }
                    if (HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()) {
                        statusMsg = "Add SpaceXAI API key for Realtime Voice"
                        return@AssistantChatTab
                    }
                    if (GrokAssistantVoiceSession.isLive) {
                        GrokAssistantVoiceSession.stop()
                        voiceLive = false
                        statusMsg = "Voice stopped"
                    } else {
                        val seed = draft.trim().takeIf { it.isNotEmpty() }
                        if (seed != null) draft = ""
                        GrokAssistantVoiceSession.start(appCtx, seedUserText = seed, openMic = true)
                        statusMsg = "Voice Agent connecting…"
                    }
                },
                onSend = { sendMessage(it) },
                onAttachMedia = {
                    if (!store.enabled || busy) {
                        statusMsg = if (!store.enabled) "Turn on Enabled first" else "Busy"
                        return@AssistantChatTab
                    }
                    pickMedia.launch("image/*")
                },
                onGoSetup = { tab = AssistantTab.Setup },
                onOpenHistory = { tab = AssistantTab.History; reloadSessions() },
            )
            AssistantTab.History -> AssistantHistoryTab(
                sessions = sessions,
                activeSessionId = activeSessionId,
                onNew = {
                    store.newSession()
                    reloadSessions()
                    tab = AssistantTab.Chat
                },
                onSelect = { id ->
                    if (store.selectSession(id)) {
                        reloadSessions()
                        tab = AssistantTab.Chat
                    }
                },
                onDelete = { id ->
                    store.deleteSession(id)
                    reloadSessions()
                },
            )
            AssistantTab.Setup -> AssistantSetupTab(
                enabled = enabled,
                onEnabledChange = {
                    enabled = it
                    store.enabled = it
                    GrokAssistantWakeService.sync(appCtx)
                    // Overlay is ephemeral — never auto-start a permanent bubble.
                    if (!it) {
                        GrokAssistantOverlayService.stop(appCtx)
                    }
                },
                mode = mode,
                onModeChange = {
                    mode = it
                    store.mode = it
                },
                voiceId = voiceId,
                onVoiceIdChange = {
                    voiceId = it
                    store.voiceId = it
                    voicePreviewMsg = GROK_VOICES.find { v -> v.id == it }?.let { v ->
                        "${v.label} — ${v.tone}"
                    }
                },
                preferDeviceTts = preferDeviceTts,
                onPreferDeviceChange = {
                    preferDeviceTts = it
                    store.preferDeviceTts = it
                },
                speakReplies = speakReplies,
                onSpeakRepliesChange = {
                    speakReplies = it
                    store.speakReplies = it
                },
                voiceRealtimeEnabled = voiceRealtimeEnabled,
                onVoiceRealtimeChange = { on ->
                    voiceRealtimeEnabled = on
                    store.voiceRealtimeEnabled = on
                    if (!on && GrokAssistantVoiceSession.isLive) {
                        GrokAssistantVoiceSession.stop()
                        voiceLive = false
                    }
                    statusMsg = if (on) {
                        "Realtime Voice on — speak via mic; Build CLI used as a tool"
                    } else {
                        "Realtime Voice off — STT → Build → TTS path"
                    }
                },
                hasXaiKey = hasXaiKey,
                voicePreviewMsg = voicePreviewMsg,
                onPreviewVoice = { previewVoice() },
                overlayEnabled = overlayEnabled,
                canDrawOverlays = canDrawOverlays,
                onOverlayEnabledChange = { on ->
                    overlayEnabled = on
                    store.overlayEnabled = on
                    if (on) {
                        if (!GrokAssistantOverlayService.canDrawOverlays(appCtx)) {
                            statusMsg = "Grant “Display over other apps” for wake sessions"
                            GrokAssistantOverlayService.openOverlayPermissionSettings(context)
                        } else {
                            statusMsg = "Overlay allowed — shows only on Okay Grok / Show"
                        }
                    } else {
                        GrokAssistantOverlayService.stop(appCtx)
                        statusMsg = "Overlay disabled"
                    }
                },
                onRequestOverlayPermission = {
                    GrokAssistantOverlayService.openOverlayPermissionSettings(context)
                    statusMsg = "Open system setting, allow overlay, return here"
                },
                onShowOverlay = {
                    canDrawOverlays = GrokAssistantOverlayService.canDrawOverlays(appCtx)
                    if (!canDrawOverlays) {
                        GrokAssistantOverlayService.openOverlayPermissionSettings(context)
                        statusMsg = "Grant overlay permission first"
                        return@AssistantSetupTab
                    }
                    if (!store.enabled) {
                        statusMsg = "Turn on Enabled first"
                        return@AssistantSetupTab
                    }
                    store.overlayEnabled = true
                    overlayEnabled = true
                    GrokAssistantOverlayService.startListeningForCommand(appCtx)
                    statusMsg = "Session open — listening · close when done"
                },
                onHideOverlay = {
                    GrokAssistantOverlayService.stop(appCtx)
                    statusMsg = "Overlay dismissed"
                },
                wakeWordEnabled = wakeWordEnabled,
                hasMic = hasMic,
                onWakeWordChange = { on ->
                    wakeWordEnabled = on
                    store.wakeWordEnabled = on
                    if (on) {
                        if (!store.enabled) {
                            statusMsg = "Turn on Enabled first"
                            return@AssistantSetupTab
                        }
                        hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                            appCtx,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!hasMic) {
                            statusMsg = "Mic permission required for ${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}"
                        }
                        GrokAssistantWakeService.sync(appCtx)
                        statusMsg = if (hasMic) {
                            "${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY} on — say “Okay Grok” then your request"
                        } else {
                            "${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY} armed — grant microphone permission"
                        }
                    } else {
                        GrokAssistantWakeService.stop(appCtx)
                        statusMsg = "${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY} off"
                    }
                },
                isDefaultAssistant = isDefaultAssistant,
                onOpenAssistantSettings = {
                    GrokAssistantEntry.openDefaultAssistantSettings(context)
                    statusMsg = "Set GrokifyOS as digital assistant / voice input if listed"
                },
                onRequestAssistantRole = {
                    val act = context as? android.app.Activity
                    val launched = if (act != null) {
                        GrokAssistantEntry.requestAssistantRole(act)
                    } else {
                        false
                    }
                    isDefaultAssistant = GrokAssistantEntry.isAssistantRoleHeld(appCtx)
                    statusMsg = when {
                        isDefaultAssistant -> "Already default assistant"
                        launched -> "Confirm in system dialog (OEM may need Voice Input settings)"
                        else -> "Role unavailable — use Voice input / Default apps settings"
                    }
                },
                onTestHardwareEntry = {
                    if (!store.enabled) {
                        statusMsg = "Turn on Enabled first"
                        return@AssistantSetupTab
                    }
                    GrokAssistantEntry.activate(context, listen = true, openPane = false)
                    statusMsg = "Simulated assist button — overlay listening"
                },
                templates = templates,
                promptKind = promptKind,
                onPromptKindChange = {
                    promptKind = it
                    editingId = null
                    addingCustom = false
                },
                editingId = editingId,
                editLabel = editLabel,
                editBlurb = editBlurb,
                editBody = editBody,
                onEditLabel = { editLabel = it },
                onEditBlurb = { editBlurb = it },
                onEditBody = { editBody = it },
                onStartEdit = { tpl ->
                    addingCustom = false
                    editingId = tpl.id
                    editLabel = tpl.label
                    editBlurb = tpl.blurb
                    editBody = tpl.body
                    statusMsg = null
                },
                onCancelEdit = {
                    editingId = null
                    addingCustom = false
                },
                onSaveEdit = {
                    val id = editingId ?: return@AssistantSetupTab
                    val existing = templates.firstOrNull { it.id == id }
                    val updated = AssistantPromptTemplate(
                        id = id,
                        kind = existing?.kind ?: promptKind,
                        label = editLabel.trim().ifBlank { existing?.label ?: id },
                        blurb = editBlurb,
                        body = editBody,
                        enabled = existing?.enabled ?: true,
                        builtIn = existing?.builtIn ?: false,
                    )
                    store.upsertTemplate(updated)
                    templates = store.templates()
                    editingId = null
                    addingCustom = false
                    statusMsg = "Saved · ${updated.label}"
                },
                onReset = { id ->
                    if (store.resetTemplate(id)) {
                        templates = store.templates()
                        if (editingId == id) editingId = null
                        statusMsg = "Reset · $id"
                    }
                },
                onToggleEnabled = { id, on ->
                    store.setTemplateEnabled(id, on)
                    templates = store.templates()
                },
                onDelete = { id ->
                    if (store.deleteTemplate(id)) {
                        templates = store.templates()
                        if (editingId == id) editingId = null
                        statusMsg = "Deleted · $id"
                    }
                },
                addingCustom = addingCustom,
                onStartAddCustom = {
                    addingCustom = true
                    editingId = "custom_${UUID.randomUUID().toString().take(8)}"
                    editLabel = "Custom style"
                    editBlurb = ""
                    editBody = "STYLE: "
                    promptKind = AssistantPromptKind.Extra
                },
                onSaveCustom = {
                    val id = editingId ?: return@AssistantSetupTab
                    val tpl = AssistantPromptTemplate(
                        id = id,
                        kind = AssistantPromptKind.Extra,
                        label = editLabel.trim().ifBlank { "Custom" },
                        blurb = editBlurb,
                        body = editBody,
                        enabled = true,
                        builtIn = false,
                    )
                    store.upsertTemplate(tpl)
                    templates = store.templates()
                    editingId = null
                    addingCustom = false
                    statusMsg = "Added · ${tpl.label}"
                },
                statusMsg = statusMsg,
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear conversation?") },
            text = { Text("Removes all messages from this assistant chat. Prompts and settings stay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clearTranscript()
                        reloadTranscript()
                        showClearConfirm = false
                    },
                ) {
                    Text("Clear", color = GrokifyColors.GlowAmber)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = GrokifyColors.TextMuted)
                }
            },
            containerColor = GrokifyColors.VoidElevated,
            titleContentColor = GrokifyColors.TextPrimary,
            textContentColor = GrokifyColors.TextMuted,
        )
    }
}

@Composable
private fun AssistantChatTab(
    enabled: Boolean,
    transcript: List<AssistantChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    speakReplies: Boolean,
    onSpeakRepliesChange: (Boolean) -> Unit,
    voiceRealtimeEnabled: Boolean,
    voiceLive: Boolean,
    voiceStatus: String?,
    voiceTurn: GrokAssistantVoiceSession.Turn,
    voiceLevel: Float,
    voiceBars: FloatArray,
    partialUser: String?,
    partialAssistant: String?,
    hasXaiKey: Boolean,
    onToggleVoiceLive: () -> Unit,
    onSend: (String) -> Unit,
    onAttachMedia: () -> Unit,
    onGoSetup: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val listState = rememberLazyListState()
    val liveUser = partialUser?.trim().orEmpty()
    val liveAsst = partialAssistant?.trim().orEmpty()
    val showLivePane = voiceLive || liveUser.isNotEmpty() || liveAsst.isNotEmpty()
    val scrollKey = "${transcript.size}|${liveUser.length}|${liveAsst.length}|$busy|$voiceLive"
    LaunchedEffect(scrollKey) {
        if (transcript.isNotEmpty() || showLivePane) {
            // committed + optional live user + live asst + visualizer spacer
            val extra = (if (liveUser.isNotEmpty()) 1 else 0) +
                (if (liveAsst.isNotEmpty() || voiceLive) 1 else 0) + 2
            val target = (transcript.size + extra - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Speak replies",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = speakReplies,
                onCheckedChange = onSpeakRepliesChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GrokifyColors.Void,
                    checkedTrackColor = GrokifyColors.GlowViolet,
                    uncheckedThumbColor = GrokifyColors.TextMuted,
                    uncheckedTrackColor = GrokifyColors.PanelSoft,
                ),
            )
        }

        if (voiceLive) {
            VoiceReactiveStrip(
                turn = voiceTurn,
                level = voiceLevel,
                bars = voiceBars,
                status = voiceStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else if (!voiceStatus.isNullOrBlank()) {
            Text(
                voiceStatus,
                color = GrokifyColors.GlowMint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty() && !showLivePane) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (!enabled) "Assistant is off" else "New conversation",
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (!enabled) {
                            "Turn on the master switch in Setup, then send a message."
                        } else if (voiceRealtimeEnabled) {
                            "Mic → Realtime Voice (web/X search + Grok Build tool). " +
                                "Typed chat still uses Build. “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}” with wake on."
                        } else {
                            "Chat uses Grok Build · attach an image for vision · TTS from vault or device. " +
                                "Say “${GrokAssistantWake.PRIMARY_PHRASE_DISPLAY}” with wake on."
                        },
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    if (!enabled) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onGoSetup) {
                            Text("Open Setup", color = GrokifyColors.GlowViolet)
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onOpenHistory) {
                            Text("Open history", color = GrokifyColors.GlowCyan)
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(transcript, key = { it.id }) { msg ->
                        AssistantBubble(msg)
                    }
                    // Live streaming captions while voice session is open
                    if (liveUser.isNotEmpty()) {
                        item(key = "_live_user") {
                            AssistantBubble(
                                AssistantChatMessage(
                                    id = "_live_user",
                                    role = "user",
                                    text = liveUser,
                                ),
                                streaming = true,
                            )
                        }
                    }
                    if (liveAsst.isNotEmpty()) {
                        item(key = "_live_asst") {
                            AssistantBubble(
                                AssistantChatMessage(
                                    id = "_live_asst",
                                    role = "assistant",
                                    text = liveAsst,
                                ),
                                streaming = true,
                            )
                        }
                    } else if (busy || (voiceLive && voiceTurn != GrokAssistantVoiceSession.Turn.UserSpeaking)) {
                        item(key = "_busy") {
                            Row(
                                Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = when (voiceTurn) {
                                        GrokAssistantVoiceSession.Turn.GrokSpeaking -> GrokifyColors.GlowMint
                                        GrokAssistantVoiceSession.Turn.ToolBusy -> GrokifyColors.GlowCyan
                                        else -> GrokifyColors.GlowViolet
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        voiceLive -> voiceStatus ?: assistantTurnLabel(voiceTurn)
                                        else -> "Thinking…"
                                    },
                                    color = GrokifyColors.TextDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(GrokifyColors.VoidElevated)
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 120.dp),
                placeholder = {
                    Text(
                        if (!enabled) "Enable assistant in Setup…" else "Message Grok Assistant…",
                        color = GrokifyColors.TextDim,
                        fontSize = 14.sp,
                    )
                },
                enabled = enabled && !busy,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GrokifyColors.TextPrimary,
                    unfocusedTextColor = GrokifyColors.TextPrimary,
                    disabledTextColor = GrokifyColors.TextDim,
                    focusedBorderColor = GrokifyColors.GlowViolet,
                    unfocusedBorderColor = GrokifyColors.PanelBorder,
                    cursorColor = GrokifyColors.GlowViolet,
                    focusedContainerColor = GrokifyColors.PanelSoft,
                    unfocusedContainerColor = GrokifyColors.PanelSoft,
                    disabledContainerColor = GrokifyColors.PanelSoft,
                ),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            // Full pane: media upload (vision). Screen look stays on the overlay only.
            IconButton(
                onClick = onAttachMedia,
                enabled = enabled && !busy && !voiceLive,
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach image",
                    tint = if (enabled && !busy && !voiceLive) {
                        GrokifyColors.GlowCyan
                    } else {
                        GrokifyColors.TextDim
                    },
                )
            }
            IconButton(
                onClick = onToggleVoiceLive,
                enabled = enabled && voiceRealtimeEnabled && (hasXaiKey || voiceLive),
            ) {
                Icon(
                    if (voiceLive) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (voiceLive) "Stop voice" else "Start Realtime Voice",
                    tint = when {
                        voiceLive -> GrokifyColors.GlowMint
                        enabled && voiceRealtimeEnabled && hasXaiKey -> GrokifyColors.GlowViolet
                        else -> GrokifyColors.TextDim
                    },
                )
            }
            IconButton(
                onClick = { onSend(draft) },
                enabled = enabled && !busy && !voiceLive && draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && !busy && !voiceLive && draft.isNotBlank()) {
                        GrokifyColors.GlowViolet
                    } else {
                        GrokifyColors.TextDim
                    },
                )
            }
        }
    }
}

@Composable
private fun AssistantHistoryTab(
    sessions: List<AssistantSessionMeta>,
    activeSessionId: String,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Button(
            onClick = onNew,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = GrokifyColors.GlowCyan,
                contentColor = Color(0xFF041016),
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("New chat", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        if (sessions.isEmpty()) {
            Text("No conversations yet.", color = GrokifyColors.TextMuted)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { s ->
                    val active = s.id.equals(activeSessionId, ignoreCase = true)
                    val countLabel = when {
                        s.messageCount <= 0 -> "Empty"
                        s.messageCount == 1 -> "1 msg"
                        else -> "${s.messageCount} msgs"
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) GrokifyColors.GlowCyan.copy(alpha = 0.1f)
                                else GrokifyColors.PanelSoft,
                            )
                            .border(
                                1.dp,
                                if (active) GrokifyColors.GlowCyan.copy(alpha = 0.4f)
                                else GrokifyColors.PanelBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(role = Role.Button) { onSelect(s.id) }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title,
                                color = GrokifyColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatSessionTime(s.updatedAt)} · $countLabel",
                                color = if (s.messageCount <= 0) GrokifyColors.TextDim
                                else GrokifyColors.GlowMint.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(onClick = { onDelete(s.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = GrokifyColors.GlowRose,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSessionTime(ms: Long): String {
    if (ms <= 0L) return "—"
    val sdf = java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}

/** Load a content Uri into a downscaled JPEG for vision. */
private fun loadImageUriAsJpeg(
    ctx: android.content.Context,
    uri: Uri,
    maxEdge: Int = 1600,
    quality: Int = 82,
): ByteArray? {
    return try {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val srcMax = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        while (srcMax / sample > maxEdge * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        try {
            GrokAssistantScreenLookActivity.compressJpeg(bmp, maxEdge = maxEdge, quality = quality)
                ?: run {
                    // Fallback if companion not accessible as expected
                    var working = bmp
                    val maxDim = max(bmp.width, bmp.height)
                    if (maxDim > maxEdge) {
                        val scale = maxEdge.toFloat() / maxDim
                        val w = max(1, (bmp.width * scale).roundToInt())
                        val h = max(1, (bmp.height * scale).roundToInt())
                        working = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                    }
                    val out = ByteArrayOutputStream()
                    working.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                    if (working !== bmp) working.recycle()
                    out.toByteArray()
                }
        } finally {
            bmp.recycle()
        }
    } catch (_: Exception) {
        null
    }
}

internal fun assistantTurnLabel(turn: GrokAssistantVoiceSession.Turn): String = when (turn) {
    GrokAssistantVoiceSession.Turn.Idle -> "Idle"
    GrokAssistantVoiceSession.Turn.Connecting -> "Connecting…"
    GrokAssistantVoiceSession.Turn.Listening -> "Listening…"
    GrokAssistantVoiceSession.Turn.UserSpeaking -> "Hearing you…"
    GrokAssistantVoiceSession.Turn.Thinking -> "Thinking…"
    GrokAssistantVoiceSession.Turn.GrokSpeaking -> "Grok speaking…"
    GrokAssistantVoiceSession.Turn.ToolBusy -> "Grok Build…"
    GrokAssistantVoiceSession.Turn.Error -> "Error"
}

/** Shared sound-reactive turn strip (full chat + floating overlay). */
@Composable
internal fun VoiceReactiveStrip(
    turn: GrokAssistantVoiceSession.Turn,
    level: Float,
    bars: FloatArray,
    status: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    micMuted: Boolean = false,
) {
    val accent = when (turn) {
        GrokAssistantVoiceSession.Turn.UserSpeaking -> GrokifyColors.GlowViolet
        GrokAssistantVoiceSession.Turn.GrokSpeaking -> GrokifyColors.GlowMint
        GrokAssistantVoiceSession.Turn.ToolBusy -> GrokifyColors.GlowCyan
        GrokAssistantVoiceSession.Turn.Thinking -> GrokifyColors.GlowRose
        GrokAssistantVoiceSession.Turn.Connecting -> GrokifyColors.GlowCyan
        GrokAssistantVoiceSession.Turn.Error -> GrokifyColors.GlowAmber
        else -> GrokifyColors.GlowMint.copy(alpha = 0.75f)
    }
    val pulse = rememberInfiniteTransition(label = "voicePulse")
    val breathe by pulse.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (turn) {
                    GrokAssistantVoiceSession.Turn.UserSpeaking -> 420
                    GrokAssistantVoiceSession.Turn.GrokSpeaking -> 560
                    GrokAssistantVoiceSession.Turn.Thinking,
                    GrokAssistantVoiceSession.Turn.ToolBusy,
                    -> 900
                    else -> 1400
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val spin by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    val orbSize = if (compact) 40.dp else 56.dp
    val barH = if (compact) 20.dp else 28.dp
    val padH = if (compact) 8.dp else 12.dp
    val padV = if (compact) 6.dp else 10.dp
    val corner = if (compact) 12.dp else 16.dp
    val label = assistantTurnLabel(turn)
    val mutedHint = if (micMuted && turn == GrokAssistantVoiceSession.Turn.GrokSpeaking) {
        "Mic muted · Grok talking"
    } else if (micMuted && turn == GrokAssistantVoiceSession.Turn.Thinking) {
        "Mic muted · thinking"
    } else {
        null
    }
    val sub = mutedHint ?: status?.takeIf { it.isNotBlank() && it != label }

    Row(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(GrokifyColors.VoidElevated)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(corner))
            .padding(horizontal = padH, vertical = padV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Orb
        Box(
            Modifier.size(orbSize),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val base = size.minDimension * 0.28f
                val live = level.coerceIn(0f, 1f)
                val r = base * (0.85f + live * 0.75f) * breathe
                // Outer glow rings
                drawCircle(
                    color = accent.copy(alpha = 0.10f + live * 0.22f),
                    radius = r * 1.55f,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = accent.copy(alpha = 0.18f + live * 0.28f),
                    radius = r * 1.22f,
                    center = Offset(cx, cy),
                )
                // Core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.95f),
                            accent.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = r * 1.1f,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
                // Rotating arc (Grok / thinking)
                if (turn == GrokAssistantVoiceSession.Turn.GrokSpeaking ||
                    turn == GrokAssistantVoiceSession.Turn.Thinking ||
                    turn == GrokAssistantVoiceSession.Turn.ToolBusy ||
                    turn == GrokAssistantVoiceSession.Turn.Connecting
                ) {
                    val sweep = 70f + live * 90f
                    drawArc(
                        color = accent.copy(alpha = 0.85f),
                        startAngle = spin - 90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(cx - r * 1.35f, cy - r * 1.35f),
                        size = Size(r * 2.7f, r * 2.7f),
                        style = Stroke(width = if (compact) 2.6f else 3.2f, cap = StrokeCap.Round),
                    )
                }
                // Mic rays when user speaking
                if (turn == GrokAssistantVoiceSession.Turn.UserSpeaking) {
                    val rays = if (compact) 8 else 10
                    for (i in 0 until rays) {
                        val a = (i / rays.toFloat()) * 2f * PI.toFloat() + spin * 0.01f
                        val inner = r * 1.05f
                        val outer = r * (1.25f + live * 0.55f + (i % 3) * 0.04f)
                        drawLine(
                            color = accent.copy(alpha = 0.35f + live * 0.45f),
                            start = Offset(cx + cos(a) * inner, cy + sin(a) * inner),
                            end = Offset(cx + cos(a) * outer, cy + sin(a) * outer),
                            strokeWidth = if (compact) 2f else 2.4f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = accent,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (!sub.isNullOrBlank()) {
                Text(
                    sub,
                    color = GrokifyColors.TextDim,
                    fontSize = if (compact) 9.sp else 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            // Waveform bars
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(barH),
            ) {
                val n = bars.size.coerceAtLeast(1)
                val gap = if (compact) 1.8f else 2.5f
                val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(2f)
                val midY = size.height / 2f
                for (i in 0 until n) {
                    val v = bars.getOrElse(i) { 0f }.coerceIn(0.04f, 1f)
                    // Soft idle motion so bars never look frozen
                    val idle = 0.08f + 0.04f * sin((spin + i * 18f) * (PI.toFloat() / 180f))
                    val h = size.height * (if (level < 0.03f && turn == GrokAssistantVoiceSession.Turn.Listening) {
                        idle
                    } else {
                        0.12f + v * 0.88f
                    })
                    val x = i * (barW + gap)
                    val color = when {
                        i > n * 0.72f -> accent
                        i > n * 0.4f -> accent.copy(alpha = 0.75f)
                        else -> accent.copy(alpha = 0.45f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, midY - h / 2f),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(msg: AssistantChatMessage, streaming: Boolean = false) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    val bg = when {
        isUser -> GrokifyColors.GlowViolet.copy(alpha = if (streaming) 0.14f else 0.2f)
        isError -> GrokifyColors.GlowAmber.copy(alpha = 0.16f)
        else -> GrokifyColors.Panel
    }
    val border = when {
        isUser -> GrokifyColors.GlowViolet.copy(alpha = if (streaming) 0.55f else 0.38f)
        isError -> GrokifyColors.GlowAmber.copy(alpha = 0.42f)
        streaming -> GrokifyColors.GlowMint.copy(alpha = 0.55f)
        else -> GrokifyColors.PanelBorder.copy(alpha = 0.9f)
    }
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (isUser) 14.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 14.dp,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                when {
                    isUser && streaming -> "You · live"
                    isUser -> "You"
                    isError -> "Error"
                    streaming -> "Grok · live"
                    else -> "Grok"
                },
                color = when {
                    isUser -> GrokifyColors.GlowViolet
                    isError -> GrokifyColors.GlowAmber
                    else -> GrokifyColors.GlowMint
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                msg.text.ifBlank { if (streaming) "…" else "" },
                color = if (streaming) {
                    GrokifyColors.TextPrimary.copy(alpha = 0.92f)
                } else {
                    GrokifyColors.TextPrimary
                },
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontStyle = if (streaming) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

@Composable
private fun AssistantSetupTab(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    mode: AssistantMode,
    onModeChange: (AssistantMode) -> Unit,
    voiceId: String,
    onVoiceIdChange: (String) -> Unit,
    preferDeviceTts: Boolean,
    onPreferDeviceChange: (Boolean) -> Unit,
    speakReplies: Boolean,
    onSpeakRepliesChange: (Boolean) -> Unit,
    voiceRealtimeEnabled: Boolean,
    onVoiceRealtimeChange: (Boolean) -> Unit,
    hasXaiKey: Boolean,
    voicePreviewMsg: String?,
    onPreviewVoice: () -> Unit,
    overlayEnabled: Boolean,
    canDrawOverlays: Boolean,
    onOverlayEnabledChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
    wakeWordEnabled: Boolean,
    hasMic: Boolean,
    onWakeWordChange: (Boolean) -> Unit,
    isDefaultAssistant: Boolean,
    onOpenAssistantSettings: () -> Unit,
    onRequestAssistantRole: () -> Unit,
    onTestHardwareEntry: () -> Unit,
    templates: List<AssistantPromptTemplate>,
    promptKind: AssistantPromptKind,
    onPromptKindChange: (AssistantPromptKind) -> Unit,
    editingId: String?,
    editLabel: String,
    editBlurb: String,
    editBody: String,
    onEditLabel: (String) -> Unit,
    onEditBlurb: (String) -> Unit,
    onEditBody: (String) -> Unit,
    onStartEdit: (AssistantPromptTemplate) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onReset: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    addingCustom: Boolean,
    onStartAddCustom: () -> Unit,
    onSaveCustom: () -> Unit,
    statusMsg: String?,
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // Master
        SetupSectionLabel("ASSISTANT", GrokifyColors.GlowViolet)
        SetupRow(
            title = "Enabled",
            subtitle = "Master switch for chat replies",
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = switchColors(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Mode", color = GrokifyColors.TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantMode.entries.forEach { m ->
                val selected = mode == m
                FilterChip(
                    selected = selected,
                    onClick = { onModeChange(m) },
                    label = {
                        Text(
                            when (m) {
                                AssistantMode.Conversation -> "Conversation"
                                AssistantMode.Dev -> "Dev"
                            },
                            fontSize = 12.sp,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowMint.copy(alpha = 0.25f),
                        selectedLabelColor = GrokifyColors.GlowMint,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = GrokifyColors.GlowMint,
                    ),
                )
            }
        }
        Text(
            when (mode) {
                AssistantMode.Conversation -> "Everyday Q&A — warm and direct"
                AssistantMode.Dev -> "Engineering partner — text-only tools in v1"
            },
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("VOICE · TTS", GrokifyColors.GlowMint)
        Text(
            if (hasXaiKey) "Grok Voice · xAI key found" else "Grok Voice · add xAI key or use device TTS",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        val voiceScroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(voiceScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GROK_VOICES.forEach { v ->
                val selected = voiceId.equals(v.id, ignoreCase = true)
                FilterChip(
                    selected = selected,
                    onClick = { onVoiceIdChange(v.id) },
                    label = { Text(v.label, fontSize = 12.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowMint.copy(alpha = 0.25f),
                        selectedLabelColor = GrokifyColors.GlowMint,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = GrokifyColors.GlowMint,
                    ),
                )
            }
        }
        if (!voicePreviewMsg.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(voicePreviewMsg, color = GrokifyColors.TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        SetupRow("Prefer device TTS", "Skip Grok Voice / xAI") {
            Switch(
                checked = preferDeviceTts,
                onCheckedChange = onPreferDeviceChange,
                colors = switchColors(),
            )
        }
        SetupRow("Speak replies", "TTS after each Build/text answer") {
            Switch(
                checked = speakReplies,
                onCheckedChange = onSpeakRepliesChange,
                colors = switchColors(),
            )
        }
        TextButton(onClick = onPreviewVoice) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = GrokifyColors.GlowMint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Preview voice", color = GrokifyColors.GlowMint, fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("REALTIME VOICE", GrokifyColors.GlowMint)
        Text(
            "xAI Voice Agent (speech-to-speech, under 1s first audio). Server tools: web_search, " +
                "x_search. Client tool: prompt_grok_build → host Grok Build for deep research / " +
                "coding (especially Dev mode). Typed chat still uses Build directly. Needs " +
                "SpaceXAI API key (about $0.05/min while live).",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        SetupRow(
            title = "Realtime Voice",
            subtitle = when {
                !hasXaiKey -> "Add SpaceXAI key in Settings"
                voiceRealtimeEnabled -> "Mic / Okay Grok → Voice Agent"
                else -> "Off · use STT → Build → TTS"
            },
        ) {
            Switch(
                checked = voiceRealtimeEnabled,
                onCheckedChange = onVoiceRealtimeChange,
                enabled = hasXaiKey || voiceRealtimeEnabled,
                colors = switchColors(GrokifyColors.GlowMint),
            )
        }
        if (!hasXaiKey) {
            Text(
                "Vault key spacexai_api_key required for Realtime Voice.",
                color = GrokifyColors.GlowAmber,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("MINI OVERLAY", GrokifyColors.GlowCyan)
        Text(
            "Ephemeral panel over other apps — not always on. " +
                "Opens on Okay Grok / Show, closes with ✕ or Expand. " +
                "No history here · Look crops the screen · tap mic to listen.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        SetupRow(
            title = "Allow overlay",
            subtitle = if (canDrawOverlays) {
                "Permission granted · not always visible"
            } else {
                "Needs “Display over other apps”"
            },
        ) {
            Switch(
                checked = overlayEnabled,
                onCheckedChange = onOverlayEnabledChange,
                colors = switchColors(GrokifyColors.GlowCyan),
            )
        }
        if (!canDrawOverlays) {
            TextButton(onClick = onRequestOverlayPermission) {
                Text("Grant overlay permission", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onShowOverlay) {
                Text("Show & listen", color = GrokifyColors.GlowMint, fontSize = 13.sp)
            }
            TextButton(onClick = onHideOverlay) {
                Text("Dismiss", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("OKAY GROK", GrokifyColors.GlowViolet)
        Text(
            "Primary: Okay Grok / Ok Grok (also Hey/Hi/Yo). " +
                "Punctuation is ignored (“Okay, Grok!”). " +
                "STT near-misses of Grok also count (Brock, Rock, Crock, Flock, Jock, Truck, Quack…). " +
                "Uses on-device speech recognition (battery + mic).",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        SetupRow(
            title = "Wake word",
            subtitle = when {
                !enabled -> "Enable assistant first"
                !hasMic -> "Microphone permission needed"
                wakeWordEnabled -> "Listening for “Okay Grok”"
                else -> "Off"
            },
        ) {
            Switch(
                checked = wakeWordEnabled,
                onCheckedChange = onWakeWordChange,
                enabled = enabled,
                colors = switchColors(GrokifyColors.GlowViolet),
            )
        }
        if (!hasMic) {
            Text(
                "Grant microphone so Okay Grok can hear you.",
                color = GrokifyColors.GlowAmber,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("SYSTEM · BT · AUTO", GrokifyColors.GlowCyan)
        Text(
            "Home long-press / assist button, headset voice keys, and Android Auto " +
                "launcher can open Grok Assistant when the system routes them here.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isDefaultAssistant) "Default assistant role: held"
            else "Default assistant role: not held (OEM-dependent)",
            color = if (isDefaultAssistant) GrokifyColors.GlowMint else GrokifyColors.TextMuted,
            fontSize = 11.sp,
        )
        TextButton(onClick = onRequestAssistantRole) {
            Text("Request default assistant role", color = GrokifyColors.GlowCyan, fontSize = 13.sp)
        }
        TextButton(onClick = onOpenAssistantSettings) {
            Text("Open voice / default apps settings", color = GrokifyColors.GlowMint, fontSize = 13.sp)
        }
        TextButton(onClick = onTestHardwareEntry) {
            Text("Test assist entry (listen)", color = GrokifyColors.GlowViolet, fontSize = 13.sp)
        }
        Text(
            "BT: use your headset’s assistant / voice button — many headsets fire " +
                "VOICE_COMMAND or the default digital assistant. " +
                "Android Auto: GrokifyOS is a car launcher entry; use assist from the head unit when mapped.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("PROMPT TEMPLATES", GrokifyColors.GlowAmber)
        Text(
            "Core identity, mode prompts, and style extras. Edit bodies, reset built-ins, toggle extras.",
            color = GrokifyColors.TextDim,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(8.dp))
        val kindScroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(kindScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistantPromptKind.entries.forEach { kind ->
                val on = promptKind == kind
                FilterChip(
                    selected = on,
                    onClick = { onPromptKindChange(kind) },
                    label = { Text(kind.sectionLabel, fontSize = 11.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GrokifyColors.GlowAmber.copy(alpha = 0.22f),
                        selectedLabelColor = GrokifyColors.GlowAmber,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = on,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = GrokifyColors.GlowAmber,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val kindTemplates = templates.filter { it.kind == promptKind }
        kindTemplates.forEach { tpl ->
            val isEditing = editingId == tpl.id && !addingCustom
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GrokifyColors.PanelSoft)
                    .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tpl.label + if (tpl.builtIn) "" else " · custom",
                            color = GrokifyColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (tpl.blurb.isNotBlank()) {
                            Text(
                                tpl.blurb,
                                color = GrokifyColors.TextDim,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (tpl.kind == AssistantPromptKind.Extra) {
                        Switch(
                            checked = tpl.enabled,
                            onCheckedChange = { onToggleEnabled(tpl.id, it) },
                            colors = switchColors(GrokifyColors.GlowAmber),
                        )
                    }
                }
                if (!isEditing) {
                    Text(
                        tpl.body.take(120) + if (tpl.body.length > 120) "…" else "",
                        color = GrokifyColors.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onStartEdit(tpl) }) {
                            Text("Edit", fontSize = 11.sp, color = GrokifyColors.GlowCyan)
                        }
                        if (tpl.builtIn) {
                            TextButton(onClick = { onReset(tpl.id) }) {
                                Text("Reset", fontSize = 11.sp, color = GrokifyColors.TextDim)
                            }
                        } else {
                            TextButton(onClick = { onDelete(tpl.id) }) {
                                Text("Delete", fontSize = 11.sp, color = GrokifyColors.GlowAmber)
                            }
                        }
                    }
                } else {
                    PromptEditorFields(
                        label = editLabel,
                        blurb = editBlurb,
                        body = editBody,
                        onLabel = onEditLabel,
                        onBlurb = onEditBlurb,
                        onBody = onEditBody,
                        onSave = onSaveEdit,
                        onCancel = onCancelEdit,
                    )
                }
            }
        }
        if (promptKind == AssistantPromptKind.Extra) {
            Spacer(Modifier.height(6.dp))
            if (addingCustom && editingId != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GrokifyColors.PanelSoft)
                        .border(1.dp, GrokifyColors.GlowAmber.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text("New custom style", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
                    PromptEditorFields(
                        label = editLabel,
                        blurb = editBlurb,
                        body = editBody,
                        onLabel = onEditLabel,
                        onBlurb = onEditBlurb,
                        onBody = onEditBody,
                        onSave = onSaveCustom,
                        onCancel = onCancelEdit,
                    )
                }
            } else {
                TextButton(onClick = onStartAddCustom) {
                    Text("+ Add custom style", color = GrokifyColors.GlowAmber, fontSize = 12.sp)
                }
            }
        }
        if (!statusMsg.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(statusMsg, color = GrokifyColors.GlowMint, fontSize = 11.sp)
        }

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("LOOK AT SCREEN", GrokifyColors.GlowCyan)
        Text(
            "Capture the display, drag a crop box, ask Grok. Uses SpaceXAI vision (vault key). " +
                "Available in Chat and the mini overlay.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasXaiKey) "SpaceXAI key ready for vision"
            else "Add spacexai_api_key in Settings for Look",
            color = if (hasXaiKey) GrokifyColors.GlowMint else GrokifyColors.GlowAmber,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(20.dp))
        SetupSectionLabel("LATER", GrokifyColors.TextDim)
        ComingSoonRow("Dev mode real host tools / file edit")
        ComingSoonRow("DSP keyword engine (lower battery)")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PromptEditorFields(
    label: String,
    blurb: String,
    body: String,
    onLabel: (String) -> Unit,
    onBlurb: (String) -> Unit,
    onBody: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = label,
        onValueChange = { onLabel(it.take(48)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Label", fontSize = 11.sp) },
        colors = editorFieldColors(),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = blurb,
        onValueChange = { onBlurb(it.take(120)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Blurb", fontSize = 11.sp) },
        colors = editorFieldColors(),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = body,
        onValueChange = { onBody(it.take(4000)) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp),
        label = { Text("Body", fontSize = 11.sp) },
        colors = editorFieldColors(),
        minLines = 4,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onSave) {
            Text("Save", fontSize = 11.sp, color = GrokifyColors.GlowMint)
        }
        TextButton(onClick = onCancel) {
            Text("Cancel", fontSize = 11.sp, color = GrokifyColors.TextDim)
        }
    }
}

@Composable
private fun SetupSectionLabel(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SetupRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = GrokifyColors.TextPrimary, fontSize = 14.sp)
            Text(subtitle, color = GrokifyColors.TextDim, fontSize = 11.sp)
        }
        trailing()
    }
}

@Composable
private fun ComingSoonRow(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(GrokifyColors.PanelSoft.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = GrokifyColors.TextDim,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text("Soon", color = GrokifyColors.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun switchColors(track: Color = GrokifyColors.GlowViolet) = SwitchDefaults.colors(
    checkedThumbColor = GrokifyColors.Void,
    checkedTrackColor = track,
    uncheckedThumbColor = GrokifyColors.TextMuted,
    uncheckedTrackColor = GrokifyColors.PanelSoft,
)

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    focusedBorderColor = GrokifyColors.GlowAmber,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    cursorColor = GrokifyColors.GlowAmber,
    focusedLabelColor = GrokifyColors.GlowAmber,
    unfocusedLabelColor = GrokifyColors.TextDim,
    focusedContainerColor = GrokifyColors.Void,
    unfocusedContainerColor = GrokifyColors.Void,
)
