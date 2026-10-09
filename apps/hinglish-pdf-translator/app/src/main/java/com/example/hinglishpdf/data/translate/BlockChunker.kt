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
 * Groups document blocks into prompt-sized chunks without ever breaking a
 * block's structure: blocks are packed whole while they fit, and only a block
 * longer than [maxWords] is split (at sentence boundaries).
 */
object BlockChunker {

    /** Small models lose track of line IDs in very long lists, so cap lines too. */
    const val MAX_LINES_PER_CHUNK = 30

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
     * Words per chunk for a model with [contextTokens] tokens. The prompt
     * (guidelines + example) takes ~450 tokens; an English word is ~1.4 tokens
     * and its Roman-Hindi translation ~2.8, so each word costs ~4.2 tokens.
     */
    fun wordsFor(contextTokens: Int): Int = ((contextTokens - 450) / 4.2).toInt().coerceIn(60, 350)
}
