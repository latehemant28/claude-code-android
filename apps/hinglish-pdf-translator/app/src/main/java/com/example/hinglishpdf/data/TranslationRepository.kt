package com.example.hinglishpdf.data

import android.net.Uri
import com.example.hinglishpdf.data.llm.LlmTranslator
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import com.example.hinglishpdf.data.text.TextChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Everything the UI needs to show progress, in the order it happens. */
sealed interface TranslationEvent {
    data class ExtractingPage(val page: Int, val pageCount: Int) : TranslationEvent
    data class ChunkStarted(val chunkNumber: Int, val chunkCount: Int) : TranslationEvent
    data class TextGenerated(val text: String) : TranslationEvent
    data class ChunkFinished(val chunkNumber: Int, val chunkCount: Int) : TranslationEvent
}

class NoExtractableTextException : IllegalStateException(
    "No text found in this PDF. It is probably scanned images; run it through OCR first.",
)

/**
 * The full pipeline: PDF -> page text -> word-limited chunks -> streamed
 * Hinglish, one chunk after another.
 */
class TranslationRepository(
    private val extractor: PdfTextExtractor,
    private val translator: LlmTranslator,
    private val maxWordsPerChunk: Int = TextChunker.DEFAULT_MAX_WORDS,
) {

    fun translatePdf(uri: Uri): Flow<TranslationEvent> = flow {
        // 1. Extract every page (on IO, see PdfTextExtractor).
        val pages = mutableListOf<String>()
        extractor.extractPages(uri).collect { page ->
            emit(TranslationEvent.ExtractingPage(page.pageNumber, page.pageCount))
            pages += page.text
        }

        // 2. Chunk the whole document so we know the total for "chunk X of N".
        val chunks = TextChunker.chunk(pages.joinToString("\n\n"), maxWordsPerChunk)
        if (chunks.isEmpty()) throw NoExtractableTextException()

        // 3. Translate sequentially; the model can only run one prompt at a time.
        chunks.forEachIndexed { index, chunk ->
            val number = index + 1
            emit(TranslationEvent.ChunkStarted(number, chunks.size))
            translator.translate(chunk).collect { emit(TranslationEvent.TextGenerated(it)) }
            emit(TranslationEvent.ChunkFinished(number, chunks.size))
        }
    }
}
