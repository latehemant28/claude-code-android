package com.example.hinglishpdf.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyDetectorTest {

    private val gemini = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K_lMnOpQrStUv" // 39 characters, like real keys
    private val geminiAq = "AQ." + "Ab8RNabcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ"
    private val openAi = "sk-proj-" + "a".repeat(48) + "_B-c"
    private val openAiLegacy = "sk-" + "A1b2".repeat(12)
    private val anthropic = "sk-ant-api03-" + "x".repeat(86) + "-AA"
    private val groq = "gsk_" + "Q".repeat(52)

    @Test
    fun `each provider's key is recognised`() {
        assertEquals(AIProvider.GEMINI to gemini, ApiKeyDetector.detect(gemini, AIProvider.GEMINI))
        assertEquals(AIProvider.GEMINI to geminiAq, ApiKeyDetector.detect(geminiAq, AIProvider.GEMINI))
        assertEquals(AIProvider.OPENAI to openAi, ApiKeyDetector.detect(openAi, AIProvider.OPENAI))
        assertEquals(AIProvider.OPENAI to openAiLegacy, ApiKeyDetector.detect(openAiLegacy, AIProvider.OPENAI))
        assertEquals(AIProvider.ANTHROPIC to anthropic, ApiKeyDetector.detect(anthropic, AIProvider.ANTHROPIC))
        assertEquals(AIProvider.GROQ to groq, ApiKeyDetector.detect(groq, AIProvider.GROQ))
    }

    @Test
    fun `a key for another provider goes to that provider`() {
        assertEquals(AIProvider.GROQ to groq, ApiKeyDetector.detect(groq, AIProvider.GEMINI))
        // An Anthropic key is never mistaken for an OpenAI one, whichever is selected.
        assertEquals(AIProvider.ANTHROPIC to anthropic, ApiKeyDetector.detect(anthropic, AIProvider.OPENAI))
    }

    @Test
    fun `keys are found inside copied text, with spaces or quotes around them`() {
        assertEquals(AIProvider.GEMINI to gemini, ApiKeyDetector.detect("  $gemini\n", AIProvider.GEMINI))
        assertEquals(AIProvider.GROQ to groq, ApiKeyDetector.detect("GROQ_API_KEY=\"$groq\"", AIProvider.GROQ))
    }

    @Test
    fun `ordinary text and near misses are ignored`() {
        assertNull(ApiKeyDetector.detect("Create API key", AIProvider.GEMINI))
        assertNull(ApiKeyDetector.detect("AIzaShort", AIProvider.GEMINI))
        assertNull(ApiKeyDetector.detect(gemini + "EXTRA", AIProvider.GEMINI)) // too long to be a Gemini key
        assertNull(ApiKeyDetector.detect("https://example.com/sk-", AIProvider.OPENAI))
        assertNull(ApiKeyDetector.detect("gsk_tooShort", AIProvider.GROQ))
        assertNull(ApiKeyDetector.detect(null, AIProvider.GROQ))
        assertNull(ApiKeyDetector.detect("x".repeat(10_000) + groq, AIProvider.GROQ)) // not a whole page
    }
}
