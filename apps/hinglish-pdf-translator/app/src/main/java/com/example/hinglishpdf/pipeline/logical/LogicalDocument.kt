package com.example.hinglishpdf.pipeline.logical

import com.example.hinglishpdf.pipeline.PipelineStore
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.SourceRef

/** What a logical element is. LIST, TABLE and TABLE_ROW are containers; the others hold text. */
enum class ElementType { PARAGRAPH, HEADING, LIST, LIST_ITEM, TABLE, TABLE_ROW, TABLE_CELL, CAPTION, FOOTNOTE, QUOTE, TOC_ENTRY, TITLE, ALT_TEXT, UNKNOWN }

/**
 * One element of the logical document. A text element points at its
 * paragraph (text, inline codes, source spans); a container has children: a
 * list its items (an item may hold a nested list), a table its rows, a row
 * its cells.
 */
data class LogicalElement(
    val id: String,
    val type: ElementType,
    val paragraph: Int? = null,
    val level: Int = 0,
    val children: List<LogicalElement> = emptyList(),
)

/**
 * Stage 2's output: one document-wide stream of typed elements, independent
 * of pages and spine files. [paragraphs] are the assembled paragraphs (a
 * paragraph cut by a page, column or file break is already one); the
 * furniture among them is listed apart, never in the stream.
 */
data class LogicalDocument(
    val parserVersion: Int,
    val paragraphs: List<ParsedParagraph>,
    val elements: List<LogicalElement>,
    val furniture: List<Int>,
) {
    /** The text elements, depth first, in reading order. */
    fun leaves(): List<LogicalElement> {
        val out = mutableListOf<LogicalElement>()
        fun walk(e: LogicalElement) {
            if (e.paragraph != null) out += e
            e.children.forEach(::walk)
        }
        elements.forEach(::walk)
        return out
    }

    fun paragraphOf(element: LogicalElement): ParsedParagraph? = element.paragraph?.let { paragraphs[it] }

    companion object {
        fun of(doc: ParsedDocument, parserVersion: Int = PipelineStore.PARSER_VERSION): LogicalDocument =
            LogicalStructure.build(doc.paragraphs, parserVersion)
    }
}

/**
 * Builds the element tree from the assembled stream: consecutive list items
 * become a list (deeper items a nested list inside the item before them),
 * consecutive cells of one table become a table of rows, everything else is
 * a text element of its own type. Furniture is set apart.
 */
object LogicalStructure {

    private class Node(val type: ElementType, val paragraph: Int? = null, val level: Int = 0) {
        val children = mutableListOf<Node>()
    }

    fun build(paragraphs: List<ParsedParagraph>, parserVersion: Int): LogicalDocument {
        val top = mutableListOf<Node>()
        val furniture = mutableListOf<Int>()
        var lists = ArrayDeque<Node>() // open lists, outermost first
        var table: Node? = null
        var tableKey: Pair<String, Int>? = null

        paragraphs.forEachIndexed { i, p ->
            if (p.role.furniture) {
                furniture += i
                return@forEachIndexed
            }
            if (p.role != ParagraphRole.LIST_ITEM) lists = ArrayDeque()
            if (p.role != ParagraphRole.TABLE_CELL) {
                table = null
                tableKey = null
            }
            when (p.role) {
                ParagraphRole.LIST_ITEM -> {
                    val item = Node(ElementType.LIST_ITEM, i, p.level)
                    while (lists.size > 1 && lists.last().level > p.level) lists.removeLast()
                    when {
                        lists.isEmpty() -> Node(ElementType.LIST, level = p.level).also { lists.addLast(it); top += it }
                        p.level > lists.last().level -> {
                            val nested = Node(ElementType.LIST, level = p.level)
                            (lists.last().children.lastOrNull() ?: lists.last()).children += nested
                            lists.addLast(nested)
                        }
                    }
                    lists.last().children += item
                }
                ParagraphRole.TABLE_CELL -> {
                    val cell = p.cell
                    val key = sourceKey(p) to (cell?.table ?: -1 - i)
                    if (table == null || key != tableKey) {
                        table = Node(ElementType.TABLE).also { top += it }
                        tableKey = key
                    }
                    val rowIndex = cell?.row ?: table!!.children.size
                    val row = table!!.children.firstOrNull { it.level == rowIndex }
                        ?: Node(ElementType.TABLE_ROW, level = rowIndex).also { table!!.children += it }
                    row.children += Node(ElementType.TABLE_CELL, i, cell?.column ?: row.children.size)
                }
                else -> top += Node(typeOf(p.role), i, p.level)
            }
        }
        fun freeze(node: Node, id: String): LogicalElement =
            LogicalElement(id, node.type, node.paragraph, node.level, node.children.mapIndexed { k, c -> freeze(c, "$id.${k + 1}") })
        return LogicalDocument(parserVersion, paragraphs, top.mapIndexed { k, n -> freeze(n, "e${k + 1}") }, furniture)
    }

    private fun sourceKey(p: ParsedParagraph): String = when (val ref = p.ref) {
        is SourceRef.Pdf -> "page ${ref.page}"
        is SourceRef.Epub -> ref.file
    }

    private fun typeOf(role: ParagraphRole): ElementType = when (role) {
        ParagraphRole.HEADING -> ElementType.HEADING
        ParagraphRole.PARAGRAPH -> ElementType.PARAGRAPH
        ParagraphRole.QUOTE -> ElementType.QUOTE
        ParagraphRole.CAPTION -> ElementType.CAPTION
        ParagraphRole.FOOTNOTE -> ElementType.FOOTNOTE
        ParagraphRole.TOC_LABEL -> ElementType.TOC_ENTRY
        ParagraphRole.TITLE -> ElementType.TITLE
        ParagraphRole.ALT_TEXT -> ElementType.ALT_TEXT
        ParagraphRole.LIST_ITEM -> ElementType.LIST_ITEM
        ParagraphRole.TABLE_CELL -> ElementType.TABLE_CELL
        else -> ElementType.UNKNOWN
    }
}
