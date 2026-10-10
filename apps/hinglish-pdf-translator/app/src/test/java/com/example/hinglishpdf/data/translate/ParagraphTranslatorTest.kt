package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.PageEvent
import com.example.hinglishpdf.data.RequestPacer
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.ai.AITranslator
import com.example.hinglishpdf.data.ai.TranslatorException
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.translate.ChunkUnit
import com.example.hinglishpdf.pipeline.translate.ParagraphChunker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One pipeline request through the real repository (pacing, retries), with a scripted model. */
class ParagraphTranslatorTest {

    /** "Translates" each marked line to HI(text), placeholders kept; [script] can make request n misbehave. */
    private class FakeModel(private val script: (request: Int, lines: List<String>) -> String? = { _, _ -> null }) : AITranslator {
        val prompts = mutableListOf<String>()

        override fun translate(chunk: String): Flow<String> = flow {
            prompts += chunk
            val lines = chunk.substringAfter("</STRUCTURE_MARKERS>").trim().lines()
            val scripted = script(prompts.size, lines)
            if (scripted == "BLOCK") throw TranslatorException.Blocked("SAFETY")
            val answer = scripted ?: lines.joinToString("\n") { line ->
                val marker = Regex("^(\\{COL\\})?\\{[A-Z0-9]+\\}").find(line)!!.value
                "$marker HI(${line.removePrefix(marker).trim()})"
            }
            answer.chunked(7).forEach { emit(it) } // streamed in pieces
        }
    }

    private fun p(text: String, tags: List<PlaceholderTag> = emptyList()) =
        ParsedParagraph(ParagraphRole.PARAGRAPH, text, tags, SourceRef.Pdf(1, Box(0f, 0f, 1f, 1f)))

    private fun units(vararg paragraphs: ParsedParagraph): List<ChunkUnit> = ParagraphChunker.units(paragraphs.toList(), PipelineConfig())

    private fun run(model: FakeModel, units: List<ChunkUnit>): List<String?> {
        var result: List<String?> = emptyList()
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = TranslationRepository(model, RequestPacer(0, 0) { testScheduler.currentTime }, workDispatcher = dispatcher, chunkPauseMillis = { 0 })
            result = ParagraphTranslator(repository, dispatcher).translate(units).filterIsInstance<PageEvent.PageFinished>().single().translations
        }
        return result
    }

    @Test
    fun `three paragraphs, one request, each answer in its place`() {
        val model = FakeModel()
        val result = run(model, units(p("One."), p("Two."), p("Three.")))
        assertEquals(listOf("HI(One.)", "HI(Two.)", "HI(Three.)"), result)
        assertEquals(1, model.prompts.size)
    }

    @Test
    fun `when blocks are merged, each paragraph is asked again on its own`() {
        val model = FakeModel { n, _ -> if (n == 1) "{P} HI(One. Two.)\n{P} HI(Three.)" else null }
        val result = run(model, units(p("One."), p("Two."), p("Three.")))
        assertEquals(listOf("HI(One.)", "HI(Two.)", "HI(Three.)"), result)
        assertEquals(4, model.prompts.size)
    }

    @Test
    fun `a lost placeholder is asked again, then repaired, never dropped`() {
        val bold = PlaceholderTag(1, PlaceholderTag.Kind.PAIRED, "b", "<b></b>")
        // Request 1 loses {1}; the retry of that paragraph alone loses it again.
        val model = FakeModel { n, lines -> if (n <= 2) lines.joinToString("\n") { it.replace("{1}", "").replace("{/1}", "") } else null }
        val result = run(model, units(p("Say {1}hi{/1}.", listOf(bold)), p("Plain.")))
        assertEquals("{P} Say hi. {1}{/1}", "{P} " + result[0])
        assertEquals("{P} Plain.", "{P} " + result[1]) // the scripted answer for request 1 echoes it unchanged
    }

    @Test
    fun `text the provider refuses keeps its original`() {
        val model = FakeModel { _, lines -> if (lines.any { "secret" in it }) "BLOCK" else null }
        val result = run(model, units(p("A secret."), p("Fine.")))
        assertEquals(listOf(null, "HI(Fine.)"), result)
        assertTrue(model.prompts.size == 3)
    }
}
