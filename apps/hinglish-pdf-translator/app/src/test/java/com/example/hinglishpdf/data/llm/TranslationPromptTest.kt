package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPromptTest {

    private fun unit(kind: BlockKind, text: String, level: Int = 0, marker: String = "", cont: Boolean = false) =
        TranslationUnit(0, text, kind, level, marker, cont)

    private val units = listOf(
        unit(BlockKind.HEADING, "Getting started", level = 2),
        unit(BlockKind.PARAGRAPH, "First, we need to plan."),
        unit(BlockKind.BULLET, "Open the app", level = 0),
        unit(BlockKind.BULLET, "Tap settings", level = 1),
        unit(BlockKind.NUMBERED, "Save the file", marker = "3."),
    )

    /** The prompt exactly as specified (copied from the request, trailing spaces included). */
    private val specified =
        "You are a master literary and context-aware translator. \n" +
        "Translate the following text from {sourceLanguage} to {targetLanguage}.\n" +
        "\n" +
        "CRITICAL TONE & STYLE RULES:\n" +
        "1. CONTEXT FIRST, TRANSLATE SECOND: Before translating, internally analyze the entire paragraph to grasp the underlying meaning, philosophy, and context. Ensure the core essence of the message is preserved.\n" +
        "2. MODERN & CONVERSATIONAL: Do NOT use rigid, archaic, or overly formal textbook vocabulary (e.g., avoid pure formal Sanskritized Hindi; avoid archaic Spanish). \n" +
        "3. NATURAL FLOW: Translate the text using contemporary, everyday language—exactly how educated native speakers of {targetLanguage} converse in modern times.\n" +
        "4. EMOTION SENSE-FOR-SENSE: Translate sense-for-sense, not word-for-word. Adapt idioms and humor so they make sense in {targetLanguage} without losing the original meaning.\n" +
        "\n" +
        "FORMATTING RULES:\n" +
        "1. STRICT RETENTION: Maintain all original line breaks, bullet points, paragraphs, and markdown.\n" +
        "2. DO NOT TRANSLATE TAGS: Never translate character names, speaker labels, or structural tags. Leave them in their original script.\n" +
        "3. COMPLETENESS: Do not skip or summarize any part of the text. Return only the translated text, with zero conversational filler from your side."

    @Test
    fun `the prompt template is embedded exactly`() {
        assertEquals(specified, TranslationPrompt.TEMPLATE)
    }

    @Test
    fun `the languages are filled in`() {
        val prompt = TranslationPrompt.system(Language.ENGLISH, Language.SPANISH)
        // The specified prompt, word for word, then the output contract.
        assertTrue(prompt.startsWith(specified.replace("{sourceLanguage}", "English").replace("{targetLanguage}", "Spanish") + "\n\nOUTPUT CONTRACT:"))
        assertTrue(prompt.contains("Translate every heading, sentence, list item and quotation into Spanish"))
        assertTrue(!prompt.contains("IMPORTANT: An earlier answer"))
        assertTrue(TranslationPrompt.system(Language.ENGLISH, Language.SPANISH, strict = true).endsWith("answer with the same blocks."))
        assertTrue(prompt.contains("Translate the following text from English to Spanish."))
        assertTrue(prompt.contains("educated native speakers of Spanish converse"))
        assertTrue(!prompt.contains("{"))

        val auto = TranslationPrompt.system(Language.AUTO_DETECT, Language.HINDI)
        assertTrue(auto.contains("Translate the following text from its original language (detect it automatically) to Hindi."))
    }

    @Test
    fun `a target-language label is removed`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "See you later."))
        assertEquals(listOf("Hasta luego."), TranslationPrompt.parse("Spanish: Hasta luego.", one, Language.SPANISH))
        assertEquals(listOf("Hasta luego."), TranslationPrompt.parse("Español: Hasta luego.", one, Language.SPANISH))
        assertEquals(listOf("À plus tard."), TranslationPrompt.parse("Translation: À plus tard.", one, Language.FRENCH))
    }

    @Test
    fun `the request is just the chunk, as markdown with the original structure`() {
        val chunk = "## Getting started\n\nFirst, we need to plan.\n\n- Open the app\n\n  - Tap settings\n\n3. Save the file"
        assertEquals(chunk, TranslationPrompt.build(units))
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
            TranslationPrompt.parse(raw, units),
        )
    }

    @Test
    fun `echoed input tags and a code fence are removed`() {
        val raw = "```\n<input>\n## Shuru karte hain\n\nPehle, hume plan karna hoga.\n\n- App open karo\n\n  - Settings pe tap karo\n\n3. File save kar do\n</input>\n```"
        assertEquals(
            listOf("Shuru karte hain", "Pehle, hume plan karna hoga.", "App open karo", "Settings pe tap karo", "File save kar do"),
            TranslationPrompt.parse(raw, units),
        )
    }

    @Test
    fun `a style-example label and wrapping quotes are removed, real quotations kept`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "A young man went to visit the philosopher."))
        assertEquals(
            listOf("Ek naujawan philosopher se milne gaya."),
            TranslationPrompt.parse("Good Hinglish: \"Ek naujawan philosopher se milne gaya.\"", one),
        )
        val quoted = listOf(unit(BlockKind.PARAGRAPH, "\"Wait,\" she said."))
        assertEquals(listOf("\"Ruko,\" usne kaha."), TranslationPrompt.parse("\"Ruko,\" usne kaha.", quoted))
    }

    @Test
    fun `a merged or split answer is not guessed at`() {
        val raw = "## Shuru karte hain\n\nPehle plan karna hoga. App open karo aur settings pe tap karo.\n\n3. File save karo"
        assertEquals(List(5) { null }, TranslationPrompt.parse(raw, units))
    }

    @Test
    fun `a single block gets the whole answer, even across lines`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "Let's catch up later."))
        assertEquals(
            listOf("Baad mein catch up karte hain, theek hai?"),
            TranslationPrompt.parse("Here is the translation:\nBaad mein catch up karte hain,\ntheek hai?", one),
        )
    }

    @Test
    fun `a "Modern Hindi" label is removed but speaker labels stay`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "YOUTH: I don't believe it."))
        assertEquals(
            listOf("YOUTH: मुझे इस पर यकीन नहीं है।"),
            TranslationPrompt.parse("Modern Hindi: YOUTH: मुझे इस पर यकीन नहीं है।", one),
        )
    }
}
