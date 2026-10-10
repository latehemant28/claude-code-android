package com.example.hinglishpdf.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtaAndInputTest {

    @Test
    fun `estimates wait for real data, then under-promise`() {
        assertNull(Eta.remainingMillis(doneAtStart = 10, done = 11, total = 100, startedAt = 0, now = 60_000)) // one page: too early
        assertNull(Eta.remainingMillis(10, 13, 100, startedAt = 0, now = 10_000)) // ten seconds: too early
        // 4 pages in 2 minutes = 30 s a page; 86 left = 43 min, padded by 1.3 = 55.9 min.
        val left = Eta.remainingMillis(10, 14, 100, startedAt = 0, now = 120_000)!!
        assertEquals((30_000.0 * 86 * 1.3).toLong(), left)
        assertEquals("About 60 min left", Eta.format(left)) // rounded up to 5 minutes
        assertEquals(0L, Eta.remainingMillis(0, 100, 100, 0, 1))
    }

    @Test
    fun `time left is phrased simply and rounded up`() {
        assertEquals("Estimating time…", Eta.format(null))
        assertEquals("Almost done", Eta.format(0))
        assertEquals("About a minute left", Eta.format(40_000))
        assertEquals("About 4 min left", Eta.format(3 * 60_000 + 1))
        assertEquals("About 25 min left", Eta.format(21 * 60_000L))
        assertEquals("About 1 h 15 min left", Eta.format(62 * 60_000L))
        assertEquals("About 2 h left", Eta.format(120 * 60_000L))
        assertEquals("About 2 days left", Eta.format(30 * 60 * 60_000L))
    }

    @Test
    fun `pasted keys are cleaned instead of rejected`() {
        val key = "AIzaSyD-abcdefghijklmnopqrstuvwx"
        assertEquals(key, InputCleaner.apiKey("  $key \n"))
        assertEquals(key, InputCleaner.apiKey("\"$key\""))
        assertEquals(key, InputCleaner.apiKey("GEMINI_API_KEY=$key"))
        assertEquals(key, InputCleaner.apiKey("api_key: '$key'"))
        assertEquals("sk-proj-123456789012345678901234", InputCleaner.apiKey("Authorization: Bearer sk-proj-1234567890 12345678901234"))
        assertNull(InputCleaner.apiKeyProblem(key))
        assertEquals(true, InputCleaner.apiKeyProblem("short")?.contains("too short"))
        assertEquals(true, InputCleaner.apiKeyProblem("https://aistudio.google.com/app/apikey")?.contains("web address"))
    }

    @Test
    fun `model names and service addresses are fixed up`() {
        assertEquals("gpt-4o-mini", InputCleaner.modelName(" 'gpt-4o-mini' "))
        assertEquals("https://api.together.xyz/v1", InputCleaner.serviceUrl("api.together.xyz/v1"))
        assertEquals("https://api.together.xyz/v1", InputCleaner.serviceUrl("http://api.together.xyz/v1"))
        assertEquals("https://example.com/v1", InputCleaner.serviceUrl(" \"https://example.com/v1\" "))
    }
}
