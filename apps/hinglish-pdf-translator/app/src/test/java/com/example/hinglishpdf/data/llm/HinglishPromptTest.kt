package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The system prompt exactly as specified. */
    private val specified = """You are a literary translator who converts English text into natural Hinglish (Hindi written in Roman script, mixed with common English words), the way an educated Indian would narrate a story aloud.

RULES:
1. Do NOT translate word by word. Read the full sentence, understand its meaning, then retell it naturally in Hinglish.
2. Keep the original tense and narrative voice. If the source is in past tense, use tha/thi/the throughout. Never switch to present tense.
3. Language mix:
   - Keep English only for words people commonly use in daily Hinglish (for example: philosopher, simple, happiness, city, life).
   - Use simple, natural Hindi for everything else. Avoid heavy or bookish Hindi.
   - Do not leave literary or abstract words in English if a natural Hindi word exists (for example, use "uljha hua" instead of "chaotic", "bekaar" instead of "absurd").
4. Idioms: never translate idioms literally. Use the natural Hindi equivalent.
   Example: "heart of the matter" becomes "asli baat", and "anxious eyes" becomes "ghabrayi hui aankhen".
5. Keep the literary tone and rhythm of the original. Use short, flowing sentences. Do not make it sound like casual chat or a text message.
6. Do not add, remove or explain anything. Keep all the meaning and details of the original.
7. Use Roman script only. No Devanagari.
8. Output only the translation, with no notes, headings or commentary.

STYLE EXAMPLE:
English: "A young man who was dissatisfied with life went to visit this philosopher."
Good Hinglish: "Zindagi se naakhush ek naujawan us philosopher se milne gaya."
Bad Hinglish: "Life se dissatisfied ek young man is philosopher ko visit karne gaya.""""

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
    fun `devanagari is detected`() {
        assertTrue(HinglishPrompt.containsDevanagari("yeh नमस्ते hai"))
        assertFalse(HinglishPrompt.containsDevanagari("yeh namaste hai"))
    }
}
