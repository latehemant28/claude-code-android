package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.text.TextChunker

/**
 * One block of a chunk: a whole block, or one piece of a block that was too
 * long for one chunk ([continuation] pieces carry no marker of their own).
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
 * Cuts ONE page into chunks for Gemini without ever breaking a block's
 * structure: whole blocks are packed while they fit, and only a block longer
 * than the limit is split, at sentence boundaries.
 *
 * The free Gemini tier limits requests per minute and per day, not text size,
 * so chunks are large: a normal book page is a single request.
 */
object BlockChunker {

    /** Words per request; a dense printed page is 400-600 words. */
    const val CHUNK_WORDS = 800

    /** Blocks per request, so the answer can be matched back reliably. */
    const val MAX_BLOCKS_PER_CHUNK = 40

    fun chunk(
        blocks: List<DocBlock>,
        maxWords: Int = CHUNK_WORDS,
        maxLines: Int = MAX_BLOCKS_PER_CHUNK,
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
