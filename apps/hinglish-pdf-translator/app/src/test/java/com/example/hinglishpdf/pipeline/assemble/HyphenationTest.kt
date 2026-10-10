package com.example.hinglishpdf.pipeline.assemble

import com.example.hinglishpdf.pipeline.PipelineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-source hyphen rule: the book's vocabulary, then standalone words. No bundled dictionary. */
class HyphenationTest {

    private val config = PipelineConfig()

    @Test
    fun `whole word seen in the book joins, even when the continuation stands alone`() {
        val h = Hyphenation.build(sequenceOf("the rainwater ran", "water water everywhere"), config)
        assertTrue(h.joins("rain", "water"))
    }

    @Test
    fun `compound seen mid-line keeps its hyphen`() {
        val h = Hyphenation.build(sequenceOf("a well-known man"), config)
        assertFalse(h.joins("well", "known"))
    }

    @Test
    fun `lower-case continuation that is not a standalone word joins`() {
        assertTrue(Hyphenation.NONE.joins("transla", "tion"))
    }

    @Test
    fun `a continuation the book uses on its own often enough keeps the hyphen`() {
        val h = Hyphenation.build(sequenceOf("time and time again"), config)
        assertFalse(h.joins("half", "time"))
        // Once is not enough (standaloneMinCount = 2).
        assertTrue(Hyphenation.build(sequenceOf("only one time"), config).joins("half", "time"))
    }

    @Test
    fun `standalone words from the config count too, in any language`() {
        val h = Hyphenation.build(emptySequence(), PipelineConfig(commonStandaloneWords = listOf("Known", "बार")))
        assertFalse(h.joins("well", "known"))
        assertFalse(h.joins("एक", "बार"))
    }

    @Test
    fun `an upper-case continuation keeps the hyphen`() {
        assertFalse(Hyphenation.NONE.joins("Anglo", "Saxon"))
    }

    @Test
    fun `fragments at line breaks are not learnt as words`() {
        // "tion" starts a line after "transla-": it must not become a standalone word, nor "transla" a word.
        val h = Hyphenation.build(sequenceOf("a long transla-", "tion of it", "another transla-", "tion here"), config)
        assertTrue(h.joins("transla", "tion"))
    }

    @Test
    fun `join keeps or drops the hyphen in running text`() {
        assertEquals("a translation", Hyphenation.NONE.join("a transla-", "tion"))
        assertEquals("an Anglo-Saxon", Hyphenation.NONE.join("an Anglo-", "Saxon"))
        assertEquals(null, Hyphenation.NONE.join("no hyphen", "here"))
    }
}
