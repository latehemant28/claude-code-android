package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.pdf.PdfGlyph
import com.example.hinglishpdf.pipeline.pdf.PdfPageGlyphs
import com.example.hinglishpdf.pipeline.segment.Box
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Glyphs to lines: glyphs on one baseline form a line, small raised marks
 * (footnote numbers) join the line below them, and a gap wider than
 * [PipelineConfig.columnGapEm] splits a line into pieces (a column gutter, a
 * table cell). Words are joined at gaps over [PipelineConfig.wordGapEm];
 * bold and italic stretches become runs.
 */
object LineBuilder {

    fun lines(page: PdfPageGlyphs, config: PipelineConfig): List<TextLine> {
        val glyphs = page.glyphs.filter { it.text.isNotBlank() && it.size > 0f }.sortedWith(compareBy({ it.baseline }, { it.x }))
        val groups = mutableListOf<MutableList<PdfGlyph>>()
        for (g in glyphs) {
            val group = groups.lastOrNull { abs(it.first().baseline - g.baseline) <= 0.25f * maxOf(it.first().size, g.size) }
            if (group != null) group += g else groups += mutableListOf(g)
        }
        // Small raised glyphs (footnote marks) belong to the line just below them.
        val superscripts = mutableMapOf<MutableList<PdfGlyph>, MutableList<PdfGlyph>>()
        val standalone = mutableListOf<MutableList<PdfGlyph>>()
        for (group in groups) {
            val size = group.maxOf { it.size }
            val text = group.joinToString("") { it.text }
            val host = if (group.size <= 4 && text.all { it.isDigit() || it in "*†‡§¹²³⁴⁵⁶⁷⁸⁹⁰" }) {
                groups.firstOrNull { other ->
                    other !== group && other.maxOf { it.size } >= size * 1.2f &&
                        (other.first().baseline - group.first().baseline) in (0.1f * size)..(0.9f * other.maxOf { it.size }) &&
                        group.first().x >= other.minOf { it.x } - size && group.first().x <= other.maxOf { it.x + it.width } + size
                }
            } else {
                null
            }
            if (host != null) superscripts.getOrPut(host) { mutableListOf() } += group else standalone += group
        }
        return standalone.flatMap { group ->
            val marks = superscripts[group].orEmpty().toSet()
            fragments(page.page, (group + marks).sortedBy { it.x }, marks, config)
        }
    }

    private fun fragments(pageNumber: Int, glyphs: List<PdfGlyph>, marks: Set<PdfGlyph>, config: PipelineConfig): List<TextLine> {
        val out = mutableListOf<TextLine>()
        var current = mutableListOf<PdfGlyph>()
        fun flush() {
            if (current.isNotEmpty()) out += lineOf(pageNumber, current, marks, config)
            current = mutableListOf()
        }
        for (g in glyphs) {
            val prev = current.lastOrNull()
            if (prev != null && g.x - (prev.x + prev.width) > config.columnGapEm * maxOf(prev.size, g.size) && g !in marks) flush()
            current += g
        }
        flush()
        return out
    }

    private fun lineOf(pageNumber: Int, glyphs: List<PdfGlyph>, marks: Set<PdfGlyph>, config: PipelineConfig): TextLine {
        val normal = glyphs.filter { it !in marks }.ifEmpty { glyphs }
        val size = normal.groupingBy { (it.size * 2).roundToInt() }.eachCount().maxBy { it.value }.key / 2f
        val baseline = normal.map { it.baseline }.sorted()[normal.size / 2]
        val runs = mutableListOf<TextRun>()
        var prev: PdfGlyph? = null
        for (g in glyphs) {
            val sup = g in marks
            val gap = prev?.let { g.x - (it.x + it.width) } ?: 0f
            val space = prev != null && !sup && gap > config.wordGapEm * size && !prev.text.endsWith(" ") && !g.text.startsWith(" ")
            val text = (if (space) " " else "") + g.text
            val last = runs.lastOrNull()
            if (last != null && last.bold == g.bold && last.italic == g.italic && last.superscript == sup) {
                runs[runs.lastIndex] = last.copy(text = last.text + text)
            } else if (space && last != null && !sup && !last.superscript) {
                // The space between two differently styled words stays outside both.
                runs[runs.lastIndex] = last.copy(text = last.text + " ")
                runs += TextRun(g.text, g.bold, g.italic, sup)
            } else {
                runs += TextRun(text, g.bold, g.italic, sup)
            }
            prev = g
        }
        val box = Box(
            glyphs.minOf { it.x },
            normal.minOf { it.baseline - it.size * 0.8f },
            glyphs.maxOf { it.x + it.width },
            normal.maxOf { it.baseline + it.size * 0.25f },
        )
        val font = normal.groupingBy { it.font }.eachCount().maxBy { it.value }.key
        return TextLine(pageNumber, box, baseline, size, runs, font)
    }
}
