package com.example.hinglishpdf.pipeline.units

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.PipelineStore
import com.example.hinglishpdf.pipeline.logical.ElementType
import com.example.hinglishpdf.pipeline.logical.LogicalDocument
import com.example.hinglishpdf.pipeline.logical.LogicalElement
import com.example.hinglishpdf.pipeline.pdf.OutlineEntry
import com.example.hinglishpdf.pipeline.pdf.PageGeometry
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.Placeholders
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.SourceSpan
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** What a text unit is; a contents entry or running head included. */
enum class UnitKind { PARAGRAPH, HEADING, LIST_ITEM, TABLE_CELL, CAPTION, FOOTNOTE, QUOTE, TOC_ENTRY, TITLE, ALT_TEXT, RUNNING_HEAD, OTHER }

/**
 * Translatable content (Okapi's text unit): placeholder text with a code
 * table for its inline codes (paired {1}…{/1} for bold, italic, links,
 * superscripts; standalone [[IMG_3]] for images, breaks, footnote marks).
 *
 * @param paragraph the paragraph of the parse it comes from (null for a running head).
 * @param groups the containers (list, table, row) it sits in, outermost first.
 * @param linkedTo the heading unit a contents entry names: it must reuse that translation.
 */
data class TextUnit(
    val id: String,
    val elementId: String?,
    val kind: UnitKind,
    val paragraph: Int?,
    val text: String,
    val codes: List<PlaceholderTag>,
    val translate: Boolean,
    val level: Int = 0,
    val groups: List<String> = emptyList(),
    val linkedTo: String? = null,
)

/** A unit's text with what a reader sees (placeholders resolved), for matching. */
val TextUnit.plain: String get() = Placeholders.toPlain(text, codes)

/** Where a stretch of a unit's text came from, in a form the skeleton file can hold. */
data class SpanSlot(
    val page: Int? = null,
    val box: Box? = null,
    val file: String? = null,
    val xpath: String? = null,
    val run: Int = 0,
    val attribute: String? = null,
    val start: Int,
    val end: Int,
)

data class SkeletonNode(val id: String, val type: ElementType, val level: Int, val unit: String?, val children: List<SkeletonNode>)

/** A header, footer, page number or printer's mark, with its place and (running heads) the unit translating it. */
data class FurnitureSlot(val page: Int?, val file: String?, val role: ParagraphRole, val box: Box?, val text: String, val unit: String?)

/** A PDF bookmark and the heading unit it opens. */
data class OutlineLink(val title: String, val page: Int, val depth: Int, val unit: String?)

/**
 * Everything that is not translated (Okapi's skeleton): the element tree
 * with slots pointing to unit ids, each unit's source spans, page furniture,
 * page sizes and images (PDF), bookmarks. For an EPUB the original archive
 * is the rest of the skeleton: untouched files are copied byte for byte.
 */
data class Skeleton(
    val format: String,
    val source: String,
    val parserVersion: Int,
    val pages: List<PageGeometry>,
    val outline: List<OutlineLink>,
    val elements: List<SkeletonNode>,
    val slots: Map<String, List<SpanSlot>>,
    val furniture: List<FurnitureSlot>,
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()
        fun fromJson(json: String): Skeleton = gson.fromJson(json, Skeleton::class.java)
    }
}

class Extraction(val units: List<TextUnit>, val skeleton: Skeleton)

/**
 * Stage 3: the logical document split into text units (what is
 * translated) and a skeleton (what is kept). Every text element is a unit,
 * front and back matter included; numbers alone are units marked not to
 * translate. Running heads become one unit per distinct text. Contents
 * entries are linked to the headings they name: EPUB through the nav / NCX
 * link targets, any book through printed contents lines ("Chapter One ....
 * 5") that name headings, and PDF bookmarks through their title and page.
 */
object TextUnitExtractor {

    fun extract(doc: ParsedDocument, format: String, sourceName: String, config: PipelineConfig = PipelineConfig()): Extraction {
        val logical = LogicalDocument.of(doc)
        val units = mutableListOf<TextUnit>()
        val unitOfParagraph = mutableMapOf<Int, String>()

        fun walk(e: LogicalElement, groups: List<String>): SkeletonNode {
            var unitId: String? = null
            e.paragraph?.let { index ->
                val p = doc.paragraphs[index]
                unitId = "u${units.size + 1}"
                unitOfParagraph[index] = unitId!!
                units += TextUnit(unitId!!, e.id, kindOf(e.type), index, p.text, p.tags, p.translatable, p.level, groups)
            }
            val childGroups = if (e.children.isEmpty()) groups else groups + e.id
            return SkeletonNode(e.id, e.type, e.level, unitId, e.children.map { walk(it, childGroups) })
        }
        val tree = logical.elements.map { walk(it, emptyList()) }

        // Running heads: one unit per text (digits aside); page numbers and printer's marks have none.
        val furniture = mutableListOf<FurnitureSlot>()
        val headUnits = mutableMapOf<String, String>()
        for (index in logical.furniture) {
            val p = doc.paragraphs[index]
            val running = p.role == ParagraphRole.HEADER || p.role == ParagraphRole.FOOTER
            val unit = if (running && p.text.any { it.isLetter() }) {
                headUnits.getOrPut(p.text.replace(DIGITS, "#").lowercase()) {
                    "h${headUnits.size + 1}".also { id -> units += TextUnit(id, null, UnitKind.RUNNING_HEAD, index, p.text, p.tags, true) }
                }
            } else {
                null
            }
            val ref = p.ref
            furniture += FurnitureSlot((ref as? SourceRef.Pdf)?.page, (ref as? SourceRef.Epub)?.file, p.role, (ref as? SourceRef.Pdf)?.box, p.text, unit)
        }

        val linked = linkContents(units, doc, config)
        val outline = linkOutline(doc, linked, config)
        val slots = linked.filter { it.paragraph != null && it.kind != UnitKind.RUNNING_HEAD }
            .associate { u -> u.id to doc.paragraphs[u.paragraph!!].spans.map(::slotOf) }
        val skeleton = Skeleton(
            format = format,
            source = sourceName,
            parserVersion = PipelineStore.PARSER_VERSION,
            pages = doc.metadata["pageGeometry"]?.let { gson.fromJson<List<PageGeometry>>(it, geometryType) }.orEmpty(),
            outline = outline,
            elements = tree,
            slots = slots,
            furniture = furniture,
        )
        return Extraction(linked, skeleton)
    }

    /** Contents entries pointing at headings: EPUB link targets, then printed contents lines. */
    private fun linkContents(units: List<TextUnit>, doc: ParsedDocument, config: PipelineConfig): List<TextUnit> {
        val headings = units.filter { it.kind == UnitKind.HEADING }
        val byLink = headings.mapNotNull { h -> doc.paragraphs[h.paragraph!!].link?.let { it to h.id } }.toMap()
        fun fileOf(u: TextUnit) = (doc.paragraphs[u.paragraph!!].ref as? SourceRef.Epub)?.file

        val out = units.toMutableList()
        out.forEachIndexed { i, u ->
            if (u.kind != UnitKind.TOC_ENTRY || u.paragraph == null) return@forEachIndexed
            val target = doc.paragraphs[u.paragraph].link ?: return@forEachIndexed
            val file = target.substringBefore('#')
            val heading = byLink[target]
                ?: headings.firstOrNull { fileOf(it) == file }?.id
                ?: units.firstOrNull { it.kind == UnitKind.TITLE && fileOf(it) == file }?.id
            if (heading != null) out[i] = u.copy(linkedTo = heading)
        }

        // Printed contents: a page (or file) with enough lines naming headings.
        val headingByName = headings.groupBy { normalize(it.plain) }
        val candidates = out.withIndex().filter { (_, u) ->
            u.kind in setOf(UnitKind.PARAGRAPH, UnitKind.LIST_ITEM, UnitKind.TABLE_CELL) && u.paragraph != null
        }.mapNotNull { (i, u) ->
            val title = config.tocLine.matchEntire(u.plain.trim())?.groupValues?.get(1)?.let(::normalize).orEmpty()
            val heading = headingByName[title]?.let { list -> list.firstOrNull { it.id != u.id } }
            if (title.isEmpty() || heading == null) null else Triple(i, u, heading)
        }
        candidates.groupBy { (_, u, _) -> placeOf(doc, u) }.values.filter { it.size >= config.tocMinEntries }.flatten().forEach { (i, u, heading) ->
            out[i] = u.copy(kind = UnitKind.TOC_ENTRY, linkedTo = heading.id)
        }
        return out
    }

    /** PDF bookmarks to the heading on their page with the same name (or the first heading there). */
    private fun linkOutline(doc: ParsedDocument, units: List<TextUnit>, config: PipelineConfig): List<OutlineLink> {
        val entries = doc.metadata["outline"]?.let { gson.fromJson<List<OutlineEntry>>(it, outlineType) }.orEmpty()
        val headings = units.filter { it.kind == UnitKind.HEADING }
        return entries.map { e ->
            val onPage = headings.filter { h -> doc.paragraphs[h.paragraph!!].spans.any { (it.ref as? SourceRef.Pdf)?.page == e.page } }
            val unit = (onPage.firstOrNull { normalize(it.plain) == normalize(e.title) } ?: onPage.firstOrNull()
                ?: headings.firstOrNull { normalize(it.plain) == normalize(e.title) })?.id
            OutlineLink(e.title, e.page, e.depth, unit)
        }
    }

    private fun placeOf(doc: ParsedDocument, u: TextUnit): String = when (val ref = doc.paragraphs[u.paragraph!!].ref) {
        is SourceRef.Pdf -> "page ${ref.page}"
        is SourceRef.Epub -> ref.file
    }

    private fun slotOf(span: SourceSpan): SpanSlot = when (val ref = span.ref) {
        is SourceRef.Pdf -> SpanSlot(page = ref.page, box = ref.box, start = span.start, end = span.end)
        is SourceRef.Epub -> SpanSlot(file = ref.file, xpath = ref.xpath, run = ref.run, attribute = ref.attribute, start = span.start, end = span.end)
    }

    /** For matching names: lower case, letters and digits only, single spaces. */
    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(SPACES, " ").trim()

    private fun kindOf(type: ElementType): UnitKind = when (type) {
        ElementType.PARAGRAPH -> UnitKind.PARAGRAPH
        ElementType.HEADING -> UnitKind.HEADING
        ElementType.LIST_ITEM -> UnitKind.LIST_ITEM
        ElementType.TABLE_CELL -> UnitKind.TABLE_CELL
        ElementType.CAPTION -> UnitKind.CAPTION
        ElementType.FOOTNOTE -> UnitKind.FOOTNOTE
        ElementType.QUOTE -> UnitKind.QUOTE
        ElementType.TOC_ENTRY -> UnitKind.TOC_ENTRY
        ElementType.TITLE -> UnitKind.TITLE
        ElementType.ALT_TEXT -> UnitKind.ALT_TEXT
        else -> UnitKind.OTHER
    }

    private val gson = Gson()
    private val geometryType = object : TypeToken<List<PageGeometry>>() {}.type
    private val outlineType = object : TypeToken<List<OutlineEntry>>() {}.type
    private val DIGITS = Regex("\\d+")
    private val SPACES = Regex("\\s+")
}
