package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.Hyphenation
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.LineBox
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTextBuilder
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One character drawn on a PDF page. Points, origin at the top left; [baseline] is y of the baseline. */
data class PdfGlyph(
    val text: String,
    val x: Float,
    val baseline: Float,
    val width: Float,
    val size: Float,
    val font: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * Everything read from one page: its glyphs, how many images it draws, and
 * where it draws them (top-left origin, as for glyphs).
 */
data class PdfPageGlyphs(
    val page: Int,
    val width: Float,
    val height: Float,
    val glyphs: List<PdfGlyph>,
    val images: Int = 0,
    val imageBoxes: List<Box> = emptyList(),
)

/**
 * What a page holds: real text, only a picture of text (needs OCR), nothing,
 * or nothing worth translating ("intentionally left blank", a printer's
 * page, only a page number).
 */
enum class PageKind { TEXT, SCANNED, BLANK, BOILERPLATE }

/** The layout analysis of a whole PDF. */
data class PdfLayoutResult(
    /** Every block of every page in reading order, page furniture included (with its furniture role). */
    val paragraphs: List<ParsedParagraph>,
    val pageKinds: Map<Int, PageKind>,
    /** Running headers, footers, page numbers and printer's marks kept out of the text. */
    val droppedLines: Int,
    val tables: Int,
    /** Pages with text laid out in more than one column, with their most columns. */
    val columns: Map<Int, Int>,
    /** The book's vocabulary, for hyphens at page breaks (see DocumentAssembler). */
    val hyphenation: Hyphenation = Hyphenation.NONE,
) {
    val scannedPages: List<Int> get() = pageKinds.filterValues { it == PageKind.SCANNED }.keys.sorted()
    val boilerplatePages: List<Int> get() = pageKinds.filterValues { it == PageKind.BOILERPLATE }.keys.sorted()

    /** Mostly pictures of text: route the book to OCR. */
    val needsOcr: Boolean
        get() {
            val nonBlank = pageKinds.values.count { it == PageKind.TEXT || it == PageKind.SCANNED }
            return nonBlank > 0 && scannedPages.size * 2 >= nonBlank
        }
}

/**
 * Rebuilds reading order and structure from positioned glyphs, then
 * classifies every block of every page as body text, heading, list item,
 * header, footer, page number, caption, table cell, footnote, boilerplate or
 * unknown:
 *
 * 1. text-layer check per page (scans go to OCR);
 * 2. lines, with superscript footnote marks attached;
 * 3. page furniture: lines in the top or bottom margin zone that repeat
 *    across pages (odd and even pages counted apart, digits ignored), look
 *    like page numbers, or match the running-head patterns; printer's marks
 *    anywhere; pages left intentionally blank;
 * 4. figure text (inside an image), tables, and regions laid out in columns,
 *    each column read on its own and top to bottom;
 * 5. paragraphs: a new one at a vertical gap over [PipelineConfig.paragraphGapRatio]
 *    times the line spacing, a first-line indent, a heading, a list item
 *    (bullet or number), a footnote or a column change; words hyphenated at
 *    line ends rejoined by [Hyphenation]; captions by pattern or by an image
 *    next to them; each block keeps its font tier.
 *
 * Bold and italic spans inside a paragraph become placeholders; footnote
 * marks become [[SUP_n]]. Paragraphs stay on their page: joining text across
 * pages and columns is DocumentAssembler's job.
 *
 * Pure Kotlin: the PDF reader only supplies [PdfPageGlyphs].
 */
object PdfLayout {

    fun analyze(pages: List<PdfPageGlyphs>, config: PipelineConfig = PipelineConfig()): PdfLayoutResult {
        val kinds = pages.associate { it.page to kindOf(it) }.toMutableMap()
        val textPages = pages.filter { kinds[it.page] == PageKind.TEXT }
        val glyphs = textPages.flatMap { it.glyphs }
        val bodySize = bodySize(glyphs)
        val tiers = Tiers.of(glyphs, bodySize, config.tierTolerance)

        val linesByPage = textPages.associate { page -> page.page to lines(page, config) }
        val furniture = furniture(linesByPage, textPages, bodySize, tiers, config)

        // Per page: what is left after furniture, split into figure text, tables and running text.
        class Plan(val page: PdfPageGlyphs, val furniture: List<Pair<Line, ParagraphRole>>, val flow: List<Line>, val figure: List<Line>, val table: List<Line>)
        var tables = 0
        val columns = mutableMapOf<Int, Int>()
        val plans = textPages.map { page ->
            val all = linesByPage[page.page].orEmpty()
            val marked = furniture[page.page].orEmpty()
            val pageFurniture = all.filter { it in marked }.map { it to marked.getValue(it) }.toMutableList()
            var content = all.filter { it !in marked }
            if (isBoilerplatePage(content, config)) {
                pageFurniture += content.map { it to ParagraphRole.BOILERPLATE }
                content = emptyList()
            }
            if (content.isEmpty()) kinds[page.page] = PageKind.BOILERPLATE
            val figureBoxes = figureBoxes(page, config)
            val figure = content.filter { line -> figureBoxes.any { it.contains(line.box.centerX, line.box.centerY) } }
            val rest = content - figure.toSet()
            val table = tableLines(rest, config)
            if (table.isNotEmpty()) tables++
            val (ordered, columnCount) = readingOrder(rest - table.toSet(), config)
            if (columnCount > 1) columns[page.page] = columnCount
            Plan(page, pageFurniture, ordered, figure, table)
        }

        val leading = Leading.measure(plans.map { it.flow }, config)
        val hyphenation = Hyphenation.build(plans.asSequence().flatMap { it.flow.asSequence() }.map { it.text }, config)
        val context = Context(bodySize, tiers, leading, hyphenation, config)

        val paragraphs = mutableListOf<ParsedParagraph>()
        var dropped = 0
        for (plan in plans) {
            val page = plan.page
            val (top, bottom) = plan.furniture.sortedBy { it.first.baseline }.partition { it.first.box.y0 < page.height / 2 }
            fun addFurniture(lines: List<Pair<Line, ParagraphRole>>) = lines.forEach { (line, role) ->
                if (role.furniture) dropped++
                paragraphs += paragraph(listOf(line), role, 0, context)
            }
            addFurniture(top)
            paragraphs += paragraphsOf(plan.flow, page, context)
            paragraphs += paragraphsOf(plan.figure.sortedWith(compareBy({ it.baseline }, { it.box.x0 })), page, context, forced = ParagraphRole.CAPTION)
            // Cells after the page's text: they are read as their own units.
            plan.table.sortedWith(compareBy({ it.baseline }, { it.box.x0 })).forEach { line ->
                paragraphs += paragraph(listOf(line), ParagraphRole.TABLE_CELL, 0, context)
            }
            addFurniture(bottom)
        }
        return PdfLayoutResult(paragraphs, kinds, dropped, tables, columns, hyphenation)
    }

    /** What every paragraph of the book is measured against. */
    private class Context(val bodySize: Float, val tiers: Tiers, val leading: Leading, val hyphenation: Hyphenation, val config: PipelineConfig)

    // ------------------------------------------------------------------ text layer

    /**
     * A page with text has a usable text layer. Without any (or with only
     * garbage: unmapped fonts give U+FFFD or private-use characters) but
     * with images, it is a scan.
     */
    internal fun kindOf(page: PdfPageGlyphs): PageKind {
        val chars = page.glyphs.joinToString("") { it.text }.filterNot { it.isWhitespace() }
        val usable = chars.count { it.isLetterOrDigit() && it != '�' && it !in ''..'' }
        val readable = chars.length >= MIN_TEXT_CHARS && usable * 10 >= chars.length * 5
        val sparse = chars.isNotEmpty() && usable * 10 >= chars.length * 5 && page.images == 0
        return when {
            readable || sparse -> PageKind.TEXT
            page.images > 0 || chars.isNotEmpty() -> PageKind.SCANNED // a picture of text, or an unreadable text layer
            else -> PageKind.BLANK
        }
    }

    // ------------------------------------------------------------------ lines

    internal data class Run(val text: String, val bold: Boolean, val italic: Boolean, val superscript: Boolean = false)

    /**
     * One line of text, or a piece of one when a wide gap (column gutter,
     * table) splits it. [column] is set by [readingOrder]: 0 outside column
     * regions, 1, 2... inside one.
     */
    internal data class Line(
        val page: Int,
        val box: Box,
        val baseline: Float,
        val size: Float,
        val runs: List<Run>,
        val font: String,
        val column: Int = 0,
    ) {
        val text: String get() = runs.joinToString("") { it.text }
        val bold: Boolean get() = boldShare() > 0.6f
        val italic: Boolean get() = runs.filter { !it.superscript }.let { r -> r.isNotEmpty() && r.sumOf { if (it.italic) it.text.length else 0 } * 10 > r.sumOf { it.text.length } * 6 }

        fun boldShare(): Float {
            val visible = runs.filter { !it.superscript }
            val total = visible.sumOf { it.text.count { c -> !c.isWhitespace() } }
            return if (total == 0) 0f else visible.sumOf { r -> if (r.bold) r.text.count { !it.isWhitespace() } else 0 }.toFloat() / total
        }
    }

    private fun lines(page: PdfPageGlyphs, config: PipelineConfig): List<Line> {
        val glyphs = page.glyphs.filter { it.text.isNotBlank() && it.size > 0f }.sortedWith(compareBy({ it.baseline }, { it.x }))
        // Group by baseline.
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

    /** Splits one baseline group at wide gaps and joins glyphs into words and styled runs. */
    private fun fragments(pageNumber: Int, glyphs: List<PdfGlyph>, marks: Set<PdfGlyph>, config: PipelineConfig): List<Line> {
        val out = mutableListOf<Line>()
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

    private fun lineOf(pageNumber: Int, glyphs: List<PdfGlyph>, marks: Set<PdfGlyph>, config: PipelineConfig): Line {
        val normal = glyphs.filter { it !in marks }.ifEmpty { glyphs }
        val size = normal.groupingBy { (it.size * 2).roundToInt() }.eachCount().maxBy { it.value }.key / 2f
        val baseline = normal.map { it.baseline }.sorted()[normal.size / 2]
        val runs = mutableListOf<Run>()
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
                runs += Run(g.text, g.bold, g.italic, sup)
            } else {
                runs += Run(text, g.bold, g.italic, sup)
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
        return Line(pageNumber, box, baseline, size, runs, font)
    }

    // ------------------------------------------------------------------ page furniture

    /**
     * The furniture lines of each page, with their role. In the margin zones:
     * page numbers (pattern), running headers and footers (repeated on enough
     * pages of the same side or in a run of them, or shaped like a running
     * head whose number follows the page number), and small stray lines
     * ([ParagraphRole.UNKNOWN]). Anywhere: printer's marks.
     */
    private fun furniture(
        byPage: Map<Int, List<Line>>,
        pages: List<PdfPageGlyphs>,
        bodySize: Float,
        tiers: Tiers,
        config: PipelineConfig,
    ): Map<Int, Map<Line, ParagraphRole>> {
        val heights = pages.associate { it.page to it.height }
        fun zone(line: Line): Int {
            val h = heights[line.page] ?: return 0
            return when {
                line.box.y1 < h * config.marginTop -> TOP
                line.box.y0 > h * (1 - config.marginBottom) -> BOTTOM
                else -> 0
            }
        }
        fun key(line: Line) = line.text.lowercase().replace(DIGITS, "#").replace(SPACES, " ").trim()

        // Repetition, odd and even pages apart: verso and recto running heads differ.
        val pageList = pages.map { it.page }.sorted()
        val sidePages = pageList.groupBy { it % 2 }
        fun needed(count: Int) = maxOf(config.repetitionMinPages, ceil(config.repetitionShare * count).toInt())
        val pagesWithKey = mutableMapOf<String, MutableSet<Int>>()
        byPage.values.flatten().filter { zone(it) != 0 }.forEach { pagesWithKey.getOrPut(key(it)) { sortedSetOf() } += it.page }
        fun repeated(line: Line): Boolean {
            val on = pagesWithKey[key(line)] ?: return false
            val side = sidePages[line.page % 2].orEmpty()
            val onSide = side.filter { it in on }
            if (on.size >= needed(pageList.size) || onSide.size >= needed(side.size)) return true
            // A run of consecutive pages of this side (a chapter's running head).
            var run = 0
            var best = 0
            for (p in side) {
                run = if (p in on) run + 1 else 0
                best = maxOf(best, run)
            }
            return best >= config.repetitionMinRun
        }

        // A running head's number moves with the page: the same offset on several pages.
        fun numberOf(text: String): Int? = EDGE_NUMBER.find(text)?.value?.toIntOrNull()
        val patterned = byPage.values.flatten().filter { line -> zone(line) != 0 && config.headerFooter.any { it.matches(line.text.trim()) } }
        val offsets = patterned.mapNotNull { line -> numberOf(line.text.trim())?.let { it - line.page } }.groupingBy { it }.eachCount()

        fun isolated(line: Line, lines: List<Line>): Boolean {
            val reach = config.paragraphGapRatio * config.defaultLeadingEm * line.size
            return lines.none { it !== line && abs(it.baseline - line.baseline) > 0.25f * line.size && abs(it.baseline - line.baseline) <= reach }
        }

        return byPage.mapValues { (_, lines) ->
            buildMap {
                for (line in lines) {
                    val text = line.text.trim()
                    val zone = zone(line)
                    val role = when {
                        config.boilerplateLine.any { it.containsMatchIn(text) } -> ParagraphRole.BOILERPLATE
                        zone == 0 -> null
                        config.pageNumber.any { it.matches(text) } -> ParagraphRole.PAGE_NUMBER
                        repeated(line) -> if (zone == TOP) ParagraphRole.HEADER else ParagraphRole.FOOTER
                        line in patterned && isolated(line, lines) && runningHeadShape(line, text, tiers, offsets, config) ->
                            if (zone == TOP) ParagraphRole.HEADER else ParagraphRole.FOOTER
                        tiers.of(line.size) < 0 && isolated(line, lines) && !config.footnoteStart.containsMatchIn(text) &&
                            line.size < bodySize -> ParagraphRole.UNKNOWN
                        else -> null
                    }
                    if (role != null) put(line, role)
                }
            }
        }
    }

    /**
     * A margin line shaped like "12 Chapter Title" is a running head unless
     * it could be a one-line footnote ("1 See below.", small, numbered) whose
     * number does not move with the page.
     */
    private fun runningHeadShape(line: Line, text: String, tiers: Tiers, offsets: Map<Int, Int>, config: PipelineConfig): Boolean {
        val number = EDGE_NUMBER.find(text)?.value?.toIntOrNull()
        val follows = number != null && (offsets[number - line.page] ?: 0) >= config.repetitionMinPages
        val footnoteLike = tiers.of(line.size) < 0 && config.footnoteStart.containsMatchIn(text)
        return follows || !footnoteLike
    }

    /** "This page intentionally left blank", or only printer's marks. */
    private fun isBoilerplatePage(content: List<Line>, config: PipelineConfig): Boolean {
        if (content.isEmpty()) return false
        val text = content.sortedWith(compareBy({ it.baseline }, { it.box.x0 })).joinToString(" ") { it.text.trim() }.replace(SPACES, " ").trim()
        return config.boilerplatePage.any { it.matches(text) }
    }

    /** Where figures are drawn; page-sized images are backgrounds and do not count. */
    private fun figureBoxes(page: PdfPageGlyphs, config: PipelineConfig): List<Box> =
        page.imageBoxes.filter { it.width > 0f && it.height > 0f && it.width * it.height <= page.width * page.height * config.figureMaxPageShare }

    // ------------------------------------------------------------------ tables

    /**
     * Lines that form a table: at least three rows (two if there are three or
     * more columns) of short pieces side by side, whose left edges line up.
     * Cells are narrow on average, which tells a table from two columns of
     * body text; so does flow: in columns of prose a piece often runs on into
     * the one below it (no closing punctuation, then a lower-case start).
     */
    private fun tableLines(lines: List<Line>, config: PipelineConfig): List<Line> {
        val textWidth = (lines.maxOfOrNull { it.box.x1 } ?: 0f) - (lines.minOfOrNull { it.box.x0 } ?: 0f)
        if (textWidth <= 0f) return emptyList()
        val rows = lines.groupBy { (it.baseline / 2f).roundToInt() }.toSortedMap().values
            .map { row -> row.sortedBy { it.box.x0 } }
        val out = mutableListOf<Line>()
        var block = mutableListOf<List<Line>>()
        fun isRow(row: List<Line>) = row.size >= 2 && row.all { it.box.width < textWidth * config.tableCellMaxRatio }
        fun aligned(a: List<Line>, b: List<Line>) = a.count { x -> b.any { abs(it.box.x0 - x.box.x0) <= 4f } } >= 2
        fun flow(block: List<List<Line>>): Float {
            var pairs = 0
            var flowing = 0
            for ((upper, lower) in block.zipWithNext()) {
                for (cell in upper) {
                    val below = lower.firstOrNull { abs(it.box.x0 - cell.box.x0) <= 4f } ?: continue
                    pairs++
                    val next = below.text.trimStart().firstOrNull()
                    if (!config.endsTerminally(cell.text) && next != null && next.isLowerCase()) flowing++
                }
            }
            return if (pairs == 0) 0f else flowing.toFloat() / pairs
        }
        fun close() {
            val columns = block.maxOfOrNull { it.size } ?: 0
            val cells = block.flatten()
            val narrowCells = cells.isNotEmpty() && cells.sumOf { it.box.width.toDouble() } / cells.size < textWidth * config.tableCellMeanRatio
            val enoughRows = block.size >= 3 || (block.size >= 2 && columns >= 3)
            if (narrowCells && enoughRows && flow(block) < config.tableMaxFlow) block.forEach { out += it }
            block = mutableListOf()
        }
        for (row in rows) {
            val previous = block.lastOrNull()
            when {
                !isRow(row) -> close()
                previous == null -> block += row
                aligned(previous, row) && row.first().box.y0 - previous.first().box.y1 < 2.5f * row.first().size -> block += row
                else -> {
                    close()
                    block += row
                }
            }
        }
        close()
        return out
    }

    // ------------------------------------------------------------------ columns and reading order

    /**
     * Reading order, region by region. Lines spanning most of the text width
     * are read where they stand and split the page into regions; a region of
     * narrower lines with a gutter (a vertical strip no line crosses, with
     * text on both sides) is laid out in columns, read one column at a time,
     * top to bottom. Columns are numbered 1, 2... and never merged into one
     * line. Returns the lines and the most columns any region has.
     */
    private fun readingOrder(lines: List<Line>, config: PipelineConfig): Pair<List<Line>, Int> {
        if (lines.isEmpty()) return lines to 1
        val left = lines.minOf { it.box.x0 }
        val width = lines.maxOf { it.box.x1 } - left
        val sorted = lines.sortedWith(compareBy({ it.baseline }, { it.box.x0 }))
        val out = mutableListOf<Line>()
        var most = 1
        var region = mutableListOf<Line>()
        fun flushRegion() {
            val gutters = gutters(region, config)
            if (gutters.isEmpty()) {
                out += region
            } else {
                most = maxOf(most, gutters.size + 1)
                region.groupBy { line -> gutters.count { (_, g1) -> line.box.x0 >= g1 - 1f } }.toSortedMap()
                    .forEach { (index, column) -> out += column.sortedBy { it.baseline }.map { it.copy(column = index + 1) } }
            }
            region = mutableListOf()
        }
        for (line in sorted) {
            if (line.box.width >= width * config.narrowLineRatio) {
                flushRegion()
                out += line
            } else {
                region += line
            }
        }
        flushRegion()
        return out to most
    }

    private fun gutters(lines: List<Line>, config: PipelineConfig): List<Pair<Float, Float>> {
        if (lines.size < config.minColumnLines) return emptyList()
        val left = lines.minOf { it.box.x0 }
        val right = lines.maxOf { it.box.x1 }
        val bins = ((right - left).toInt() + 1).coerceAtLeast(1)
        val covered = BooleanArray(bins)
        lines.forEach { line ->
            for (x in (line.box.x0 - left).toInt().coerceIn(0, bins - 1)..(line.box.x1 - left).toInt().coerceIn(0, bins - 1)) covered[x] = true
        }
        val out = mutableListOf<Pair<Float, Float>>()
        var x = 0
        while (x < bins) {
            if (!covered[x]) {
                val start = x
                while (x < bins && !covered[x]) x++
                val g0 = left + start
                val g1 = left + x
                val leftSide = lines.count { it.box.x1 <= g0 + 1f }
                val rightSide = lines.count { it.box.x0 >= g1 - 1f }
                if (g1 - g0 >= config.minGutter && leftSide >= 2 && rightSide >= 2 && leftSide * 5 >= lines.size && rightSide * 5 >= lines.size) {
                    out += g0 to g1
                }
            }
            x++
        }
        return out
    }

    // ------------------------------------------------------------------ paragraphs

    private fun paragraphsOf(lines: List<Line>, page: PdfPageGlyphs, context: Context, forced: ParagraphRole? = null): List<ParsedParagraph> {
        val config = context.config
        val bodySize = context.bodySize
        val out = mutableListOf<ParsedParagraph>()
        var current = mutableListOf<Line>()
        var role = ParagraphRole.PARAGRAPH
        var level = 0
        var marker: String? = null
        val figures = figureBoxes(page, config)
        val lineByLine = lineStructured(lines, config)

        fun flush() {
            if (current.isNotEmpty()) {
                val finalRole = if (role == ParagraphRole.PARAGRAPH && isCaption(current, figures, context)) ParagraphRole.CAPTION else role
                out += paragraph(current, forced ?: finalRole, level, context, marker)
            }
            current = mutableListOf()
            marker = null
        }

        for (line in lines) {
            val text = line.text.trim()
            val prev = current.lastOrNull()
            val heading = forced == null && isHeading(line, text, context)
            val footnote = line.size <= bodySize * config.footnoteSizeRatio && line.box.y0 > page.height * config.footnoteZone
            val startsFootnote = footnote && config.footnoteStart.containsMatchIn(text)
            val bullet = config.bullet.find(text)
            val gap = prev != null && line.baseline - prev.baseline > config.paragraphGapRatio * context.leading.of(prev.size)
            val numbered = config.numbered.containsMatchIn(text) &&
                (prev == null || role == ParagraphRole.LIST_ITEM || config.endsTerminally(prev.text) || gap)
            val columnRight = if (prev != null) columnRight(lines, prev) else 0f
            val fullLine = prev != null && prev.box.x1 >= columnRight - config.shortLineEm * prev.size

            val startsNew = when {
                prev == null -> true
                line.column != prev.column -> true
                heading != (role == ParagraphRole.HEADING) -> true
                heading -> abs(line.size - prev.size) > 0.5f || gap
                startsFootnote -> true
                bullet != null || numbered -> true
                footnote != (role == ParagraphRole.FOOTNOTE) && role != ParagraphRole.LIST_ITEM -> true
                context.tiers.of(line.size) != context.tiers.of(prev.size) -> true
                gap -> true
                line.box.y0 < prev.box.y0 -> true // a jump back up
                role == ParagraphRole.LIST_ITEM -> {
                    // An item runs on in lines indented past its bullet, or after a full line.
                    val first = current.first()
                    val hanging = line.box.x0 >= first.box.x0 + config.listContinuationEm * line.size
                    !(hanging || (fullLine && !config.endsTerminally(prev.text)))
                }
                line.box.x0 > prev.box.x0 + config.indentEm * line.size -> true // first-line indent
                prev in lineByLine && line in lineByLine && !startsLowercase(line) -> true // one entry per line
                !fullLine && config.endsTerminally(prev.text) -> true // the last, shorter line of a paragraph
                else -> false
            }
            if (startsNew) {
                flush()
                role = when {
                    heading -> ParagraphRole.HEADING
                    startsFootnote || (footnote && role == ParagraphRole.FOOTNOTE) -> ParagraphRole.FOOTNOTE
                    bullet != null || numbered -> ParagraphRole.LIST_ITEM
                    else -> ParagraphRole.PARAGRAPH
                }
                level = when (role) {
                    ParagraphRole.HEADING -> headingLevel(line.size, bodySize, config)
                    ParagraphRole.LIST_ITEM -> {
                        val left = lines.filter { it.column == line.column }.minOf { it.box.x0 }
                        ((line.box.x0 - left) / (config.listLevelEm * line.size)).toInt()
                    }
                    else -> 0
                }
            }
            if (bullet != null && current.isEmpty()) {
                marker = bullet.value.trim()
                current += withoutBullet(line, config)
            } else {
                current += line
            }
        }
        flush()
        return out
    }

    /**
     * A caption: a paragraph starting like "Figure 3." (short, or set apart
     * in its own size), or a short one right above or below a figure.
     */
    private fun isCaption(lines: List<Line>, figures: List<Box>, context: Context): Boolean {
        val config = context.config
        val short = lines.size <= config.captionMaxLines
        val first = lines.first().text.trim()
        if (config.caption.any { it.containsMatchIn(first) } && (short || context.tiers.of(lines.first().size) != 0)) return true
        if (!short || figures.isEmpty()) return false
        val box = lines.map { it.box }.reduce(Box::union)
        val reach = config.captionGapEm * lines.first().size
        return figures.any { f ->
            val overlaps = box.x0 < f.x1 && box.x1 > f.x0
            val below = box.y0 - f.y1 in -1f..reach
            val above = f.y0 - box.y1 in -1f..reach
            overlaps && (below || above)
        }
    }

    private fun columnRight(lines: List<Line>, line: Line): Float =
        lines.filter { it.column == line.column && it.box.x0 < line.box.x1 && it.box.x1 > line.box.x0 }.maxOf { it.box.x1 }

    private fun startsLowercase(line: Line) = line.text.trimStart().firstOrNull()?.isLowerCase() == true

    /**
     * Lines of columns that are lists of entries rather than prose: in each
     * run of lines of one column, the lines rarely run on into a lower-case
     * next line.
     */
    private fun lineStructured(lines: List<Line>, config: PipelineConfig): Set<Line> {
        val out = HashSet<Line>()
        var run = mutableListOf<Line>()
        fun close() {
            if (run.size >= 2 && run.first().column > 0) {
                val pairs = run.zipWithNext()
                val flowing = pairs.count { (a, b) -> !config.endsTerminally(a.text) && startsLowercase(b) }
                if (flowing.toFloat() / pairs.size < config.columnMinFlow) out += run
            }
            run = mutableListOf()
        }
        for (line in lines) {
            if (run.isNotEmpty() && run.last().column != line.column) close()
            run += line
        }
        close()
        return out
    }

    private fun isHeading(line: Line, text: String, context: Context): Boolean {
        if (text.isEmpty() || text.length > 120) return false
        if (line.size >= context.bodySize * context.config.headingSizeRatio) return true
        val words = text.split(SPACES).size
        return line.bold && words <= context.config.boldHeadingMaxWords && !context.config.endsTerminally(text) && text.any { it.isLetter() }
    }

    private fun headingLevel(size: Float, bodySize: Float, config: PipelineConfig): Int = when {
        size >= bodySize * 1.8f -> 1
        size >= bodySize * 1.4f -> 2
        size >= bodySize * config.headingSizeRatio -> 3
        else -> 4
    }

    private fun withoutBullet(line: Line, config: PipelineConfig): Line {
        val first = line.runs.first()
        val stripped = config.bullet.replace(first.text.trimStart(), "")
        return line.copy(runs = listOf(first.copy(text = stripped)) + line.runs.drop(1))
    }

    /**
     * Joins lines into one paragraph: placeholder text (bold / italic spans
     * that differ from the paragraph's own style as {n}…{/n}, footnote marks
     * as [[SUP_n]]), words hyphenated at line ends rejoined or kept by
     * [Hyphenation], and the box of each line kept against its stretch of
     * text.
     */
    private fun paragraph(lines: List<Line>, role: ParagraphRole, level: Int, context: Context, marker: String? = null): ParsedParagraph {
        // Pieces with the separator that joins each line to the next.
        data class Piece(val run: Run, val line: Int, var trailing: String = "")
        val pieces = mutableListOf<Piece>()
        lines.forEachIndexed { i, line ->
            val runs = line.runs.mapIndexed { r, run ->
                var text = run.text
                if (r == 0) text = text.trimStart()
                if (r == line.runs.lastIndex) text = text.trimEnd()
                run.copy(text = text)
            }.filter { it.text.isNotEmpty() }
            if (runs.isEmpty()) return@forEachIndexed
            val previous = pieces.lastOrNull()
            if (previous != null) {
                val prevText = previous.run.text
                val word = HYPHENATED.find(prevText)
                val next = LEADING_WORD.find(runs.first().text)?.value
                when {
                    prevText.endsWith('­') -> pieces[pieces.lastIndex] = previous.copy(run = previous.run.copy(text = prevText.dropLast(1)))
                    word != null && next != null && context.hyphenation.joins(word.groupValues[1], next) ->
                        pieces[pieces.lastIndex] = previous.copy(run = previous.run.copy(text = prevText.dropLast(1)))
                    word != null -> Unit // "Anglo-" + "Saxon", "well-" + "known": the hyphen is real
                    else -> pieces.last().trailing = " "
                }
            }
            runs.forEach { pieces += Piece(it, i) }
        }

        val visible = pieces.filter { !it.run.superscript }
        val dominantBold = visible.sumOf { if (it.run.bold) it.run.text.length else 0 } * 2 > visible.sumOf { it.run.text.length }
        val dominantItalic = visible.sumOf { if (it.run.italic) it.run.text.length else 0 } * 2 > visible.sumOf { it.run.text.length }
        fun styleOf(run: Run): String? {
            val b = run.bold && !dominantBold
            val i = run.italic && !dominantItalic
            return when {
                b && i -> "bi"
                b -> "b"
                i -> "i"
                else -> null
            }
        }

        val builder = PlaceholderTextBuilder()
        val ranges = mutableMapOf<Int, IntRange>()
        fun record(line: Int, start: Int, end: Int) {
            if (end <= start) return
            val existing = ranges[line]
            ranges[line] = if (existing == null) start until end else minOf(existing.first, start) until maxOf(existing.last + 1, end)
        }
        // Spaces at the edge of a styled span go outside its tags: "{1}very{/1} important".
        var open: String? = null
        fun styleAfter(index: Int): String? = pieces.getOrNull(index + 1)?.takeIf { !it.run.superscript }?.let { styleOf(it.run) }
        pieces.forEachIndexed { index, piece ->
            val start = builder.length
            if (piece.run.superscript) {
                builder.standalone("SUP", piece.run.text.trim())
                record(piece.line, start, builder.length)
                if (piece.trailing.isNotEmpty()) {
                    if (open != null && styleAfter(index) != open) {
                        builder.close()
                        open = null
                    }
                    builder.text(piece.trailing)
                }
            } else {
                val style = styleOf(piece.run)
                val text = piece.run.text
                val core = text.trim()
                val leading = text.substring(0, text.length - text.trimStart().length)
                val trailing = text.substring(text.trimEnd().length) + piece.trailing
                if (style != open) {
                    if (open != null) builder.close()
                    builder.text(leading)
                    if (style != null) builder.open(style, MARKUP.getValue(style))
                    open = style
                } else {
                    builder.text(leading)
                }
                val coreStart = builder.length
                builder.text(core)
                record(piece.line, coreStart, builder.length)
                if (trailing.isNotEmpty()) {
                    if (open != null && styleAfter(index) != open) {
                        builder.close()
                        open = null
                    }
                    builder.text(trailing)
                }
            }
        }
        if (open != null) builder.close()
        val (text, tags) = builder.result()

        val box = lines.map { it.box }.reduce(Box::union)
        val size = lines.groupingBy { it.size }.eachCount().maxBy { it.value }.key
        val style = TextStyle(
            font = lines.groupingBy { it.font }.eachCount().maxBy { it.value }.key,
            size = size,
            bold = dominantBold,
            italic = dominantItalic,
        )
        return ParsedParagraph(
            role = role,
            text = text,
            tags = tags,
            ref = SourceRef.Pdf(lines.first().page, box),
            level = level,
            style = style,
            lineBoxes = ranges.toSortedMap().map { (line, range) -> LineBox(range, lines[line].page, lines[line].box) },
            tier = context.tiers.of(size),
            column = lines.first().column,
            marker = marker,
        )
    }

    private fun bodySize(glyphs: List<PdfGlyph>): Float =
        glyphs.filter { it.text.isNotBlank() }
            .groupingBy { (it.size * 2).roundToInt() }.eachCount()
            .maxByOrNull { it.value }?.key?.div(2f) ?: 10f

    // ------------------------------------------------------------------ measures

    /**
     * Font-size tiers: 0 is the body size, 1 the largest size used above it,
     * 2 the next, and so on; -1 anything smaller than the body. Sizes within
     * the tolerance of each other share a tier.
     */
    internal class Tiers(private val body: Float, private val above: List<Float>, private val tolerance: Float) {
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
    internal class Leading(private val bySize: Map<Int, Float>, private val defaultEm: Float) {
        fun of(size: Float): Float = bySize[(size * 2).roundToInt()] ?: (defaultEm * size)

        companion object {
            fun measure(pages: List<List<Line>>, config: PipelineConfig): Leading {
                val deltas = mutableMapOf<Int, MutableList<Int>>()
                for (lines in pages) {
                    for ((a, b) in lines.zipWithNext()) {
                        if (a.column != b.column || abs(a.size - b.size) > 0.25f) continue
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

    private const val MIN_TEXT_CHARS = 10
    private const val TOP = 1
    private const val BOTTOM = 2

    private val MARKUP = mapOf("b" to "<b></b>", "i" to "<i></i>", "bi" to "<b><i></i></b>")
    private val DIGITS = Regex("\\d+")
    private val SPACES = Regex("\\s+")
    private val EDGE_NUMBER = Regex("^\\d+|\\d+$")
    private val HYPHENATED = Regex("([\\p{L}\\p{M}]+)[-‐]$")
    private val LEADING_WORD = Regex("^[\\p{L}\\p{M}]+")
}

private val Box.centerX: Float get() = (x0 + x1) / 2
private val Box.centerY: Float get() = (y0 + y1) / 2
private fun Box.contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
