package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.pdf.PdfPageGlyphs
import com.example.hinglishpdf.pipeline.segment.Box
import kotlin.math.abs
import kotlin.math.ceil

/** A role with what every role scored. */
class Scored(val role: BlockRole, val scores: Map<BlockRole, Float>)

/** Adds up signal weights per role; the highest score wins, ties going to the role added first (body). */
private class Scores(body: Float, unknown: Float) {
    val map = linkedMapOf(BlockRole.BODY to body, BlockRole.UNKNOWN to unknown)
    fun add(role: BlockRole, weight: Float) {
        map[role] = (map[role] ?: 0f) + weight
    }
    fun result(): Scored = Scored(map.entries.maxBy { it.value }.key, map.toMap())
}

/**
 * Page furniture, line by line: what the margin zones hold (running heads,
 * page numbers, stray notes) and printer's marks anywhere. Signals, each
 * with its weight in [com.example.hinglishpdf.pipeline.LayoutWeights]:
 * margin zone; repetition across pages with digits ignored, counted for odd
 * and even pages apart, or in a run of pages of one side; page-number and
 * running-head patterns (a running head's number must move with the page);
 * printer's-mark patterns.
 */
object FurnitureClassifier {

    /** Lines whose best role is not body, page by page. */
    fun classify(byPage: Map<Int, List<TextLine>>, pages: List<PdfPageGlyphs>, bodySize: Float, tiers: Tiers, config: PipelineConfig): Map<Int, Map<TextLine, Scored>> {
        val w = config.weights
        val heights = pages.associate { it.page to it.height }
        fun zone(line: TextLine): Int {
            val h = heights[line.page] ?: return 0
            return when {
                line.box.y1 < h * config.marginTop -> TOP
                line.box.y0 > h * (1 - config.marginBottom) -> BOTTOM
                else -> 0
            }
        }
        fun key(line: TextLine) = line.text.lowercase().replace(DIGITS, "#").replace(SPACES, " ").trim()

        val pageList = pages.map { it.page }.sorted()
        val sidePages = pageList.groupBy { it % 2 }
        fun needed(count: Int) = maxOf(config.repetitionMinPages, ceil(config.repetitionShare * count).toInt())
        val pagesWithKey = mutableMapOf<String, MutableSet<Int>>()
        byPage.values.flatten().filter { zone(it) != 0 }.forEach { pagesWithKey.getOrPut(key(it)) { sortedSetOf() } += it.page }
        fun repeated(line: TextLine): Boolean {
            val on = pagesWithKey[key(line)] ?: return false
            val side = sidePages[line.page % 2].orEmpty()
            if (on.size >= needed(pageList.size) || side.count { it in on } >= needed(side.size)) return true
            var run = 0
            var best = 0
            for (p in side) {
                run = if (p in on) run + 1 else 0
                best = maxOf(best, run)
            }
            return best >= config.repetitionMinRun
        }

        val patterned = byPage.values.flatten().filter { line -> zone(line) != 0 && config.headerFooter.any { it.matches(line.text.trim()) } }.toSet()
        val offsets = patterned.mapNotNull { line -> numberOf(line.text.trim())?.let { it - line.page } }.groupingBy { it }.eachCount()

        fun isolated(line: TextLine, lines: List<TextLine>): Boolean {
            val reach = config.paragraphGapRatio * config.defaultLeadingEm * line.size
            return lines.none { it !== line && abs(it.baseline - line.baseline) > 0.25f * line.size && abs(it.baseline - line.baseline) <= reach }
        }

        return byPage.mapValues { (_, lines) ->
            buildMap {
                for (line in lines) {
                    val text = line.text.trim()
                    val zone = zone(line)
                    val scores = Scores(w.body, w.unknown)
                    if (config.boilerplateLine.any { it.containsMatchIn(text) }) scores.add(BlockRole.BOILERPLATE, w.boilerplatePattern)
                    if (zone != 0) {
                        val side = if (zone == TOP) BlockRole.HEADER else BlockRole.FOOTER
                        scores.add(side, w.marginZone)
                        scores.add(BlockRole.PAGE_NUMBER, w.marginZone)
                        val pageNumber = config.pageNumber.any { it.matches(text) }
                        if (pageNumber) scores.add(BlockRole.PAGE_NUMBER, w.pageNumberPattern)
                        val repeats = repeated(line)
                        if (repeats) scores.add(side, w.repetition)
                        val alone = isolated(line, lines)
                        val runningHead = line in patterned && alone
                        if (runningHead) {
                            scores.add(side, w.runningHeadPattern)
                            val number = numberOf(text)
                            val follows = number != null && (offsets[number - line.page] ?: 0) >= config.repetitionMinPages
                            val footnoteLike = tiers.of(line.size) < 0 && config.footnoteStart.containsMatchIn(text)
                            if (footnoteLike && !follows) scores.add(side, -w.footnoteLikePenalty)
                        }
                        val nothingElse = !pageNumber && !repeats && !runningHead
                        if (nothingElse && tiers.of(line.size) < 0 && alone && !config.footnoteStart.containsMatchIn(text) && line.size < bodySize) {
                            scores.add(BlockRole.UNKNOWN, w.strayMargin)
                        }
                    }
                    val scored = scores.result()
                    if (scored.role != BlockRole.BODY) put(line, scored)
                }
            }
        }
    }

    /** A number at the start or end of a line (a running head's page number). */
    private fun numberOf(text: String): Int? = EDGE_NUMBER.find(text)?.value?.toIntOrNull()

    /** "This page intentionally left blank", or only printer's marks. */
    fun isBoilerplatePage(content: List<TextLine>, config: PipelineConfig): Boolean {
        if (content.isEmpty()) return false
        val text = content.sortedWith(compareBy({ it.baseline }, { it.box.x0 })).joinToString(" ") { it.text.trim() }.replace(SPACES, " ").trim()
        return config.boilerplatePage.any { it.matches(text) }
    }

    private const val TOP = 1
    private const val BOTTOM = 2
    private val DIGITS = Regex("\\d+")
    private val SPACES = Regex("\\s+")
    private val EDGE_NUMBER = Regex("^\\d+|\\d+$")
}

/**
 * A run of lines that belong together, with what the segmentation saw at
 * its first line: [kind] is the provisional role (heading, list item,
 * footnote or body) that also decided where the block starts.
 */
class BlockDraft(val lines: List<TextLine>, val kind: BlockRole, val level: Int, val marker: String?, val numbered: Boolean, val forced: BlockRole?)

/**
 * Content blocks, by weighted signals ([com.example.hinglishpdf.pipeline.LayoutWeights]):
 * font tier and bold short lines (heading), bullet or number in a place a
 * list can start (list item), small type low on the page with a footnote
 * mark or after a footnote (footnote), "Figure n" patterns, an image right
 * above or below, or text drawn inside an image (caption), a table (table
 * cell).
 */
object BlockClassifier {

    fun classify(draft: BlockDraft, previous: BlockRole?, page: PdfPageGlyphs, figures: List<Box>, bodySize: Float, tiers: Tiers, config: PipelineConfig): Scored {
        val w = config.weights
        val scores = Scores(w.body, w.unknown)
        val first = draft.lines.first()
        val text = first.text.trim()
        val tier = tiers.of(first.size)
        when (draft.forced) {
            BlockRole.CAPTION -> scores.add(BlockRole.CAPTION, w.insideFigure)
            BlockRole.TABLE_CELL -> scores.add(BlockRole.TABLE_CELL, w.tableCell)
            else -> Unit
        }
        if (draft.kind == BlockRole.HEADING) scores.add(BlockRole.HEADING, if (tier > 0) w.headingTier else w.boldHeading)
        if (draft.kind == BlockRole.LIST_ITEM) scores.add(BlockRole.LIST_ITEM, if (draft.numbered) w.numbered else w.bullet)
        val smallAndLow = first.size <= bodySize * config.footnoteSizeRatio && first.box.y0 > page.height * config.footnoteZone
        if (smallAndLow) {
            scores.add(BlockRole.FOOTNOTE, w.footnoteZone)
            if (config.footnoteStart.containsMatchIn(text)) scores.add(BlockRole.FOOTNOTE, w.footnoteMark)
            if (previous == BlockRole.FOOTNOTE) scores.add(BlockRole.FOOTNOTE, w.footnoteContinues)
        }
        val short = draft.lines.size <= config.captionMaxLines
        if (config.caption.any { it.containsMatchIn(text) } && (short || tier != 0)) scores.add(BlockRole.CAPTION, w.captionPattern)
        if (short && nextToFigure(draft.lines, figures, config)) scores.add(BlockRole.CAPTION, w.nearImage)
        return scores.result()
    }

    /** Right above or below an image, overlapping it horizontally. */
    private fun nextToFigure(lines: List<TextLine>, figures: List<Box>, config: PipelineConfig): Boolean {
        if (figures.isEmpty()) return false
        val box = lines.map { it.box }.reduce(Box::union)
        val reach = config.captionGapEm * lines.first().size
        return figures.any { f ->
            val overlaps = box.x0 < f.x1 && box.x1 > f.x0
            overlaps && (box.y0 - f.y1 in -1f..reach || f.y0 - box.y1 in -1f..reach)
        }
    }
}
