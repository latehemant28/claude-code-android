package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.ChunkTooLargeException
import com.example.hinglishpdf.data.llm.HinglishPrompt
import com.example.hinglishpdf.data.llm.LlmTranslator
import com.example.hinglishpdf.data.text.TextChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Progress of one page, streamed as it is translated. */
sealed interface PageEvent {
    data class ChunkStarted(val chunk: Int, val chunkCount: Int) : PageEvent

    /** New model output for the current micro-chunk. */
    data class Token(val text: String) : PageEvent

    /** A micro-chunk is done; [translations] holds every block finished so far. */
    data class ChunkFinished(val chunk: Int, val chunkCount: Int, val translations: List<String?>) : PageEvent

    /** The whole page: one entry per block (null = kept as-is, e.g. code). */
    data class PageFinished(val translations: List<String?>) : PageEvent
}

/**
 * Translates ONE page: splits it into 100-150 word micro-chunks, runs them
 * through the model one after another, and stitches the answers back onto the
 * page's headings, bullets, numbering and paragraphs.
 *
 * Runs on [Dispatchers.Default]; collectors (the service, then the UI on the
 * main thread) only receive small events.
 */
class PageTranslator(private val translator: LlmTranslator) {

    fun translatePage(blocks: List<DocBlock>): Flow<PageEvent> = flow {
        val result = arrayOfNulls<String>(blocks.size)
        val pieces = mutableMapOf<Int, MutableList<String>>()
        val chunks = BlockChunker.chunk(blocks)

        chunks.forEachIndexed { index, units ->
            emit(PageEvent.ChunkStarted(index + 1, chunks.size))
            val translated = translateChunk(units, stream = this)

            // Stitch: a block split across chunks is re-joined in order.
            units.forEachIndexed { i, unit ->
                val text = translated[i] ?: unit.text // never lose content
                val parts = pieces.getOrPut(unit.blockIndex) { mutableListOf() }
                if (!unit.continuation) parts.clear()
                parts += text
                result[unit.blockIndex] = parts.joinToString(" ")
            }
            emit(PageEvent.ChunkFinished(index + 1, chunks.size, result.toList()))
        }
        emit(PageEvent.PageFinished(result.toList()))
    }.flowOn(Dispatchers.Default)

    /**
     * One micro-chunk. If the answer cannot be matched block for block, or a
     * block comes back in Devanagari, those blocks are re-translated one at a
     * time.
     */
    private suspend fun translateChunk(
        units: List<TranslationUnit>,
        stream: FlowCollector<PageEvent>?,
    ): List<String?> {
        val results = try {
            HinglishPrompt.parse(generate(HinglishPrompt.build(units), stream), units).toMutableList()
        } catch (e: ChunkTooLargeException) {
            if (units.size == 1) return listOf(translateOversized(units.single()))
            MutableList<String?>(units.size) { null }
        }
        if (units.size > 1) {
            units.forEachIndexed { i, unit ->
                val r = results[i]
                if (r == null || HinglishPrompt.containsDevanagari(r)) {
                    results[i] = translateChunk(listOf(unit), stream = null).first() ?: r
                }
            }
        }
        return results
    }

    /** Only for a model with a very small context: halve the text until it fits. */
    private suspend fun translateOversized(unit: TranslationUnit): String? {
        val words = TextChunker.countWords(unit.text)
        if (words < 20) return null
        return TextChunker.split(unit.text, words / 2).mapIndexed { i, piece ->
            translateChunk(listOf(unit.copy(text = piece, continuation = unit.continuation || i > 0)), null).first()
                ?: piece
        }.joinToString(" ")
    }

    private suspend fun generate(prompt: String, stream: FlowCollector<PageEvent>?): String {
        val out = StringBuilder()
        translator.generate(prompt).collect { piece ->
            out.append(piece)
            stream?.emit(PageEvent.Token(piece))
        }
        return out.toString()
    }
}
