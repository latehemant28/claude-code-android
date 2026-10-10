package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.Hyphenation
import com.example.hinglishpdf.pipeline.pdf.PageKind
import com.example.hinglishpdf.pipeline.pdf.PdfLayout
import com.example.hinglishpdf.pipeline.pdf.PdfPageGlyphs
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.TableCellRef

/**
 * Stage 1, physical layout analysis: from page geometry to classified
 * blocks in reading order. No decisions about the text's content here
 * beyond what its shape and patterns say.
 *
 *  1. text-layer check per page (scans go to OCR);
 *  2. lines ([LineBuilder]);
 *  3. furniture, line by line ([FurnitureClassifier]); pages left blank on
 *     purpose become boilerplate;
 *  4. zones: text inside images (figure text), tables ([TableDetector]),
 *     and the rest, ordered by recursive X-Y cut together with the tables
 *     and figures as whole regions ([XyCut]), which also gives columns;
 *  5. blocks ([BlockBuilder]) and their roles ([BlockClassifier]);
 *  6. reading order: X-Y order, footnotes last; furniture kept apart.
 */
object LayoutAnalyzer {

    private sealed interface Zone {
        data class Line(val line: TextLine) : Zone
        data class TableZone(val index: Int, val table: Table) : Zone
        data class Figure(val lines: List<TextLine>) : Zone
    }

    private class Plan(
        val page: PdfPageGlyphs,
        val furniture: List<Pair<TextLine, Scored>>,
        val order: List<XyCut.Placed<Zone>>,
        val figures: List<Box>,
        val columns: Int,
        val tables: Int,
    ) {
        val flow: List<TextLine> get() = order.mapNotNull { (it.value as? Zone.Line)?.line?.copy(column = it.column, columnId = it.columnId) }
    }

    fun analyze(pages: List<PdfPageGlyphs>, config: PipelineConfig = PipelineConfig()): DocumentLayout {
        val kinds = pages.associate { it.page to PdfLayout.kindOf(it) }.toMutableMap()
        val textPages = pages.filter { kinds[it.page] == PageKind.TEXT }
        val glyphs = textPages.flatMap { it.glyphs }
        val bodySize = bodySizeOf(glyphs)
        val tiers = Tiers.of(glyphs, bodySize, config.tierTolerance)

        val linesByPage = textPages.associate { page -> page.page to LineBuilder.lines(page, config) }
        val furniture = FurnitureClassifier.classify(linesByPage, textPages, bodySize, tiers, config)

        val plans = textPages.map { page ->
            val all = linesByPage[page.page].orEmpty()
            val marked = furniture[page.page].orEmpty()
            val pageFurniture = all.filter { it in marked }.map { it to marked.getValue(it) }.toMutableList()
            var content = all.filter { it !in marked }
            if (FurnitureClassifier.isBoilerplatePage(content, config)) {
                pageFurniture += content.map { it to Scored(BlockRole.BOILERPLATE, mapOf(BlockRole.BOILERPLATE to config.weights.boilerplatePattern)) }
                content = emptyList()
            }
            if (content.isEmpty()) kinds[page.page] = PageKind.BOILERPLATE

            val figures = figureBoxes(page, config)
            val inFigure = content.groupBy { line -> figures.indexOfFirst { it.contains((line.box.x0 + line.box.x1) / 2, (line.box.y0 + line.box.y1) / 2) } }
            val rest = inFigure[-1].orEmpty()
            val tables = TableDetector.detect(rest, config)
            val tableLines = tables.flatMap { t -> t.cells.map { it.first } }.toSet()
            val items = rest.filter { it !in tableLines }.map { XyCut.Item<Zone>(it.box, Zone.Line(it)) } +
                tables.mapIndexed { i, t -> XyCut.Item<Zone>(t.box, Zone.TableZone(i, t)) } +
                figures.mapIndexed { i, box -> XyCut.Item<Zone>(box, Zone.Figure(inFigure[i].orEmpty())) }
            val ordered = XyCut.order(items, bodySize, config)
            Plan(page, pageFurniture, ordered.order, figures, ordered.columns, tables.size)
        }

        val leading = Leading.measure(plans.map { it.flow }, config)
        val hyphenation = Hyphenation.build(plans.asSequence().flatMap { it.flow.asSequence() }.map { it.text }, config)
        val builder = BlockBuilder(bodySize, tiers, leading, config)

        val layouts = pages.map { page ->
            val plan = plans.firstOrNull { it.page.page == page.page }
                ?: return@map PageLayout(page.page, page.width, page.height, kinds.getValue(page.page), emptyList(), emptyList(), 1, 0)
            val content = mutableListOf<LayoutBlock>()
            var previous: BlockRole? = null
            fun add(draft: BlockDraft, column: Int, columnId: String, cell: TableCellRef? = null) {
                val scored = BlockClassifier.classify(draft, previous, page, plan.figures, bodySize, tiers, config)
                previous = scored.role
                val size = draft.lines.groupingBy { it.size }.eachCount().maxBy { it.value }.key
                content += LayoutBlock(
                    id = "", page = page.page, role = scored.role, box = draft.lines.map { it.box }.reduce(Box::union),
                    fontTier = tiers.of(size), columnId = columnId, column = column, readingIndex = 0,
                    lines = draft.lines, level = if (scored.role == BlockRole.HEADING || scored.role == BlockRole.LIST_ITEM) draft.level else 0,
                    marker = draft.marker.takeIf { scored.role == BlockRole.LIST_ITEM }, cell = cell, scores = scored.scores,
                )
            }
            // Runs of lines between tables and figures become blocks; tables and figures are expanded in place.
            var run = mutableListOf<TextLine>()
            fun flushRun() {
                if (run.isNotEmpty()) builder.build(run, page).forEach { add(it, it.lines.first().column, it.lines.first().columnId) }
                run = mutableListOf()
            }
            for (placed in plan.order) {
                when (val zone = placed.value) {
                    is Zone.Line -> run += zone.line.copy(column = placed.column, columnId = placed.columnId)
                    is Zone.TableZone -> {
                        flushRun()
                        for ((line, row, col) in zone.table.cells) {
                            add(BlockDraft(listOf(line), BlockRole.BODY, 0, null, false, BlockRole.TABLE_CELL), placed.column, placed.columnId, TableCellRef(zone.index, row, col))
                        }
                    }
                    is Zone.Figure -> {
                        flushRun()
                        val lines = zone.lines.map { it.copy(column = placed.column, columnId = placed.columnId) }.sortedWith(compareBy({ it.baseline }, { it.box.x0 }))
                        builder.build(lines, page, forced = BlockRole.CAPTION).forEach { add(it, placed.column, placed.columnId) }
                    }
                }
            }
            flushRun()

            val ordered = if (config.footnotesLast) content.filter { it.role != BlockRole.FOOTNOTE } + content.filter { it.role == BlockRole.FOOTNOTE } else content
            val blocks = ordered.mapIndexed { i, b -> b.copy(id = "p${page.page}.b$i", readingIndex = i) }
            val furnitureBlocks = plan.furniture.sortedWith(compareBy({ it.first.baseline }, { it.first.box.x0 })).mapIndexed { i, (line, scored) ->
                LayoutBlock(
                    id = "p${page.page}.f$i", page = page.page, role = scored.role, box = line.box, fontTier = tiers.of(line.size),
                    columnId = "", column = 0, readingIndex = -1, lines = listOf(line), scores = scored.scores,
                )
            }
            PageLayout(page.page, page.width, page.height, kinds.getValue(page.page), blocks, furnitureBlocks, plan.columns, plan.tables)
        }
        return DocumentLayout(layouts, bodySize, hyphenation)
    }

    /** Where figures are drawn; page-sized images are backgrounds and do not count. */
    fun figureBoxes(page: PdfPageGlyphs, config: PipelineConfig): List<Box> =
        page.imageBoxes.filter { it.width > 0f && it.height > 0f && it.width * it.height <= page.width * page.height * config.figureMaxPageShare }

    private fun Box.contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
}
