package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishPromptTest {

    private fun unit(kind: BlockKind, text: String, level: Int = 0, marker: String = "", cont: Boolean = false) =
        TranslationUnit(0, text, kind, level, marker, cont)

    private val units = listOf(
        unit(BlockKind.HEADING, "Getting started", level = 2),
        unit(BlockKind.PARAGRAPH, "First, we need to plan."),
        unit(BlockKind.BULLET, "Open the app", level = 0),
        unit(BlockKind.BULLET, "Tap settings", level = 1),
        unit(BlockKind.NUMBERED, "Save the file", marker = "3."),
    )

    /** The system prompt as specified, plus the </examples> line its text was missing. */
    private val specified = "You are an expert, context-aware translator translating English books into natural, conversational Hindi using the Devanagari script (हिंदी). \n" +
        "\n" +
        "<rules>\n" +
        "1. Script & Tone: Use the Devanagari script. Keep it casual, modern, and easy to read, exactly like how modern urban Indians speak.\n" +
        "2. Context-Awareness (Crucial): Analyze the genre and tone of the text. Automatically adapt the pronouns (आप vs तुम) and style based on the context (e.g., use 'आप' for philosophical/respectful dialogues, and 'तुम' for friendly/casual fiction).\n" +
        "3. Vocabulary: Do NOT use highly formal, pure, or academic Hindi words (avoid 'विकल्प', 'निर्णय', 'समावेश'). Use common English words written in Devanagari (e.g., 'ऑप्शन', 'डिसाइड', 'सिस्टम', 'प्लान'). You can also leave highly technical words in the English script.\n" +
        "4. Formatting & Labels: Maintain exact formatting, paragraphs, and bullet points. Do NOT translate speaker labels, character names, or structural tags (e.g., keep 'YOUTH:', 'PHILOSOPHER:', 'Chapter 1' exactly as they are in English).\n" +
        "5. Output: Output ONLY the translated text. Never add conversational filler, introductions, or explanations.\n" +
        "</rules>\n" +
        "\n" +
        "<examples>\n" +
        "English: Believe me, he had no other option, so he decided to plan this.\n" +
        "Modern Hindi: मेरा यकीन मानिए, उसके पास कोई और ऑप्शन नहीं था, इसलिए उसने यह प्लान डिसाइड किया।\n" +
        "\n" +
        "English: The system architecture is quite complex, but we can optimize the database queries easily.\n" +
        "Modern Hindi: सिस्टम आर्किटेक्चर थोड़ा कॉम्प्लेक्स है, लेकिन हम डेटाबेस क्वेरीज को आसानी से ऑप्टिमाइज़ कर सकते हैं।\n" +
        "</examples>"

    @Test
    fun `system prompt is embedded exactly`() {
        assertEquals(specified, HinglishPrompt.SYSTEM_PROMPT)
    }

    @Test
    fun `the request is just the chunk, as markdown with the original structure`() {
        val chunk = "## Getting started\n\nFirst, we need to plan.\n\n- Open the app\n\n  - Tap settings\n\n3. Save the file"
        assertEquals(chunk, HinglishPrompt.build(units))
    }

    @Test
    fun `answer is mapped back block by block, markers and labels stripped`() {
        val raw = """
            Hinglish: ## Shuru karte hain

            Pehle, hume plan karna hoga.

            - App open karo
              * Settings pe tap karo
            3. File save kar do
        """.trimIndent()
        assertEquals(
            listOf("Shuru karte hain", "Pehle, hume plan karna hoga.", "App open karo", "Settings pe tap karo", "File save kar do"),
            HinglishPrompt.parse(raw, units),
        )
    }

    @Test
    fun `echoed input tags and a code fence are removed`() {
        val raw = "```\n<input>\n## Shuru karte hain\n\nPehle, hume plan karna hoga.\n\n- App open karo\n\n  - Settings pe tap karo\n\n3. File save kar do\n</input>\n```"
        assertEquals(
            listOf("Shuru karte hain", "Pehle, hume plan karna hoga.", "App open karo", "Settings pe tap karo", "File save kar do"),
            HinglishPrompt.parse(raw, units),
        )
    }

    @Test
    fun `a style-example label and wrapping quotes are removed, real quotations kept`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "A young man went to visit the philosopher."))
        assertEquals(
            listOf("Ek naujawan philosopher se milne gaya."),
            HinglishPrompt.parse("Good Hinglish: \"Ek naujawan philosopher se milne gaya.\"", one),
        )
        val quoted = listOf(unit(BlockKind.PARAGRAPH, "\"Wait,\" she said."))
        assertEquals(listOf("\"Ruko,\" usne kaha."), HinglishPrompt.parse("\"Ruko,\" usne kaha.", quoted))
    }

    @Test
    fun `a merged or split answer is not guessed at`() {
        val raw = "## Shuru karte hain\n\nPehle plan karna hoga. App open karo aur settings pe tap karo.\n\n3. File save karo"
        assertEquals(List(5) { null }, HinglishPrompt.parse(raw, units))
    }

    @Test
    fun `a single block gets the whole answer, even across lines`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "Let's catch up later."))
        assertEquals(
            listOf("Baad mein catch up karte hain, theek hai?"),
            HinglishPrompt.parse("Here is the translation:\nBaad mein catch up karte hain,\ntheek hai?", one),
        )
    }

    @Test
    fun `a "Modern Hindi" label is removed but speaker labels stay`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "YOUTH: I don't believe it."))
        assertEquals(
            listOf("YOUTH: मुझे इस पर यकीन नहीं है।"),
            HinglishPrompt.parse("Modern Hindi: YOUTH: मुझे इस पर यकीन नहीं है।", one),
        )
    }
}
