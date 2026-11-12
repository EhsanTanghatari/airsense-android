package com.airsense.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.airsense.monitor.data.Metric

object Palette {
    val Bg = Color(0xFF0B1220)
    val Surface = Color(0xFF111A2E)
    val SurfaceHi = Color(0xFF18233B)
    val Outline = Color(0xFF24324F)
    val Text = Color(0xFFE6EDF7)
    val Muted = Color(0xFF8A99B4)
    val Teal = Color(0xFF5EEAD4)
    val Sky = Color(0xFF38BDF8)
    val Violet = Color(0xFFA78BFA)
    val Amber = Color(0xFFFBBF24)
    val Orange = Color(0xFFFB923C)
    val Blue = Color(0xFF60A5FA)
    val Red = Color(0xFFF87171)
    val Green = Color(0xFF34D399)
}

fun Metric.color(): Color = when (this) {
    Metric.PM25 -> Palette.Teal
    Metric.PM10 -> Palette.Sky
    Metric.PM1 -> Palette.Violet
    Metric.TEMPERATURE -> Palette.Orange
    Metric.HUMIDITY -> Palette.Blue
    Metric.GAS -> Palette.Amber
}

private val scheme = darkColorScheme(
    primary = Palette.Teal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF0F3B3A),
    onPrimaryContainer = Palette.Teal,
    secondary = Palette.Sky,
    onSecondary = Color(0xFF001E2C),
    tertiary = Palette.Violet,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Bg,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHi,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerLow = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHi,
    surfaceContainerHighest = Color(0xFF1E2A45),
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Red,
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun AirSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
