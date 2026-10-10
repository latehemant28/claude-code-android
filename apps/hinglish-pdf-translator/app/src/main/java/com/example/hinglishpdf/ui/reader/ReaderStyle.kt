package com.example.hinglishpdf.ui.reader

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.example.hinglishpdf.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Devanagari reading fonts, downloaded from Google Fonts through Google Play
 * Services the first time they are shown and cached by the system. Until a
 * font arrives (or on a phone without Play Services), the system's own
 * Devanagari font is used, so text is always readable.
 */
private val googleFonts = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun googleFamily(name: String) = FontFamily(
    Font(GoogleFont(name), googleFonts, FontWeight.Normal),
    Font(GoogleFont(name), googleFonts, FontWeight.Bold),
)

/** The three font styles offered in the "Aa" sheet. */
enum class ReaderFont(val label: String, val googleName: String) {
    SANS("Sans-serif", "Mukta"),
    SERIF("Serif", "Noto Serif Devanagari"),
    CASUAL("Casual", "Kalam"),
    ;

    val family: FontFamily by lazy { googleFamily(googleName) }
}

/** How translated text is drawn: font and body size (headings scale with it). */
data class ReaderStyle(
    val font: ReaderFont = ReaderFont.SANS,
    val textSizeSp: Float = DEFAULT_SIZE_SP,
) {
    companion object {
        const val MIN_SIZE_SP = 14f
        const val MAX_SIZE_SP = 28f
        const val DEFAULT_SIZE_SP = 18f
    }
}

/** The reading style for everything below it in the UI tree. */
val LocalReaderStyle = staticCompositionLocalOf { ReaderStyle() }

/** Remembers the chosen reading style on this phone. */
class ReaderSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private val _style = MutableStateFlow(
        ReaderStyle(
            font = runCatching { ReaderFont.valueOf(prefs.getString(KEY_FONT, null)!!) }.getOrDefault(ReaderFont.SANS),
            textSizeSp = prefs.getFloat(KEY_SIZE, ReaderStyle.DEFAULT_SIZE_SP)
                .coerceIn(ReaderStyle.MIN_SIZE_SP, ReaderStyle.MAX_SIZE_SP),
        ),
    )
    val style: StateFlow<ReaderStyle> = _style.asStateFlow()

    fun setFont(font: ReaderFont) = update(_style.value.copy(font = font))

    fun setTextSize(sp: Float) =
        update(_style.value.copy(textSizeSp = sp.coerceIn(ReaderStyle.MIN_SIZE_SP, ReaderStyle.MAX_SIZE_SP)))

    private fun update(style: ReaderStyle) {
        _style.value = style
        prefs.edit().putString(KEY_FONT, style.font.name).putFloat(KEY_SIZE, style.textSizeSp).apply()
    }

    private companion object {
        const val KEY_FONT = "font"
        const val KEY_SIZE = "text_size_sp"
    }
}
