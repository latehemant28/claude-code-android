package com.example.hinglishpdf.data.document

/** Renders blocks as plain text that keeps headings, bullets, numbering and paragraphs. */
object DocumentFormatter {

    /**
     * @param translations translated text per block index; blocks without one
     *   (not translated yet, or code) fall back to the original text.
     */
    fun toPlainText(blocks: List<DocBlock>, translations: List<String?>): String {
        val out = StringBuilder()
        var previous: DocBlock? = null
        blocks.forEachIndexed { index, block ->
            val text = block.plain(translations.getOrNull(index) ?: block.text)
            if (text.isBlank()) return@forEachIndexed

            val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
            val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
            if (out.isNotEmpty()) {
                // List items sit on consecutive lines; everything else gets a blank line.
                out.append(if (isList && prevIsList) "\n" else "\n\n")
            }
            out.append(
                when (block.kind) {
                    BlockKind.HEADING -> text
                    BlockKind.BULLET -> "    ".repeat(block.level) + bulletFor(block.level) + " " + text
                    BlockKind.NUMBERED -> "    ".repeat(block.level) + block.marker + " " + text
                    BlockKind.QUOTE -> text.lines().joinToString("\n") { "> $it" }
                    BlockKind.PARAGRAPH, BlockKind.CODE -> text
                },
            )
            previous = block
        }
        return out.toString()
    }
}
