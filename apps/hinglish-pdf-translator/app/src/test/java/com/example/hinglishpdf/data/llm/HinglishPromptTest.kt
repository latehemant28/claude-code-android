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

    /** The system prompt exactly as specified, with the micro-chunk in place of the placeholder. */
    private val specified = """You are an expert at translating English to natural, modern conversational Hinglish (Hindi written in Roman script). 

CORE RULES:
- Do NOT use highly formal or pure Hindi words (like 'samavesh', 'vyatirikta', 'prabhavshali').
- Keep it casual, modern, and easy to read, exactly like a WhatsApp chat between modern urban Indians.
- Keep English words for technical terms, concepts, verbs, or common objects (e.g., 'discussion', 'points', 'exception', 'philosophy', 'system', 'optimize').
- Strictly preserve the original document's formatting, paragraphs, and bullet points.
- Output ONLY the translated text without any extra conversational filler.

MODERN HINGLISH EXAMPLES:
English: First, we need to plan the discussion points. You believe people can change.
Hinglish: Pehle, hume discussion ke points plan karne honge. Aapko lagta hai ki log badal sakte hain.

English: The system architecture is quite complex, but we can optimize the database queries easily.
Hinglish: System architecture thoda complex hai, lekin hum database queries ko easily optimize kar sakte hain.

English: Let's catch up later to finalize the project details and fix the bugs.
Hinglish: Baad mein catch up karte hain taaki project details finalize kar sakein aur bugs fix kar dein.

Translate this text in the exact same casual style:
[INSERT MICRO-CHUNK HERE]"""

    @Test
    fun `system prompt is embedded exactly`() {
        assertEquals(specified, HinglishPrompt.SYSTEM_PROMPT)
    }

    @Test
    fun `micro-chunk goes in as markdown with the original structure`() {
        val chunk = "## Getting started\n\nFirst, we need to plan.\n\n- Open the app\n\n  - Tap settings\n\n3. Save the file"
        assertEquals(specified.replace("[INSERT MICRO-CHUNK HERE]", chunk), HinglishPrompt.build(units))
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
