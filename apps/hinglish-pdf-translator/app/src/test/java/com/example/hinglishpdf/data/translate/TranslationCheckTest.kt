package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.translate.TranslationCheck.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCheckTest {

    private val source = "The first example comes from Rome, where the senate met every morning to argue about the war."

    private fun check(output: String?, to: Language = Language.HINDI, text: String = source) =
        TranslationCheck.problem(text, output, Language.ENGLISH, to)

    @Test
    fun `a proper translation passes`() {
        assertNull(check("पहला उदाहरण रोम से आता है, जहाँ सीनेट हर सुबह युद्ध पर बहस करने के लिए मिलती थी।"))
        assertNull(check("El primer ejemplo viene de Roma, donde el senado se reunía cada mañana para discutir la guerra.", Language.SPANISH))
        assertNull(check("最初の例はローマです。元老院は毎朝戦争について議論した。", Language.JAPANESE))
    }

    @Test
    fun `nothing back is missing`() {
        assertEquals(Problem.MISSING, check(null))
        assertEquals(Problem.MISSING, check("  "))
    }

    @Test
    fun `english left in place is caught, wholly or mid-paragraph`() {
        assertEquals(Problem.UNTRANSLATED, check(source))
        assertEquals(Problem.UNTRANSLATED, check(source, Language.SPANISH))
        // Translation that falls back to the original half-way through.
        assertEquals(
            Problem.UNTRANSLATED,
            check("पहला उदाहरण रोम से आता है, where the senate met every morning to argue about the war.", Language.SPANISH),
        )
        assertEquals(Problem.UNTRANSLATED, check("पहला उदाहरण: where the senate met every morning to argue about the war."))
    }

    @Test
    fun `an answer that stops part-way is incomplete`() {
        assertEquals(Problem.INCOMPLETE, check("पहला उदाहरण।"))
    }

    @Test
    fun `short blocks such as names and headings are not second-guessed`() {
        assertNull(check("Chapter 3", text = "Chapter 3"))
        assertNull(check("Gandalf और Frodo", text = "Gandalf and Frodo"))
        assertNull(TranslationCheck.problem(source, source, Language.ENGLISH, Language.ENGLISH))
    }
}
