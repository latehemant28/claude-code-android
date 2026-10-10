package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.pipeline.segment.Box

/** Builds a page glyph by glyph: every glyph 0.5 em wide, words 0.3 em apart. */
internal class PageBuilder(val number: Int, val width: Float = 600f, val height: Float = 800f) {
    val glyphs = mutableListOf<PdfGlyph>()
    val imageBoxes = mutableListOf<Box>()
    var images = 0

    /** Writes [text] from [x] on [baseline]; returns the x after it. */
    fun text(text: String, x: Float, baseline: Float, size: Float = 10f, bold: Boolean = false, italic: Boolean = false, font: String = "Serif"): Float {
        var at = x
        for (c in text) {
            if (c == ' ') {
                at += 0.3f * size
            } else {
                glyphs += PdfGlyph(c.toString(), at, baseline, 0.5f * size, size, font, bold, italic)
                at += 0.5f * size
            }
        }
        return at
    }

    /** Lines of body text, [leading] apart, from [baseline] down; returns the next free baseline. */
    fun lines(texts: List<String>, x: Float, baseline: Float, leading: Float = 12f, size: Float = 10f): Float {
        texts.forEachIndexed { i, t -> text(t, x, baseline + i * leading, size) }
        return baseline + texts.size * leading
    }

    fun image(box: Box) {
        images++
        imageBoxes += box
    }

    fun build() = PdfPageGlyphs(number, width, height, glyphs.toList(), images, imageBoxes.toList())
}

internal fun page(number: Int = 1, block: PageBuilder.() -> Unit) = PageBuilder(number).apply(block).build()
