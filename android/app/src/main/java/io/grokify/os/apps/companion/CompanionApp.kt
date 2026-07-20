package io.grokify.os.apps.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.core.content.ContextCompat
import io.grokify.os.apps.GROK_VOICES
import io.grokify.os.apps.plugin.HostAiClient
import io.grokify.os.apps.plugin.HostApiKeyStore
import io.grokify.os.data.ApiKeyIds
import io.grokify.os.ui.theme.GrokifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private val CompanionAccent = GrokifyColors.GlowViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionPane(onBack: () -> Unit) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val store = remember { CompanionStore(appCtx) }
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf(store.history()) }
    var draft by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf(store.systemPrompt) }
    var voiceId by remember { mutableStateOf(store.voiceId) }
    var preferDeviceTts by remember { mutableStateOf(store.preferDeviceTts) }
    var modelSource by remember { mutableStateOf(store.modelSource) }
    var userModelPath by remember { mutableStateOf(store.userModelPath) }

    var avatarState by remember { mutableStateOf(CompanionAvatarState.Idle) }
    var mouth by remember { mutableFloatStateOf(0f) }
    var voiceActive by remember { mutableStateOf(CompanionVoiceSession.isActive()) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var partialUser by remember { mutableStateOf<String?>(null) }
    var partialAssistant by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var showChat by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var stageReady by remember { mutableStateOf(false) }
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasXaiKey by remember {
        mutableStateOf(!HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank())
    }

    fun reloadHistory() {
        history = store.history()
    }

    fun flashStatus(msg: String?) {
        statusMsg = msg
    }

    fun mapTurn(turn: CompanionVoiceSession.Turn): CompanionAvatarState = when (turn) {
        CompanionVoiceSession.Turn.Idle -> CompanionAvatarState.Idle
        CompanionVoiceSession.Turn.Connecting,
        CompanionVoiceSession.Turn.Listening,
        -> CompanionAvatarState.Listening
        CompanionVoiceSession.Turn.Thinking -> CompanionAvatarState.Thinking
        CompanionVoiceSession.Turn.Speaking -> CompanionAvatarState.Speaking
        CompanionVoiceSession.Turn.Error -> CompanionAvatarState.Idle
    }

    // Always-fresh store/history hooks for the long-lived voice listener.
    val storeLatest = rememberUpdatedState(store)
    val busyLatest = rememberUpdatedState(busy)
    val onHistoryCommitted = rememberUpdatedState { role: String, text: String ->
        val body = text.trim()
        if (body.isEmpty()) return@rememberUpdatedState
        when (role.lowercase()) {
            "user" -> storeLatest.value.appendMessage(CompanionMessage.user(body, source = "voice"))
            "assistant" -> storeLatest.value.appendMessage(
                CompanionMessage.assistant(body, source = "voice"),
            )
            else -> return@rememberUpdatedState
        }
        history = storeLatest.value.history()
    }

    val voiceListener = remember {
        object : CompanionVoiceSession.Listener {
            override fun onSnapshot(snap: CompanionVoiceSession.Snapshot) {
                voiceActive = CompanionVoiceSession.isActive() ||
                    snap.turn == CompanionVoiceSession.Turn.Connecting
                voiceStatus = snap.statusLine
                partialUser = snap.partialUser
                partialAssistant = snap.partialAssistant
                // Prefer voice-driven stage while session owns the mic/playback path.
                if (CompanionVoiceSession.isActive() ||
                    snap.turn == CompanionVoiceSession.Turn.Connecting ||
                    snap.turn == CompanionVoiceSession.Turn.Error
                ) {
                    if (!busyLatest.value) {
                        avatarState = mapTurn(snap.turn)
                        mouth = snap.mouth.coerceIn(0f, 1f)
                    }
                }
                if (snap.turn == CompanionVoiceSession.Turn.Idle ||
                    snap.turn == CompanionVoiceSession.Turn.Error
                ) {
                    voiceActive = false
                    if (!busyLatest.value) {
                        avatarState = CompanionAvatarState.Idle
                        mouth = 0f
                    }
                    partialUser = null
                    partialAssistant = null
                }
            }

            override fun onTranscriptCommitted(role: String, text: String) {
                onHistoryCommitted.value(role, text)
            }

            override fun onError(message: String) {
                // Soft errors (timeouts, server faults mid-session) keep the WS live.
                // Only demote UI when the session actually stopped.
                flashStatus(message.take(160))
                val stillLive = CompanionVoiceSession.isActive()
                voiceActive = stillLive
                if (!stillLive && !busyLatest.value) {
                    avatarState = CompanionAvatarState.Idle
                    mouth = 0f
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CompanionVoiceSession.stop()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMic = granted
        if (granted) {
            startCompanionVoice(
                appCtx = appCtx,
                store = store,
                listener = voiceListener,
                onStatus = { flashStatus(it) },
            )
            voiceActive = true
            avatarState = CompanionAvatarState.Listening
            voiceStatus = "Connecting Companion voice…"
        } else {
            flashStatus("Microphone permission required for voice")
        }
    }

    val openModelTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            flashStatus("Copying model pack…")
            val result = withContext(Dispatchers.IO) {
                copyUserModelTree(appCtx, uri)
            }
            if (result.isSuccess) {
                val path = result.getOrNull().orEmpty()
                userModelPath = path
                modelSource = CompanionStore.SOURCE_USER
                store.userModelPath = path
                store.modelSource = CompanionStore.SOURCE_USER
                flashStatus("User model loaded")
            } else {
                flashStatus(result.exceptionOrNull()?.message ?: "Could not load model pack")
                modelSource = CompanionStore.SOURCE_BUNDLED
                store.modelSource = CompanionStore.SOURCE_BUNDLED
            }
        }
    }

    fun requestVoiceStart() {
        hasXaiKey = !HostApiKeyStore.getValue(appCtx, ApiKeyIds.SPACEXAI).isNullOrBlank()
        if (!hasXaiKey) {
            flashStatus("Add SpaceXAI API key for Companion voice")
            return
        }
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        // Live session: mic tap = barge-in interrupt (not a half-dead reconnect).
        if (CompanionVoiceSession.isActive()) {
            val t = CompanionVoiceSession.currentTurn()
            if (t == CompanionVoiceSession.Turn.Connecting) {
                // Stuck / slow connect — hard restart instead of interrupt-noop.
                CompanionVoiceSession.stop()
                voiceActive = false
            } else {
                CompanionVoiceSession.interrupt()
                flashStatus("Interrupted")
                return
            }
        }
        startCompanionVoice(
            appCtx = appCtx,
            store = store,
            listener = voiceListener,
            onStatus = { flashStatus(it) },
        )
        voiceActive = true
        avatarState = CompanionAvatarState.Listening
        voiceStatus = "Connecting Companion voice…"
    }

    fun stopVoice() {
        CompanionVoiceSession.stop()
        voiceActive = false
        voiceStatus = null
        partialUser = null
        partialAssistant = null
        if (!busy) {
            avatarState = CompanionAvatarState.Idle
            mouth = 0f
        }
        flashStatus("Voice stopped")
    }

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        // Text path pauses local avatar control; voice session can still be active but we avoid clobber.
        busy = true
        draft = ""
        store.appendMessage(CompanionMessage.user(trimmed, source = "text"))
        reloadHistory()
        avatarState = CompanionAvatarState.Thinking
        mouth = 0f
        flashStatus(null)
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    completeCompanionTurn(appCtx, store, trimmed)
                }
                if (reply.isFailure) {
                    val err = reply.exceptionOrNull()?.message ?: "complete_failed"
                    store.appendMessage(CompanionMessage.error(err))
                    reloadHistory()
                    flashStatus(err.take(160))
                    avatarState = CompanionAvatarState.Idle
                    return@launch
                }
                val answer = reply.getOrNull().orEmpty()
                store.appendMessage(CompanionMessage.assistant(answer, source = "text"))
                reloadHistory()
                if (answer.isNotBlank()) {
                    avatarState = CompanionAvatarState.Speaking
                    // Coarse mouth pulse while TTS plays (no PCM from speak API).
                    val speakJob = launch {
                        var t = 0f
                        while (isActive && busyLatest.value) {
                            t += 0.18f
                            mouth = (0.25f + 0.35f * kotlin.math.sin(t.toDouble()).toFloat())
                                .coerceIn(0f, 1f)
                            delay(50)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        val speakOpts = JSONObject()
                            .put("voice_id", store.voiceId)
                            .put("prefer_device", store.preferDeviceTts)
                            .put("language", "en")
                            // Block until playback finishes so mouth animation lasts
                            // and MediaPlayer is not abandoned mid-utterance.
                            .put("wait", true)
                            .toString()
                        val raw = HostAiClient.speak(appCtx, answer, speakOpts)
                        val ok = runCatching {
                            JSONObject(raw).optBoolean("ok", false)
                        }.getOrDefault(false)
                        if (!ok) {
                            val err = runCatching {
                                JSONObject(raw).optString("error", "speak_failed")
                            }.getOrDefault("speak_failed")
                            withContext(Dispatchers.Main) {
                                flashStatus("TTS: ${err.take(100)}")
                            }
                        }
                    }
                    speakJob.cancel()
                }
            } catch (e: Exception) {
                store.appendMessage(CompanionMessage.error(e.message ?: "send_failed"))
                reloadHistory()
                flashStatus(e.message?.take(160) ?: "send_failed")
            } finally {
                busy = false
                mouth = 0f
                if (!CompanionVoiceSession.isActive()) {
                    avatarState = CompanionAvatarState.Idle
                }
            }
        }
    }

    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(GrokifyColors.Void),
    ) {
        CompanionTopBar(
            connectionLabel = companionConnectionLabel(
                busy = busy,
                voiceActive = voiceActive,
                avatarState = avatarState,
                voiceStatus = voiceStatus,
            ),
            connectionColor = companionConnectionColor(
                busy = busy,
                voiceActive = voiceActive,
                avatarState = avatarState,
            ),
            onBack = onBack,
            onSettings = { showSettings = true },
        )

        if (!statusMsg.isNullOrBlank()) {
            Text(
                statusMsg!!,
                color = GrokifyColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        CompanionLive2dStage(
            modelSource = modelSource,
            userModelPath = userModelPath,
            avatarState = avatarState,
            mouth = mouth,
            onReady = { stageReady = true },
            onModelError = { msg ->
                flashStatus(msg.take(160))
                if (modelSource == CompanionStore.SOURCE_USER) {
                    modelSource = CompanionStore.SOURCE_BUNDLED
                    store.modelSource = CompanionStore.SOURCE_BUNDLED
                    flashStatus("User model failed — using bundled")
                }
            },
            onAvatarTapped = {
                if (CompanionVoiceSession.isActive()) {
                    CompanionVoiceSession.interrupt()
                } else if (!busy) {
                    // Idle tap opens chat; double-use: start voice if chat already open.
                    if (showChat) requestVoiceStart() else showChat = true
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        // Live partials strip while voice is active.
        if (voiceActive || !partialUser.isNullOrBlank() || !partialAssistant.isNullOrBlank()) {
            CompanionLivePartials(
                status = voiceStatus,
                partialUser = partialUser,
                partialAssistant = partialAssistant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        if (showChat) {
            CompanionChatPanel(
                history = history,
                draft = draft,
                onDraftChange = { draft = it },
                busy = busy,
                onSend = { sendText(it) },
                onClose = { showChat = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp),
            )
        }

        CompanionDock(
            voiceActive = voiceActive,
            busy = busy,
            showChat = showChat,
            onMic = { requestVoiceStart() },
            onStop = { stopVoice() },
            onToggleChat = {
                val opening = !showChat
                showChat = opening
                if (opening) {
                    // Re-read prefs so voice commits that raced Compose show up.
                    history = store.history()
                }
            },
        )

        if (!stageReady) {
            // subtle non-blocking hint under dock
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = settingsSheetState,
            containerColor = GrokifyColors.VoidElevated,
            contentColor = GrokifyColors.TextPrimary,
        ) {
            CompanionSettingsSheet(
                systemPrompt = systemPrompt,
                onSystemPromptChange = {
                    systemPrompt = it
                    store.systemPrompt = it
                },
                onResetPrompt = {
                    store.resetSystemPrompt()
                    systemPrompt = store.systemPrompt
                    flashStatus("Prompt reset")
                },
                voiceId = voiceId,
                onVoiceIdChange = {
                    voiceId = it
                    store.voiceId = it
                },
                preferDeviceTts = preferDeviceTts,
                onPreferDeviceTtsChange = {
                    preferDeviceTts = it
                    store.preferDeviceTts = it
                },
                modelSource = modelSource,
                onModelSourceChange = { src ->
                    modelSource = src
                    store.modelSource = src
                    if (src == CompanionStore.SOURCE_USER && userModelPath.isBlank()) {
                        flashStatus("Load a model pack first")
                    }
                },
                userModelPath = userModelPath,
                onLoadModel = { openModelTree.launch(null) },
                hasXaiKey = hasXaiKey,
                onClearHistory = { showClearConfirm = true },
                onClose = { showSettings = false },
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear conversation?") },
            text = {
                Text("Removes Companion chat history. Prompt, voice, and model settings stay.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.clearHistory()
                        reloadHistory()
                        showClearConfirm = false
                        flashStatus("History cleared")
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
private fun CompanionTopBar(
    connectionLabel: String,
    connectionColor: Color,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
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
                "Companion",
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                "Warm friend · Live2D + voice",
                color = GrokifyColors.TextDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            color = connectionColor.copy(alpha = 0.18f),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Text(
                connectionLabel,
                color = connectionColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                maxLines = 1,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = CompanionAccent,
            )
        }
    }
}

@Composable
private fun CompanionDock(
    voiceActive: Boolean,
    busy: Boolean,
    showChat: Boolean,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onToggleChat: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GrokifyColors.VoidElevated)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = onStop,
            enabled = voiceActive,
        ) {
            Icon(
                Icons.Filled.Stop,
                contentDescription = "Stop voice",
                tint = if (voiceActive) GrokifyColors.GlowRose else GrokifyColors.TextDim,
            )
        }

        Button(
            onClick = onMic,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (voiceActive) {
                    CompanionAccent.copy(alpha = 0.85f)
                } else {
                    CompanionAccent
                },
                contentColor = GrokifyColors.Void,
                disabledContainerColor = GrokifyColors.PanelSoft,
                disabledContentColor = GrokifyColors.TextDim,
            ),
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = if (voiceActive) "Interrupt" else "Start voice",
                modifier = Modifier.size(28.dp),
            )
        }

        IconButton(onClick = onToggleChat) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = if (showChat) "Hide chat" else "Show chat",
                tint = if (showChat) CompanionAccent else GrokifyColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun CompanionLivePartials(
    status: String?,
    partialUser: String?,
    partialAssistant: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GrokifyColors.Panel.copy(alpha = 0.9f))
            .border(1.dp, GrokifyColors.PanelBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        if (!status.isNullOrBlank()) {
            Text(
                status,
                color = CompanionAccent,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!partialUser.isNullOrBlank()) {
            Text(
                "You: $partialUser",
                color = GrokifyColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!partialAssistant.isNullOrBlank()) {
            Text(
                "Companion: $partialAssistant",
                color = GrokifyColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompanionChatPanel(
    history: List<CompanionMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.lastIndex)
        }
    }
    Column(
        modifier
            .background(GrokifyColors.Panel)
            .border(1.dp, GrokifyColors.PanelBorder)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chat",
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) {
                Text("Hide", color = GrokifyColors.TextMuted, fontSize = 12.sp)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    Text(
                        "Say hi by voice or type a message.",
                        color = GrokifyColors.TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(history, key = { it.id }) { msg ->
                CompanionChatBubble(msg)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Message Companion…", color = GrokifyColors.TextDim, fontSize = 13.sp)
                },
                singleLine = true,
                enabled = !busy,
                colors = companionFieldColors(),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { onSend(draft) },
                enabled = !busy && draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (!busy && draft.isNotBlank()) CompanionAccent else GrokifyColors.TextDim,
                )
            }
        }
    }
}

@Composable
private fun CompanionChatBubble(msg: CompanionMessage) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    val bg = when {
        isError -> GrokifyColors.GlowRose.copy(alpha = 0.12f)
        isUser -> GrokifyColors.UserBubble
        else -> GrokifyColors.AssistantBubble
    }
    val fg = when {
        isError -> GrokifyColors.GlowRose
        else -> GrokifyColors.TextPrimary
    }
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                when {
                    isError -> "Error"
                    isUser -> if (msg.source == "voice") "You · voice" else "You"
                    else -> if (msg.source == "voice") "Companion · voice" else "Companion"
                },
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
            )
            Text(msg.text, color = fg, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CompanionSettingsSheet(
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    voiceId: String,
    onVoiceIdChange: (String) -> Unit,
    preferDeviceTts: Boolean,
    onPreferDeviceTtsChange: (Boolean) -> Unit,
    modelSource: String,
    onModelSourceChange: (String) -> Unit,
    userModelPath: String,
    onLoadModel: () -> Unit,
    hasXaiKey: Boolean,
    onClearHistory: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Settings",
                color = GrokifyColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) {
                Text("Done", color = CompanionAccent)
            }
        }

        SectionLabel("SYSTEM PROMPT")
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            minLines = 5,
            maxLines = 10,
            colors = companionFieldColors(),
        )
        TextButton(onClick = onResetPrompt) {
            Text("Reset to default", color = GrokifyColors.GlowAmber, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("VOICE · TTS")
        Text(
            if (hasXaiKey) {
                "Grok Voice · xAI key found"
            } else {
                "Grok Voice · add SpaceXAI key or use device TTS"
            },
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
                        selectedContainerColor = CompanionAccent.copy(alpha = 0.25f),
                        selectedLabelColor = CompanionAccent,
                        containerColor = GrokifyColors.PanelSoft,
                        labelColor = GrokifyColors.TextPrimary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = GrokifyColors.PanelBorder,
                        selectedBorderColor = CompanionAccent,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsRow("Prefer device TTS", "Skip Grok Voice / xAI") {
            Switch(
                checked = preferDeviceTts,
                onCheckedChange = onPreferDeviceTtsChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GrokifyColors.Void,
                    checkedTrackColor = CompanionAccent,
                    uncheckedThumbColor = GrokifyColors.TextMuted,
                    uncheckedTrackColor = GrokifyColors.PanelSoft,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionLabel("LIVE2D MODEL")
        ModelSourceRow(
            label = "Bundled",
            selected = modelSource == CompanionStore.SOURCE_BUNDLED,
            onClick = { onModelSourceChange(CompanionStore.SOURCE_BUNDLED) },
        )
        ModelSourceRow(
            label = "User pack",
            selected = modelSource == CompanionStore.SOURCE_USER,
            onClick = { onModelSourceChange(CompanionStore.SOURCE_USER) },
        )
        if (userModelPath.isNotBlank()) {
            Text(
                userModelPath,
                color = GrokifyColors.TextDim,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }
        Button(
            onClick = onLoadModel,
            colors = ButtonDefaults.buttonColors(
                containerColor = CompanionAccent.copy(alpha = 0.25f),
                contentColor = CompanionAccent,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Load model pack…")
        }
        Text(
            "Pick a folder with a Cubism 3/4 pack (*.model3.json + .moc3 + textures). " +
                "Live2D free samples (Akari, Hiyori, …) work if you download them from " +
                "live2d.com — most GitHub “avatar” repos do not ship model binaries (license). " +
                "Copied into app storage and selectable as User pack.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("HISTORY")
        TextButton(onClick = onClearHistory) {
            Text("Clear conversation history", color = GrokifyColors.GlowRose, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = CompanionAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(bottom = 6.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsRow(
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
private fun ModelSourceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = CompanionAccent,
                unselectedColor = GrokifyColors.TextMuted,
            ),
        )
        Text(
            label,
            color = GrokifyColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun companionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = GrokifyColors.TextPrimary,
    unfocusedTextColor = GrokifyColors.TextPrimary,
    focusedBorderColor = CompanionAccent,
    unfocusedBorderColor = GrokifyColors.PanelBorder,
    cursorColor = CompanionAccent,
    focusedContainerColor = GrokifyColors.PanelSoft,
    unfocusedContainerColor = GrokifyColors.PanelSoft,
    disabledTextColor = GrokifyColors.TextDim,
    disabledBorderColor = GrokifyColors.PanelBorder,
    disabledContainerColor = GrokifyColors.Panel,
)

private fun companionConnectionLabel(
    busy: Boolean,
    voiceActive: Boolean,
    avatarState: CompanionAvatarState,
    voiceStatus: String?,
): String {
    if (busy && avatarState == CompanionAvatarState.Thinking) return "Thinking"
    if (busy && avatarState == CompanionAvatarState.Speaking) return "Speaking"
    if (voiceActive) {
        val status = voiceStatus.orEmpty()
        if (status.contains("Connecting", ignoreCase = true) ||
            status.contains("Minting", ignoreCase = true) ||
            status.contains("Opening", ignoreCase = true) ||
            status.contains("Configuring", ignoreCase = true)
        ) {
            return "Connecting"
        }
        return when (avatarState) {
            CompanionAvatarState.Listening -> "Listening"
            CompanionAvatarState.Thinking -> "Thinking"
            CompanionAvatarState.Speaking -> "Speaking"
            CompanionAvatarState.Idle -> voiceStatus?.take(18) ?: "Voice"
        }
    }
    return "Idle"
}

private fun companionConnectionColor(
    busy: Boolean,
    voiceActive: Boolean,
    avatarState: CompanionAvatarState,
): Color {
    if (busy || voiceActive) {
        return when (avatarState) {
            CompanionAvatarState.Speaking -> GrokifyColors.GlowMint
            CompanionAvatarState.Thinking -> CompanionAccent
            CompanionAvatarState.Listening -> GrokifyColors.GlowCyan
            CompanionAvatarState.Idle -> GrokifyColors.TextMuted
        }
    }
    return GrokifyColors.TextMuted
}

private fun startCompanionVoice(
    appCtx: Context,
    store: CompanionStore,
    listener: CompanionVoiceSession.Listener,
    onStatus: (String) -> Unit,
) {
    val system = CompanionPrompts.assembleSystem(store.systemPrompt)
    val historyBlock = CompanionPrompts.formatHistoryBlock(store.history())
    val instructions = if (historyBlock.isBlank()) {
        system
    } else {
        "$system\n\nRecent conversation:\n$historyBlock"
    }
    CompanionVoiceSession.start(
        ctx = appCtx,
        instructions = instructions,
        voiceId = store.voiceId,
        listener = listener,
    )
    onStatus("Connecting Companion voice…")
}

/** Host complete → assistant text. History already includes the just-appended user message. */
private fun completeCompanionTurn(
    ctx: Context,
    store: CompanionStore,
    userText: String,
): Result<String> {
    return try {
        val system = CompanionPrompts.assembleSystem(store.systemPrompt)
        // Drop the trailing user turn so we can append a clean "User: …" line once.
        val prior = CompanionPrompts.contextWindow(store.history()).let { win ->
            if (win.lastOrNull()?.role == "user" &&
                win.last().text.trim() == userText.trim()
            ) {
                win.dropLast(1)
            } else {
                win
            }
        }
        val recent = CompanionPrompts.formatHistoryBlock(prior)
        val promptForModel = if (recent.isBlank()) {
            userText
        } else {
            "Recent conversation:\n$recent\n\nUser: $userText"
        }
        val options = JSONObject()
            .put("system", system)
            .put("session_title", "Companion")
            .toString()
        val raw = HostAiClient.complete(ctx, promptForModel, options)
        val env = runCatching { JSONObject(raw) }.getOrElse {
            return Result.failure(Exception("bad_complete_response"))
        }
        if (!env.optBoolean("ok", false)) {
            val err = env.optString("error", "complete_failed")
                .ifBlank { "complete_failed" }
            val hint = env.optString("hint", "").trim()
            return Result.failure(
                Exception(if (hint.isNotBlank()) "$err — $hint" else err),
            )
        }
        val text = env.optString("text", env.optString("reply", ""))
            .ifBlank { env.optString("message", "") }
            .trim()
        if (text.isBlank()) {
            Result.failure(Exception("empty_reply"))
        } else {
            Result.success(text)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Copy a SAF document tree into `filesDir/companion/user_model/` and return the
 * absolute path of the first `*.model3.json` found.
 */
private fun copyUserModelTree(ctx: Context, treeUri: Uri): Result<String> {
    return try {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val destRoot = File(ctx.filesDir, "companion/user_model")
        if (destRoot.exists()) {
            destRoot.deleteRecursively()
        }
        destRoot.mkdirs()

        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
        copyDocumentChildren(ctx, treeUri, childrenUri, destRoot)

        val modelJson = destRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }
            ?: return Result.failure(Exception("No .model3.json found in pack"))
        Result.success(modelJson.absolutePath)
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "model_copy_failed"))
    }
}

private fun copyDocumentChildren(
    ctx: Context,
    treeUri: Uri,
    childrenUri: Uri,
    destDir: File,
) {
    val resolver = ctx.contentResolver
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            val docId = cursor.getString(idIdx) ?: continue
            val name = cursor.getString(nameIdx)?.ifBlank { null } ?: continue
            val mime = cursor.getString(mimeIdx).orEmpty()
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                val sub = File(destDir, name)
                sub.mkdirs()
                val subChildren = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                copyDocumentChildren(ctx, treeUri, subChildren, sub)
            } else {
                val out = File(destDir, name)
                resolver.openInputStream(docUri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

