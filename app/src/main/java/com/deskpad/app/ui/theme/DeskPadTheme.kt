package com.deskpad.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskpad.app.model.ThemeStyle

enum class PadPattern {
    DOT_MATRIX,
    CYBER_GRID,
    SUBTLE_RECESSED
}

@Immutable
data class DeskPadPalette(
    val style: ThemeStyle,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val padSurface: Color,
    val padGridColor: Color,
    val border: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val statusConnected: Color,
    val statusDisconnected: Color,
    val keyBackground: Color,
    val keyBorder: Color,
    val padCornerRadius: Dp,
    val pillCornerRadius: Dp,
    val padPattern: PadPattern
)

val LocalDeskPadPalette = staticCompositionLocalOf { paletteFor(ThemeStyle.OBSIDIAN_GLASS) }

fun paletteFor(style: ThemeStyle): DeskPadPalette = when (style) {
    ThemeStyle.OBSIDIAN_GLASS -> DeskPadPalette(
        style = style,
        background = Color(0xFF050608),
        surface = Color(0xFF0F1117),
        surfaceElevated = Color(0xFF171A23),
        padSurface = Color(0xFF0A0C11),
        padGridColor = Color(0x1FFFFFFF),
        border = Color(0x1EFFFFFF),
        textPrimary = Color(0xFFF8FAFC),
        textMuted = Color(0xFF94A3B8),
        accent = Color(0xFF38BDF8),
        onAccent = Color(0xFF031520),
        statusConnected = Color(0xFF22D3EE),
        statusDisconnected = Color(0xFF64748B),
        keyBackground = Color(0xFF171B24),
        keyBorder = Color(0x22FFFFFF),
        padCornerRadius = 24.dp,
        pillCornerRadius = 16.dp,
        padPattern = PadPattern.DOT_MATRIX
    )

    ThemeStyle.TACTILE_MONO -> DeskPadPalette(
        style = style,
        background = Color(0xFF101012),
        surface = Color(0xFF1A1A1D),
        surfaceElevated = Color(0xFF232327),
        padSurface = Color(0xFF151518),
        padGridColor = Color(0x24FFFFFF),
        border = Color(0xFF2C2C32),
        textPrimary = Color(0xFFF4F4F0),
        textMuted = Color(0xFF8E8E93),
        accent = Color(0xFFFF3B30),
        onAccent = Color(0xFFFFFFFF),
        statusConnected = Color(0xFFFF3B30),
        statusDisconnected = Color(0xFF52525B),
        keyBackground = Color(0xFF232327),
        keyBorder = Color(0xFF34343B),
        padCornerRadius = 14.dp,
        pillCornerRadius = 10.dp,
        padPattern = PadPattern.DOT_MATRIX
    )

    ThemeStyle.NORDIC_STUDIO -> DeskPadPalette(
        style = style,
        background = Color(0xFFF6F5F1),
        surface = Color(0xFFFFFFFF),
        surfaceElevated = Color(0xFFEFEDE7),
        padSurface = Color(0xFFEAE8E1),
        padGridColor = Color(0x1818181B),
        border = Color(0xFFE2DFD7),
        textPrimary = Color(0xFF18181B),
        textMuted = Color(0xFF71717A),
        accent = Color(0xFF10B981),
        onAccent = Color(0xFFFFFFFF),
        statusConnected = Color(0xFF10B981),
        statusDisconnected = Color(0xFFA1A1AA),
        keyBackground = Color(0xFFFFFFFF),
        keyBorder = Color(0xFFDCD9D0),
        padCornerRadius = 22.dp,
        pillCornerRadius = 16.dp,
        padPattern = PadPattern.SUBTLE_RECESSED
    )

    ThemeStyle.CYBER_DECK -> DeskPadPalette(
        style = style,
        background = Color(0xFF0B0D17),
        surface = Color(0xFF14172B),
        surfaceElevated = Color(0xFF1D2240),
        padSurface = Color(0xFF101325),
        padGridColor = Color(0x228B5CF6),
        border = Color(0x4D8B5CF6),
        textPrimary = Color(0xFFEEF2FF),
        textMuted = Color(0xFF818CF8),
        accent = Color(0xFFA855F7),
        onAccent = Color(0xFFFFFFFF),
        statusConnected = Color(0xFF34D399),
        statusDisconnected = Color(0xFF6366F1),
        keyBackground = Color(0xFF1D2240),
        keyBorder = Color(0x44A855F7),
        padCornerRadius = 18.dp,
        pillCornerRadius = 12.dp,
        padPattern = PadPattern.CYBER_GRID
    )

    ThemeStyle.MATERIAL_FLUID -> DeskPadPalette(
        style = style,
        background = Color(0xFF141218),
        surface = Color(0xFF211F26),
        surfaceElevated = Color(0xFF2B2930),
        padSurface = Color(0xFF1D1B20),
        padGridColor = Color(0x1FD0BCFF),
        border = Color(0x29D0BCFF),
        textPrimary = Color(0xFFE6E0E9),
        textMuted = Color(0xFFCAC4D0),
        accent = Color(0xFFD0BCFF),
        onAccent = Color(0xFF381E72),
        statusConnected = Color(0xFFA6F7BD),
        statusDisconnected = Color(0xFF938F99),
        keyBackground = Color(0xFF36343B),
        keyBorder = Color(0x24D0BCFF),
        padCornerRadius = 28.dp,
        pillCornerRadius = 20.dp,
        padPattern = PadPattern.DOT_MATRIX
    )
}

@Composable
fun DeskPadTheme(
    style: ThemeStyle,
    content: @Composable () -> Unit
) {
    val palette = paletteFor(style)
    val colorScheme = if (style.isLight) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border
        )
    }

    CompositionLocalProvider(LocalDeskPadPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
