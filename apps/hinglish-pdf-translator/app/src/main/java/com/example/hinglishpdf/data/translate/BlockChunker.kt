package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.text.TextChunker

/**
 * One line of a prompt: a whole block, or one piece of a block that was too
 * long to send at once ([continuation] pieces carry no marker of their own).
 */
data class TranslationUnit(
    val blockIndex: Int,
    val text: String,
    val kind: BlockKind,
    val level: Int,
    val marker: String,
    val continuation: Boolean,
)

/**
 * Groups blocks into prompt-sized parts without ever breaking a block's
 * structure: blocks are packed whole while they fit, and only a block longer
 * than the budget is split (at sentence boundaries).
 *
 * For PDFs this runs on ONE page at a time, and a normal page fits a single
 * prompt whole; it only splits a page that would overflow the model's context.
 */
object BlockChunker {

    /** Small models lose track of line IDs in very long lists, so cap lines too. */
    const val MAX_LINES_PER_CHUNK = 80

    fun chunk(
        blocks: List<DocBlock>,
        maxWords: Int,
        maxLines: Int = MAX_LINES_PER_CHUNK,
    ): List<List<TranslationUnit>> {
        require(maxWords > 0 && maxLines > 0)
        val chunks = mutableListOf<List<TranslationUnit>>()
        val current = mutableListOf<TranslationUnit>()
        var words = 0

        blocks.forEachIndexed { index, block ->
            if (!block.isTranslatable) return@forEachIndexed
            val pieces = TextChunker.split(block.text, maxWords)
            pieces.forEachIndexed { p, piece ->
                val pieceWords = TextChunker.countWords(piece)
                if (current.isNotEmpty() && (words + pieceWords > maxWords || current.size >= maxLines)) {
                    chunks += current.toList()
                    current.clear()
                    words = 0
                }
                current += TranslationUnit(index, piece, block.kind, block.level, block.marker, continuation = p > 0)
                words += pieceWords
            }
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    /**
     * Words per prompt for a model with [contextTokens] tokens. The prompt
     * (guidelines + example) takes ~550 tokens; an English word is ~1.4 tokens
     * and its Roman Hindi/Marathi translation ~3, so each word costs ~4.4.
     * With a 4096-token model (Llama 3.2 3B) that is 700 words: a full,
     * dense book page.
     */
    fun wordsFor(contextTokens: Int): Int = ((contextTokens - 550) / 4.4).toInt().coerceIn(60, 700)

    /**
     * Cuts a page-less document (EPUB) into consecutive page-sized sections
     * of whole blocks. Every block, translatable or not, lands in exactly one
     * section, in order, so the sections can be stitched back together.
     */
    fun sections(blocks: List<DocBlock>, maxWords: Int): List<List<DocBlock>> {
        val sections = mutableListOf<List<DocBlock>>()
        val current = mutableListOf<DocBlock>()
        var words = 0
        for (block in blocks) {
            val w = TextChunker.countWords(block.text)
            if (current.isNotEmpty() && words + w > maxWords) {
                sections += current.toList()
                current.clear()
                words = 0
            }
            current += block
            words += w
        }
        if (current.isNotEmpty()) sections += current.toList()
        return sections
    }
}
