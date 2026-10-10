package com.example.hinglishpdf.pipeline.segment

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSegmenterTest {

    private fun split(text: String) = SentenceSegmenter.split(text).map { it.text }

    @Test
    fun `plain sentences are split at their ends`() {
        assertEquals(
            listOf("It was cold.", "Was it?", "Yes!", "Then it snowed…", "Everyone stayed in."),
            split("It was cold. Was it? Yes! Then it snowed… Everyone stayed in."),
        )
    }

    @Test
    fun `the whitespace between sentences is kept for rejoining`() {
        val sentences = SentenceSegmenter.split("One.  Two.\nThree.")
        assertEquals(listOf("  ", "\n", ""), sentences.map { it.separator })
        val rejoined = sentences.joinToString("") { it.text + it.separator }
        assertEquals("One.  Two.\nThree.", rejoined)
    }

    @Test
    fun `abbreviations and initials do not end a sentence`() {
        assertEquals(
            listOf("Mr. Smith met Dr. Rao at 5 p.m. on Main St. near the U.S. embassy.", "They talked."),
            split("Mr. Smith met Dr. Rao at 5 p.m. on Main St. near the U.S. embassy. They talked."),
        )
        assertEquals(listOf("J. K. Rowling wrote it, e.g. in cafés, i.e. anywhere."), split("J. K. Rowling wrote it, e.g. in cafés, i.e. anywhere."))
        assertEquals(listOf("See Fig. 3 and No. 12 on p. 45.", "Done."), split("See Fig. 3 and No. 12 on p. 45. Done."))
        // "no." without a number after it is an ordinary word.
        assertEquals(listOf("She said no.", "Then she left."), split("She said no. Then she left."))
    }

    @Test
    fun `decimals, versions and addresses are not cut`() {
        assertEquals(
            listOf("Pi is 3.14 and the version is 1.5.2 at example.com today.", "Next."),
            split("Pi is 3.14 and the version is 1.5.2 at example.com today. Next."),
        )
    }

    @Test
    fun `quoted dialogue stays whole`() {
        assertEquals(listOf("\"Stop. Wait here,\" she said."), split("\"Stop. Wait here,\" she said."))
        assertEquals(listOf("“I am here. Come in.”", "He sat down."), split("“I am here. Come in.” He sat down."))
        assertEquals(listOf("“Wait!” he cried.", "Nobody did."), split("“Wait!” he cried. Nobody did."))
        assertEquals(listOf("‘Don’t. Please don’t,’ she said."), split("‘Don’t. Please don’t,’ she said."))
    }

    @Test
    fun `a lower-case continuation is not a new sentence`() {
        assertEquals(listOf("Wait... what was that?", "A cat."), split("Wait... what was that? A cat."))
    }

    @Test
    fun `devanagari sentences end at the danda`() {
        assertEquals(listOf("वह घर गया।", "फिर वह सो गया।"), split("वह घर गया। फिर वह सो गया।"))
    }

    @Test
    fun `placeholders stay with their sentence and are never cut`() {
        assertEquals(
            listOf("It was {1}very{/1} cold.[[SUP_2]]", "{3}Then{/3} it snowed."),
            split("It was {1}very{/1} cold.[[SUP_2]] {3}Then{/3} it snowed."),
        )
        // A cut inside {1}...{/1} would leave both halves unbalanced: only the one after {/1} is made.
        assertEquals(listOf("{1}One. Two.{/1}", "Three."), split("{1}One. Two.{/1} Three."))
        assertEquals(listOf("{1}One. Two. Three{/1}"), split("{1}One. Two. Three{/1}"))
        // A closing tag after the full stop belongs to the sentence it closes.
        assertEquals(listOf("{1}Bold sentence.{/1}", "Next one."), split("{1}Bold sentence.{/1} Next one."))
    }

    @Test
    fun `a single sentence or blank text`() {
        assertEquals(listOf("No full stop at the end"), split("  No full stop at the end  "))
        assertEquals(emptyList<String>(), split("   "))
    }
}
