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

    /** The system prompt exactly as specified, with the chunk in place of the placeholder. */
    private val specified = """You are a highly accurate English to Hinglish translator. You only translate the text inside the <input> tags. Do not repeat the examples. 

<rules>
1. Translate to natural, modern Hinglish (Hindi in Roman script).
2. Keep it casual like a WhatsApp chat between modern urban Indians.
3. Keep English words for technical terms, concepts, verbs, or common objects (e.g., 'discussion', 'trauma', 'system').
4. Never use pure Hindi/Devanagari words like 'samavesh' or 'vyatirikta'.
5. Output ONLY the final translation. Do not add any extra text or repeat the prompt.
</rules>

<examples>
English: Let's catch up later to finalize the project details.
Hinglish: Baad mein catch up karte hain taaki project details finalize kar sakein.
</examples>

Translate the following text:
<input>
[INSERT TEXT CHUNK HERE]
</input>"""

    @Test
    fun `system prompt is embedded exactly`() {
        assertEquals(specified, HinglishPrompt.SYSTEM_PROMPT)
    }

    @Test
    fun `chunk goes in as markdown with the original structure`() {
        val chunk = "## Getting started\n\nFirst, we need to plan.\n\n- Open the app\n\n  - Tap settings\n\n3. Save the file"
        assertEquals(specified.replace("[INSERT TEXT CHUNK HERE]", chunk), HinglishPrompt.build(units))
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
