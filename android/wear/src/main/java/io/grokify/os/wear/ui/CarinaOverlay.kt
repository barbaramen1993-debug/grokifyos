package io.grokify.os.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import io.grokify.os.wear.voice.CarinaVoiceSession
import kotlin.math.min

private val Bg = Color(0xFF03050A)
private val Cyan = Color(0xFF22D3EE)
private val Magenta = Color(0xFFE879F9)
private val Amber = Color(0xFFFBBF24)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF94A3B8)
private val Panel = Color(0xCC0F172A)

@Composable
fun CarinaOverlay(
    snap: CarinaVoiceSession.Snapshot,
    apiKeySet: Boolean,
    keySourceLabel: String = "local",
    syncStatus: String = "",
    /** One-tap OTA status line from [io.grokify.os.wear.update.WearSelfUpdater]. */
    updateStatus: String = "",
    updateProgress: Float = -1f,
    updateRunning: Boolean = false,
    hasDeviceToken: Boolean = false,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onRequestPhoneKey: () -> Unit = {},
    onUpdateApp: () -> Unit = {},
    onSaveDeviceToken: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf("") }

    val breath by rememberInfiniteTransition(label = "cbreath").animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cbreathS",
    )
    val glow by rememberInfiniteTransition(label = "cglow").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cglowA",
    )
    val ringColor = when (snap.turn) {
        CarinaVoiceSession.Turn.Listening -> Cyan
        CarinaVoiceSession.Turn.Thinking -> Amber
        CarinaVoiceSession.Turn.Speaking -> Magenta
        CarinaVoiceSession.Turn.Connecting -> TextMuted
        CarinaVoiceSession.Turn.Error -> Color(0xFFF87171)
        CarinaVoiceSession.Turn.Idle -> Magenta.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        // Same radial chrome as the main HUD.
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) / 2f * 0.96f * breath
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ringColor.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r * 0.92f,
                ),
                radius = r * 0.92f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.35f),
                radius = r * 0.98f,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)),
                ),
            )
            // Outer progress-style rings
            drawArc(
                color = Magenta.copy(alpha = 0.55f),
                startAngle = -90f,
                sweepAngle = 220f + snap.level * 80f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.90f, cy - r * 0.90f),
                size = Size(r * 1.80f, r * 1.80f),
                style = Stroke(width = 6f, cap = StrokeCap.Round),
            )
            drawArc(
                color = Cyan.copy(alpha = 0.45f),
                startAngle = 20f,
                sweepAngle = 200f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.78f, cy - r * 0.78f),
                size = Size(r * 1.56f, r * 1.56f),
                style = Stroke(width = 4.5f, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColor.copy(alpha = 0.35f),
                startAngle = glow,
                sweepAngle = 48f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.98f, cy - r * 0.98f),
                size = Size(r * 1.96f, r * 1.96f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )
        }

        if (showSettings) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Carina · Settings", color = Cyan, fontSize = 13.sp)

                // —— One-tap self-update (check + download + install) ——
                Text("App update", color = Magenta, fontSize = 11.sp)
                Text(
                    updateStatus.ifBlank {
                        if (hasDeviceToken) "Tap Update — LTE/Wi‑Fi"
                        else "Sync phone token first"
                    },
                    color = if (updateRunning) Amber else TextMuted,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                )
                if (updateProgress in 0f..1f && updateRunning) {
                    Text(
                        "${(updateProgress * 100).toInt()}%",
                        color = Cyan,
                        fontSize = 10.sp,
                    )
                }
                Chip(
                    if (updateRunning) "Updating…" else "Update app",
                    if (updateRunning) TextMuted else Cyan,
                ) {
                    if (!updateRunning) onUpdateApp()
                }

                Text("API key", color = Magenta, fontSize = 11.sp)
                Text(
                    if (apiKeySet) "Using $keySourceLabel key"
                    else "Uses phone SpaceXAI key when paired",
                    color = TextMuted,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                )
                if (syncStatus.isNotBlank()) {
                    Text(
                        syncStatus,
                        color = Amber,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Chip("Sync from phone", Cyan, onRequestPhoneKey)
                Text(
                    "Or paste SpaceXAI key",
                    color = TextMuted.copy(alpha = 0.8f),
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                )
                BasicTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(Cyan),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .padding(10.dp),
                    decorationBox = { inner ->
                        if (keyDraft.isEmpty()) {
                            Text("xai-…", color = TextMuted.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                        inner()
                    },
                )
                if (!hasDeviceToken) {
                    Text(
                        "Device token (OTA, if unpaired)",
                        color = TextMuted.copy(alpha = 0.8f),
                        fontSize = 8.sp,
                        textAlign = TextAlign.Center,
                    )
                    BasicTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(Cyan),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Panel)
                            .padding(10.dp),
                        decorationBox = { inner ->
                            if (tokenDraft.isEmpty()) {
                                Text(
                                    "device token…",
                                    color = TextMuted.copy(alpha = 0.5f),
                                    fontSize = 10.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Save", Cyan) {
                        if (keyDraft.isNotBlank()) onSaveApiKey(keyDraft)
                        if (tokenDraft.isNotBlank()) onSaveDeviceToken(tokenDraft)
                        showSettings = false
                    }
                    Chip("Back", TextMuted) {
                        showSettings = false
                    }
                }
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("CARINA", color = Magenta, fontSize = 14.sp)
            Text(
                snap.statusLine
                    ?: if (apiKeySet) "Key: $keySourceLabel · swipe-up voice"
                    else "Waiting for phone SpaceXAI key…",
                color = TextMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            if (!apiKeySet && syncStatus.isNotBlank()) {
                Text(
                    syncStatus,
                    color = Amber,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                )
            }

            LevelBars(level = snap.level, turn = snap.turn)

            val user = snap.partialUser
            val asst = snap.partialAssistant
            if (!user.isNullOrBlank()) {
                Text(
                    "You: ${user.take(120)}",
                    color = Cyan,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!asst.isNullOrBlank()) {
                Text(
                    asst.take(160),
                    color = TextPrimary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))

            val live = snap.turn != CarinaVoiceSession.Turn.Idle &&
                snap.turn != CarinaVoiceSession.Turn.Error
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) {
                    Chip("End", Amber, onStop)
                } else {
                    Chip(
                        if (apiKeySet) "Talk" else "Sync key",
                        Cyan,
                    ) {
                        if (apiKeySet) onStart() else {
                            onRequestPhoneKey()
                            showSettings = true
                        }
                    }
                }
                Chip("Key", TextMuted) { showSettings = true }
                Chip("HUD", TextMuted, onDismiss)
            }

            Text(
                "Tools: telemetry · apps · Spotify · notes",
                color = TextMuted.copy(alpha = 0.7f),
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LevelBars(
    level: Float,
    turn: CarinaVoiceSession.Turn,
) {
    val color = when (turn) {
        CarinaVoiceSession.Turn.Listening -> Cyan
        CarinaVoiceSession.Turn.Thinking -> Amber
        CarinaVoiceSession.Turn.Speaking -> Magenta
        CarinaVoiceSession.Turn.Connecting -> TextMuted
        CarinaVoiceSession.Turn.Error -> Color(0xFFF87171)
        CarinaVoiceSession.Turn.Idle -> TextMuted
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(32.dp),
    ) {
        val n = 12
        for (i in 0 until n) {
            val wave = ((i + 1f) / n)
            val active = level >= wave * 0.45f
            val h = (6f + 22f * (if (active) level.coerceAtLeast(0.2f) else 0.12f)).dp
            Box(
                modifier = Modifier
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) color else color.copy(alpha = 0.18f))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF0F172A),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
