package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.DocumentFormatter
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.translate.ChunkUnit
import java.util.TreeMap

/**
 * Pages for a book translated through the pipeline. Translation runs on the
 * assembled paragraphs, but people think in pages, and pages are what is
 * saved, shown and resumed: a PDF keeps its real pages (a paragraph running
 * over a page break has one block on each page, its translation split back
 * between them); an EPUB is cut into page-sized sections of whole
 * paragraphs. Page furniture is not shown.
 */
object PipelinePages {

    fun build(bookId: Long, doc: ParsedDocument, format: DocFormat, sectionWords: Int): List<PageEntity> {
        val pages = when (format) {
            DocFormat.PDF -> pdfPages(bookId, doc)
            DocFormat.EPUB -> {
                val blocks = doc.paragraphs.withIndex().filter { !it.value.role.furniture }.map { (i, p) -> block(p, i, 0, p.text, p.tags) }
                BlockChunker.sections(blocks, sectionWords).mapIndexed { n, section -> PageEntity(bookId, n + 1, 0f, 0f, section) }
            }
        }
        // A page with nothing to translate is done from the start. (A block counts if its
        // paragraph does: the end of a paragraph may be only "1990." on its page.)
        return pages.map { page ->
            val translatable = page.sourceBlocks.any { b -> b.paragraph?.let { doc.paragraphs[it].translatable } == true }
            if (translatable) page else finished(page)
        }
    }

    private fun pdfPages(bookId: Long, doc: ParsedDocument): List<PageEntity> {
        val byPage = TreeMap<Int, MutableList<DocBlock>>()
        (1..doc.pageCount).forEach { byPage[it] = mutableListOf() }
        doc.paragraphs.forEachIndexed { i, p ->
            if (p.role.furniture) return@forEachIndexed
            val texts = DocumentAssembler.sourceTexts(p)
            val parts = p.parts.ifEmpty { listOf(null) }
            parts.forEachIndexed { k, part ->
                val page = ((part?.ref ?: p.ref) as? SourceRef.Pdf)?.page ?: 1
                byPage.getOrPut(page) { mutableListOf() } += block(p, i, k, texts[k], partTags(p, k))
            }
        }
        return byPage.map { (n, blocks) -> PageEntity(bookId, n, 0f, 0f, blocks) }
    }

    /** The tags of part [k] of [p], numbered as in that part's own text. */
    internal fun partTags(p: ParsedParagraph, k: Int): List<PlaceholderTag> {
        if (p.parts.size <= 1) return p.tags
        val from = p.parts[k].idOffset
        val to = p.parts.getOrNull(k + 1)?.idOffset ?: Int.MAX_VALUE
        return p.tags.filter { it.id in (from + 1)..to }.map { it.copy(id = it.id - from) }
    }

    private fun block(p: ParsedParagraph, index: Int, part: Int, text: String, tags: List<PlaceholderTag>): DocBlock {
        val kind = when {
            p.role == ParagraphRole.HEADING || p.role == ParagraphRole.TITLE -> BlockKind.HEADING
            p.role == ParagraphRole.LIST_ITEM && p.marker != null -> BlockKind.BULLET
            p.role == ParagraphRole.QUOTE -> BlockKind.QUOTE
            else -> BlockKind.PARAGRAPH
        }
        val level = when (kind) {
            BlockKind.HEADING -> p.level.coerceIn(1, 6)
            BlockKind.BULLET -> p.level
            else -> 0
        }
        // A heading or bullet continued from the previous page is plain text there.
        return DocBlock(if (part > 0) BlockKind.PARAGRAPH else kind, text, level, "", paragraph = index, part = part, tags = tags)
    }

    private fun finished(page: PageEntity): PageEntity {
        val none = List(page.sourceBlocks.size) { null }
        return page.copy(translations = none, translatedText = DocumentFormatter.toPlainText(page.sourceBlocks, none), translatedAt = 0L)
    }

    /** True if these page rows were built by [build] (old books keep their page-by-page translation). */
    fun isPipeline(pages: List<PageEntity>): Boolean = pages.any { page -> page.sourceBlocks.any { it.paragraph != null } }
}

/**
 * Fills a book's unsaved pages as paragraph translations arrive, and hands
 * back each page once every paragraph on it is translated, in page order, so
 * it can be saved (and resumed from) at once.
 *
 * @param pages the pages not saved yet.
 */
class PageFiller(pages: List<PageEntity>, private val paragraphs: List<ParsedParagraph>) {

    private class Open(val page: PageEntity, val translations: Array<String?>, val waiting: MutableSet<Int>)

    private val open = TreeMap<Int, Open>()
    /** paragraph → (page, block index, part) of its blocks on unsaved pages. */
    private val places = mutableMapOf<Int, MutableList<Triple<Int, Int, Int>>>()

    init {
        for (page in pages.sortedBy { it.pageNumber }) {
            val waiting = mutableSetOf<Int>()
            page.sourceBlocks.forEachIndexed { b, block ->
                val index = block.paragraph ?: return@forEachIndexed
                places.getOrPut(index) { mutableListOf() } += Triple(page.pageNumber, b, block.part)
                if (paragraphs.getOrNull(index)?.translatable == true) waiting += index
            }
            open[page.pageNumber] = Open(page, arrayOfNulls(page.sourceBlocks.size), waiting)
        }
    }

    /** The paragraphs still to translate, in order. */
    val paragraphsToTranslate: Set<Int> get() = open.values.flatMap { it.waiting }.toSortedSet()

    /** The first unsaved page [paragraph] is on (for "page 45 of 300"). */
    fun pageOf(paragraph: Int): Int = places[paragraph]?.minOf { it.first } ?: open.firstKey()

    /** The page's blocks and the translations it has so far (for the live view). */
    fun progress(page: Int): Pair<List<DocBlock>, List<String?>> =
        open[page]?.let { it.page.sourceBlocks to it.translations.toList() } ?: (emptyList<DocBlock>() to emptyList())

    /**
     * Records the translation of [paragraph] (null: keep the original), split
     * back over the pages it runs across. Returns the pages now complete.
     */
    fun fill(paragraph: Int, translation: String?): List<PageEntity> {
        val p = paragraphs[paragraph]
        val pieces = if (translation == null) null else DocumentAssembler.splitBack(p, translation)
        for ((page, block, part) in places[paragraph].orEmpty()) {
            val o = open[page] ?: continue
            o.translations[block] = pieces?.getOrNull(part)
            o.waiting -= paragraph
        }
        return release()
    }

    /** Pieces of a paragraph too long for one request, until all are back. */
    private val pieces = mutableMapOf<Int, Array<String?>>()

    /**
     * Records one request's answers ([results] aligned with [units]): a
     * paragraph is filled when its last piece is back (a piece the provider
     * refused keeps its original text). Returns the pages now complete.
     */
    fun fill(units: List<ChunkUnit>, results: List<String?>): List<PageEntity> {
        val done = mutableListOf<PageEntity>()
        units.forEachIndexed { i, u ->
            val parts = pieces.getOrPut(u.paragraph) { arrayOfNulls(u.pieces) }
            parts[u.piece] = results.getOrNull(i) ?: if (u.pieces > 1) u.text else null
            if (u.piece == u.pieces - 1) {
                pieces.remove(u.paragraph)
                done += fill(u.paragraph, if (u.pieces == 1) parts[0] else parts.joinToString(" ") { it.orEmpty() }.trim())
            }
        }
        return done
    }

    /** Pages complete from the first unsaved one on. */
    fun release(): List<PageEntity> {
        val done = mutableListOf<PageEntity>()
        while (open.isNotEmpty() && open.firstEntry().value.waiting.isEmpty()) {
            done += complete(open.pollFirstEntry().value)
        }
        return done
    }

    /** Whatever is left (only if a paragraph was never filled): saved with what it has. */
    fun finish(): List<PageEntity> = open.values.map(::complete).also { open.clear() }

    private fun complete(o: Open): PageEntity {
        val translations = o.translations.toList()
        return o.page.copy(
            translations = translations,
            translatedText = DocumentFormatter.toPlainText(o.page.sourceBlocks, translations),
            translatedAt = System.currentTimeMillis(),
        )
    }
}
