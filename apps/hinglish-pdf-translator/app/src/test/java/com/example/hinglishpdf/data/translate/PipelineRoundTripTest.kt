package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.PageEvent
import com.example.hinglishpdf.data.RequestPacer
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.ai.AITranslator
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.pdf.PdfLayout
import com.example.hinglishpdf.pipeline.pdf.page
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.translate.MarkerPrompt
import com.example.hinglishpdf.pipeline.translate.ParagraphChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live flow end to end, with an identity "translation": pages → layout
 * → assembly → paragraph requests with markers → answers → pages saved.
 * Every page gets its text back, nothing of the page furniture gets in, and
 * no request ends in the middle of a sentence.
 */
class PipelineRoundTripTest {

    /** Answers with the blocks exactly as sent: an identity translation that keeps every marker. */
    private class Echo : AITranslator {
        val prompts = mutableListOf<String>()
        override fun translate(chunk: String): Flow<String> = flow {
            prompts += chunk
            emit(chunk.substringAfter("</STRUCTURE_MARKERS>").trim())
        }
    }

    private fun book(): ParsedDocument {
        val pages = (1..3).map { n ->
            page(n) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                when (n) {
                    1 -> {
                        text("Chapter One", 50f, 90f, size = 18f)
                        lines(listOf("The water rose all through the night until the", "lower streets of the old town were deep under"), 50f, 680f)
                    }
                    2 -> {
                        lines(listOf("water, and the people carried what they could", "up the hill to the church. Nobody slept."), 50f, 100f)
                        text("• a coat", 50f, 140f)
                        text("• a lamp", 50f, 152f)
                    }
                    else -> lines(listOf("In the morning the water went down again and", "left a brown line along every wall."), 50f, 100f)
                }
                text("$n", 295f, 785f, size = 9f)
            }
        }
        val config = PipelineConfig()
        val layout = PdfLayout.analyze(pages, config)
        val paragraphs = DocumentAssembler(config, layout.hyphenation).assemble(layout.paragraphs)
        return ParsedDocument(null, null, emptyMap(), false, 3, emptyList(), false, paragraphs, Segmenter.segment(paragraphs))
    }

    @Test
    fun `identity translation through the live flow gives every page its text back`() = runTest {
        val doc = book()
        val config = PipelineConfig()
        val pages = PipelinePages.build(1L, doc, DocFormat.PDF, 350)
        val filler = PageFiller(pages.filter { it.translations == null }, doc.paragraphs)
        val model = Echo()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val translator = ParagraphTranslator(
            TranslationRepository(model, RequestPacer(0, 0) { testScheduler.currentTime }, workDispatcher = dispatcher, chunkPauseMillis = { 0 }),
            dispatcher,
        )
        val saved = mutableListOf<PageEntity>()
        saved += filler.release()
        for (chunk in ParagraphChunker.chunks(ParagraphChunker.units(doc.paragraphs, config, filler.paragraphsToTranslate), config)) {
            // Every request ends where a paragraph ends: never mid-sentence.
            val last = chunk.last()
            assertTrue(last.piece == last.pieces - 1 && doc.paragraphs[last.paragraph].text.trim().endsWith(last.text))
            val result = translator.translate(chunk).filterIsInstance<PageEvent.PageFinished>().single().translations
            saved += filler.fill(chunk, result)
        }
        saved += filler.finish()

        assertEquals(listOf(1, 2, 3), saved.map { it.pageNumber })
        fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Same words in the same order across the book (a joined paragraph may move a
        // sentence across the page break, never lose or repeat one).
        assertEquals(
            words(pages.joinToString(" ") { p -> p.sourceBlocks.joinToString(" ") { it.plain() } }),
            words(saved.joinToString(" ") { it.translatedText.orEmpty() }.replace("•", "")),
        )
        // No header, page number or fragment inside the text.
        val text = saved.joinToString("\n") { it.translatedText.orEmpty() }
        assertFalse(text.contains("RIVERS"))
        assertFalse(Regex("(?m)^[123]$").containsMatchIn(text))
        // The sentence across the page break went in one request, whole.
        assertTrue(model.prompts.any { "until the lower streets of the old town were deep under water, and the people" in it })
        // One structure marker per block in every request.
        for (prompt in model.prompts) {
            val blocks = prompt.substringAfter("</STRUCTURE_MARKERS>").trim().lines()
            assertEquals(blocks.size, MarkerPrompt.markers(blocks.joinToString("\n")).count { it != "{COL}" })
        }
    }
}
