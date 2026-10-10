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

// The brand palette: deep indigo and slate, with violet, pink and cyan accents.
private val Indigo300 = Color(0xFFA5B4FC)
private val Indigo400 = Color(0xFF818CF8)
private val Indigo500 = Color(0xFF6366F1)
private val Indigo600 = Color(0xFF4F46E5)
private val Indigo900 = Color(0xFF1E1B4B)
private val Violet500 = Color(0xFF8B5CF6)
private val Violet300 = Color(0xFFC4B5FD)
private val Pink500 = Color(0xFFEC4899)
private val Cyan400 = Color(0xFF22D3EE)
private val Cyan700 = Color(0xFF0E7490)
private val Emerald500 = Color(0xFF10B981)
private val Amber500 = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Indigo900,
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Cyan700,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF083344),
    background = Color(0xFFF3F4FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF3F4FA),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E5F1),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF8F9FD),
    surfaceContainerHigh = Color(0xFFECEEF7),
    surfaceContainerHighest = Color(0xFFE2E5F1),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD9DDEA),
)

private val DarkColors = darkColorScheme(
    primary = Indigo300,
    onPrimary = Color(0xFF14123A),
    primaryContainer = Color(0xFF2E2A78),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Violet300,
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF3B2470),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Cyan400,
    tertiaryContainer = Color(0xFF164E63),
    onTertiaryContainer = Color(0xFFCFFAFE),
    background = Color(0xFF0B0E1A),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0B0E1A),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF232842),
    onSurfaceVariant = Color(0xFFA3AEC6),
    surfaceContainerLowest = Color(0xFF080A14),
    surfaceContainerLow = Color(0xFF131729),
    surfaceContainer = Color(0xFF171C31),
    surfaceContainerHigh = Color(0xFF1E2440),
    surfaceContainerHighest = Color(0xFF262D4D),
    outline = Color(0xFF5B6585),
    outlineVariant = Color(0xFF2A3150),
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
    accent = Brush.linearGradient(listOf(Indigo500, Violet500, Pink500)),
    heroWash = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF26236B), Color(0xFF3A1F66), Color(0xFF1A2342)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE0E7FF), Color(0xFFEDE9FE), Color(0xFFFCE7F3)))
    },
    success = Emerald500,
    warning = Amber500,
    accentStart = if (dark) Indigo400 else Indigo600,
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
