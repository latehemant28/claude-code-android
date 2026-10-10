package com.example.hinglishpdf.data

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.ServerException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationRepositoryTest {

    /**
     * Stands in for Gemini: translates each Markdown block of the chunk to
     * "HI(text)", keeping its marker. [script] can make a given request
     * (1-based) misbehave.
     */
    private class FakeGemini(private val script: (request: Int, blocks: Int) -> Fault = { _, _ -> Fault.NONE }) :
        HinglishModel {
        val prompts = mutableListOf<String>()

        override fun translate(chunk: String): Flow<String> = flow {
            prompts += chunk
            val blocks = chunk.split("\n\n")
            val out = blocks.map { block ->
                val marker = Regex("^(#+ |\\s*- |\\s*\\d+\\. )?").find(block)!!.value
                marker to "HI(${block.removePrefix(marker)})"
            }.toMutableList()
            when (script(prompts.size, blocks.size)) {
                Fault.RATE_LIMIT -> throw GeminiException.Transient("Gemini rate limit reached", retryAfterMillis = 20_000, rateLimited = true)
                Fault.DAILY_QUOTA -> throw GeminiException.DailyQuota()
                Fault.BLOCKED -> throw GeminiException.Blocked("SAFETY")
                Fault.BAD_KEY -> throw GeminiException.Fatal("Gemini rejected the API key.")
                Fault.DEVANAGARI_IN_LAST -> out[out.lastIndex] = out.last().first to "जल्दी निकलना"
                Fault.MERGES_PARAGRAPHS -> {
                    out[1] = "" to out[1].second + " " + out[2].second
                    out.removeAt(2)
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

    private enum class Fault { NONE, RATE_LIMIT, DAILY_QUOTA, BLOCKED, BAD_KEY, DEVANAGARI_IN_LAST, MERGES_PARAGRAPHS }

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
        val waits = events.filterIsInstance<PageEvent.Waiting>().map { it.seconds }
        assertEquals(List(12) { 60 }, waits) // 60 s even though Gemini said "retry in 20s"
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
            } catch (e: GeminiException) {
                assertTrue(e is GeminiException.DailyQuota || e is GeminiException.Fatal)
            }
        }
    }

    @Test
    fun `sdk errors are sorted into what to do`() {
        val classify = { e: Throwable -> GeminiHinglishModel.classify(e, "gemini-x") }

        val perMinute = classify(QuotaExceededException("Quota exceeded for metric ... Please retry in 23.4s.", null))
        assertTrue(perMinute is GeminiException.Transient && perMinute.rateLimited)
        assertEquals(24_400L, (perMinute as GeminiException.Transient).retryAfterMillis)

        assertTrue(classify(QuotaExceededException("quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier", null)) is GeminiException.DailyQuota)
        assertTrue(classify(InvalidAPIKeyException("API key not valid", null)) is GeminiException.Fatal)
        val noFreeQuota = classify(
            QuotaExceededException("Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-x", null),
        )
        assertTrue(noFreeQuota is GeminiException.ModelUnavailable) // skip it, don't wait for it
        val retired = classify(ServerException("models/gemini-x is not found for API version v1beta", null))
        assertTrue(retired is GeminiException.ModelUnavailable) // the fallback moves on to the next model
        assertTrue((classify(ServerException("429 RESOURCE_EXHAUSTED", null)) as GeminiException.Transient).rateLimited)
        assertTrue(classify(ServerException("503 The model is overloaded", null)) is GeminiException.Transient)
        assertTrue(classify(RuntimeException("wrapped", UnknownHostException("generativelanguage.googleapis.com"))) is GeminiException.Transient)
        assertTrue(classify(IOException("connection reset")) is GeminiException.Transient)
        assertNull((classify(ServerException("500 internal", null)) as GeminiException.Transient).retryAfterMillis)
    }
}
