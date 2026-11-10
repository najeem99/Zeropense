package com.najeemfasil.expense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Theme tokens (FSD §12.14). */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val income: Color,
    val expense: Color,
    val warning: Color,
    val warningBg: Color,
    val isDark: Boolean
)

val LightPalette = Palette(
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEEF0F3),
    border = Color(0xFFE1E4E8),
    text = Color(0xFF11181C),
    textMuted = Color(0xFF687076),
    primary = Color(0xFF1B6EF3),
    onPrimary = Color(0xFFFFFFFF),
    income = Color(0xFF13804B),
    expense = Color(0xFFC9372C),
    warning = Color(0xFFA35F00),
    warningBg = Color(0xFFFFF4E0),
    isDark = false
)

val DarkPalette = Palette(
    background = Color(0xFF0E1012),
    surface = Color(0xFF181B1F),
    surfaceAlt = Color(0xFF22262B),
    border = Color(0xFF2D3238),
    text = Color(0xFFECEDEE),
    textMuted = Color(0xFF9BA1A6),
    primary = Color(0xFF5B9BFF),
    onPrimary = Color(0xFF0E1012),
    income = Color(0xFF4CC38A),
    expense = Color(0xFFFF6B5E),
    warning = Color(0xFFF5B342),
    warningBg = Color(0xFF3A2A0E),
    isDark = true
)

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val pill = 999.dp
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** The current colours, like `useColors()` in the Expo app. */
val colors: Palette
    @Composable
    @ReadOnlyComposable
    get() = LocalPalette.current

@Composable
fun ZeropenseTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            background = p.background,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surfaceAlt,
            onSurfaceVariant = p.textMuted,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.surfaceAlt,
            secondaryContainer = p.surfaceAlt,
            onSecondaryContainer = p.text,
            outline = p.border,
            outlineVariant = p.border,
            error = p.expense
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            onPrimary = p.onPrimary,
            background = p.background,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.surfaceAlt,
            onSurfaceVariant = p.textMuted,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.surfaceAlt,
            secondaryContainer = p.surfaceAlt,
            onSecondaryContainer = p.text,
            outline = p.border,
            outlineVariant = p.border,
            error = p.expense
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
