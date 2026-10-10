package com.example.hinglishpdf.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private fun words(n: Int, prefix: String = "w") = (1..n).joinToString(" ") { "$prefix$it" }

    @Test
    fun `blank text gives no chunks`() {
        assertEquals(emptyList<String>(), TextChunker.chunk("  \n\n \u000C "))
    }

    @Test
    fun `short paragraphs are packed into one chunk and keep their breaks`() {
        val chunks = TextChunker.chunk("First para\nwraps here.\n\nSecond para.", maxWords = 50)
        assertEquals(listOf("First para wraps here.\n\nSecond para."), chunks)
    }

    @Test
    fun `no chunk ever exceeds the word limit`() {
        val text = (1..30).joinToString("\n\n") { p ->
            (1..(p % 7 + 1)).joinToString(" ") { s -> words(s * 5, "p${p}s$s") + "." }
        } + "\n\n" + words(1234, "runon")
        val chunks = TextChunker.chunk(text, maxWords = 100)
        assertTrue(chunks.all { TextChunker.countWords(it) <= 100 })
        // Nothing lost or duplicated.
        assertEquals(TextChunker.countWords(text), chunks.sumOf { TextChunker.countWords(it) })
    }

    @Test
    fun `long paragraph splits on sentence boundaries`() {
        val s1 = words(6, "a") + "."
        val s2 = words(6, "b") + "?"
        val s3 = words(6, "c") + "।"
        val chunks = TextChunker.chunk("$s1 $s2 $s3", maxWords = 12)
        assertEquals(listOf("$s1 $s2", s3), chunks)
    }

    @Test
    fun `run-on sentence is hard split into word windows`() {
        val chunks = TextChunker.chunk(words(25), maxWords = 10)
        assertEquals(listOf(10, 10, 5), chunks.map(TextChunker::countWords))
        assertEquals(words(25), chunks.joinToString(" "))
    }

    @Test
    fun `hyphenated line breaks are rejoined but capitalised compounds kept`() {
        val chunks = TextChunker.chunk("trans-\nlation and On-\nDevice", maxWords = 50)
        assertEquals(listOf("translation and On-Device"), chunks)
    }
}
