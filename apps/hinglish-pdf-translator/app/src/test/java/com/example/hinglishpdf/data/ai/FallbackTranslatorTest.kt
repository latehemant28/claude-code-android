package com.example.hinglishpdf.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackTranslatorTest {

    private var clock = 0L
    private val calls = mutableListOf<String>()

    /** Each model answers "<model>: <chunk>" unless [failures] says otherwise for that call. */
    private fun engine(
        models: List<String> = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash-lite", "gemini-3.8-flash"),
        failures: (model: String) -> TranslatorException? = { null },
        failMidStream: TranslatorException? = null,
    ) = FallbackTranslator(models, "Gemini", now = { clock }) { model ->
        AITranslator { chunk ->
            flow {
                calls += model
                if (failMidStream != null) {
                    emit("half ")
                    throw failMidStream
                }
                failures(model)?.let { throw it }
                emit("$model: $chunk")
            }
        }
    }

    private val retired = { m: String -> if (m.startsWith("gemini-1.5")) TranslatorException.ModelUnavailable(m) else null }

    @Test
    fun `retired models are skipped silently and remembered`() = runTest {
        val gemini = engine(failures = retired)
        assertNull(gemini.activeModel.value)

        assertEquals(listOf("gemini-3.5-flash-lite: page 1"), gemini.translate("page 1").toList())
        assertEquals("gemini-3.5-flash-lite", gemini.activeModel.value)
        assertEquals(listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash-lite"), calls)

        calls.clear()
        gemini.translate("page 2").toList()
        assertEquals(listOf("gemini-3.5-flash-lite"), calls) // no more requests to the dead models
    }

    @Test
    fun `a rate-limited model is swapped, then preferred again once it may retry`() = runTest {
        var limited = true
        val gemini = engine(failures = { m ->
            retired(m) ?: if (m == "gemini-3.5-flash-lite" && limited) {
                TranslatorException.Transient("429", retryAfterMillis = 30_000, rateLimited = true)
            } else {
                null
            }
        })
        assertEquals(listOf("gemini-3.8-flash: a"), gemini.translate("a").toList())

        limited = false
        clock += 10_000
        assertEquals(listOf("gemini-3.8-flash: b"), gemini.translate("b").toList()) // still cooling down
        clock += 25_000
        assertEquals(listOf("gemini-3.5-flash-lite: c"), gemini.translate("c").toList()) // back to the preferred one
    }

    @Test
    fun `daily quota on every live model stops the book`() = runTest {
        val gemini = engine(failures = { m -> retired(m) ?: TranslatorException.DailyQuota() })
        try {
            gemini.translate("x").toList()
            fail("expected DailyQuota")
        } catch (e: TranslatorException.DailyQuota) {
            // expected: the book pauses and resumes later
        }
    }

    @Test
    fun `when every model is rate-limited, wait for the first one to free up`() = runTest {
        val gemini = engine(failures = { m ->
            retired(m) ?: TranslatorException.Transient(
                "429",
                retryAfterMillis = if (m == "gemini-3.8-flash") 5_000 else 20_000,
                rateLimited = true,
            )
        })
        try {
            gemini.translate("x").toList()
            fail("expected Transient")
        } catch (e: TranslatorException.Transient) {
            assertTrue(e.rateLimited)
            assertEquals(5_000L, e.retryAfterMillis)
        }
    }

    @Test
    fun `a list of only retired models fails clearly`() = runTest {
        val gemini = engine(models = listOf("gemini-1.5-flash", "gemini-1.5-pro"), failures = retired)
        try {
            gemini.translate("x").toList()
            fail("expected Fatal")
        } catch (e: TranslatorException.Fatal) {
            assertTrue(e.message!!.contains("gemini-1.5-flash, gemini-1.5-pro"))
        }
    }

    @Test
    fun `problems another model cannot fix are not hidden`() = runTest {
        val gemini = engine(failures = { TranslatorException.Fatal("Gemini rejected the API key.") })
        try {
            gemini.translate("x").toList()
            fail("expected Fatal")
        } catch (e: TranslatorException.Fatal) {
            assertEquals(listOf("gemini-1.5-flash"), calls) // no pointless fallback
        }
    }

    @Test
    fun `a model that fails mid-answer is retried cleanly, not spliced with another`() = runTest {
        val gemini = engine(failMidStream = TranslatorException.Transient("connection reset"))
        val received = mutableListOf<String>()
        try {
            gemini.translate("x").collect { received += it }
            fail("expected Transient")
        } catch (e: TranslatorException.Transient) {
            assertEquals(listOf("half "), received)
            assertEquals(listOf("gemini-1.5-flash"), calls)
        }
    }

    @Test
    fun `a refusal mid-answer stays a refusal, so the text is kept in English`() = runTest {
        val gemini = engine(failMidStream = TranslatorException.Blocked("SAFETY"))
        try {
            gemini.translate("x").toList()
            fail("expected Blocked")
        } catch (e: TranslatorException.Blocked) {
            assertEquals(listOf("gemini-1.5-flash"), calls)
        }
    }

    @Test
    fun `a model typed in the app is tried first`() {
        assertEquals(listOf("gpt-x", "gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini"), AIProvider.OPENAI.models(" gpt-x "))
        assertEquals(AIProvider.OPENAI.defaultModels, AIProvider.OPENAI.models(""))
        assertEquals(AIProvider.GROQ.defaultModels, AIProvider.GROQ.models("llama-3.3-70b-versatile")) // no duplicate
    }

    @Test
    fun `each provider gets its own translator strategy`() {
        assertTrue(AIProvider.GEMINI.createTranslator("k", "m") is GeminiTranslator)
        assertTrue(AIProvider.OPENAI.createTranslator("k", "m") is OpenAITranslator)
        assertTrue(AIProvider.ANTHROPIC.createTranslator("k", "m") is AnthropicTranslator)
        assertTrue(AIProvider.GROQ.createTranslator("k", "m") is GroqTranslator)
        assertTrue(AIProvider.SARVAM.createTranslator("k", "m") is SarvamTranslator)
        assertTrue(AIProvider.OPENROUTER.createTranslator("k", "m") is OpenRouterTranslator)
        assertTrue(AIProvider.CEREBRAS.createTranslator("k", "m") is CerebrasTranslator)
        assertTrue(AIProvider.MISTRAL.createTranslator("k", "m") is MistralTranslator)
        assertTrue(AIProvider.DEEPSEEK.createTranslator("k", "m") is DeepSeekTranslator)
        assertTrue(AIProvider.XAI.createTranslator("k", "m") is XAITranslator)
        assertTrue(AIProvider.COHERE.createTranslator("k", "m") is CohereTranslator)
        assertTrue(AIProvider.CUSTOM.createTranslator("k", "m", "https://example.com/v1") is CustomTranslator)
        // Every provider but Custom has models to fall back on, and a page to get a key.
        AIProvider.entries.filter { it != AIProvider.CUSTOM }.forEach {
            assertTrue(it.name, it.defaultModels.isNotEmpty() && it.keyPageUrl!!.startsWith("https://"))
        }
        assertEquals(listOf("my-model"), AIProvider.CUSTOM.models("my-model"))
    }
}
