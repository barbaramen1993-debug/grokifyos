package io.grokify.os.apps.companion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontFamily
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
    var debugOverlay by remember { mutableStateOf(store.debugOverlay) }
    var debugEntries by remember { mutableStateOf(CompanionDebugLog.snapshot()) }
    var debugExpanded by remember { mutableStateOf(true) }
    var debugSelectedId by remember { mutableStateOf<Long?>(null) }

    var avatarState by remember { mutableStateOf(CompanionAvatarState.Idle) }
    var mouth by remember { mutableFloatStateOf(0f) }
    var voiceActive by remember { mutableStateOf(CompanionVoiceSession.isActive()) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var partialUser by remember { mutableStateOf<String?>(null) }
    var partialAssistant by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusEpoch by remember { mutableStateOf(0) }
    var showChat by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    /** When non-null: rename dialog for this joint id (leftHand, vrLeft, …). */
    var jointRename by remember { mutableStateOf<JointRenameDraft?>(null) }
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
        if (msg.isNullOrBlank()) return
        val epoch = statusEpoch + 1
        statusEpoch = epoch
        scope.launch {
            delay(3_500)
            if (statusEpoch == epoch) statusMsg = null
        }
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
        val next = when (role.lowercase()) {
            "user" -> storeLatest.value.appendMessage(CompanionMessage.user(body, source = "voice"))
            "assistant" -> storeLatest.value.appendMessage(
                CompanionMessage.assistant(body, source = "voice"),
            )
            else -> return@rememberUpdatedState
        }
        history = next
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
        CompanionDebugLog.setEnabled(debugOverlay)
        val listener = {
            debugEntries = CompanionDebugLog.snapshot()
        }
        CompanionDebugLog.addListener(listener)
        onDispose {
            CompanionDebugLog.removeListener(listener)
            CompanionVoiceSession.stop()
        }
    }

    LaunchedEffect(debugOverlay) {
        CompanionDebugLog.setEnabled(debugOverlay)
        store.debugOverlay = debugOverlay
        CompanionStageHost.setDebugSkeleton(debugOverlay)
        debugEntries = CompanionDebugLog.snapshot()
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

    val openVrmFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            flashStatus("Copying VRM…")
            val result = withContext(Dispatchers.IO) {
                copyUserVrmFile(appCtx, uri)
            }
            if (result.isSuccess) {
                val path = result.getOrNull().orEmpty()
                userModelPath = path
                modelSource = CompanionStore.SOURCE_USER
                store.userModelPath = path
                store.modelSource = CompanionStore.SOURCE_USER
                flashStatus("User VRM loaded")
            } else {
                flashStatus(result.exceptionOrNull()?.message ?: "Could not load VRM")
                // Keep prior good path if copy failed without replacing files.
                if (userModelPath.isBlank() || !File(userModelPath).isFile) {
                    modelSource = CompanionStore.SOURCE_BUNDLED
                    store.modelSource = CompanionStore.SOURCE_BUNDLED
                }
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
        // Text path needs exclusive audio: stop voice so mic/TTS cannot stack.
        if (CompanionVoiceSession.isActive() ||
            CompanionVoiceSession.currentTurn() == CompanionVoiceSession.Turn.Connecting
        ) {
            CompanionVoiceSession.stop()
            voiceActive = false
            voiceStatus = null
            partialUser = null
            partialAssistant = null
        }
        busy = true
        draft = ""
        history = store.appendMessage(CompanionMessage.user(trimmed, source = "text"))
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Out,
                "text→",
                trimmed.take(160),
                trimmed.take(4_000),
            )
        }
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
                    if (CompanionDebugLog.enabled) {
                        CompanionDebugLog.append(
                            CompanionDebugLog.Dir.Sys,
                            "text←",
                            "error",
                            err.take(2_000),
                        )
                    }
                    history = store.appendMessage(CompanionMessage.error(err))
                    flashStatus(err.take(160))
                    avatarState = CompanionAvatarState.Idle
                    return@launch
                }
                val answer = reply.getOrNull().orEmpty()
                if (CompanionDebugLog.enabled) {
                    CompanionDebugLog.append(
                        CompanionDebugLog.Dir.In,
                        "text←",
                        answer.take(160),
                        answer.take(4_000),
                    )
                }
                history = store.appendMessage(CompanionMessage.assistant(answer, source = "text"))
                if (answer.isNotBlank()) {
                    avatarState = CompanionAvatarState.Speaking
                    // Pseudo-syllable mouth while device TTS plays (no PCM stream).
                    val speakJob = launch {
                        var t = 0f
                        var syllable = 0f
                        while (isActive && busyLatest.value) {
                            t += 0.22f
                            syllable += 0.38f
                            // Burst open on syllable peaks, dip toward closed between.
                            val syll = kotlin.math.sin(syllable.toDouble()).toFloat()
                            val syll2 = kotlin.math.sin((syllable * 1.7f + 0.4f).toDouble()).toFloat()
                            val open = (
                                0.12f +
                                    0.42f * ((syll + 1f) * 0.5f) +
                                    0.18f * ((syll2 + 1f) * 0.5f) +
                                    0.08f * kotlin.math.sin((t * 2.1f).toDouble()).toFloat()
                                ).coerceIn(0f, 1f)
                            // Occasional near-closed "consonant" dip.
                            mouth = if (syll > 0.92f) open * 0.25f else open
                            delay(36)
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
                history = store.appendMessage(CompanionMessage.error(e.message ?: "send_failed"))
                flashStatus(e.message?.take(160) ?: "send_failed")
            } finally {
                busy = false
                mouth = 0f
                avatarState = CompanionAvatarState.Idle
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

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            CompanionLive2dStage(
                modelSource = modelSource,
                userModelPath = userModelPath,
                avatarState = avatarState,
                mouth = mouth,
                debugSkeleton = debugOverlay,
                onReady = { stageReady = true },
                onModelLoaded = { path ->
                    val name = path.substringAfterLast('/').substringAfterLast('\\')
                        .ifBlank { "VRM" }
                        .removeSuffix(".vrm")
                        .removeSuffix(".VRM")
                    flashStatus("Avatar: $name")
                    val labels = store.jointLabelsJson
                    if (labels.isNotBlank()) {
                        CompanionStageHost.setJointLabels(labels)
                    }
                    if (debugOverlay) {
                        CompanionStageHost.setDebugSkeleton(true)
                    }
                },
                onModelError = { msg ->
                    flashStatus(msg.take(160))
                    if (modelSource == CompanionStore.SOURCE_USER) {
                        modelSource = CompanionStore.SOURCE_BUNDLED
                        store.modelSource = CompanionStore.SOURCE_BUNDLED
                        flashStatus("User model failed — using Seed-san")
                    }
                },
                // Stage canvas is orbit-only (rotate / pan / pinch-zoom).
                // Chat + voice stay on their toolbar buttons — no avatar-tap side effects.
                onAvatarTapped = {},
                onJointPicked = { info ->
                    val id = info.optString("id").trim()
                    if (id.isEmpty()) return@CompanionLive2dStage
                    val current = info.optString("name").ifBlank {
                        info.optString("default_name").ifBlank { id }
                    }
                    val def = info.optString("default_name").ifBlank { id }
                    jointRename = JointRenameDraft(
                        id = id,
                        draft = current,
                        defaultName = def,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (debugOverlay) {
                CompanionDebugOverlay(
                    entries = debugEntries,
                    expanded = debugExpanded,
                    selectedId = debugSelectedId,
                    onToggleExpand = { debugExpanded = !debugExpanded },
                    onSelect = { id ->
                        debugSelectedId = if (debugSelectedId == id) null else id
                    },
                    onClear = {
                        CompanionDebugLog.clear()
                        debugEntries = emptyList()
                        debugSelectedId = null
                    },
                    onCopy = { text ->
                        val body = text.trim()
                        if (body.isEmpty()) {
                            Toast.makeText(appCtx, "Nothing to copy", Toast.LENGTH_SHORT).show()
                            return@CompanionDebugOverlay
                        }
                        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (cm == null) {
                            Toast.makeText(appCtx, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
                            return@CompanionDebugOverlay
                        }
                        cm.setPrimaryClip(ClipData.newPlainText("companion_debug", body))
                        val lines = body.lineSequence().count()
                        Toast.makeText(
                            appCtx,
                            if (lines > 1) "Copied $lines lines" else "Copied",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                )
            }
        }

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
                debugOverlay = debugOverlay,
                onDebugOverlayChange = { on ->
                    debugOverlay = on
                    store.debugOverlay = on
                    CompanionDebugLog.setEnabled(on)
                    CompanionStageHost.setDebugSkeleton(on)
                    if (on) {
                        flashStatus("Debug: AI traffic + bone wireframe")
                    }
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
                onLoadModel = {
                    openVrmFile.launch(
                        arrayOf(
                            "application/octet-stream",
                            "model/gltf-binary",
                            "application/gltf-buffer",
                            "*/*",
                        ),
                    )
                },
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

    val rename = jointRename
    if (rename != null) {
        AlertDialog(
            onDismissRequest = { jointRename = null },
            title = { Text("Name joint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Bone id: ${rename.id}",
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Default: ${rename.defaultName}",
                        color = GrokifyColors.TextMuted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = rename.draft,
                        onValueChange = { next ->
                            jointRename = rename.copy(draft = next.take(64))
                        },
                        singleLine = true,
                        label = { Text("Display name") },
                        colors = companionFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Saved on this device. observe_environment returns " +
                            "joint_labels + named_joints so the AI can read names and positions.",
                        color = GrokifyColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val label = rename.draft.trim()
                        CompanionStageHost.setJointLabel(rename.id, label)
                        // Merge into prefs map.
                        val map = runCatching {
                            val raw = store.jointLabelsJson
                            if (raw.isBlank()) JSONObject() else JSONObject(raw)
                        }.getOrElse { JSONObject() }
                        if (label.isEmpty() ||
                            label.equals(rename.defaultName, ignoreCase = false) ||
                            label == rename.id
                        ) {
                            map.remove(rename.id)
                        } else {
                            map.put(rename.id, label)
                        }
                        store.jointLabelsJson = map.toString()
                        CompanionStageHost.setJointLabels(store.jointLabelsJson.ifBlank { "{}" })
                        flashStatus(
                            if (label.isBlank() || label == rename.defaultName) {
                                "Joint ${rename.id} → default"
                            } else {
                                "Joint ${rename.id} → \"$label\""
                            },
                        )
                        jointRename = null
                    },
                ) {
                    Text("Save", color = CompanionAccent)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            CompanionStageHost.setJointLabel(rename.id, "")
                            val map = runCatching {
                                val raw = store.jointLabelsJson
                                if (raw.isBlank()) JSONObject() else JSONObject(raw)
                            }.getOrElse { JSONObject() }
                            map.remove(rename.id)
                            store.jointLabelsJson = map.toString()
                            CompanionStageHost.setJointLabels(
                                store.jointLabelsJson.ifBlank { "{}" },
                            )
                            flashStatus("Joint ${rename.id} reset")
                            jointRename = null
                        },
                    ) {
                        Text("Reset", color = GrokifyColors.GlowAmber)
                    }
                    TextButton(onClick = { jointRename = null }) {
                        Text("Cancel", color = GrokifyColors.TextMuted)
                    }
                }
            },
            containerColor = GrokifyColors.VoidElevated,
            titleContentColor = GrokifyColors.TextPrimary,
            textContentColor = GrokifyColors.TextMuted,
        )
    }
}

/** Draft state for the joint rename dialog. */
private data class JointRenameDraft(
    val id: String,
    val draft: String,
    val defaultName: String,
)

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
                "Warm friend · VRM + voice",
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
    debugOverlay: Boolean,
    onDebugOverlayChange: (Boolean) -> Unit,
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
        SectionLabel("DEBUG")
        SettingsRow(
            "AI + skeleton debug",
            "Tool traffic · bones · tap joints to name (saved for AI)",
        ) {
            Switch(
                checked = debugOverlay,
                onCheckedChange = onDebugOverlayChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GrokifyColors.Void,
                    checkedTrackColor = CompanionAccent,
                    uncheckedThumbColor = GrokifyColors.TextMuted,
                    uncheckedTrackColor = GrokifyColors.PanelSoft,
                ),
            )
        }
        Text(
            "→ host sends / AI reads (user text, tool results, session prompt). " +
                "← AI sends (assistant text, tool calls). Skeleton: cyan bones, " +
                "joint spheres (tap to rename), cyan/magenta hand controllers, yellow HMD. " +
                "Names persist and appear in observe_environment as joint_labels / named_joints.",
            color = GrokifyColors.TextDim,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        SectionLabel("VRM AVATAR")
        ModelSourceRow(
            label = "Bundled (Seed-san)",
            selected = modelSource == CompanionStore.SOURCE_BUNDLED,
            onClick = { onModelSourceChange(CompanionStore.SOURCE_BUNDLED) },
        )
        ModelSourceRow(
            label = "User .vrm",
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
            Text("Load .vrm file…")
        }
        Text(
            "Pick a single .vrm file (VRoid Hub, booth.pm VRChat packs, etc.). " +
                "Copied into app storage. Needs real VRM 0.x/1.0 (not FBX/Unity package). " +
                "Default: Seed-san (VRM Public License 1.0).",
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
private fun CompanionDebugOverlay(
    entries: List<CompanionDebugLog.Entry>,
    expanded: Boolean,
    selectedId: Long?,
    onToggleExpand: () -> Unit,
    onSelect: (Long) -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && expanded) {
            listState.scrollToItem(entries.lastIndex.coerceAtLeast(0))
        }
    }
    val selectedEntry = selectedId?.let { id -> entries.firstOrNull { it.id == id } }
    Surface(
        modifier = modifier,
        color = GrokifyColors.VoidElevated.copy(alpha = 0.92f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            CompanionAccent.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "AI debug · ${entries.size}",
                    color = CompanionAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onToggleExpand),
                )
                TextButton(
                    onClick = {
                        // Selected row if any; otherwise entire log.
                        val text = if (selectedEntry != null) {
                            CompanionDebugLog.formatEntry(selectedEntry)
                        } else {
                            CompanionDebugLog.formatAll(entries)
                        }
                        onCopy(text)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (selectedEntry != null) "Copy" else "Copy all",
                        color = Color(0xFF4FD1C5),
                        fontSize = 11.sp,
                    )
                }
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Clear", color = GrokifyColors.GlowRose, fontSize = 11.sp)
                }
                TextButton(
                    onClick = onToggleExpand,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (expanded) "Hide" else "Show",
                        color = CompanionAccent,
                        fontSize = 11.sp,
                    )
                }
            }
            if (!expanded) return@Column
            Text(
                "→ out · ← in · · joint · tap row · Copy / Copy all",
                color = GrokifyColors.TextDim,
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.id }) { e ->
                    val dirMark = when (e.dir) {
                        CompanionDebugLog.Dir.Out -> "→"
                        CompanionDebugLog.Dir.In -> "←"
                        CompanionDebugLog.Dir.Sys -> "·"
                    }
                    val dirColor = when (e.dir) {
                        CompanionDebugLog.Dir.Out -> Color(0xFF4FD1C5)
                        CompanionDebugLog.Dir.In -> Color(0xFFF687B3)
                        CompanionDebugLog.Dir.Sys -> when (e.kind) {
                            "joint" -> Color(0xFFF6E05E)
                            else -> GrokifyColors.TextMuted
                        }
                    }
                    val selected = selectedId == e.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (selected) CompanionAccent.copy(alpha = 0.12f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(e.id) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "$dirMark [${e.kind}] ${e.summary}",
                            color = dirColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (selected) 6 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected && e.detail.isNotBlank()) {
                            Text(
                                e.detail,
                                color = GrokifyColors.TextPrimary.copy(alpha = 0.85f),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 24,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
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
        // Exact phase from VoiceSession (docs handshake) — show short label.
        when {
            status.contains("Minting", ignoreCase = true) -> return "Minting…"
            status.contains("Retrying", ignoreCase = true) -> return "Retrying…"
            status.contains("Opening", ignoreCase = true) -> return "Opening…"
            status.contains("Configuring", ignoreCase = true) -> return "Configuring…"
            status.contains("session ready", ignoreCase = true) -> return "Almost…"
            status.contains("Connecting", ignoreCase = true) -> return "Connecting…"
            status.contains("timed out", ignoreCase = true) ||
                status.contains("config error", ignoreCase = true) ||
                (
                    status.contains("socket", ignoreCase = true) &&
                        (
                            status.contains("fail", ignoreCase = true) ||
                                status.contains("closed", ignoreCase = true) ||
                                status.contains("timeout", ignoreCase = true)
                            )
                    ) ->
                return "Error"
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
        if (CompanionDebugLog.enabled) {
            CompanionDebugLog.append(
                CompanionDebugLog.Dir.Out,
                "complete",
                "system + prompt",
                "SYSTEM:\n${system.take(3_000)}\n\nPROMPT:\n${promptForModel.take(3_000)}",
            )
        }
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
 * Copy a single SAF document (`.vrm`) into `filesDir/companion/user_model/`.
 * Writes into a staging dir first so a failed import never wipes the previous model.
 */
private fun copyUserVrmFile(ctx: Context, fileUri: Uri): Result<String> {
    return try {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                fileUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val destRoot = File(ctx.filesDir, "companion/user_model")
        val staging = File(ctx.filesDir, "companion/user_model_staging")
        if (staging.exists()) {
            staging.deleteRecursively()
        }
        if (!staging.mkdirs()) {
            return Result.failure(Exception("Could not create model staging dir"))
        }

        var displayName = "avatar.vrm"
        ctx.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIdx >= 0) {
                cursor.getString(nameIdx)?.ifBlank { null }?.let { displayName = it }
            }
        }
        // Sanitize path segments (SAF names can contain slashes on some providers).
        displayName = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[^\w.\- ]+"""), "_")
            .ifBlank { "avatar.vrm" }
        if (!displayName.endsWith(".vrm", ignoreCase = true)) {
            // Many providers omit the extension or use a generic name.
            if (displayName.contains('.')) {
                staging.deleteRecursively()
                return Result.failure(Exception("Pick a .vrm avatar file (got $displayName)"))
            }
            displayName = "$displayName.vrm"
        }

        val staged = File(staging, displayName)
        ctx.contentResolver.openInputStream(fileUri)?.use { input ->
            FileOutputStream(staged).use { output -> input.copyTo(output) }
        } ?: run {
            staging.deleteRecursively()
            return Result.failure(Exception("Could not read VRM file"))
        }

        if (staged.length() < 64) {
            staging.deleteRecursively()
            return Result.failure(Exception("VRM file looks empty"))
        }
        // glTF binary magic "glTF" — catches zip/unity packages renamed to .vrm.
        if (!CompanionModelAssets.looksLikeGltfBinary(staged)) {
            staging.deleteRecursively()
            return Result.failure(
                Exception(
                    "Not a binary VRM/glTF file (booth Unity packages need the .vrm inside the zip)",
                ),
            )
        }

        // Swap: only replace the live pack after validation succeeds.
        if (destRoot.exists()) {
            destRoot.deleteRecursively()
        }
        if (!staging.renameTo(destRoot)) {
            // Cross-filesystem fallback: copy then delete staging.
            if (!destRoot.mkdirs()) {
                staging.deleteRecursively()
                return Result.failure(Exception("Could not create model dir"))
            }
            val out = File(destRoot, displayName)
            staged.inputStream().use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            staging.deleteRecursively()
            if (!out.isFile || !CompanionModelAssets.looksLikeGltfBinary(out)) {
                return Result.failure(Exception("Could not install VRM file"))
            }
            return Result.success(out.absolutePath)
        }
        val out = File(destRoot, displayName)
        if (!out.isFile) {
            return Result.failure(Exception("VRM install missing after swap"))
        }
        Result.success(out.absolutePath)
    } catch (e: Exception) {
        runCatching {
            File(ctx.filesDir, "companion/user_model_staging").deleteRecursively()
        }
        Result.failure(Exception(e.message ?: "vrm_copy_failed"))
    }
}

