package com.example.hinglishpdf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The brand palette: deep indigo / purple, with violet, pink and cyan accents.
private val Pink500 = Color(0xFFEC4899)
private val Cyan700 = Color(0xFF0E7490)
private val Emerald500 = Color(0xFF10B981)
private val Amber500 = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B45E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF1C0F5C),
    secondary = Color(0xFFB0307F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD7F3),
    onSecondaryContainer = Color(0xFF3E0A2E),
    tertiary = Cyan700,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF083344),
    background = Color(0xFFF5F4FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF5F4FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE5E1F3),
    onSurfaceVariant = Color(0xFF4B5068),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF9F8FD),
    surfaceContainerHigh = Color(0xFFEEEBF8),
    surfaceContainerHighest = Color(0xFFE5E1F3),
    outline = Color(0xFF948FB0),
    outlineVariant = Color(0xFFDCD8EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4A5FF),
    onPrimary = Color(0xFF21105C),
    primaryContainer = Color(0xFF2B2A5C),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFF0A6E0),
    onSecondary = Color(0xFF3E0A2E),
    secondaryContainer = Color(0xFF4A2550),
    onSecondaryContainer = Color(0xFFFFD7F3),
    tertiary = Color(0xFF7DD3FC),
    tertiaryContainer = Color(0xFF164E63),
    onTertiaryContainer = Color(0xFFCFFAFE),
    background = Color(0xFF0D0F1C),
    onBackground = Color(0xFFE6E8F2),
    surface = Color(0xFF0D0F1C),
    onSurface = Color(0xFFE6E8F2),
    surfaceVariant = Color(0xFF232842),
    onSurfaceVariant = Color(0xFFA3ACC6),
    surfaceContainerLowest = Color(0xFF090B15),
    surfaceContainerLow = Color(0xFF141729),
    surfaceContainer = Color(0xFF171A2C),
    surfaceContainerHigh = Color(0xFF1E2238),
    surfaceContainerHighest = Color(0xFF272C46),
    outline = Color(0xFF4A5170),
    outlineVariant = Color(0xFF2A2F48),
)

/** Rounded everywhere: 12 dp chips, 20 dp cards, 28 dp sheets and dialogs. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Colours outside the Material scheme: the accent gradients and status colours. */
@Immutable
data class BrandColors(
    /** The primary action and the progress bars: indigo → violet → pink. */
    val accent: Brush,
    /** Hero cards: a soft wash of the same hues. */
    val heroWash: Brush,
    val success: Color,
    val warning: Color,
    val accentStart: Color,
    val accentEnd: Color,
)

private fun brand(dark: Boolean) = BrandColors(
    accent = Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFFC04FD8), Pink500)),
    heroWash = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF26236B), Color(0xFF3A1F66), Color(0xFF1A2342)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE0E7FF), Color(0xFFEDE9FE), Color(0xFFFCE7F3)))
    },
    success = Emerald500,
    warning = Amber500,
    accentStart = if (dark) Color(0xFFB4A5FF) else Color(0xFF5B45E0),
    accentEnd = Pink500,
)

val LocalBrand = staticCompositionLocalOf { brand(dark = false) }

/** The brand's extra colours, next to MaterialTheme.colorScheme. */
val MaterialTheme.brand: BrandColors
    @Composable get() = LocalBrand.current

/**
 * Material 3 theme in the app's own indigo / slate palette, light or dark with
 * the system. Material You wallpaper colours are not used, so the brand
 * gradients always sit on matching surfaces.
 */
@Composable
fun HinglishPdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBrand provides brand(darkTheme)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = AppShapes,
            content = content,
        )
    }
}
