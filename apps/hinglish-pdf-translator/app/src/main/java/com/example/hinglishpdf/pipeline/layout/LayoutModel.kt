package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.assemble.Hyphenation
import com.example.hinglishpdf.pipeline.pdf.PageKind
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.TableCellRef

/**
 * What a block of a printed page is (stage 1, physical layout). Every block
 * gets exactly one. Furniture is kept for the merger but never translated.
 */
enum class BlockRole {
    BODY, HEADING, LIST_ITEM, CAPTION, TABLE_CELL, FOOTNOTE, HEADER, FOOTER, PAGE_NUMBER, BOILERPLATE, UNKNOWN;

    val furniture: Boolean get() = this == HEADER || this == FOOTER || this == PAGE_NUMBER || this == BOILERPLATE

    fun paragraphRole(): ParagraphRole = when (this) {
        BODY -> ParagraphRole.PARAGRAPH
        HEADING -> ParagraphRole.HEADING
        LIST_ITEM -> ParagraphRole.LIST_ITEM
        CAPTION -> ParagraphRole.CAPTION
        TABLE_CELL -> ParagraphRole.TABLE_CELL
        FOOTNOTE -> ParagraphRole.FOOTNOTE
        HEADER -> ParagraphRole.HEADER
        FOOTER -> ParagraphRole.FOOTER
        PAGE_NUMBER -> ParagraphRole.PAGE_NUMBER
        BOILERPLATE -> ParagraphRole.BOILERPLATE
        UNKNOWN -> ParagraphRole.UNKNOWN
    }
}

/** A stretch of one line in one style; [superscript] marks a footnote mark raised above the line. */
data class TextRun(val text: String, val bold: Boolean, val italic: Boolean, val superscript: Boolean = false)

/**
 * One line of text, or a piece of one when a wide gap (column gutter, table)
 * splits it. Points, origin at the top left. [column] and [columnId] are set
 * by the reading order: 0 / "" outside a column region; 1, 2... inside one.
 */
data class TextLine(
    val page: Int,
    val box: Box,
    val baseline: Float,
    val size: Float,
    val runs: List<TextRun>,
    val font: String,
    val column: Int = 0,
    val columnId: String = "",
) {
    val text: String get() = runs.joinToString("") { it.text }
    val bold: Boolean get() = boldShare() > 0.6f
    val italic: Boolean
        get() = runs.filter { !it.superscript }.let { r -> r.isNotEmpty() && r.sumOf { if (it.italic) it.text.length else 0 } * 10 > r.sumOf { it.text.length } * 6 }

    fun boldShare(): Float {
        val visible = runs.filter { !it.superscript }
        val total = visible.sumOf { it.text.count { c -> !c.isWhitespace() } }
        return if (total == 0) 0f else visible.sumOf { r -> if (r.bold) r.text.count { !it.isWhitespace() } else 0 }.toFloat() / total
    }
}

/**
 * One block of a page: its role, box, font tier (0 body, 1..n larger sizes
 * from the largest down, -1 smaller), column, position in reading order and
 * lines. [scores] keeps what each role scored, for debugging.
 *
 * @param readingIndex 0, 1, 2... over the page's content blocks; -1 for furniture.
 * @param level heading level (1-4) or list nesting (0 = top).
 * @param marker a list item's bullet, taken off its first line.
 */
data class LayoutBlock(
    val id: String,
    val page: Int,
    val role: BlockRole,
    val box: Box,
    val fontTier: Int,
    val columnId: String,
    val column: Int,
    val readingIndex: Int,
    val lines: List<TextLine>,
    val level: Int = 0,
    val marker: String? = null,
    val cell: TableCellRef? = null,
    val scores: Map<BlockRole, Float> = emptyMap(),
) {
    val text: String get() = lines.joinToString(" ") { it.text.trim() }
}

/**
 * Stage 1's output for one page: content blocks in reading order, and the
 * furniture (headers, footers, page numbers, boilerplate) apart, with its
 * place, for the merger.
 */
data class PageLayout(
    val page: Int,
    val width: Float,
    val height: Float,
    val kind: PageKind,
    val blocks: List<LayoutBlock>,
    val furniture: List<LayoutBlock>,
    val columns: Int,
    val tables: Int,
)

/** Every page's layout, with what the whole book is measured against. */
data class DocumentLayout(
    val pages: List<PageLayout>,
    val bodySize: Float,
    val hyphenation: Hyphenation,
)
