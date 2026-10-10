package com.example.hinglishpdf.pipeline.translate

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.PlaceholderTextBuilder
import com.example.hinglishpdf.pipeline.segment.SentenceSegmenter
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section C: requests of whole paragraphs, ending at sentence boundaries, with structure markers. */
class MarkerChunksTest {

    private val config = PipelineConfig()

    private fun p(text: String, role: ParagraphRole = ParagraphRole.PARAGRAPH, level: Int = 0, column: Int = 0, tags: List<PlaceholderTag> = emptyList()) =
        ParsedParagraph(role, text, tags, SourceRef.Pdf(1, Box(0f, 0f, 1f, 1f)), level = level, column = column)

    private fun words(n: Int, sentenceEvery: Int = 10) =
        (1..n).joinToString(" ") { if (it % sentenceEvery == 0) "Word$it." else "Word$it" }.let { if (it.endsWith(".")) it else "$it." }

    @Test
    fun `one to three whole paragraphs per request, never more`() {
        val paragraphs = (1..7).map { p("Paragraph number $it is short.") }
        val chunks = ParagraphChunker.chunks(ParagraphChunker.units(paragraphs, config), config)
        assertEquals(listOf(3, 3, 1), chunks.map { it.size })
        assertEquals((0..6).toList(), chunks.flatten().map { it.paragraph })
        assertEquals(listOf(2, 2), ParagraphChunker.chunks(ParagraphChunker.units(paragraphs.take(4), config), config.copy(chunkMaxParagraphs = 2)).map { it.size })
    }

    @Test
    fun `the word limit closes a request early, at a paragraph end`() {
        val paragraphs = listOf(p(words(300)), p(words(300)), p(words(300)))
        val chunks = ParagraphChunker.chunks(ParagraphChunker.units(paragraphs, config), config)
        assertEquals(listOf(2, 1), chunks.map { it.size }) // 600 words, then 300: 900 would pass 800
    }

    @Test
    fun `a paragraph longer than a request goes alone, cut between sentences`() {
        val long = p(words(2000))
        val units = ParagraphChunker.units(listOf(p("Before."), long, p("After.")), config)
        val pieces = units.filter { it.paragraph == 1 }
        assertTrue(pieces.size >= 3)
        assertTrue(pieces.all { it.words <= config.chunkMaxWords })
        // Every piece ends a sentence and the pieces rebuild the paragraph.
        assertTrue(pieces.all { it.text.endsWith(".") })
        assertEquals(long.text, pieces.joinToString(" ") { it.text })
        val chunks = ParagraphChunker.chunks(units, config)
        assertTrue(chunks.filter { c -> c.any { it.paragraph == 1 } }.all { it.size == 1 })
    }

    @Test
    fun `a single sentence longer than the limit is never cut`() {
        val sentence = (1..900).joinToString(" ") { "w$it" } + "."
        val units = ParagraphChunker.units(listOf(p(sentence)), config)
        assertEquals(listOf(sentence), units.map { it.text })
    }

    @Test
    fun `untranslatable blocks and page furniture are not sent`() {
        val units = ParagraphChunker.units(listOf(p("12"), p("A BOOK", ParagraphRole.HEADER), p("Real text.")), config)
        assertEquals(listOf(2), units.map { it.paragraph })
    }

    @Test
    fun `markers carry the structure, and the technical rules come first`() {
        val units = ParagraphChunker.units(
            listOf(
                p("Chapter One", ParagraphRole.HEADING, level = 1),
                p("Bring a coat.", ParagraphRole.LIST_ITEM),
                p("Left column text.", column = 1),
                p("Right column text.", column = 2),
            ),
            config,
        )
        val prompt = MarkerPrompt.build(units)
        assertTrue(prompt.startsWith("<TECHNICAL_RULES>"))
        assertTrue(prompt.contains("- Copy placeholders like {1}, {/1}, [[IMG_3]] exactly, in order. Never translate, merge, or delete them."))
        assertTrue(prompt.endsWith("{H1} Chapter One\n{LI} Bring a coat.\n{COL}{P} Left column text.\n{COL}{P} Right column text."))
        assertEquals(listOf("{H1}", "{LI}", "{COL}", "{P}", "{COL}", "{P}"), MarkerPrompt.markers(prompt.substringAfter("</STRUCTURE_MARKERS>")))
    }

    @Test
    fun `an answer is matched back by its markers`() {
        val units = ParagraphChunker.units(listOf(p("Chapter One", ParagraphRole.HEADING, 1), p("He left.", tags = emptyList()), p("She stayed.")), config)
        val answer = "Here is the translation:\n```\n{H1} Adhyay Ek\n{P} Woh chala gaya.\n{P} Woh ruk gayi.\n```"
        assertEquals(listOf("Adhyay Ek", "Woh chala gaya.", "Woh ruk gayi."), MarkerPrompt.parse(answer, units))
    }

    @Test
    fun `merged, dropped or relabelled blocks are not guessed at`() {
        val units = ParagraphChunker.units(listOf(p("One."), p("Two."), p("Three.")), config)
        assertEquals(List(3) { null }, MarkerPrompt.parse("{P} Ek. Do.\n{P} Teen.", units))
        assertEquals(List(3) { null }, MarkerPrompt.parse("{P} Ek.\n{LI} Do.\n{P} Teen.", units))
    }

    @Test
    fun `a one-block request takes the whole answer, markers or not`() {
        val units = ParagraphChunker.units(listOf(p("One sentence.")), config)
        assertEquals(listOf("Ek vakya."), MarkerPrompt.parse("Ek vakya.", units))
        assertEquals(listOf("Ek vakya."), MarkerPrompt.parse("{P} Ek vakya.", units))
    }

    @Test
    fun `placeholders - a different order is fine, a lost one is repaired at the end`() {
        val source = "He said {1}hello{/1} to {2}the man{/2}.[[SUP_3]]"
        assertEquals(false, MarkerPrompt.repair(source, "Usne {2}aadmi{/2} ko {1}hello{/1} kaha.[[SUP_3]]").second)
        val (fixed, repaired) = MarkerPrompt.repair(source, "Usne aadmi ko {1}hello kaha.")
        assertTrue(repaired)
        assertEquals("Usne aadmi ko hello kaha. {1}{/1}{2}{/2}[[SUP_3]]", fixed)
    }

    @Test
    fun `source text that looks like a marker is protected`() {
        val builder = PlaceholderTextBuilder()
        builder.text("Press {P} to pause.")
        val (text, tags) = builder.result()
        assertEquals("Press [[TXT_1]] to pause.", text)
        assertEquals("{P}", tags.single().markup)
        assertFalse(MarkerPrompt.markers(text).isNotEmpty())
    }

    @Test
    fun `sentences are split after assembly - a request never ends mid-sentence`() {
        val paragraphs = listOf(p("The river rose all night and the town woke up to water. Nobody slept."), p("Morning came."))
        for (chunk in ParagraphChunker.chunks(ParagraphChunker.units(paragraphs, config), config)) {
            val last = chunk.last().text
            assertEquals(last.trim(), SentenceSegmenter.split(last).last().let { last.substring(0, it.end) }.trim())
            assertTrue(last.endsWith("."))
        }
    }
}
