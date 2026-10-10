package com.example.hinglishpdf.data

import com.example.hinglishpdf.data.ai.AITranslator
import com.example.hinglishpdf.data.ai.TranslatorException
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.llm.TranslationPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationRepositoryTest {

    /**
     * Stands in for Gemini: translates each Markdown block of the chunk to
     * [hi] of it, keeping its marker. [script] can make a given request
     * (1-based) misbehave.
     */
    private class FakeGemini(private val script: (request: Int, blocks: Int) -> Fault = { _, _ -> Fault.NONE }) :
        AITranslator {
        val prompts = mutableListOf<String>()
        val systemPrompts = mutableListOf<String>()

        override fun translate(systemPrompt: String, chunk: String): Flow<String> = flow {
            prompts += chunk
            systemPrompts += systemPrompt
            val blocks = chunk.split("\n\n")
            val out = blocks.map { block ->
                val marker = Regex("^(#+ |\\s*- |\\s*\\d+\\. )?").find(block)!!.value
                marker to hi(block.removePrefix(marker))
            }.toMutableList()
            when (script(prompts.size, blocks.size)) {
                Fault.RATE_LIMIT -> throw TranslatorException.Transient("Gemini rate limit reached", retryAfterMillis = 20_000, rateLimited = true)
                Fault.DAILY_QUOTA -> throw TranslatorException.DailyQuota()
                Fault.BLOCKED -> throw TranslatorException.Blocked("SAFETY")
                Fault.BAD_KEY -> throw TranslatorException.Fatal("Gemini rejected the API key.")
                Fault.DEVANAGARI_IN_LAST -> out[out.lastIndex] = out.last().first to "जल्दी निकलना"
                Fault.MERGES_PARAGRAPHS -> {
                    out[1] = "" to out[1].second + " " + out[2].second
                    out.removeAt(2)
                }
                Fault.COPIES_SECOND -> out[1] = out[1].first to blocks[1] // left in English
                Fault.EMPTY_SECOND -> out[1] = out[1].first to ""
                Fault.TRUNCATED -> {
                    emit(out.first().first + out.first().second.take(8))
                    throw TranslatorException.Truncated("Gemini's answer reached its length limit")
                }
                Fault.NONE -> Unit
            }
            emit("Good Hinglish: ") // the prompt's style example invites this label
            for ((marker, text) in out) {
                emit(marker)
                emit(text) // streamed in pieces
                emit("\n\n")
            }
        }
    }

    private enum class Fault {
        NONE, RATE_LIMIT, DAILY_QUOTA, BLOCKED, BAD_KEY, DEVANAGARI_IN_LAST, MERGES_PARAGRAPHS,
        COPIES_SECOND, EMPTY_SECOND, TRUNCATED,
    }

    private companion object {
        /** The fake "translation": each Latin letter swapped for a Devanagari one. */
        fun hi(text: String) = "HI(" + text.map { c ->
            if (c.lowercaseChar() in 'a'..'z') 'क' + (c.lowercaseChar() - 'a') else c
        }.joinToString("") + ")"
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
        hi("Chapter 4"), hi("It was a cold morning."), hi("Everyone was late."), hi("Bring a coat"), hi("Leave early"), null,
    )

    private fun TestScope.repository(gemini: FakeGemini) =
        TranslationRepository(
            gemini,
            RequestPacer(now = { testScheduler.currentTime }),
            workDispatcher = StandardTestDispatcher(testScheduler), // virtual time for waits
        )

    @Test
    fun `a page is one streamed request and keeps its structure`() = runTest {
        val gemini = FakeGemini()
        val events = repository(gemini).translatePage(page).toList()

        assertEquals(1, gemini.prompts.size)
        // The request is just the chunk; the instructions are the system prompt.
        assertEquals("# Chapter 4\n\nIt was a cold morning.\n\nEveryone was late.\n\n- Bring a coat\n\n2. Leave early", gemini.prompts[0])
        assertEquals(PageEvent.ChunkStarted(1, 1), events.first())
        assertTrue(events.count { it is PageEvent.Token } > 5)
        assertEquals(PageEvent.PageFinished(expected), events.last())
        // Default pair: Auto-Detect → Hindi.
        assertEquals(TranslationPrompt.system(Language.AUTO_DETECT, Language.HINDI), gemini.systemPrompts.single())
    }

    @Test
    fun `the system prompt carries the book's languages`() = runTest {
        val gemini = FakeGemini()
        repository(gemini).translatePage(page, Language.ENGLISH, Language.FRENCH).toList()
        val system = gemini.systemPrompts.single()
        assertTrue(system.contains("Translate the following text from English to French."))
        assertEquals(TranslationPrompt.system(Language.ENGLISH, Language.FRENCH), system)
    }

    @Test
    fun `a merged answer is redone in halves, never misaligned`() = runTest {
        val gemini = FakeGemini { request, _ -> if (request == 1) Fault.MERGES_PARAGRAPHS else Fault.NONE }
        val events = repository(gemini).translatePage(page).toList()
        assertEquals(3, gemini.prompts.size) // the page, then its two halves
        assertEquals(PageEvent.PageFinished(expected), events.last())
    }

    @Test
    fun `devanagari output is the expected result, not retried`() = runTest {
        val gemini = FakeGemini { _, _ -> Fault.DEVANAGARI_IN_LAST }
        val events = repository(gemini).translatePage(page).toList()
        assertEquals(1, gemini.prompts.size)
        assertEquals(PageEvent.PageFinished(expected.dropLast(2) + "जल्दी निकलना" + null), events.last())
    }

    @Test
    fun `a rate limit waits a full minute, and never stops the book`() = runTest {
        // 12 rate limits in a row: more than the old cap of 8 retries.
        val gemini = FakeGemini { request, _ -> if (request <= 12) Fault.RATE_LIMIT else Fault.NONE }
        val start = testScheduler.currentTime
        val events = repository(gemini).translatePage(page).toList()

        assertEquals(PageEvent.PageFinished(expected), events.last())
        val waits = events.filterIsInstance<PageEvent.Waiting>()
        // Each pause counts down from 60 (even though Gemini said "retry in 20s") to 1, once a second.
        assertEquals(12, waits.count { it.seconds == 60 })
        assertEquals((60 downTo 1).toList(), waits.take(60).map { it.seconds })
        assertTrue(waits.all { it.rateLimited })
        assertTrue(testScheduler.currentTime - start >= 12 * 60_000L)
    }

    @Test
    fun `every chunk is followed by a 4_5 second pause`() = runTest {
        val gemini = FakeGemini()
        val repo = repository(gemini)
        val start = testScheduler.currentTime
        repo.translatePage(page).toList()
        val afterFirst = testScheduler.currentTime
        assertTrue(afterFirst - start >= 4_500) // one chunk on this page, then the pause

        val words = { n: Int, p: String -> (1..n).joinToString(" ") { "$p$it" } + "." }
        val longPage = (1..30).map { DocBlock(BlockKind.PARAGRAPH, words(45, "w$it")) } // 2 chunks
        repo.translatePage(longPage).toList()
        assertTrue(testScheduler.currentTime - afterFirst >= 2 * 4_500)
    }

    @Test
    fun `refused text stays in english instead of stopping the book`() = runTest {
        val gemini = FakeGemini { _, blocks -> if (blocks == 1) Fault.BLOCKED else Fault.NONE }
        val single = listOf(DocBlock(BlockKind.PARAGRAPH, "Some passage Gemini refuses."))
        val events = repository(gemini).translatePage(single).toList()
        assertEquals(PageEvent.PageFinished(listOf("Some passage Gemini refuses.")), events.last())
    }

    @Test
    fun `daily quota and a bad key stop the book`() = runTest {
        for (fault in listOf(Fault.DAILY_QUOTA, Fault.BAD_KEY)) {
            try {
                repository(FakeGemini { _, _ -> fault }).translatePage(page).toList()
                fail("expected $fault to stop the page")
            } catch (e: TranslatorException) {
                assertTrue(e is TranslatorException.DailyQuota || e is TranslatorException.Fatal)
            }
        }
    }

    @Test
    fun `the pause after a chunk follows the selected provider`() = runTest {
        var pause = 4_500L
        val repo = TranslationRepository(
            FakeGemini(),
            RequestPacer(minIntervalMillis = { 0 }, now = { testScheduler.currentTime }),
            workDispatcher = StandardTestDispatcher(testScheduler),
            chunkPauseMillis = { pause },
        )
        val start = testScheduler.currentTime
        repo.translatePage(page).toList()
        assertEquals(4_500L, testScheduler.currentTime - start)

        pause = 1_000L // e.g. switched to a paid provider
        val second = testScheduler.currentTime
        repo.translatePage(page).toList()
        assertEquals(1_000L, testScheduler.currentTime - second)
    }

    @Test
    fun `a provider that fails for good hands over to one with a saved key`() = runTest {
        var switched = 0
        val gemini = FakeGemini { request, _ -> if (request == 1) Fault.BAD_KEY else Fault.NONE }
        val repo = TranslationRepository(
            gemini,
            RequestPacer(now = { testScheduler.currentTime }),
            workDispatcher = StandardTestDispatcher(testScheduler),
            failover = ProviderFailover { switched++; "Google Gemini" },
        )
        val events = repo.translatePage(page).toList()
        assertEquals(1, switched)
        assertEquals(PageEvent.ProviderSwitched("Google Gemini", "Gemini rejected the API key."), events.filterIsInstance<PageEvent.ProviderSwitched>().single())
        assertEquals(PageEvent.PageFinished(expected), events.last()) // carried on, nothing lost
    }

    @Test
    fun `the daily quota hands over too, and with no other key the book stops`() = runTest {
        val repo = TranslationRepository(
            FakeGemini { _, _ -> Fault.DAILY_QUOTA },
            RequestPacer(now = { testScheduler.currentTime }),
            workDispatcher = StandardTestDispatcher(testScheduler),
            failover = ProviderFailover { null }, // no other provider has a key
        )
        try {
            repo.translatePage(page).toList()
            fail("expected DailyQuota")
        } catch (e: TranslatorException.DailyQuota) {
            // expected
        }
    }

    @Test
    fun `pacing follows each provider's free tier`() {
        assertEquals(20_000L, com.example.hinglishpdf.data.ai.AIProvider.OPENAI.chunkPauseMillis)
        assertEquals(12_000L, com.example.hinglishpdf.data.ai.AIProvider.ANTHROPIC.chunkPauseMillis)
        assertEquals(4_000L, com.example.hinglishpdf.data.ai.AIProvider.GEMINI.chunkPauseMillis)
        assertEquals(2_000L, com.example.hinglishpdf.data.ai.AIProvider.GROQ.chunkPauseMillis)
        com.example.hinglishpdf.data.ai.AIProvider.entries.forEach { assertEquals(60_000L, it.rateLimitWaitMillis) }
    }

    // ------------------------------------------------- 100% translation

    private val story = listOf(
        DocBlock(BlockKind.HEADING, "32 Historical examples", level = 2),
        DocBlock(BlockKind.PARAGRAPH, "The first example comes from Rome, where the senate met every morning to argue."),
        DocBlock(BlockKind.PARAGRAPH, "The second example is from Athens."),
    )

    @Test
    fun `text the AI left in english mid-page is sent again, strictly`() = runTest {
        val gemini = FakeGemini { request, _ -> if (request == 1) Fault.COPIES_SECOND else Fault.NONE }
        val events = repository(gemini).translatePage(story).toList()

        assertEquals(2, gemini.prompts.size)
        assertEquals(story[1].text, gemini.prompts[1]) // only the untranslated block
        assertTrue(gemini.systemPrompts[1].contains("translate ALL of it into Hindi"))
        assertEquals(PageEvent.PageFinished(story.map { hi(it.text) }), events.last())
    }

    @Test
    fun `a block the AI left empty is translated, never blank`() = runTest {
        val gemini = FakeGemini { request, _ -> if (request == 1) Fault.EMPTY_SECOND else Fault.NONE }
        val events = repository(gemini).translatePage(story).toList()
        assertEquals(PageEvent.PageFinished(story.map { hi(it.text) }), events.last())
    }

    @Test
    fun `an answer cut off at the length limit is redone in parts`() = runTest {
        // The whole page, then its halves: the first half is cut off too, so it
        // is halved again; nothing is kept half-done or in English.
        val gemini = FakeGemini { request, blocks -> if (request <= 2 && blocks > 1) Fault.TRUNCATED else Fault.NONE }
        val events = repository(gemini).translatePage(story).toList()
        assertEquals(PageEvent.PageFinished(story.map { hi(it.text) }), events.last())
    }

    @Test
    fun `a long paragraph cut off on its own is translated sentence by sentence`() = runTest {
        val paragraph = "The senate met every morning to argue about the war. " +
            "Nobody agreed on anything at all. The consuls left the city in anger."
        val gemini = FakeGemini { request, _ -> if (request == 1) Fault.TRUNCATED else Fault.NONE }
        val events = repository(gemini).translatePage(listOf(DocBlock(BlockKind.PARAGRAPH, paragraph))).toList()

        // The paragraph, then its parts, split between sentences.
        val parts = gemini.prompts.drop(1)
        assertTrue(parts.size >= 2)
        assertEquals(paragraph, parts.joinToString(" "))
        assertEquals(PageEvent.PageFinished(listOf(parts.joinToString(" ") { hi(it) })), events.last())
    }

    @Test
    fun `every request asks for 100 percent of the text, block for block`() = runTest {
        val gemini = FakeGemini()
        repository(gemini).translatePage(story).toList()
        val system = gemini.systemPrompts.single()
        assertTrue(system.startsWith(TranslationPrompt.TEMPLATE.substringBefore("{sourceLanguage}")))
        assertTrue(system.contains("Never stop early and never leave a sentence in the original language."))
        assertTrue(system.contains("Never merge two blocks or split one"))
    }
}
