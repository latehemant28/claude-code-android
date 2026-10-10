package com.example.hinglishpdf.ui.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every error the app produces turns into guidance with a way forward. */
class ErrorGuideTest {

    private fun action(message: String) = ErrorGuide.of(message).primary

    @Test
    fun `the app's own error messages map to the right next step`() {
        assertEquals(GuideAction.FIX_KEY, action("No API key for Google Gemini. Paste one in the app, then tap Resume."))
        assertEquals(GuideAction.FIX_KEY, action("Gemini rejected the API key (API key not valid). Check the key in the app or in local.properties."))
        assertEquals(GuideAction.FIX_KEY, action("Sarvam AI rejected the API key (Invalid or missing authentication credentials). Check the key you saved."))
        assertEquals(GuideAction.ADD_CREDIT, action("Your OpenAI account has no credit left (insufficient_quota). Add billing credit, then tap Resume."))
        assertEquals(GuideAction.CHANGE_PROVIDER, action("The free Gemini daily quota is used up. Tap Resume tomorrow; the book continues from the same page."))
        assertEquals(GuideAction.CHANGE_PROVIDER, action("The daily quota of this API key is used up. Tap Resume later; the book continues from the same page."))
        assertEquals(GuideAction.SET_MODEL, action("None of these Groq models are available to this API key: a, b. They are retired, or this key has no quota for them (\"limit: 0\"). Check the key, or type a current model name in the Model field."))
        assertEquals(GuideAction.CHANGE_PROVIDER, action("The Gemini API is not available in your country or region."))
        assertEquals(GuideAction.ACCEPT_TERMS, action("Accept the Terms of Use in the app first (menu ⋮ → Terms of Use), then tap Resume."))
        assertEquals(GuideAction.CHOOSE_OTHER_FILE, action("This PDF is scanned: 12 of 14 pages are pictures of text, with no text to translate. It needs text recognition (OCR), which is coming in a later update."))
        assertEquals(GuideAction.CHOOSE_OTHER_FILE, action("This PDF is password-protected."))
        assertEquals(GuideAction.CHOOSE_OTHER_FILE, action("Only PDF and EPUB files are supported."))
        assertEquals(GuideAction.SAVE_AGAIN, action("Could not write to Downloads."))
        assertEquals(GuideAction.RESUME, action("Gemini is busy (overloaded). Gave up after 8 retries; check the connection and tap Resume."))
        assertEquals(GuideAction.RESUME, action("No internet connection"))
        assertEquals(GuideAction.FIX_KEY, action("The Custom AI address must start with https:// (for example https://example.com/v1)."))
    }

    @Test
    fun `unknown errors still offer a way on, and keep the details`() {
        val guide = ErrorGuide.of("Groq error 418: I'm a teapot")
        assertEquals(GuideAction.RESUME, guide.primary)
        assertEquals(GuideAction.CHANGE_PROVIDER, guide.secondary)
        assertEquals("Groq error 418: I'm a teapot", guide.detail)
        assertTrue(guide.keepsProgress)
        assertEquals(GuideAction.RESUME, ErrorGuide.of(null).primary)
    }

    @Test
    fun `guidance never blames the user and says when progress is kept`() {
        val messages = listOf("No API key for X", "X rejected the API key", "no credit left", "daily quota", "No internet connection", "weird")
        for (m in messages) {
            val g = ErrorGuide.of(m)
            assertFalse(g.title, g.title.contains("you failed", ignoreCase = true) || g.title.contains("invalid input", ignoreCase = true))
            assertTrue(g.keepsProgress)
        }
        assertFalse(ErrorGuide.of("This PDF is scanned").keepsProgress) // nothing to keep: a new file is needed
    }
}
