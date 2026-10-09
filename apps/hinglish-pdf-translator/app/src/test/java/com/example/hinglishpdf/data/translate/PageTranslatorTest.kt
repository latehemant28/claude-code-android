package com.example.hinglishpdf.data.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.LlmEngine
import com.example.hinglishpdf.data.llm.LlmTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    /**
     * Answers like a small model would: echoes each "[id] marker text" line
     * as "[id] marker HI(text)", but on the full-page prompt it skips line 3
     * and writes line 4 in Devanagari. Single-line retries are answered well.
     */
    private class FakeEngine : LlmEngine {
        val prompts = mutableListOf<String>()
        override val backendName = "CPU"
        override val contextTokens = 4096
        override fun close() = Unit
        override fun generate(prompt: String): Flow<String> = flow {
            prompts += prompt
            val input = prompt.substringAfter("Output ONLY the translated text:\n").lines().filter { it.startsWith("[") }
            val fullPage = input.size > 1
            for (line in input) {
                val id = line.substringAfter('[').substringBefore(']').toInt()
                val body = line.substringAfter("] ")
                val marker = Regex("^(#+ |\\s*- |\\s*\\d+\\. )?").find(body)!!.value
                val text = body.removePrefix(marker)
                when {
                    fullPage && id == 3 -> Unit
                    fullPage && id == 4 -> emit("[$id] $marker नमस्ते\n")
                    else -> {
                        emit("[$id] $marker")
                        emit("HI($text)\n") // streamed in pieces
                    }
                }
            }
        }
    }

    @Test
    fun `whole page in one prompt, structure kept, gaps retried`() = runTest {
        val engine = FakeEngine()
        val translator = LlmTranslator(ApplicationProvider.getApplicationContext<Context>())
        translator.useEngine(engine)
        val page = listOf(
            DocBlock(BlockKind.HEADING, "Chapter 4", level = 1),
            DocBlock(BlockKind.PARAGRAPH, "It was a cold morning."),
            DocBlock(BlockKind.BULLET, "Bring a coat", level = 0),
            DocBlock(BlockKind.NUMBERED, "Leave early", marker = "2."),
            DocBlock(BlockKind.CODE, "x = 1"),
        )

        val live = mutableListOf<String>()
        val result = PageTranslator(translator).translatePage(page, TargetLanguage.HINGLISH) { live += it }

        assertEquals(
            listOf("HI(Chapter 4)", "HI(It was a cold morning.)", "HI(Bring a coat)", "HI(Leave early)", null),
            result,
        )
        // One prompt for the whole page, then one retry each for the two bad lines.
        assertEquals(3, engine.prompts.size)
        assertTrue(engine.prompts[0].contains("[1] # Chapter 4\n[2] It was a cold morning.\n[3] - Bring a coat\n[4] 2. Leave early\n"))
        assertTrue(live.last().contains("[2] HI(It was a cold morning.)"))
    }
}
