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

    /** The prompt exactly as specified (copied from the request, trailing space included). */
    private val specified =
        "You are an expert, context-aware translator translating {Source_Language} books into natural, conversational {Target_Language} using the {Target_Script} script. \n" +
        "\n" +
        "<rules>\n" +
        "1. Script & Tone: Use the {Target_Script} script. Keep it natural, modern, and easy to read, exactly like how modern native speakers communicate in daily life.\n" +
        "2. Context-Awareness (Crucial): Analyze the genre and tone of the text. Automatically adapt the pronouns, honorifics, and formality style based on the context (e.g., use formal/respectful phrasing for philosophical dialogues, and friendly/casual phrasing for modern fiction).\n" +
        "3. Vocabulary: Do NOT use highly archaic, purely academic, or strictly literal translations. Use commonly accepted loan words where appropriate for modern readers. You can leave highly technical or specific global terms in the English script if they lack a natural equivalent.\n" +
        "4. Formatting & Labels: Maintain exact formatting, paragraphs, and bullet points. Do NOT translate speaker labels, character names, or structural tags (e.g., keep 'YOUTH:', 'PHILOSOPHER:', 'Chapter 1' exactly as they are in the original text).\n" +
        "5. Output: Output ONLY the translated text. Never add conversational filler, introductions, or explanations.\n" +
        "</rules>"

    @Test
    fun `the prompt template is embedded exactly`() {
        assertEquals(specified, TranslationPrompt.TEMPLATE)
    }

    @Test
    fun `the languages and the script are filled in`() {
        val prompt = TranslationPrompt.system(Language.ENGLISH, Language.SPANISH)
        val filled = specified.replace("{Source_Language}", "English").replace("{Target_Language}", "Spanish")
            .replace("{Target_Script}", "Latin")
        // The specified prompt, word for word, then the output contract.
        assertTrue(prompt.startsWith(filled + "\n\nOUTPUT CONTRACT:"))
        assertTrue(prompt.contains("translating English books into natural, conversational Spanish using the Latin script."))
        assertTrue(prompt.contains("Translate every heading, sentence, list item and quotation into Spanish"))
        assertTrue(!prompt.contains("IMPORTANT: An earlier answer"))
        assertTrue(TranslationPrompt.system(Language.ENGLISH, Language.SPANISH, strict = true).endsWith("answer with the same blocks."))
        assertTrue(!prompt.contains("{"))

        val hindi = TranslationPrompt.system(Language.AUTO_DETECT, Language.HINDI)
        assertTrue(hindi.contains("translating foreign-language books into natural, conversational Hindi using the Devanagari script."))
        assertTrue(hindi.contains("1. Script & Tone: Use the Devanagari script."))
        assertEquals("Gurmukhi", Language.PUNJABI.script)
        assertEquals("Urdu (Perso-Arabic Nastaliq)", Language.URDU.script)
        assertEquals("Hangul", Language.KOREAN.script)
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
