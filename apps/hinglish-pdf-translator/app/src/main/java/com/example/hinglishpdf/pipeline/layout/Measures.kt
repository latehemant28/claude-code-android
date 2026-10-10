package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.pdf.PdfGlyph
import kotlin.math.abs
import kotlin.math.roundToInt

/** The body font size: the size most characters of the book are set in. */
internal fun bodySizeOf(glyphs: List<PdfGlyph>): Float =
    glyphs.filter { it.text.isNotBlank() }
        .groupingBy { (it.size * 2).roundToInt() }.eachCount()
        .maxByOrNull { it.value }?.key?.div(2f) ?: 10f

/**
 * Font-size tiers: 0 is the body size, 1 the largest size used above it,
 * 2 the next, and so on; -1 anything smaller than the body. Sizes within
 * the tolerance of each other share a tier.
 */
class Tiers(private val body: Float, private val above: List<Float>, private val tolerance: Float) {
    fun of(size: Float): Int = when {
        abs(size - body) <= body * tolerance -> 0
        size < body -> -1
        else -> above.indexOfFirst { abs(size - it) <= it * tolerance }.let { if (it < 0) above.count { s -> s > size } + 1 else it + 1 }
    }

    companion object {
        fun of(glyphs: List<PdfGlyph>, body: Float, tolerance: Float): Tiers {
            val sizes = glyphs.filter { it.text.isNotBlank() }.map { (it.size * 2).roundToInt() / 2f }.distinct()
                .filter { it > body * (1 + tolerance) }.sortedDescending()
            val heads = mutableListOf<Float>()
            for (s in sizes) if (heads.none { abs(it - s) <= it * tolerance }) heads += s
            return Tiers(body, heads, tolerance)
        }
    }
}

/** The usual distance between baselines inside a paragraph, per font size, measured on the book itself. */
class Leading(private val bySize: Map<Int, Float>, private val defaultEm: Float) {
    fun of(size: Float): Float = bySize[(size * 2).roundToInt()] ?: (defaultEm * size)

    companion object {
        /** [pages]: each page's lines in reading order. */
        fun measure(pages: List<List<TextLine>>, config: PipelineConfig): Leading {
            val deltas = mutableMapOf<Int, MutableList<Int>>()
            for (lines in pages) {
                for ((a, b) in lines.zipWithNext()) {
                    if (a.columnId != b.columnId || abs(a.size - b.size) > 0.25f) continue
                    val d = b.baseline - a.baseline
                    if (d > 0.8f * a.size && d < 3f * a.size) deltas.getOrPut((a.size * 2).roundToInt()) { mutableListOf() } += (d * 2).roundToInt()
                }
            }
            // The most common spacing is the one inside paragraphs; ties go to the tighter one.
            val bySize = deltas.mapValues { (_, d) ->
                d.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key / 2f
            }
            return Leading(bySize, config.defaultLeadingEm)
        }
    }
}
