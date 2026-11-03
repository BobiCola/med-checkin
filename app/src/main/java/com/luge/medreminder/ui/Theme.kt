package com.luge.medreminder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Blue = Color(0xFF1677FF)
val BlueDark = Color(0xFF0E5FD8)
val BlueLight = Color(0xFF4096FF)
val Indigo = Blue
val Good = Color(0xFF00B578)
val Warn = Color(0xFFFF8F1F)
val Bad = Color(0xFFFF3141)
val Gray = Color(0xFF999999)

val LocalDark = staticCompositionLocalOf { false }

/** Theme-aware palette (light: DingTalk/Alipay gray+white; dark: deep neutral). */
val PageBg: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF0F1012) else Color(0xFFF5F6F7)
val CardBg: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF1C1D21) else Color.White
val TextMain: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFFE8EAED) else Color(0xFF1F2329)
val TextSub: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF8A9099) else Color(0xFF8F959E)
val Track: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF2A2C31) else Color(0xFFF0F1F3)
val Divider: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF2A2C31) else Color(0xFFEBEDF0)
val SegBg: Color @Composable @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF26282C) else Color(0xFFE9EBEE)
val BlueGradient: Brush @Composable @ReadOnlyComposable get() =
    if (LocalDark.current) Brush.linearGradient(listOf(Color(0xFF1D4FA8), Color(0xFF15408C), Color(0xFF0D2E6B)))
    else Brush.linearGradient(listOf(BlueLight, Blue, BlueDark))

private val Light = lightColorScheme(
    primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFFE6F0FF), onPrimaryContainer = BlueDark,
    secondary = BlueLight, secondaryContainer = Color(0xFFE6F0FF), onSecondaryContainer = BlueDark,
    background = Color(0xFFF5F6F7), surface = Color.White, onSurface = Color(0xFF1F2329), onBackground = Color(0xFF1F2329),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF0F1F3), outline = Color(0xFF8F959E), outlineVariant = Color(0xFFEBEDF0), error = Bad,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4C93FF), onPrimary = Color.White, primaryContainer = Color(0xFF15408C), onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = BlueLight, secondaryContainer = Color(0xFF1D3460), onSecondaryContainer = Color(0xFFD6E4FF),
    background = Color(0xFF0F1012), surface = Color(0xFF1C1D21), onSurface = Color(0xFFE8EAED), onBackground = Color(0xFFE8EAED),
    surfaceContainer = Color(0xFF1C1D21), surfaceContainerLow = Color(0xFF1C1D21), surfaceContainerHigh = Color(0xFF232429),
    surfaceContainerHighest = Color(0xFF2A2C31), outline = Color(0xFF8A9099), outlineVariant = Color(0xFF2A2C31), error = Bad,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** Rounded card with subtle shadow (DingTalk / Alipay style). */
@Composable
fun WhiteCard(modifier: Modifier = Modifier, radius: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalDark.current
    Column(modifier.fillMaxWidth().shadow(if (dark) 0.dp else 2.dp, RoundedCornerShape(radius), ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
        .clip(RoundedCornerShape(radius)).background(CardBg), content = content)
}

@Composable
fun IconCircle(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (LocalDark.current) 0.22f else 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = TextSub)
