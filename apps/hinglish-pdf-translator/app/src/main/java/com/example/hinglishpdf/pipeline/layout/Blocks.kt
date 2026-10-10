package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.pdf.PdfPageGlyphs
import kotlin.math.abs

/**
 * Lines (in reading order) to blocks. A block ends at: a column change; a
 * vertical gap over [PipelineConfig.paragraphGapRatio] times the measured
 * line spacing; a change of font tier; a heading, list item (bullet, or a
 * number where a list can start) or footnote starting; a jump back up; a
 * first-line indent; the shorter last line of a paragraph; a contents entry
 * ending in leader dots and a page number; and, in columns of entries
 * (addresses, contents), every line.
 */
internal class BlockBuilder(
    private val bodySize: Float,
    private val tiers: Tiers,
    private val leading: Leading,
    private val config: PipelineConfig,
) {

    fun build(lines: List<TextLine>, page: PdfPageGlyphs, forced: BlockRole? = null): List<BlockDraft> {
        val out = mutableListOf<BlockDraft>()
        var current = mutableListOf<TextLine>()
        var kind = BlockRole.BODY
        var level = 0
        var marker: String? = null
        var numberedItem = false
        val lineByLine = lineStructured(lines)

        fun flush() {
            if (current.isNotEmpty()) out += BlockDraft(current, kind, level, marker, numberedItem, forced)
            current = mutableListOf()
            marker = null
        }

        for (line in lines) {
            val text = line.text.trim()
            val prev = current.lastOrNull()
            val heading = forced == null && isHeading(line, text)
            val footnote = line.size <= bodySize * config.footnoteSizeRatio && line.box.y0 > page.height * config.footnoteZone
            val startsFootnote = footnote && config.footnoteStart.containsMatchIn(text)
            val bullet = config.bullet.find(text)
            val gap = prev != null && line.baseline - prev.baseline > config.paragraphGapRatio * leading.of(prev.size)
            val numbered = config.numbered.containsMatchIn(text) &&
                (prev == null || kind == BlockRole.LIST_ITEM || config.endsTerminally(prev.text) || gap)
            val fullLine = prev != null && prev.box.x1 >= columnRight(lines, prev) - config.shortLineEm * prev.size

            val startsNew = when {
                prev == null -> true
                line.columnId != prev.columnId -> true
                heading != (kind == BlockRole.HEADING) -> true
                heading -> abs(line.size - prev.size) > 0.5f || gap
                startsFootnote -> true
                bullet != null || numbered -> true
                footnote != (kind == BlockRole.FOOTNOTE) && kind != BlockRole.LIST_ITEM -> true
                tiers.of(line.size) != tiers.of(prev.size) -> true
                gap -> true
                line.box.y0 < prev.box.y0 -> true // a jump back up
                kind == BlockRole.LIST_ITEM -> {
                    // An item runs on in lines indented past its bullet, or after a full line.
                    val hanging = line.box.x0 >= current.first().box.x0 + config.listContinuationEm * line.size
                    !(hanging || (fullLine && !config.endsTerminally(prev.text)))
                }
                line.box.x0 > prev.box.x0 + config.indentEm * line.size -> true // first-line indent
                prev in lineByLine && line in lineByLine && !startsLowercase(line) -> true // one entry per line
                config.leaderLine.matches(prev.text.trim()) -> true // a contents entry: "Chapter One ...... 5"
                !fullLine && config.endsTerminally(prev.text) -> true // the last, shorter line of a paragraph
                else -> false
            }
            if (startsNew) {
                flush()
                kind = when {
                    heading -> BlockRole.HEADING
                    startsFootnote || (footnote && kind == BlockRole.FOOTNOTE) -> BlockRole.FOOTNOTE
                    bullet != null || numbered -> BlockRole.LIST_ITEM
                    else -> BlockRole.BODY
                }
                numberedItem = bullet == null && numbered
                level = when (kind) {
                    BlockRole.HEADING -> headingLevel(line.size)
                    BlockRole.LIST_ITEM -> {
                        val left = lines.filter { it.columnId == line.columnId }.minOf { it.box.x0 }
                        ((line.box.x0 - left) / (config.listLevelEm * line.size)).toInt()
                    }
                    else -> 0
                }
            }
            if (bullet != null && current.isEmpty()) {
                marker = bullet.value.trim()
                current += withoutBullet(line)
            } else {
                current += line
            }
        }
        flush()
        return out
    }

    private fun columnRight(lines: List<TextLine>, line: TextLine): Float =
        lines.filter { it.columnId == line.columnId && it.box.x0 < line.box.x1 && it.box.x1 > line.box.x0 }.maxOf { it.box.x1 }

    private fun startsLowercase(line: TextLine) = line.text.trimStart().firstOrNull()?.isLowerCase() == true

    /**
     * Lines of columns that are lists of entries rather than prose: in each
     * run of lines of one column, the lines rarely run on into a lower-case
     * next line.
     */
    private fun lineStructured(lines: List<TextLine>): Set<TextLine> {
        val out = HashSet<TextLine>()
        var run = mutableListOf<TextLine>()
        fun close() {
            if (run.size >= 2 && run.first().column > 0) {
                val pairs = run.zipWithNext()
                val flowing = pairs.count { (a, b) -> !config.endsTerminally(a.text) && startsLowercase(b) }
                if (flowing.toFloat() / pairs.size < config.columnMinFlow) out += run
            }
            run = mutableListOf()
        }
        for (line in lines) {
            if (run.isNotEmpty() && run.last().columnId != line.columnId) close()
            run += line
        }
        close()
        return out
    }

    private fun isHeading(line: TextLine, text: String): Boolean {
        if (text.isEmpty() || text.length > 120) return false
        if (line.size >= bodySize * config.headingSizeRatio) return true
        val words = text.split(SPACES).size
        return line.bold && words <= config.boldHeadingMaxWords && !config.endsTerminally(text) && text.any { it.isLetter() }
    }

    private fun headingLevel(size: Float): Int = when {
        size >= bodySize * 1.8f -> 1
        size >= bodySize * 1.4f -> 2
        size >= bodySize * config.headingSizeRatio -> 3
        else -> 4
    }

    private fun withoutBullet(line: TextLine): TextLine {
        val first = line.runs.first()
        val stripped = config.bullet.replace(first.text.trimStart(), "")
        return line.copy(runs = listOf(first.copy(text = stripped)) + line.runs.drop(1))
    }

    private companion object {
        val SPACES = Regex("\\s+")
    }
}
