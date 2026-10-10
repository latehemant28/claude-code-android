package com.example.hinglishpdf.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackGeminiModelTest {

    private var clock = 0L
    private val calls = mutableListOf<String>()

    /** Each model answers "<model>: <chunk>" unless [failures] says otherwise for that call. */
    private fun engine(
        models: List<String> = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-3.5-flash-lite", "gemini-3.8-flash"),
        failures: (model: String) -> GeminiException? = { null },
        failMidStream: Boolean = false,
    ) = FallbackGeminiModel(models, now = { clock }) { model ->
        HinglishModel { chunk ->
            flow {
                calls += model
                if (failMidStream) {
                    emit("half ")
                    throw GeminiException.Transient("connection reset")
                }
                failures(model)?.let { throw it }
                emit("$model: $chunk")
            }
        }
    }

    private val retired = { m: String -> if (m.startsWith("gemini-1.5")) GeminiException.ModelUnavailable(m) else null }

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
                GeminiException.Transient("429", retryAfterMillis = 30_000, rateLimited = true)
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
        val gemini = engine(failures = { m -> retired(m) ?: GeminiException.DailyQuota() })
        try {
            gemini.translate("x").toList()
            fail("expected DailyQuota")
        } catch (e: GeminiException.DailyQuota) {
            // expected: the book pauses and resumes later
        }
    }

    @Test
    fun `when every model is rate-limited, wait for the first one to free up`() = runTest {
        val gemini = engine(failures = { m ->
            retired(m) ?: GeminiException.Transient(
                "429",
                retryAfterMillis = if (m == "gemini-3.8-flash") 5_000 else 20_000,
                rateLimited = true,
            )
        })
        try {
            gemini.translate("x").toList()
            fail("expected Transient")
        } catch (e: GeminiException.Transient) {
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
        } catch (e: GeminiException.Fatal) {
            assertTrue(e.message!!.contains("gemini-1.5-flash, gemini-1.5-pro"))
        }
    }

    @Test
    fun `problems another model cannot fix are not hidden`() = runTest {
        val gemini = engine(failures = { GeminiException.Fatal("Gemini rejected the API key.") })
        try {
            gemini.translate("x").toList()
            fail("expected Fatal")
        } catch (e: GeminiException.Fatal) {
            assertEquals(listOf("gemini-1.5-flash"), calls) // no pointless fallback
        }
    }

    @Test
    fun `a model that fails mid-answer is retried cleanly, not spliced with another`() = runTest {
        val gemini = engine(failMidStream = true)
        val received = mutableListOf<String>()
        try {
            gemini.translate("x").collect { received += it }
            fail("expected Transient")
        } catch (e: GeminiException.Transient) {
            assertEquals(listOf("half "), received)
            assertEquals(listOf("gemini-1.5-flash"), calls)
        }
    }
}
