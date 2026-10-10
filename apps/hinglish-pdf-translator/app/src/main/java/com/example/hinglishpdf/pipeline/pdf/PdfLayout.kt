package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.Hyphenation
import com.example.hinglishpdf.pipeline.layout.DocumentLayout
import com.example.hinglishpdf.pipeline.layout.LayoutAnalyzer
import com.example.hinglishpdf.pipeline.layout.LayoutBlock
import com.example.hinglishpdf.pipeline.layout.TextRun
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.LineBox
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTextBuilder
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TextStyle

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
    /** Stage 1's full output: classified blocks with reading order, furniture apart. */
    val layout: DocumentLayout? = null,
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
 * The PDF side of the pipeline's parse: stage 1 ([LayoutAnalyzer]: blocks,
 * roles and reading order from page geometry) turned into paragraphs, page
 * by page in reading order: the page's header furniture, its content blocks,
 * then its footer furniture. Bold and italic spans inside a paragraph become
 * placeholders; footnote marks become [[SUP_n]]; words hyphenated at line
 * ends are rejoined or kept by [Hyphenation]. Paragraphs stay on their page:
 * joining text across pages and columns is DocumentAssembler's job.
 *
 * Pure Kotlin: the PDF reader only supplies [PdfPageGlyphs].
 */
object PdfLayout {

    fun analyze(pages: List<PdfPageGlyphs>, config: PipelineConfig = PipelineConfig()): PdfLayoutResult {
        val layout = LayoutAnalyzer.analyze(pages, config)
        val paragraphs = mutableListOf<ParsedParagraph>()
        var dropped = 0
        for (page in layout.pages) {
            val (top, bottom) = page.furniture.partition { it.box.y0 < page.height / 2 }
            fun addFurniture(blocks: List<LayoutBlock>) = blocks.forEach { block ->
                if (block.role.furniture) dropped++
                paragraphs += paragraph(block, Hyphenation.NONE)
            }
            addFurniture(top)
            page.blocks.forEach { paragraphs += paragraph(it, layout.hyphenation) }
            addFurniture(bottom)
        }
        return PdfLayoutResult(
            paragraphs = paragraphs,
            pageKinds = layout.pages.associate { it.page to it.kind },
            droppedLines = dropped,
            tables = layout.pages.sumOf { it.tables },
            columns = layout.pages.filter { it.columns > 1 }.associate { it.page to it.columns },
            hyphenation = layout.hyphenation,
            layout = layout,
        )
    }

    /**
     * A page with text has a usable text layer. Without any (or with only
     * garbage: unmapped fonts give U+FFFD or private-use characters) but
     * with images, it is a scan.
     */
    fun kindOf(page: PdfPageGlyphs): PageKind {
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

    /**
     * Joins lines into one paragraph: placeholder text (bold / italic spans
     * that differ from the paragraph's own style as {n}…{/n}, footnote marks
     * as [[SUP_n]]), words hyphenated at line ends rejoined or kept by
     * [Hyphenation], and the box of each line kept against its stretch of
     * text.
     */
    private fun paragraph(block: LayoutBlock, hyphenation: Hyphenation): ParsedParagraph {
        val lines = block.lines
        // Pieces with the separator that joins each line to the next.
        data class Piece(val run: TextRun, val line: Int, var trailing: String = "")
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
                    word != null && next != null && hyphenation.joins(word.groupValues[1], next) ->
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
        fun styleOf(run: TextRun): String? {
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
            role = block.role.paragraphRole(),
            text = text,
            tags = tags,
            ref = SourceRef.Pdf(lines.first().page, box),
            level = block.level,
            style = style,
            lineBoxes = ranges.toSortedMap().map { (line, range) -> LineBox(range, lines[line].page, lines[line].box) },
            tier = block.fontTier,
            column = block.column,
            marker = block.marker,
        )
    }


    private const val MIN_TEXT_CHARS = 10

    private val MARKUP = mapOf("b" to "<b></b>", "i" to "<i></i>", "bi" to "<b><i></i></b>")
    private val HYPHENATED = Regex("([\\p{L}\\p{M}]+)[-‐]$")
    private val LEADING_WORD = Regex("^[\\p{L}\\p{M}]+")
}
