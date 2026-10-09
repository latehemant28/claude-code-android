package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TargetLanguage
import com.example.hinglishpdf.data.translate.TranslationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HinglishPromptTest {

    private fun unit(kind: BlockKind, text: String, level: Int = 0, marker: String = "", cont: Boolean = false) =
        TranslationUnit(0, text, kind, level, marker, cont)

    private val units = listOf(
        unit(BlockKind.HEADING, "Getting started", level = 2),
        unit(BlockKind.BULLET, "Open the app", level = 0),
        unit(BlockKind.BULLET, "Tap settings", level = 1),
        unit(BlockKind.NUMBERED, "Save the file", marker = "3."),
        unit(BlockKind.PARAGRAPH, "It is important."),
    )

    @Test
    fun `prompt carries the guidelines, the page instruction and one tagged line per block`() {
        val prompt = HinglishPrompt.build(units, TargetLanguage.HINGLISH)
        assertTrue(prompt.contains("Do NOT use the Devanagari script"))
        assertTrue(prompt.contains("into Roman Hindi (e.g., kaam, lekin, zaroori, samajh)"))
        assertTrue(
            prompt.contains(
                "Translate the following text into natural, conversational Hinglish using ONLY the " +
                    "Latin/English alphabet. Keep technical terms in English. Do not output Devanagari " +
                    "script. Preserve all paragraphs, bullet points, and line breaks exactly as they " +
                    "appear in the source text. Output ONLY the translated text:\n[1] ## Getting started\n",
            ),
        )
        assertTrue(prompt.contains("[1] ## Getting started\n"))
        assertTrue(prompt.contains("[2] - Open the app\n"))
        assertTrue(prompt.contains("[3]   - Tap settings\n"))
        assertTrue(prompt.contains("[4] 3. Save the file\n"))
        assertTrue(prompt.contains("[5] It is important.\n"))
    }

    @Test
    fun `minglish prompt asks for Roman Marathi`() {
        val prompt = HinglishPrompt.build(units, TargetLanguage.MINGLISH)
        assertTrue(prompt.contains("into Roman Marathi"))
        assertTrue(prompt.contains("natural, conversational Minglish using ONLY"))
        assertTrue(prompt.contains("karnyasathi"))
    }

    @Test
    fun `answer is mapped back by ID with echoed markers stripped`() {
        val raw = """
            Here is the translation:
            [1] ## Shuru karna
            [2] - App kholo
            [3]   * Settings par tap karo
            [4] 3. File save karo
            [5] **Yeh zaroori hai.**
        """.trimIndent()
        assertEquals(
            listOf("Shuru karna", "App kholo", "Settings par tap karo", "File save karo", "Yeh zaroori hai."),
            HinglishPrompt.parse(raw, units),
        )
    }

    @Test
    fun `skipped lines are null and wrapped lines are joined`() {
        val raw = "[1] Shuru karna\n[2] App kholo aur\nphir wait karo\n[5] Yeh zaroori hai."
        assertEquals(
            listOf("Shuru karna", "App kholo aur phir wait karo", null, null, "Yeh zaroori hai."),
            HinglishPrompt.parse(raw, units),
        )
    }

    @Test
    fun `a single untagged answer is accepted`() {
        val one = listOf(unit(BlockKind.PARAGRAPH, "Hello there."))
        assertEquals(listOf("Namaste, kaise ho?"), HinglishPrompt.parse("Namaste, kaise ho?\n", one))
    }

    @Test
    fun `devanagari is detected`() {
        assertTrue(HinglishPrompt.containsDevanagari("yeh नमस्ते hai"))
        assertEquals(false, HinglishPrompt.containsDevanagari("yeh namaste hai"))
    }
}
