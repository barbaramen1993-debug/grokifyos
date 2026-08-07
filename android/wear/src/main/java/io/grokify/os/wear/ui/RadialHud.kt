package io.grokify.os.wear.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.grokify.os.wear.data.WearSnapshot
import io.grokify.os.wear.data.WeatherFetcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Bg = Color(0xFF03050A)
private val Cyan = Color(0xFF22D3EE)
private val CyanDim = Color(0x6622D3EE)
private val Magenta = Color(0xFFE879F9)
private val Amber = Color(0xFFFBBF24)
private val Green = Color(0xFF4ADE80)
private val Violet = Color(0xFFA78BFA)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF94A3B8)

/**
 * Radial telemetry HUD.
 *
 * Metric values are **circular progress arcs of varying lengths**. The whole arc
 * constellation is **rotated by compass heading** so turning the body revolves
 * the rings around the face (north-locked dial). Center clock stays fixed.
 * Metric icons sit at arc midpoints on the rotating dial.
 */
@Composable
fun RadialHud(
    snapshot: WearSnapshot,
    versionName: String,
    modifier: Modifier = Modifier,
    onSwipeUp: () -> Unit = {},
) {
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathScale",
    )
    val sweepGlow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val measurer = rememberTextMeasurer()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val secFmt = remember { SimpleDateFormat("ss", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragAccum < -80f) onSwipeUp()
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f },
                    onVerticalDrag = { _, dy -> dragAccum += dy },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(size.width, size.height) / 2f * 0.96f * breath

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Cyan.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r * 0.9f,
                ),
                radius = r * 0.9f,
                center = Offset(cx, cy),
            )

            drawCircle(
                color = CyanDim,
                radius = r * 0.98f,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)),
                ),
            )

            // Fixed bezel ticks + fixed north marker (world dial spins under them).
            for (i in 0 until 60) {
                val ang = Math.toRadians(i * 6.0 - 90.0)
                val outer = r * 0.98f
                val inner = if (i % 5 == 0) r * 0.92f else r * 0.95f
                val a = Cyan.copy(alpha = if (i % 5 == 0) 0.35f else 0.12f)
                drawLine(
                    color = a,
                    start = Offset(cx + cos(ang).toFloat() * inner, cy + sin(ang).toFloat() * inner),
                    end = Offset(cx + cos(ang).toFloat() * outer, cy + sin(ang).toFloat() * outer),
                    strokeWidth = if (i % 5 == 0) 2f else 1f,
                )
            }
            run {
                val nOuter = r * 0.995f
                drawLine(
                    color = Amber,
                    start = Offset(cx, cy - nOuter + 10f),
                    end = Offset(cx, cy - nOuter),
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round,
                )
            }

            drawArc(
                color = Cyan.copy(alpha = 0.28f),
                startAngle = sweepGlow,
                sweepAngle = 42f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.98f, cy - r * 0.98f),
                size = Size(r * 1.96f, r * 1.96f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )

            val hrNorm = ((snapshot.heartRateBpm ?: 0f) - 40f).coerceIn(0f, 140f) / 140f
            val stepsNorm = ((snapshot.stepsToday ?: 0L).toFloat() / 10_000f).coerceIn(0f, 1f)
            val batNorm = ((snapshot.batteryPct ?: 0).toFloat() / 100f).coerceIn(0f, 1f)
            val wxNorm = snapshot.weatherTempC?.let {
                ((it + 10f) / 50f).coerceIn(0f, 1f)
            } ?: 0.15f
            val mediaNorm = when {
                !snapshot.mediaTitle.isNullOrBlank() -> 0.85f
                !snapshot.lastNotification.isNullOrBlank() -> 0.45f
                else -> 0.12f
            }
            val sleepNorm = 0.35f + 0.15f * sin((snapshot.timeMillis / 8000.0)).toFloat()

            val heading = snapshot.headingDeg ?: 0f
            val rings = listOf(
                MetricRing(r * 0.90f, hrNorm, Magenta, -90f, 300f, 9f, HudIcon.Heart),
                MetricRing(r * 0.80f, stepsNorm, Cyan, -40f, 280f, 8f, HudIcon.Steps),
                MetricRing(r * 0.70f, batNorm, Green, 20f, 260f, 7f, HudIcon.Battery),
                MetricRing(r * 0.60f, wxNorm, Amber, 70f, 240f, 6f, HudIcon.Weather),
                MetricRing(r * 0.52f, mediaNorm, Violet, 120f, 220f, 5.5f, HudIcon.Media),
                MetricRing(r * 0.44f, sleepNorm.coerceIn(0f, 1f), Cyan.copy(alpha = 0.75f), 175f, 200f, 4.5f, HudIcon.Sleep),
            )

            rotate(degrees = -heading, pivot = Offset(cx, cy)) {
                rings.forEach { ring ->
                    drawMetricArc(
                        cx = cx,
                        cy = cy,
                        radius = ring.radius,
                        fraction = ring.fraction,
                        color = ring.color,
                        start = ring.baseStart,
                        maxSweep = ring.maxSweep,
                        stroke = ring.stroke,
                    )
                    // Icon badge at the start of each progress arc (rotates with compass dial).
                    val iconAng = Math.toRadians(ring.baseStart.toDouble())
                    val iconR = ring.radius
                    val ix = cx + cos(iconAng).toFloat() * iconR
                    val iy = cy + sin(iconAng).toFloat() * iconR
                    val iconSize = (ring.stroke * 2.4f).coerceIn(10f, 16f)
                    drawHudIconBadge(
                        icon = ring.icon,
                        center = Offset(ix, iy),
                        color = ring.color,
                        glyphSize = iconSize,
                    )
                }

                drawLine(
                    color = Amber.copy(alpha = 0.55f),
                    start = Offset(cx, cy + 8f),
                    end = Offset(cx, cy - r * 0.38f),
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round,
                )
                drawCircle(Amber.copy(alpha = 0.8f), radius = 3.5f, center = Offset(cx, cy - r * 0.38f))
            }

            val now = Date(snapshot.timeMillis)
            val timeStr = timeFmt.format(now)
            val secStr = secFmt.format(now)
            val dateStr = dateFmt.format(now)

            fun paint(
                text: String,
                y: Float,
                sizeSp: Float,
                color: Color,
                weight: FontWeight = FontWeight.Medium,
            ) {
                val style = TextStyle(
                    color = color,
                    fontSize = sizeSp.sp,
                    fontWeight = weight,
                    fontFamily = FontFamily.Monospace,
                )
                val layout = measurer.measure(text, style)
                drawText(
                    layout,
                    topLeft = Offset(cx - layout.size.width / 2f, y - layout.size.height / 2f),
                )
            }

            paint(dateStr, cy - r * 0.26f, 8f, TextMuted)
            paint(timeStr, cy - r * 0.06f, 30f, TextPrimary, FontWeight.Bold)
            paint(secStr, cy + r * 0.08f, 10f, Cyan)

            // Fixed legend under the clock: icon + short label for each progress ring.
            val legendY = cy + r * 0.20f
            val legendGap = r * 0.15f
            val legendIcons = listOf(
                Triple(HudIcon.Heart, Magenta, cx - legendGap * 2.25f),
                Triple(HudIcon.Steps, Cyan, cx - legendGap * 0.75f),
                Triple(HudIcon.Battery, Green, cx + legendGap * 0.75f),
                Triple(HudIcon.Weather, Amber, cx + legendGap * 2.25f),
            )
            legendIcons.forEach { (icon, color, x) ->
                drawHudIconBadge(icon, Offset(x, legendY), color, glyphSize = 9f, badge = false)
            }

            val hrTxt = snapshot.heartRateBpm?.let { "${it.roundToInt()} bpm" } ?: "— bpm"
            val stepTxt = snapshot.stepsToday?.let { "${formatSteps(it)} steps" } ?: "— steps"
            val batLine = snapshot.batteryPct?.let { "bat ${it}%" } ?: "bat —"
            paint("$hrTxt · $stepTxt", cy + r * 0.30f, 8f, Magenta.copy(alpha = 0.95f))
            paint(batLine, cy + r * 0.40f, 8f, Green)

            val wxLine = when {
                snapshot.weatherTempC != null ->
                    "${WeatherFetcher.formatTemp(snapshot.weatherTempC)} ${snapshot.weatherLabel ?: ""}".trim()
                snapshot.hasLocation ->
                    String.format(Locale.US, "%.2f,%.2f", snapshot.latitude, snapshot.longitude)
                else -> "loc —"
            }
            paint(wxLine.take(22), cy + r * 0.50f, 7.5f, Amber)

            val mediaLine = when {
                !snapshot.mediaTitle.isNullOrBlank() -> "♪ ${snapshot.mediaTitle}"
                !snapshot.lastNotification.isNullOrBlank() -> "✉ ${snapshot.lastNotification}"
                else -> null
            }
            if (mediaLine != null) {
                paint(mediaLine.take(24), cy + r * 0.60f, 7f, Violet)
            }

            val headingLine = snapshot.headingDeg?.let {
                "${it.roundToInt()}° ${cardinal(it)}"
            } ?: "compass —"
            paint(headingLine, cy + r * 0.72f, 8f, Amber.copy(alpha = 0.9f))
            paint("↑ Carina", cy + r * 0.84f, 7f, Cyan.copy(alpha = 0.75f))
            paint("v$versionName", cy + r * 0.93f, 6f, TextMuted.copy(alpha = 0.4f))
        }
    }
}

private enum class HudIcon { Heart, Steps, Battery, Weather, Media, Sleep }

private data class MetricRing(
    val radius: Float,
    val fraction: Float,
    val color: Color,
    val baseStart: Float,
    val maxSweep: Float,
    val stroke: Float,
    val icon: HudIcon,
)

private fun cardinal(deg: Float): String {
    val d = ((deg % 360f) + 360f) % 360f
    return when {
        d < 22.5f || d >= 337.5f -> "N"
        d < 67.5f -> "NE"
        d < 112.5f -> "E"
        d < 157.5f -> "SE"
        d < 202.5f -> "S"
        d < 247.5f -> "SW"
        d < 292.5f -> "W"
        else -> "NW"
    }
}

private fun formatSteps(n: Long): String =
    if (n >= 10_000L) String.format(Locale.US, "%.1fk", n / 1000.0) else n.toString()

private fun DrawScope.drawMetricArc(
    cx: Float,
    cy: Float,
    radius: Float,
    fraction: Float,
    color: Color,
    start: Float,
    maxSweep: Float,
    stroke: Float,
) {
    val topLeft = Offset(cx - radius, cy - radius)
    val sz = Size(radius * 2f, radius * 2f)
    drawArc(
        color = color.copy(alpha = 0.14f),
        startAngle = start,
        sweepAngle = maxSweep,
        useCenter = false,
        topLeft = topLeft,
        size = sz,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    if (fraction > 0.01f) {
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = maxSweep * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = sz,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Dark circular badge + glyph so progress-bar icons stay readable on the dial.
 */
private fun DrawScope.drawHudIconBadge(
    icon: HudIcon,
    center: Offset,
    color: Color,
    glyphSize: Float,
    badge: Boolean = true,
) {
    val s = glyphSize.coerceAtLeast(8f)
    if (badge) {
        val br = s * 1.05f
        drawCircle(Color(0xEE0B1220), radius = br, center = center)
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = br,
            center = center,
            style = Stroke(width = 1.4f),
        )
    }
    drawHudIcon(icon, center, color, s * 0.78f)
}

/** Geometric glyphs for the radial HUD (no bitmap assets). */
private fun DrawScope.drawHudIcon(icon: HudIcon, center: Offset, color: Color, size: Float) {
    val s = size.coerceAtLeast(5f)
    when (icon) {
        HudIcon.Heart -> {
            val path = Path().apply {
                val x = center.x
                val y = center.y
                moveTo(x, y + s * 0.42f)
                cubicTo(
                    x - s * 0.95f, y - s * 0.05f,
                    x - s * 0.55f, y - s * 0.95f,
                    x, y - s * 0.28f,
                )
                cubicTo(
                    x + s * 0.55f, y - s * 0.95f,
                    x + s * 0.95f, y - s * 0.05f,
                    x, y + s * 0.42f,
                )
                close()
            }
            drawPath(path, color)
        }
        HudIcon.Steps -> {
            // Footprints: heel + toe pairs
            drawCircle(color, s * 0.30f, Offset(center.x - s * 0.28f, center.y + s * 0.18f))
            drawCircle(color, s * 0.18f, Offset(center.x - s * 0.10f, center.y - s * 0.22f))
            drawCircle(color, s * 0.26f, Offset(center.x + s * 0.30f, center.y - s * 0.12f))
            drawCircle(color.copy(alpha = 0.85f), s * 0.15f, Offset(center.x + s * 0.12f, center.y + s * 0.28f))
        }
        HudIcon.Battery -> {
            val w = s * 0.85f
            val h = s * 1.15f
            val left = center.x - w / 2f
            val top = center.y - h / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f, s * 0.14f),
                style = Stroke(width = s * 0.16f),
            )
            drawRect(
                color = color,
                topLeft = Offset(center.x - w * 0.20f, top - s * 0.20f),
                size = Size(w * 0.40f, s * 0.20f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(left + w * 0.18f, top + h * 0.38f),
                size = Size(w * 0.64f, h * 0.48f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f, s * 0.08f),
            )
        }
        HudIcon.Weather -> {
            drawCircle(color, s * 0.34f, center)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val i0 = s * 0.48f
                val i1 = s * 0.78f
                drawLine(
                    color,
                    Offset(center.x + cos(a).toFloat() * i0, center.y + sin(a).toFloat() * i0),
                    Offset(center.x + cos(a).toFloat() * i1, center.y + sin(a).toFloat() * i1),
                    strokeWidth = s * 0.14f,
                    cap = StrokeCap.Round,
                )
            }
        }
        HudIcon.Media -> {
            drawCircle(color, s * 0.30f, Offset(center.x - s * 0.18f, center.y + s * 0.28f))
            drawLine(
                color,
                Offset(center.x + s * 0.10f, center.y + s * 0.28f),
                Offset(center.x + s * 0.10f, center.y - s * 0.55f),
                strokeWidth = s * 0.16f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(center.x + s * 0.10f, center.y - s * 0.55f),
                Offset(center.x + s * 0.48f, center.y - s * 0.32f),
                strokeWidth = s * 0.16f,
                cap = StrokeCap.Round,
            )
        }
        HudIcon.Sleep -> {
            drawCircle(color, s * 0.48f, center)
            drawCircle(Color(0xEE0B1220), s * 0.40f, Offset(center.x + s * 0.20f, center.y - s * 0.10f))
        }
    }
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
