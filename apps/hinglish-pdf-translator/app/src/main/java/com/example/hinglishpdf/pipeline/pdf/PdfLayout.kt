package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTextBuilder
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TextStyle
import kotlin.math.abs
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

/** Everything read from one page: its glyphs and how many images it draws. */
data class PdfPageGlyphs(val page: Int, val width: Float, val height: Float, val glyphs: List<PdfGlyph>, val images: Int = 0)

/** What a page holds: real text, only a picture of text (needs OCR), or nothing. */
enum class PageKind { TEXT, SCANNED, BLANK }

/** The layout analysis of a whole PDF. */
data class PdfLayoutResult(
    val paragraphs: List<ParsedParagraph>,
    val pageKinds: Map<Int, PageKind>,
    /** Running headers, footers and page numbers that were left out. */
    val droppedLines: Int,
    val tables: Int,
    /** Pages laid out in more than one column, with their column count. */
    val columns: Map<Int, Int>,
) {
    val scannedPages: List<Int> get() = pageKinds.filterValues { it == PageKind.SCANNED }.keys.sorted()

    /** Mostly pictures of text: route the book to OCR. */
    val needsOcr: Boolean
        get() {
            val nonBlank = pageKinds.values.count { it != PageKind.BLANK }
            return nonBlank > 0 && scannedPages.size * 2 >= nonBlank
        }
}

/**
 * Rebuilds reading order and structure from positioned glyphs:
 * text-layer check per page, lines (with superscript footnote marks
 * attached), running headers / footers removed, tables, columns, then
 * paragraphs (headings, list items, footnotes, body text) with words
 * hyphenated across lines rejoined. Bold and italic spans inside a paragraph
 * become placeholders; footnote marks become [[SUP_n]].
 *
 * Pure Kotlin: the PDF reader only supplies [PdfPageGlyphs].
 */
object PdfLayout {

    fun analyze(pages: List<PdfPageGlyphs>): PdfLayoutResult {
        val kinds = pages.associate { it.page to kindOf(it) }
        val textPages = pages.filter { kinds[it.page] == PageKind.TEXT }
        val bodySize = bodySize(textPages.flatMap { it.glyphs })

        val linesByPage = textPages.associate { page -> page.page to lines(page) }
        val (kept, dropped) = dropRunningLines(linesByPage, textPages)

        val paragraphs = mutableListOf<ParsedParagraph>()
        var tables = 0
        val columns = mutableMapOf<Int, Int>()
        for (page in textPages) {
            val lines = kept[page.page].orEmpty()
            val tableLines = tableLines(lines, page)
            if (tableLines.isNotEmpty()) tables++
            val flow = lines - tableLines.toSet()
            val (ordered, columnCount) = readingOrder(flow, page)
            if (columnCount > 1) columns[page.page] = columnCount
            paragraphs += paragraphsOf(ordered, page, bodySize)
            // Cells after the page's text: they are read as their own units.
            tableLines.sortedWith(compareBy({ it.baseline }, { it.box.x0 })).forEach { line ->
                paragraphs += paragraph(listOf(line), ParagraphRole.TABLE_CELL, 0, page.page)
            }
        }
        return PdfLayoutResult(paragraphs, kinds, dropped, tables, columns)
    }

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

    /** One line of text, or a piece of one when a wide gap (column gutter, table) splits it. */
    internal data class Line(val page: Int, val box: Box, val baseline: Float, val size: Float, val runs: List<Run>, val font: String) {
        val text: String get() = runs.joinToString("") { it.text }
        val bold: Boolean get() = boldShare() > 0.6f
        val italic: Boolean get() = runs.filter { !it.superscript }.let { r -> r.isNotEmpty() && r.sumOf { if (it.italic) it.text.length else 0 } * 10 > r.sumOf { it.text.length } * 6 }

        fun boldShare(): Float {
            val visible = runs.filter { !it.superscript }
            val total = visible.sumOf { it.text.count { c -> !c.isWhitespace() } }
            return if (total == 0) 0f else visible.sumOf { r -> if (r.bold) r.text.count { !it.isWhitespace() } else 0 }.toFloat() / total
        }
    }

    private fun lines(page: PdfPageGlyphs): List<Line> {
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
            fragments(page.page, (group + marks).sortedBy { it.x }, marks)
        }
    }

    /** Splits one baseline group at wide gaps and joins glyphs into words and styled runs. */
    private fun fragments(pageNumber: Int, glyphs: List<PdfGlyph>, marks: Set<PdfGlyph>): List<Line> {
        val out = mutableListOf<Line>()
        var current = mutableListOf<PdfGlyph>()
        fun flush() {
            if (current.isNotEmpty()) out += lineOf(pageNumber, current, marks)
            current = mutableListOf()
        }
        for (g in glyphs) {
            val prev = current.lastOrNull()
            if (prev != null && g.x - (prev.x + prev.width) > COLUMN_GAP * maxOf(prev.size, g.size) && g !in marks) flush()
            current += g
        }
        flush()
        return out
    }

    private fun lineOf(pageNumber: Int, glyphs: List<PdfGlyph>, marks: Set<PdfGlyph>): Line {
        val normal = glyphs.filter { it !in marks }.ifEmpty { glyphs }
        val size = normal.groupingBy { (it.size * 2).roundToInt() }.eachCount().maxBy { it.value }.key / 2f
        val baseline = normal.map { it.baseline }.sorted()[normal.size / 2]
        val runs = mutableListOf<Run>()
        var prev: PdfGlyph? = null
        for (g in glyphs) {
            val sup = g in marks
            val gap = prev?.let { g.x - (it.x + it.width) } ?: 0f
            val space = prev != null && !sup && gap > WORD_GAP * size && !prev.text.endsWith(" ") && !g.text.startsWith(" ")
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

    // ------------------------------------------------------------------ running headers and footers

    private fun dropRunningLines(byPage: Map<Int, List<Line>>, pages: List<PdfPageGlyphs>): Pair<Map<Int, List<Line>>, Int> {
        val heights = pages.associate { it.page to it.height }
        fun inMargin(line: Line): Boolean {
            val h = heights[line.page] ?: return false
            return line.box.y1 < h * MARGIN || line.box.y0 > h * (1 - MARGIN)
        }
        fun key(line: Line) = line.text.lowercase().replace(DIGITS, "#").replace(SPACES, " ").trim()

        val pagesWithKey = mutableMapOf<String, MutableSet<Int>>()
        byPage.values.flatten().filter(::inMargin).forEach { pagesWithKey.getOrPut(key(it)) { mutableSetOf() } += it.page }
        val needed = if (pages.size <= 3) 2 else maxOf(3, (pages.size * 0.3f).roundToInt())
        var dropped = 0
        val kept = byPage.mapValues { (_, lines) ->
            lines.filterNot { line ->
                val running = inMargin(line) && (PAGE_NUMBER.matches(line.text.trim()) || (pagesWithKey[key(line)]?.size ?: 0) >= needed)
                if (running) dropped++
                running
            }
        }
        return kept to dropped
    }

    // ------------------------------------------------------------------ tables

    /**
     * Lines that form a table: at least three rows (two if there are three or
     * more columns) of short pieces side by side, whose left edges line up.
     * Cells are narrow on average (under 30% of the text width), which tells
     * a table from two columns of body text.
     */
    private fun tableLines(lines: List<Line>, page: PdfPageGlyphs): List<Line> {
        val textWidth = (lines.maxOfOrNull { it.box.x1 } ?: 0f) - (lines.minOfOrNull { it.box.x0 } ?: 0f)
        if (textWidth <= 0f) return emptyList()
        val rows = lines.groupBy { (it.baseline / 2f).roundToInt() }.toSortedMap().values
            .map { row -> row.sortedBy { it.box.x0 } }
        val out = mutableListOf<Line>()
        var block = mutableListOf<List<Line>>()
        fun isRow(row: List<Line>) = row.size >= 2 && row.all { it.box.width < textWidth * 0.45f }
        fun aligned(a: List<Line>, b: List<Line>) = a.count { x -> b.any { abs(it.box.x0 - x.box.x0) <= 4f } } >= 2
        fun close() {
            val columns = block.maxOfOrNull { it.size } ?: 0
            val cells = block.flatten()
            val narrowCells = cells.isNotEmpty() && cells.sumOf { it.box.width.toDouble() } / cells.size < textWidth * 0.3f
            if (narrowCells && (block.size >= 3 || (block.size >= 2 && columns >= 3))) block.forEach { out += it }
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
     * Finds column gutters (vertical strips no narrow line crosses, with text
     * on both sides) and orders lines column by column, top to bottom. Lines
     * crossing a gutter (titles over both columns) split the page into bands
     * that are read in turn.
     */
    private fun readingOrder(lines: List<Line>, page: PdfPageGlyphs): Pair<List<Line>, Int> {
        if (lines.isEmpty()) return lines to 1
        val left = lines.minOf { it.box.x0 }
        val right = lines.maxOf { it.box.x1 }
        val width = right - left
        val narrow = lines.filter { it.box.width < width * 0.6f }
        val gutters = gutters(narrow, left, right)
        if (gutters.isEmpty()) return lines.sortedWith(compareBy({ it.baseline }, { it.box.x0 })) to 1

        fun column(line: Line): Int {
            val spans = gutters.any { (g0, g1) -> line.box.x0 < g0 && line.box.x1 > g1 }
            return if (spans) -1 else gutters.count { (_, g1) -> line.box.x0 >= g1 - 1f }
        }
        val ordered = mutableListOf<Line>()
        var band = mutableListOf<Line>()
        fun flushBand() {
            band.groupBy(::column).toSortedMap().values.forEach { col -> ordered += col.sortedBy { it.baseline } }
            band = mutableListOf()
        }
        for (line in lines.sortedWith(compareBy({ it.baseline }, { it.box.x0 }))) {
            if (column(line) == -1) {
                flushBand()
                ordered += line
            } else {
                band += line
            }
        }
        flushBand()
        return ordered to gutters.size + 1
    }

    private fun gutters(narrow: List<Line>, left: Float, right: Float): List<Pair<Float, Float>> {
        if (narrow.size < 6) return emptyList()
        val bins = ((right - left).toInt() + 1).coerceAtLeast(1)
        val covered = BooleanArray(bins)
        narrow.forEach { line ->
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
                val leftSide = narrow.count { it.box.x1 <= g0 + 1f }
                val rightSide = narrow.count { it.box.x0 >= g1 - 1f }
                if (g1 - g0 >= MIN_GUTTER && leftSide * 5 >= narrow.size && rightSide * 5 >= narrow.size) out += g0 to g1
            }
            x++
        }
        return out
    }

    // ------------------------------------------------------------------ paragraphs

    private fun paragraphsOf(lines: List<Line>, page: PdfPageGlyphs, bodySize: Float): List<ParsedParagraph> {
        val out = mutableListOf<ParsedParagraph>()
        var current = mutableListOf<Line>()
        var role = ParagraphRole.PARAGRAPH
        var level = 0

        fun flush() {
            if (current.isNotEmpty()) out += paragraph(current, role, level, page.page)
            current = mutableListOf()
        }

        for (line in lines) {
            val text = line.text.trim()
            val heading = isHeading(line, text, bodySize)
            val footnote = line.size <= bodySize * 0.9f && line.box.y0 > page.height * 0.6f
            val startsFootnote = footnote && FOOTNOTE_START.containsMatchIn(text)
            val bullet = BULLET.find(text)
            val numbered = NUMBERED.containsMatchIn(text)
            val prev = current.lastOrNull()

            val startsNew = when {
                prev == null -> true
                heading != (role == ParagraphRole.HEADING) -> true
                heading -> abs(line.size - prev.size) > 0.5f || line.baseline - prev.baseline > 2.2f * line.size
                startsFootnote -> true
                bullet != null || numbered -> true
                footnote != (role == ParagraphRole.FOOTNOTE) && !(role == ParagraphRole.LIST_ITEM) -> true
                abs(line.size - prev.size) > 0.15f * prev.size -> true
                line.box.y0 - prev.box.y1 > 0.9f * line.size -> true // blank line between them
                line.box.y0 < prev.box.y0 -> true // a jump back up: the next column
                line.box.x0 > prev.box.x0 + 0.8f * line.size && role != ParagraphRole.LIST_ITEM -> true // first-line indent
                prev.box.x1 < columnRight(lines, prev) - 2f * prev.size && SENTENCE_END.containsMatchIn(prev.text.trim()) -> true
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
                level = if (heading) headingLevel(line.size, bodySize) else 0
            }
            current += if (bullet != null && current.isEmpty()) withoutBullet(line) else line
        }
        flush()
        return out
    }

    private fun columnRight(lines: List<Line>, line: Line): Float =
        lines.filter { it.box.x0 < line.box.x1 && it.box.x1 > line.box.x0 }.maxOf { it.box.x1 }

    private fun isHeading(line: Line, text: String, bodySize: Float): Boolean {
        if (text.isEmpty() || text.length > 120) return false
        if (line.size >= bodySize * 1.15f) return true
        val words = text.split(SPACES).size
        return line.bold && words <= 12 && !SENTENCE_END.containsMatchIn(text) && text.any { it.isLetter() }
    }

    private fun headingLevel(size: Float, bodySize: Float): Int = when {
        size >= bodySize * 1.8f -> 1
        size >= bodySize * 1.4f -> 2
        size >= bodySize * 1.15f -> 3
        else -> 4
    }

    private fun withoutBullet(line: Line): Line {
        val first = line.runs.first()
        val stripped = BULLET.replace(first.text.trimStart(), "")
        return line.copy(runs = listOf(first.copy(text = stripped)) + line.runs.drop(1))
    }

    /**
     * Joins lines into one paragraph: placeholder text (bold / italic spans
     * that differ from the paragraph's own style as {n}…{/n}, footnote marks
     * as [[SUP_n]]), words hyphenated at line ends rejoined, and the box of
     * each line kept against its stretch of text.
     */
    internal fun paragraph(lines: List<Line>, role: ParagraphRole, level: Int, pageNumber: Int): ParsedParagraph {
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
                val nextChar = runs.first().text.first()
                when {
                    prevText.endsWith('­') -> pieces[pieces.lastIndex] = previous.copy(run = previous.run.copy(text = prevText.dropLast(1)))
                    HYPHENATED.containsMatchIn(prevText) && nextChar.isLowerCase() ->
                        pieces[pieces.lastIndex] = previous.copy(run = previous.run.copy(text = prevText.dropLast(1)))
                    HYPHENATED.containsMatchIn(prevText) -> Unit // "Anglo-" + "Saxon": the hyphen is real
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
        val style = TextStyle(
            font = lines.groupingBy { it.font }.eachCount().maxBy { it.value }.key,
            size = lines.groupingBy { it.size }.eachCount().maxBy { it.value }.key,
            bold = dominantBold,
            italic = dominantItalic,
        )
        return ParsedParagraph(
            role = role,
            text = text,
            tags = tags,
            ref = SourceRef.Pdf(pageNumber, box),
            level = level,
            style = style,
            lineBoxes = ranges.toSortedMap().map { (line, range) -> range to lines[line].box },
        )
    }

    private fun bodySize(glyphs: List<PdfGlyph>): Float =
        glyphs.filter { it.text.isNotBlank() }
            .groupingBy { (it.size * 2).roundToInt() }.eachCount()
            .maxByOrNull { it.value }?.key?.div(2f) ?: 10f

    private const val MIN_TEXT_CHARS = 10
    private const val MARGIN = 0.08f
    /** A gap wider than this many font sizes splits a line (column gutter, table cell). */
    private const val COLUMN_GAP = 1.0f
    /** A gap wider than this many font sizes is a space between words. */
    private const val WORD_GAP = 0.15f
    private const val MIN_GUTTER = 8f

    private val MARKUP = mapOf("b" to "<b></b>", "i" to "<i></i>", "bi" to "<b><i></i></b>")
    private val DIGITS = Regex("\\d+")
    private val SPACES = Regex("\\s+")
    private val PAGE_NUMBER = Regex("^(?:page\\s+)?(?:\\d{1,4}|[ivxlcdm]{1,7})(?:\\s*(?:/|of)\\s*\\d{1,4})?$", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("^[•●○◦▪▫■□‣⁃∙·➢➤►▶✓✔→\\uE000-\\uF8FF]\\s*")
    private val NUMBERED = Regex("^\\(?(?:\\d{1,3}|[a-z]|[ivx]{1,5})[.)]\\s+\\S")
    private val FOOTNOTE_START = Regex("^(?:\\d{1,3}|[*†‡§¹²³⁴⁵⁶⁷⁸⁹⁰]+)[.)]?\\s*\\S")
    private val SENTENCE_END = Regex("[.!?:;)\"”’।]$")
    private val HYPHENATED = Regex("\\p{L}[-‐]$")
}
