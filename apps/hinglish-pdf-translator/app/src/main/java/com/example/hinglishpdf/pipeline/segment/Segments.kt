package com.example.hinglishpdf.pipeline.segment

import java.security.MessageDigest

/**
 * What a paragraph is in its document; decides whether it is split into
 * sentences. [furniture] blocks (running headers and footers, page numbers,
 * printer's marks) are kept with their place for the rebuilder but never
 * translated as text.
 */
enum class ParagraphRole(val splitsIntoSentences: Boolean, val furniture: Boolean = false) {
    HEADING(false),
    PARAGRAPH(true),
    LIST_ITEM(true),
    QUOTE(true),
    CAPTION(true),
    TABLE_CELL(false),
    FOOTNOTE(true),
    /** A table-of-contents entry (NCX navLabel, nav link). */
    TOC_LABEL(false),
    /** The book's title (OPF dc:title, NCX docTitle, a chapter's <title>). */
    TITLE(false),
    /** An image's alt text. */
    ALT_TEXT(false),
    /** A running header (top margin, repeated, or shaped like one). */
    HEADER(false, furniture = true),
    /** A running footer. */
    FOOTER(false, furniture = true),
    PAGE_NUMBER(false, furniture = true),
    /** "This page intentionally left blank", printer's slugs and time stamps. */
    BOILERPLATE(false, furniture = true),
    /** Text that fits no other class (a stray margin note in another size): translated on its own, never joined. */
    UNKNOWN(false),
    ;

    /** Running text that may continue across a page, column or file break. */
    val body: Boolean get() = this == PARAGRAPH || this == LIST_ITEM || this == QUOTE

    /**
     * Blocks the reader's eye skips when following running text from one
     * page or column to the next: furniture, footnotes, figures and their
     * captions, floating tables, titles and alt text.
     */
    val transparent: Boolean
        get() = furniture || this == FOOTNOTE || this == CAPTION || this == TABLE_CELL || this == ALT_TEXT || this == TITLE || this == UNKNOWN
}

/** A rectangle on a PDF page, in points, with the origin at the top left. */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0

    fun union(other: Box) = Box(minOf(x0, other.x0), minOf(y0, other.y0), maxOf(x1, other.x1), maxOf(y1, other.y1))
}

/** Where a paragraph or segment comes from, so it can be written back and shown side by side. */
sealed interface SourceRef {
    /**
     * In an EPUB: the file inside the archive and the XPath of the element.
     * [run] tells apart several text runs in one element (text around a
     * nested list); [attribute] is set for attribute text (`alt`).
     */
    data class Epub(val file: String, val xpath: String, val run: Int = 0, val attribute: String? = null) : SourceRef

    /** On a PDF page (1-based), with the box the text covers. */
    data class Pdf(val page: Int, val box: Box) : SourceRef
}

/** The font a PDF paragraph is set in (dominant across its lines). */
data class TextStyle(val font: String, val size: Float, val bold: Boolean, val italic: Boolean)

/** The characters [range] of a paragraph's text were on this line of this page. */
data class LineBox(val range: IntRange, val page: Int, val box: Box)

/**
 * One of the source paragraphs an assembled paragraph was joined from.
 *
 * @param index the source paragraph's position in the parser's output.
 * @param ref where it is.
 * @param start where its text begins in the assembled text.
 * @param idOffset what was added to its placeholder ids to keep them unique.
 */
data class SourcePart(val index: Int, val ref: SourceRef, val start: Int, val idOffset: Int)

/**
 * One paragraph: its placeholder text, the tags those placeholders stand
 * for, and where it came from.
 *
 * @param lineBoxes PDF: character ranges of [text] and the line (page and
 *   box) they were on.
 * @param tier PDF font tier: 0 is the body size, 1 the largest size above
 *   it, 2 the next and so on; -1 is smaller than the body. Headings can be
 *   found again by it after translation.
 * @param column 0 in single-column text; 1, 2... for the columns of a
 *   region laid out side by side.
 * @param marker the list item's bullet, taken off its text.
 * @param parts set by DocumentAssembler: the source paragraph(s) this one
 *   is made of, in order. More than one when a paragraph ran on across a
 *   page, column or file break; empty before assembly.
 */
data class ParsedParagraph(
    val role: ParagraphRole,
    val text: String,
    val tags: List<PlaceholderTag>,
    val ref: SourceRef,
    val level: Int = 0,
    val style: TextStyle? = null,
    val lineBoxes: List<LineBox> = emptyList(),
    val tier: Int = 0,
    val column: Int = 0,
    val marker: String? = null,
    val parts: List<SourcePart> = emptyList(),
) {
    /**
     * Text with letters in it; numbers, symbols and marks alone are kept as
     * they are, never sent for translation. Furniture never is.
     */
    val translatable: Boolean get() = !role.furniture && Placeholders.strip(text).any { it.isLetter() }

    /** The first and last PDF page this paragraph is on (1 to 1 for EPUB). */
    val pages: IntRange
        get() {
            val all = (listOf(ref) + parts.map { it.ref }).filterIsInstance<SourceRef.Pdf>().map { it.page } + lineBoxes.map { it.page }
            return if (all.isEmpty()) 1..1 else all.min()..all.max()
        }
}

/** One sentence (or a whole heading, label, cell...) to translate. */
data class ParsedSegment(
    /** Index of its paragraph in [ParsedDocument.paragraphs]. */
    val paragraph: Int,
    /** Position within the paragraph. */
    val index: Int,
    val text: String,
    /** Whitespace that followed it in the source (rejoins the paragraph). */
    val separator: String,
    val ref: SourceRef,
    /** The placeholders it contains, with what they stand for. */
    val tags: List<PlaceholderTag>,
    /** SHA-256 of the text with placeholders renumbered from 1: equal for repeated sentences. */
    val hash: String,
    val words: Int,
)

/**
 * A parsed book, before translation. [scannedPages] lists PDF pages that
 * have no usable text layer; a book that is mostly scanned [needsOcr].
 */
data class ParsedDocument(
    val title: String?,
    val language: String?,
    val metadata: Map<String, String>,
    val hasToc: Boolean,
    val pageCount: Int,
    val scannedPages: List<Int>,
    val needsOcr: Boolean,
    val paragraphs: List<ParsedParagraph>,
    val segments: List<ParsedSegment>,
)

/** Cuts paragraphs into segments: sentences, with the paragraph as their parent. */
object Segmenter {

    fun segment(paragraphs: List<ParsedParagraph>): List<ParsedSegment> =
        paragraphs.flatMapIndexed { p, paragraph ->
            if (!paragraph.translatable) return@flatMapIndexed emptyList()
            val sentences = if (paragraph.role.splitsIntoSentences) {
                SentenceSegmenter.split(paragraph.text)
            } else {
                val trimmed = paragraph.text.trim()
                val start = paragraph.text.indexOf(trimmed)
                listOf(Sentence(trimmed, start, start + trimmed.length, ""))
            }
            sentences.filter { it.text.isNotBlank() }.mapIndexed { i, sentence ->
                val ids = Placeholders.tokenize(sentence.text).mapNotNull {
                    when (it) {
                        is PlaceholderToken.Open -> it.id
                        is PlaceholderToken.Standalone -> it.id
                        else -> null
                    }
                }.toSet()
                ParsedSegment(
                    paragraph = p,
                    index = i,
                    text = sentence.text,
                    separator = sentence.separator,
                    ref = refFor(paragraph, sentence),
                    tags = paragraph.tags.filter { it.id in ids },
                    hash = hash(sentence.text),
                    words = wordCount(sentence.text),
                )
            }
        }

    /** A segment of a PDF paragraph covers only the lines its characters were on (on the page where it starts). */
    private fun refFor(paragraph: ParsedParagraph, sentence: Sentence): SourceRef {
        val ref = paragraph.ref
        if (ref !is SourceRef.Pdf || paragraph.lineBoxes.isEmpty()) return ref
        val lines = paragraph.lineBoxes.filter { it.range.first < sentence.end && it.range.last >= sentence.start }
        val page = lines.firstOrNull()?.page ?: return ref
        return SourceRef.Pdf(page, lines.filter { it.page == page }.map { it.box }.reduce(Box::union))
    }

    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Placeholders.renumber(text.trim()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Words a reader sees (placeholders excluded): runs of letters, digits and joining marks. */
    fun wordCount(text: String): Int = WORD.findAll(Placeholders.strip(text)).count()

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
}
