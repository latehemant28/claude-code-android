package com.example.hinglishpdf.data.document

/** The structural role of a block; decides how it is prompted, shown and exported. */
enum class BlockKind { HEADING, PARAGRAPH, BULLET, NUMBERED, QUOTE, CODE }

/**
 * One structural unit of a document: a heading, a paragraph, a list item...
 *
 * @param level heading level (1-6) for [BlockKind.HEADING]; nesting depth
 *   (0 = top level) for list items; unused otherwise.
 * @param marker the original list marker for [BlockKind.NUMBERED] ("1.", "a)",
 *   "(iv)"); bullets are drawn from [level] instead.
 */
data class DocBlock(
    val kind: BlockKind,
    val text: String,
    val level: Int = 0,
    val marker: String = "",
) {
    /** Code is copied verbatim; translating it would break it. */
    val isTranslatable: Boolean get() = kind != BlockKind.CODE && text.any(Char::isLetter)
}

enum class DocFormat(val mimeType: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    EPUB("application/epub+zip", "epub"),
    ;

    companion object {
        /** The format of a saved file, from its name ("Book (Hindi).epub"). */
        fun ofFileName(name: String?): DocFormat? =
            entries.firstOrNull { name?.endsWith(".${it.extension}", ignoreCase = true) == true }
    }
}

/** A parsed input document; [file] is a private copy kept for exporting. */
data class SourceDocument(
    val title: String,
    val format: DocFormat,
    val blocks: List<DocBlock>,
    val file: java.io.File,
)

/** Bullet glyph for a nesting depth, like word processors do: • ◦ ▪ • ◦ ▪ ... */
fun bulletFor(level: Int): String = when (level % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}
