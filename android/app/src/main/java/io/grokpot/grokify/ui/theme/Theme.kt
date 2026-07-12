package io.grokpot.grokify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// High-tech void palette — cyan core, mint signal, violet thought
object GrokifyColors {
    val Void = Color(0xFF05060A)
    val VoidElevated = Color(0xFF0B0D14)
    val Panel = Color(0xFF10131C)
    val PanelSoft = Color(0xFF151925)
    val PanelBorder = Color(0xFF1E2536)
    val GlowCyan = Color(0xFF22D3EE)
    val GlowMint = Color(0xFF34D399)
    val GlowBlue = Color(0xFF60A5FA)
    val GlowViolet = Color(0xFFA78BFA)
    val GlowAmber = Color(0xFFFBBF24)
    val GlowRose = Color(0xFFF87171)
    val TextPrimary = Color(0xFFE8EDF7)
    val TextMuted = Color(0xFF8B95A8)
    val TextDim = Color(0xFF5C6578)
    val UserBubble = Color(0xFF1A2235)
    val AssistantBubble = Color(0xFF0E121C)
    val CodeBg = Color(0xFF080B12)
    val Scrim = Color(0xCC05060A)

    val HeaderGradient = Brush.verticalGradient(
        listOf(Color(0xFF0D1220), Color(0xFF05060A))
    )
    val AccentGradient = Brush.horizontalGradient(
        listOf(GlowCyan, GlowMint)
    )
    val GlowRing = Brush.radialGradient(
        listOf(GlowCyan.copy(alpha = 0.25f), Color.Transparent)
    )
}

private val scheme = darkColorScheme(
    primary = GrokifyColors.GlowCyan,
    onPrimary = Color(0xFF041016),
    secondary = GrokifyColors.GlowMint,
    onSecondary = Color(0xFF04120C),
    tertiary = GrokifyColors.GlowViolet,
    background = GrokifyColors.Void,
    onBackground = GrokifyColors.TextPrimary,
    surface = GrokifyColors.Panel,
    onSurface = GrokifyColors.TextPrimary,
    surfaceVariant = GrokifyColors.PanelSoft,
    onSurfaceVariant = GrokifyColors.TextMuted,
    outline = GrokifyColors.PanelBorder,
    error = GrokifyColors.GlowRose,
    primaryContainer = Color(0xFF0A2A32),
    onPrimaryContainer = GrokifyColors.GlowCyan,
)

private val type = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.5.sp,
        color = GrokifyColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.2.sp,
        color = GrokifyColors.TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = GrokifyColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = GrokifyColors.TextPrimary,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.8.sp,
        color = GrokifyColors.TextMuted,
    ),
)

@Composable
fun GrokifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = type,
        content = content,
    )
}
