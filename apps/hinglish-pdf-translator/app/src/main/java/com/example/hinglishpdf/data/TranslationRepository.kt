package com.example.hinglishpdf.data

import android.net.Uri
import com.example.hinglishpdf.data.document.DocumentLoader
import com.example.hinglishpdf.data.document.SourceDocument
import com.example.hinglishpdf.data.llm.HinglishPrompt
import com.example.hinglishpdf.data.llm.LlmTranslator
import com.example.hinglishpdf.data.translate.BlockChunker
import com.example.hinglishpdf.data.translate.TranslationUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.fold

/** Everything the UI needs to show progress, in the order it happens. */
sealed interface TranslationEvent {
    data class Reading(val label: String, val fraction: Float?) : TranslationEvent
    data class DocumentReady(val document: SourceDocument, val chunkCount: Int) : TranslationEvent
    data class ChunkStarted(val chunkNumber: Int, val chunkCount: Int) : TranslationEvent

    /** Raw model output for the chunk in progress, for the live preview. */
    data class TextGenerated(val text: String) : TranslationEvent

    /** Final text of every block touched by the finished chunk, by block index. */
    data class ChunkFinished(
        val chunkNumber: Int,
        val chunkCount: Int,
        val blocks: Map<Int, String>,
    ) : TranslationEvent
}

/**
 * The full pipeline: document -> structured blocks -> prompt-sized chunks ->
 * streamed Hinglish, one chunk after another, mapped back onto the blocks.
 */
class TranslationRepository(
    private val loader: DocumentLoader,
    private val translator: LlmTranslator,
) {

    fun translate(uri: Uri): Flow<TranslationEvent> = channelFlow {
        send(TranslationEvent.Reading("Opening document…", null))
        val document = loader.load(uri) { label, fraction ->
            trySend(TranslationEvent.Reading(label, fraction))
        }

        val chunks = BlockChunker.chunk(document.blocks, BlockChunker.wordsFor(translator.contextTokens))
        send(TranslationEvent.DocumentReady(document, chunks.size))

        // Pieces of blocks that were split across prompts, re-joined per block.
        val pieces = mutableMapOf<Int, MutableList<String>>()

        chunks.forEachIndexed { index, units ->
            val number = index + 1
            send(TranslationEvent.ChunkStarted(number, chunks.size))

            val raw = translator.generate(HinglishPrompt.build(units)).fold(StringBuilder()) { acc, piece ->
                send(TranslationEvent.TextGenerated(piece))
                acc.append(piece)
            }
            val results = HinglishPrompt.parse(raw.toString(), units).toMutableList()

            // Lines the model skipped or wrote in Devanagari: retry each on its own.
            if (units.size > 1) {
                units.forEachIndexed { i, unit ->
                    val result = results[i]
                    if (result == null || HinglishPrompt.containsDevanagari(result)) {
                        results[i] = translateSingle(unit) ?: result
                    }
                }
            }

            val touched = mutableMapOf<Int, String>()
            units.forEachIndexed { i, unit ->
                // Fall back to the original text rather than losing content.
                val text = results[i] ?: unit.text
                val parts = pieces.getOrPut(unit.blockIndex) { mutableListOf() }
                if (!unit.continuation) parts.clear()
                parts += text
                touched[unit.blockIndex] = parts.joinToString(" ")
            }
            send(TranslationEvent.ChunkFinished(number, chunks.size, touched))
        }
    }

    private suspend fun translateSingle(unit: TranslationUnit): String? {
        val units = listOf(unit)
        val raw = translator.generate(HinglishPrompt.build(units))
            .fold(StringBuilder()) { acc, piece -> acc.append(piece) }
        return HinglishPrompt.parse(raw.toString(), units).first()
    }
}
