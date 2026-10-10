package com.example.hinglishpdf.pipeline.segment

import java.security.MessageDigest

/** What a paragraph is in its document; decides whether it is split into sentences. */
enum class ParagraphRole(val splitsIntoSentences: Boolean) {
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

/**
 * One paragraph: its placeholder text, the tags those
 * placeholders stand for, and where it came from. For a PDF, [lineBoxes]
 * maps character ranges of [text] to the boxes of the lines they were on.
 */
data class ParsedParagraph(
    val role: ParagraphRole,
    val text: String,
    val tags: List<PlaceholderTag>,
    val ref: SourceRef,
    val level: Int = 0,
    val style: TextStyle? = null,
    val lineBoxes: List<Pair<IntRange, Box>> = emptyList(),
) {
    /** Text with letters in it; numbers, symbols and marks alone are kept as they are, never sent for translation. */
    val translatable: Boolean get() = Placeholders.strip(text).any { it.isLetter() }
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

    /** A segment of a PDF paragraph covers only the lines its characters were on. */
    private fun refFor(paragraph: ParsedParagraph, sentence: Sentence): SourceRef {
        val ref = paragraph.ref
        if (ref !is SourceRef.Pdf || paragraph.lineBoxes.isEmpty()) return ref
        val boxes = paragraph.lineBoxes
            .filter { (range, _) -> range.first < sentence.end && range.last >= sentence.start }
            .map { it.second }
        return if (boxes.isEmpty()) ref else ref.copy(box = boxes.reduce(Box::union))
    }

    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Placeholders.renumber(text.trim()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Words a reader sees (placeholders excluded): runs of letters, digits and joining marks. */
    fun wordCount(text: String): Int = WORD.findAll(Placeholders.strip(text)).count()

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
}
