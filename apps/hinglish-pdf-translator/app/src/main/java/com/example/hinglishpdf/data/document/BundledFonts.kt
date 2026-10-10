package com.example.hinglishpdf.data.document

import android.content.res.AssetManager
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.util.Log

/**
 * The Noto fonts shipped in assets/fonts (SIL Open Font License, OFL.txt):
 * Noto Sans Devanagari for Hindi, then Noto Sans (Latin subset) for English
 * names and numbers, then the phone's own fonts for anything else. The PDF
 * export draws with these, so every exported PDF embeds the same fonts and
 * looks the same on any device; the EPUB export embeds the Devanagari ones.
 */
class BundledFonts(private val assets: AssetManager) {

    val regular: Typeface by lazy { typeface(FontStyle.FONT_WEIGHT_NORMAL) }
    val bold: Typeface by lazy { typeface(FontStyle.FONT_WEIGHT_BOLD) }

    /** Font files to embed in an EPUB: published path → bytes. */
    fun epubFonts(): Map<String, ByteArray> = EPUB_FONTS.associateWith { name ->
        assets.open("fonts/$name").use { it.readBytes() }
    }

    private fun typeface(weight: Int): Typeface = try {
        Typeface.CustomFallbackBuilder(family("NotoSansDevanagari"))
            .addCustomFallback(family("NotoSans"))
            .setSystemFallback("sans-serif")
            .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
            .build()
    } catch (e: Exception) {
        // A missing or unreadable asset must not stop an export: use the phone's fonts.
        Log.w("BundledFonts", "Bundled fonts unavailable", e)
        Typeface.create(Typeface.DEFAULT, if (weight >= FontStyle.FONT_WEIGHT_BOLD) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun family(prefix: String): FontFamily =
        FontFamily.Builder(font("$prefix-Regular.ttf", FontStyle.FONT_WEIGHT_NORMAL))
            .addFont(font("$prefix-Bold.ttf", FontStyle.FONT_WEIGHT_BOLD))
            .build()

    private fun font(name: String, weight: Int): Font =
        Font.Builder(assets, "fonts/$name").setWeight(weight).build()

    companion object {
        val EPUB_FONTS = listOf("NotoSansDevanagari-Regular.ttf", "NotoSansDevanagari-Bold.ttf")
    }
}
