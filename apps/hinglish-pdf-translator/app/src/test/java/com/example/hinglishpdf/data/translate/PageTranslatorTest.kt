package com.example.hinglishpdf.data.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.LlmEngine
import com.example.hinglishpdf.data.llm.LlmTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PageTranslatorTest {

    private enum class Fault { NONE, DEVANAGARI_IN_LAST, MERGES_PARAGRAPHS }

    /**
     * Answers like a small model: translates each Markdown block of the
     * micro-chunk to "HI(text)", keeping its marker. On multi-block chunks it
     * can misbehave as configured; single-block retries are answered well.
     */
    private class FakeEngine(private val fault: Fault) : LlmEngine {
        val prompts = mutableListOf<String>()
        override val backendName = "GPU"
        override val contextTokens = 1280
        override fun close() = Unit
        override fun generate(prompt: String): Flow<String> = flow {
            prompts += prompt
            val blocks = prompt.substringAfter("Translate this text in the exact same casual style:\n").split("\n\n")
            val out = blocks.map { block ->
                val marker = Regex("^(#+ |\\s*- |\\s*\\d+\\. )?").find(block)!!.value
                marker to "HI(${block.removePrefix(marker)})"
            }.toMutableList()
            if (blocks.size > 1) {
                when (fault) {
                    Fault.DEVANAGARI_IN_LAST -> out[out.lastIndex] = out.last().first to "नमस्ते"
                    Fault.MERGES_PARAGRAPHS -> {
                        out[1] = "" to out[1].second + " " + out[2].second
                        out.removeAt(2)
                    }
                    Fault.NONE -> Unit
                }
            }
            emit("Hinglish: ") // the few-shot examples invite this label
            for ((marker, text) in out) {
                emit(marker)
                emit(text) // streamed in pieces
                emit("\n\n")
            }
        }
    }

    private val page = listOf(
        DocBlock(BlockKind.HEADING, "Chapter 4", level = 1),
        DocBlock(BlockKind.PARAGRAPH, "It was a cold morning."),
        DocBlock(BlockKind.PARAGRAPH, "Everyone was late."),
        DocBlock(BlockKind.BULLET, "Bring a coat", level = 0),
        DocBlock(BlockKind.NUMBERED, "Leave early", marker = "2."),
        DocBlock(BlockKind.CODE, "x = 1"),
    )
    private val expected = listOf(
        "HI(Chapter 4)", "HI(It was a cold morning.)", "HI(Everyone was late.)", "HI(Bring a coat)", "HI(Leave early)", null,
    )

    private suspend fun run(fault: Fault, blocks: List<DocBlock> = page): Pair<FakeEngine, List<PageEvent>> {
        val engine = FakeEngine(fault)
        val translator = LlmTranslator(ApplicationProvider.getApplicationContext<Context>())
        translator.useEngine(engine)
        return engine to PageTranslator(translator).translatePage(blocks).toList()
    }

    @Test
    fun `a page streams, keeps its structure and is stitched back`() = runTest {
        val (engine, events) = run(Fault.NONE)
        assertEquals(1, engine.prompts.size) // the whole (short) page is one micro-chunk
        assertEquals(PageEvent.ChunkStarted(1, 1), events.first())
        assertTrue(events.count { it is PageEvent.Token } > 5)
        assertEquals(PageEvent.PageFinished(expected), events.last())
        assertTrue(engine.prompts[0].endsWith("# Chapter 4\n\nIt was a cold morning.\n\nEveryone was late.\n\n- Bring a coat\n\n2. Leave early"))
    }

    @Test
    fun `a block in devanagari is retried on its own`() = runTest {
        val (engine, events) = run(Fault.DEVANAGARI_IN_LAST)
        assertEquals(2, engine.prompts.size)
        assertEquals(PageEvent.PageFinished(expected), events.last())
    }

    @Test
    fun `merged paragraphs are never misaligned, every block is redone`() = runTest {
        val (engine, events) = run(Fault.MERGES_PARAGRAPHS)
        assertEquals(1 + 5, engine.prompts.size)
        assertEquals(PageEvent.PageFinished(expected), events.last())
    }

    @Test
    fun `a long page is translated in 100-150 word micro-chunks`() = runTest {
        val words = { n: Int, p: String -> (1..n).joinToString(" ") { "$p$it" } + "." }
        val longPage = (1..10).map { DocBlock(BlockKind.PARAGRAPH, words(40, "w$it")) } // 400 words
        val (engine, events) = run(Fault.NONE, longPage)
        assertEquals(listOf(3, 3, 3, 1), engine.prompts.map { it.substringAfter("style:\n").split("\n\n").size })
        assertEquals((1..4).map { PageEvent.ChunkStarted(it, 4) }, events.filterIsInstance<PageEvent.ChunkStarted>())
        val finished = (events.last() as PageEvent.PageFinished).translations
        assertEquals(longPage.map { "HI(${it.text})" }, finished)
    }
}
