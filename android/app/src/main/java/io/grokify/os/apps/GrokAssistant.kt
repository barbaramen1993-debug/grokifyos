package io.grokify.os.apps

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private enum class AssistantTab { Chat, Setup }

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
    var canDrawOverlays by remember {
        mutableStateOf(GrokAssistantOverlayService.canDrawOverlays(appCtx))
    }
    var templates by remember { mutableStateOf(store.templates()) }
    var transcript by remember { mutableStateOf(store.transcript()) }
    var hasXaiKey by remember {
        mutableStateOf(!HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank())
    }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var voicePreviewMsg by remember { mutableStateOf<String?>(null) }

    // Re-check overlay permission when returning from system settings.
    LaunchedEffect(tab) {
        if (tab == AssistantTab.Setup) {
            canDrawOverlays = GrokAssistantOverlayService.canDrawOverlays(appCtx)
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
        canDrawOverlays = GrokAssistantOverlayService.canDrawOverlays(appCtx)
        templates = store.templates()
        transcript = store.transcript()
        hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
    }

    fun reloadTranscript() {
        transcript = store.transcript()
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
                    if (enabled) {
                        "${mode.storageKey} · ${if (speakReplies) "speak on" else "silent"}"
                    } else {
                        "Off — enable in Setup"
                    },
                    color = GrokifyColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            if (tab == AssistantTab.Chat) {
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
            listOf(AssistantTab.Chat, AssistantTab.Setup).forEach { t ->
                val selected = tab == t
                FilterChip(
                    selected = selected,
                    onClick = { tab = t; if (t == AssistantTab.Setup) reloadAll() },
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
                onSend = { sendMessage(it) },
                onGoSetup = { tab = AssistantTab.Setup },
            )
            AssistantTab.Setup -> AssistantSetupTab(
                enabled = enabled,
                onEnabledChange = {
                    enabled = it
                    store.enabled = it
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
                            statusMsg = "Grant “Display over other apps”, then Show overlay"
                            GrokAssistantOverlayService.openOverlayPermissionSettings(context)
                        } else if (store.enabled) {
                            GrokAssistantOverlayService.start(appCtx, expand = true)
                            statusMsg = "Overlay shown"
                        } else {
                            statusMsg = "Overlay pref on — enable Assistant + Show overlay"
                        }
                    } else {
                        GrokAssistantOverlayService.stop(appCtx)
                        statusMsg = "Overlay stopped"
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
                    GrokAssistantOverlayService.start(appCtx, expand = true)
                    statusMsg = "Overlay shown — drag the bubble, hold mic to talk"
                },
                onHideOverlay = {
                    GrokAssistantOverlayService.stop(appCtx)
                    statusMsg = "Overlay hidden"
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
    onSend: (String) -> Unit,
    onGoSetup: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size, busy) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.lastIndex.coerceAtLeast(0))
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (!enabled) "Assistant is off" else "Say hi to Grok Assistant",
                        color = GrokifyColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (!enabled) {
                            "Turn on the master switch in Setup, then send a message."
                        } else {
                            "Conversation uses Grok Build. Optional TTS uses the vault SpaceXAI key or device speech."
                        },
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                    )
                    if (!enabled) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onGoSetup) {
                            Text("Open Setup", color = GrokifyColors.GlowViolet)
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(transcript, key = { it.id }) { msg ->
                        AssistantBubble(msg)
                    }
                    if (busy) {
                        item(key = "_busy") {
                            Row(
                                Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = GrokifyColors.GlowViolet,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Thinking…", color = GrokifyColors.TextDim, fontSize = 12.sp)
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
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { onSend(draft) },
                enabled = enabled && !busy && draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && !busy && draft.isNotBlank()) {
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
private fun AssistantBubble(msg: AssistantChatMessage) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    val bg = when {
        isUser -> GrokifyColors.GlowViolet.copy(alpha = 0.22f)
        isError -> GrokifyColors.GlowAmber.copy(alpha = 0.18f)
        else -> GrokifyColors.PanelSoft
    }
    val border = when {
        isUser -> GrokifyColors.GlowViolet.copy(alpha = 0.4f)
        isError -> GrokifyColors.GlowAmber.copy(alpha = 0.45f)
        else -> GrokifyColors.PanelBorder
    }
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                when {
                    isUser -> "You"
                    isError -> "Error"
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
            Spacer(Modifier.height(2.dp))
            Text(
                msg.text,
                color = GrokifyColors.TextPrimary,
                fontSize = 14.sp,
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
    hasXaiKey: Boolean,
    voicePreviewMsg: String?,
    onPreviewVoice: () -> Unit,
    overlayEnabled: Boolean,
    canDrawOverlays: Boolean,
    onOverlayEnabledChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
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
        SetupRow("Speak replies", "TTS after each assistant answer") {
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
        SetupSectionLabel("MINI OVERLAY", GrokifyColors.GlowCyan)
        Text(
            "Float a compact chat over any app. Hold the mic to speak.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        SetupRow(
            title = "Overlay enabled",
            subtitle = if (canDrawOverlays) "Permission granted" else "Needs “Display over other apps”",
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
                Text("Show overlay", color = GrokifyColors.GlowMint, fontSize = 13.sp)
            }
            TextButton(onClick = onHideOverlay) {
                Text("Hide", color = GrokifyColors.TextMuted, fontSize = 13.sp)
            }
        }

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
        SetupSectionLabel("COMING SOON", GrokifyColors.TextDim)
        ComingSoonRow("Hey Grok wake word")
        ComingSoonRow("Default assistant / BT / Android Auto")
        ComingSoonRow("Look at my screen + crop")
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
