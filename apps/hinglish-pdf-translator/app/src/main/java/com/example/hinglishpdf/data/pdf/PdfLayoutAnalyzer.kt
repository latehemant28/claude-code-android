package com.example.hinglishpdf.data.pdf

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One visual line of text on a PDF page, with the layout facts needed to
 * recover structure. Coordinates are in PDF points, y grows downwards and is
 * the baseline of the line.
 */
data class PdfLine(
    val text: String,
    val page: Int,
    val x: Float,
    val y: Float,
    val fontSize: Float,
    val bold: Boolean,
    val pageHeight: Float,
    /** Where the line's last glyph ends (0 = unknown). */
    val right: Float = 0f,
)

/**
 * Rebuilds document structure from positioned lines: headings (by font size
 * and weight), bulleted and numbered list items (with nesting from their
 * indentation), and paragraphs (by vertical spacing, first-line indents and
 * short last lines). Running headers, footers and page numbers are dropped,
 * but never a heading-sized line (a chapter title at the top of its first
 * page) and never the only text of a page.
 *
 * PDFs have no real structure, so this is a heuristic; it is pure Kotlin so
 * it can be unit-tested on the JVM.
 */
object PdfLayoutAnalyzer {

    // Common bullet glyphs, plus Private Use Area chars that Symbol/Wingdings
    // bullets usually decode to (e.g. U+F0B7).
    private val BULLET = Regex("^([•●○◦▪▫■□‣⁃∙·\\-–—*➢➤►▶✓✔→\\uE000-\\uF8FF])\\s+(.+)$")
    private val NUMBERED = Regex(
        "^(\\(?(?:\\d{1,3}(?:\\.\\d{1,3})*|[a-zA-Z]|[ivxlcdmIVXLCDM]{1,6})[.)])\\s+(.+)$",
    )
    private val PAGE_NUMBER = Regex("^(?:page\\s+)?\\d{1,4}(?:\\s*(?:/|of)\\s*\\d{1,4})?$", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("[.!?:;)\"”’]$")

    private enum class LineType { HEADING, BULLET, NUMBERED, BODY }

    private data class Classified(val line: PdfLine, val type: LineType, val text: String, val marker: String)

    /** Whole-document mode: paragraphs may run across page breaks. */
    fun analyze(rawLines: List<PdfLine>): List<DocBlock> = analyzeWithPages(rawLines, splitPages = false).map { it.second }

    /**
     * Page mode: returns the blocks of each page (index 0 = page 1), never
     * merging text across a page break, so page N of the output matches page
     * N of the source. Font statistics are still taken from the whole book,
     * so heading levels and list depths are consistent between pages.
     */
    fun analyzeByPage(rawLines: List<PdfLine>, pageCount: Int): List<List<DocBlock>> {
        val pages = List(pageCount) { mutableListOf<DocBlock>() }
        for ((page, block) in analyzeWithPages(rawLines, splitPages = true)) {
            pages.getOrNull(page - 1)?.add(block)
        }
        return pages
    }

    private fun analyzeWithPages(rawLines: List<PdfLine>, splitPages: Boolean): List<Pair<Int, DocBlock>> {
        val nonBlank = rawLines.filter { it.text.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()
        val lines = dropRunningHeadersAndFooters(nonBlank, dominantFontSize(nonBlank))
        if (lines.isEmpty()) return emptyList()

        val bodySize = dominantFontSize(lines)
        val headingLevels = headingLevels(lines, bodySize)
        val classified = lines.map { classify(it, bodySize) }
        val listDepths = listDepths(classified)

        val blocks = mutableListOf<Pair<Int, DocBlock>>()
        var current: Classified? = null // first line of the block being built
        var previous: Classified? = null
        var blockRight = 0f // the right edge of the block's lines so far
        val text = StringBuilder()

        fun flush() {
            val start = current ?: return
            val content = text.toString().trim()
            if (content.isNotEmpty()) {
                blocks += start.line.page to when (start.type) {
                    LineType.HEADING -> DocBlock(
                        BlockKind.HEADING, content,
                        level = headingLevels[sizeKey(start.line.fontSize)] ?: (headingLevels.size + 1).coerceAtMost(6),
                    )
                    LineType.BULLET -> DocBlock(BlockKind.BULLET, content, level = listDepths[start.line.x.toBucket()] ?: 0)
                    LineType.NUMBERED -> DocBlock(
                        BlockKind.NUMBERED, content,
                        level = listDepths[start.line.x.toBucket()] ?: 0,
                        marker = start.marker,
                    )
                    LineType.BODY -> DocBlock(BlockKind.PARAGRAPH, content)
                }
            }
            text.clear()
            current = null
            blockRight = 0f
        }

        for (line in classified) {
            val prev = previous
            val pageBreak = splitPages && prev != null && prev.line.page != line.line.page
            val continues = !pageBreak && current != null && prev != null &&
                continuesBlock(current!!, prev, line, bodySize, blockRight)
            if (!continues) {
                flush()
                current = line
                text.append(line.text)
            } else {
                appendLine(text, line.text)
            }
            blockRight = max(blockRight, line.line.right)
            previous = line
        }
        flush()
        return blocks
    }

    private fun classify(line: PdfLine, bodySize: Float): Classified {
        val text = line.text.trim()
        BULLET.find(text)?.let { return Classified(line, LineType.BULLET, it.groupValues[2], "") }
        NUMBERED.find(text)?.let { m ->
            // "1.2 Overview" style section numbers in a big/bold font are headings.
            if (!isHeadingStyle(line, text, bodySize)) {
                return Classified(line, LineType.NUMBERED, m.groupValues[2], m.groupValues[1])
            }
        }
        val type = if (isHeadingStyle(line, text, bodySize)) LineType.HEADING else LineType.BODY
        return Classified(line, type, text, "")
    }

    private fun isHeadingStyle(line: PdfLine, text: String, bodySize: Float): Boolean {
        val words = text.split(' ').size
        if (words > 20) return false
        if (line.fontSize >= bodySize * 1.15f) return true
        return line.bold && words <= 14 && !text.endsWith('.')
    }

    /** Whether [line] belongs to the block that started with [start] and last saw [prev]. */
    private fun continuesBlock(
        start: Classified,
        prev: Classified,
        line: Classified,
        bodySize: Float,
        blockRight: Float,
    ): Boolean {
        if (line.type == LineType.BULLET || line.type == LineType.NUMBERED) return false

        val lineHeight = max(prev.line.fontSize, line.line.fontSize).coerceAtLeast(1f)
        val samePage = line.line.page == prev.line.page
        val gap = line.line.y - prev.line.y
        val tightGap = samePage && gap > 0 && gap <= lineHeight * 1.65f

        return when (start.type) {
            LineType.HEADING ->
                line.type == LineType.HEADING && tightGap &&
                    abs(line.line.fontSize - start.line.fontSize) < 0.6f
            LineType.BULLET, LineType.NUMBERED ->
                // Wrapped text of a list item: body text indented past the marker.
                line.type == LineType.BODY && tightGap && line.line.x > start.line.x + 1f
            LineType.BODY -> {
                if (line.type != LineType.BODY) return false
                if (!samePage) {
                    // A paragraph running over a page break.
                    return !SENTENCE_END.containsMatchIn(prev.text)
                }
                if (!tightGap) return false
                if (!SENTENCE_END.containsMatchIn(prev.text)) return true
                // A first-line indent after a finished sentence starts a new paragraph...
                val indented = line.line.x > prev.line.x + bodySize * 1.5f
                // ...and so does a finished sentence on a line that stops well short
                // of the text's right edge: the last line of a paragraph (books
                // without indents or extra spacing, dialogue, one-line paragraphs).
                val edge = max(blockRight, line.line.right)
                val shortLine = prev.line.right > 0f && edge - prev.line.right > bodySize * SHORT_LINE_EMS
                !(indented || shortLine)
            }
        }
    }

    private fun appendLine(text: StringBuilder, next: String) {
        val n = next.trim()
        if (text.endsWith("-") && n.firstOrNull()?.isLowerCase() == true) {
            text.setLength(text.length - 1) // "transla-" + "tion"
        } else {
            text.append(' ')
        }
        text.append(n)
    }

    /** The font size most of the text is set in, weighted by characters. */
    private fun dominantFontSize(lines: List<PdfLine>): Float =
        lines.groupBy { sizeKey(it.fontSize) }
            .maxBy { (_, group) -> group.sumOf { it.text.length } }
            .key / 2f

    /** Distinct heading sizes, largest first, mapped to levels 1, 2, 3... */
    private fun headingLevels(lines: List<PdfLine>, bodySize: Float): Map<Int, Int> {
        val sizes = lines.filter { it.fontSize >= bodySize * 1.15f }
            .map { sizeKey(it.fontSize) }
            .distinct()
            .sortedDescending()
            .take(5)
        val levels = sizes.withIndex().associate { (i, size) -> size to i + 1 }.toMutableMap()
        // Bold text at body size is the lowest heading level.
        levels.putIfAbsent(sizeKey(bodySize), (sizes.size + 1).coerceAtMost(6))
        return levels
    }

    /** Nesting depth of list items from the x position of their markers. */
    private fun listDepths(lines: List<Classified>): Map<Int, Int> =
        lines.filter { it.type == LineType.BULLET || it.type == LineType.NUMBERED }
            .map { it.line.x.toBucket() }
            .distinct()
            .sorted()
            .let { buckets ->
                // Merge buckets closer than ~8pt into the same depth.
                val depths = mutableMapOf<Int, Int>()
                var depth = -1
                var last: Int? = null
                for (b in buckets) {
                    if (last == null || b - last > 2) depth++
                    depths[b] = depth
                    last = b
                }
                depths
            }

    /**
     * Removes page numbers and text repeated in the top/bottom margin of many
     * pages (running headers and footers). Kept anyway:
     *  - a line set larger than the body text: a chapter title at the top of
     *    its opening page often repeats the running header's wording;
     *  - the text of a page that would otherwise be left with nothing but
     *    its page number (a chapter title page, a short section).
     */
    private fun dropRunningHeadersAndFooters(lines: List<PdfLine>, bodySize: Float): List<PdfLine> {
        fun inMargin(l: PdfLine) = l.pageHeight > 0 && (l.y < l.pageHeight * 0.08f || l.y > l.pageHeight * 0.92f)
        fun pattern(l: PdfLine) = l.text.trim().replace(DIGITS, "#")
        fun headingSized(l: PdfLine) = l.fontSize >= bodySize * 1.15f
        fun pageNumber(l: PdfLine) = inMargin(l) && PAGE_NUMBER.matches(l.text.trim()) && l.fontSize < bodySize * 1.3f

        val pages = lines.map { it.page }.distinct().size
        val repeated = if (pages >= 3) {
            lines.filter { inMargin(it) && !headingSized(it) }
                .groupBy(::pattern)
                .filterValues { group -> group.map { it.page }.distinct().size >= max(3, pages / 2) }
                .keys
        } else {
            emptySet()
        }
        fun runningHeader(l: PdfLine) = inMargin(l) && !headingSized(l) && pattern(l) in repeated

        return lines.groupBy { it.page }.values.flatMap { page ->
            val kept = page.filterNot { pageNumber(it) || runningHeader(it) }
            if (kept.isNotEmpty()) kept else page.filterNot(::pageNumber)
        }
    }

    private val DIGITS = Regex("\\d+")

    /** How far (in ems) a line must stop short of the right edge to count as a paragraph's last line. */
    private const val SHORT_LINE_EMS = 5f

    private fun sizeKey(size: Float) = (size * 2).roundToInt() // half-point buckets
    private fun Float.toBucket() = (this / 4f).roundToInt()     // 4pt buckets for indentation
}
