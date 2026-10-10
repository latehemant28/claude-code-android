package com.example.hinglishpdf.pipeline.assemble

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.pdf.PdfLayout
import com.example.hinglishpdf.pipeline.pdf.page
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cross-break join rule of section B, its guards, page spans and the split back. */
class DocumentAssemblerTest {

    private val config = PipelineConfig()
    private val assembler = DocumentAssembler(config, Hyphenation.NONE)

    private fun p(
        text: String,
        page: Int,
        role: ParagraphRole = ParagraphRole.PARAGRAPH,
        tier: Int = 0,
        column: Int = 0,
        tags: List<PlaceholderTag> = emptyList(),
    ) = ParsedParagraph(role, text, tags, SourceRef.Pdf(page, Box(50f, 100f, 500f, 700f)), tier = tier, column = column)

    private fun epub(text: String, file: String, role: ParagraphRole = ParagraphRole.PARAGRAPH) =
        ParsedParagraph(role, text, emptyList(), SourceRef.Epub(file, "/html/body/p[1]"))

    private fun List<ParsedParagraph>.texts() = map { it.text }

    @Test
    fun `a sentence cut by a page break is one paragraph again, with its page span`() {
        val out = assembler.assemble(listOf(p("The river rose all night and", 1), p("the town woke up to water.", 2)))
        assertEquals(listOf("The river rose all night and the town woke up to water."), out.texts())
        assertEquals(1..2, out.single().pages)
        assertEquals(listOf(0 to 0, 1 to 29), out.single().parts.map { it.index to it.start })
    }

    @Test
    fun `footnotes, captions, figures, tables and furniture between the two halves are skipped over`() {
        val out = assembler.assemble(
            listOf(
                p("He opened the old", 1),
                p("1 A note.", 1, ParagraphRole.FOOTNOTE, tier = -1),
                p("Figure 1. A door.", 1, ParagraphRole.CAPTION),
                p("12", 1, ParagraphRole.PAGE_NUMBER),
                p("A BOOK OF RIVERS", 2, ParagraphRole.HEADER),
                p("Name", 2, ParagraphRole.TABLE_CELL),
                p("door and went in.", 2),
            ),
        )
        assertEquals(
            listOf("He opened the old door and went in.", "1 A note.", "Figure 1. A door.", "12", "A BOOK OF RIVERS", "Name"),
            out.texts(),
        )
        // Every source block is still there exactly once.
        assertEquals((0..6).toList(), out.flatMap { it.parts }.map { it.index }.sorted())
    }

    @Test
    fun `no join after terminal punctuation`() {
        for (end in listOf(".", "!", "?", ":", ";", "\"", "”", "’")) {
            val out = assembler.assemble(listOf(p("He said so$end", 1), p("then he left.", 2)))
            assertEquals("after '$end'", 2, out.size)
        }
    }

    @Test
    fun `an upper-case start still joins when the next block is running text`() {
        val out = assembler.assemble(listOf(p("The letter was signed by Mr", 1), p("Smith himself.", 2)))
        assertEquals(listOf("The letter was signed by Mr Smith himself."), out.texts())
    }

    @Test
    fun `never into a heading or a new list item, and never across a font tier change even in lower case`() {
        val heading = assembler.assemble(listOf(p("and so it ended", 1), p("Chapter Two", 2, ParagraphRole.HEADING, tier = 1)))
        assertEquals(2, heading.size)
        val boldHeading = assembler.assemble(listOf(p("and so it ended", 1), p("the second part", 2, ParagraphRole.HEADING)))
        assertEquals(2, boldHeading.size)
        val list = assembler.assemble(listOf(p("You need", 1), p("a coat", 2, ParagraphRole.LIST_ITEM)))
        assertEquals(2, list.size)
        val tier = assembler.assemble(listOf(p("and so it ended", 1), p("in a smaller hand", 2, tier = -1)))
        assertEquals("the tier guard", 2, tier.size)
        val previousTier = assembler.assemble(listOf(p("A Large Line", 1, tier = 2), p("of text", 2)))
        assertEquals(2, previousTier.size)
    }

    @Test
    fun `a list item runs on only into a lower-case continuation`() {
        val runOn = assembler.assemble(listOf(p("a coat that keeps out the", 1, ParagraphRole.LIST_ITEM), p("rain and the wind", 2)))
        assertEquals(listOf("a coat that keeps out the rain and the wind"), runOn.texts())
        assertEquals(ParagraphRole.LIST_ITEM, runOn.single().role)
        val next = assembler.assemble(listOf(p("a map", 1, ParagraphRole.LIST_ITEM), p("The walk begins.", 2)))
        assertEquals(2, next.size)
    }

    @Test
    fun `blocks on the same page and column are never joined, unless a figure interrupts them`() {
        val same = assembler.assemble(listOf(p("A short line", 1), p("another paragraph.", 1)))
        assertEquals(2, same.size)
        val interrupted = assembler.assemble(listOf(p("The river rose", 1), p("Figure 1. The river.", 1, ParagraphRole.CAPTION), p("all night.", 1)))
        assertEquals(listOf("The river rose all night.", "Figure 1. The river."), interrupted.texts())
    }

    @Test
    fun `column breaks join like page breaks`() {
        val out = assembler.assemble(listOf(p("The first column ends in the", 1, column = 1), p("middle of a sentence.", 1, column = 2)))
        assertEquals(listOf("The first column ends in the middle of a sentence."), out.texts())
    }

    @Test
    fun `a paragraph running over three pages is one paragraph`() {
        val out = assembler.assemble(listOf(p("It began on one", 1), p("page, went on over the", 2), p("next and ended here.", 3)))
        assertEquals(listOf("It began on one page, went on over the next and ended here."), out.texts())
        assertEquals(1..3, out.single().pages)
    }

    @Test
    fun `placeholders of the second half are renumbered, and split back exactly`() {
        val bold = PlaceholderTag(1, PlaceholderTag.Kind.PAIRED, "b", "<b></b>")
        val sup = PlaceholderTag(2, PlaceholderTag.Kind.STANDALONE, "SUP", "1")
        val a = p("He read {1}the old{/1} book[[SUP_2]] and", 1, tags = listOf(bold, sup))
        val b = p("then {1}slept{/1} until noon. He woke late.", 2, tags = listOf(bold))
        val joined = assembler.assemble(listOf(a, b)).single()
        assertEquals("He read {1}the old{/1} book[[SUP_2]] and then {3}slept{/3} until noon. He woke late.", joined.text)
        assertEquals(listOf(1, 2, 3), joined.tags.map { it.id })
        assertEquals(listOf(a.text, b.text), DocumentAssembler.sourceTexts(joined))

        // A translation is split back at the sentence boundary nearest the join, each part renumbered.
        val translated = "Usne {1}purani{/1} kitaab[[SUP_2]] padhi. Phir woh dopahar tak {3}soya{/3}. Woh der se utha."
        assertEquals(
            listOf("Usne {1}purani{/1} kitaab[[SUP_2]] padhi.", "Phir woh dopahar tak {1}soya{/1}. Woh der se utha."),
            DocumentAssembler.splitBack(joined, translated),
        )
    }

    @Test
    fun `the split never cuts through a placeholder pair or moves one to the wrong part`() {
        val bold = PlaceholderTag(1, PlaceholderTag.Kind.PAIRED, "b", "<b></b>")
        val joined = assembler.assemble(listOf(p("One two three four five six seven", 1), p("eight. {1}Nine ten{/1}.", 2, tags = listOf(bold)))).single()
        // No sentence boundary in the translation, and its {1} (part 2's) comes early:
        // the cut is the space nearest the join that keeps {1}...{/1} in part 2.
        val translated = "Ek do {1}teen{/1} chaar paanch chhe saat aath nau das."
        val split = DocumentAssembler.splitBack(joined, translated)
        assertEquals(listOf("Ek do", "{1}teen{/1} chaar paanch chhe saat aath nau das."), split)
    }

    @Test
    fun `epub paragraphs cut by a file boundary are joined, titles and alt text in between are skipped`() {
        val out = DocumentAssembler(config).assemble(
            listOf(
                epub("The story continues in the", "ch1a.xhtml"),
                epub("A red door", "ch1a.xhtml", ParagraphRole.ALT_TEXT),
                epub("Chapter One", "ch1b.xhtml", ParagraphRole.TITLE),
                epub("next file without a break.", "ch1b.xhtml"),
                epub("A new paragraph in the same file", "ch1b.xhtml"),
                epub("Chapter Two", "ch2.xhtml", ParagraphRole.HEADING),
            ),
        )
        assertEquals(
            listOf("The story continues in the next file without a break.", "A red door", "Chapter One", "A new paragraph in the same file", "Chapter Two"),
            out.texts(),
        )
        assertEquals(listOf("ch1a.xhtml", "ch1b.xhtml"), out[0].parts.map { (it.ref as SourceRef.Epub).file })
    }

    @Test
    fun `a word hyphenated across a page break is rejoined or kept by the same rule as inside a page`() {
        val hyphenation = Hyphenation.build(sequenceOf("a well-known town", "known to all", "known again"), config)
        val assembler = DocumentAssembler(config, hyphenation)
        assertEquals(listOf("The strange contraptions stood still."), assembler.assemble(listOf(p("The strange contrap-", 1), p("tions stood still.", 2))).texts())
        assertEquals(listOf("It was a well-known fact."), assembler.assemble(listOf(p("It was a well-", 1), p("known fact.", 2))).texts())
    }

    @Test
    fun `end to end on pages - header, page number and footnote never land inside the joined paragraph`() {
        val pages = (1..4).map { n ->
            page(n) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                when (n) {
                    1 -> {
                        lines(listOf("The water rose all through the night until the", "lower streets of the old town were deep under"), 50f, 680f)
                        text("1 The flood of 1910.", 50f, 730f, size = 8f)
                    }
                    2 -> lines(listOf("water, and the people carried what they could", "up the hill to the church. Nobody slept."), 50f, 100f)
                    else -> lines(listOf("Another page of the story, page $n, ends here."), 50f, 100f)
                }
                text("$n", 295f, 785f, size = 9f)
            }
        }
        val layout = PdfLayout.analyze(pages)
        val paragraphs = DocumentAssembler(config, layout.hyphenation).assemble(layout.paragraphs)
        val body = paragraphs.filter { it.role == ParagraphRole.PARAGRAPH }
        assertEquals(
            "The water rose all through the night until the lower streets of the old town were deep under water, " +
                "and the people carried what they could up the hill to the church. Nobody slept.",
            body.first().text,
        )
        assertEquals(1..2, body.first().pages)
        assertTrue(body.none { "RIVERS" in it.text || it.text.contains(Regex("\\b[1-4]$")) })
        // Sentences are cut after assembly: the sentence across the page break is one segment.
        val segments = Segmenter.segment(paragraphs)
        assertTrue(segments.any { it.text.startsWith("The water rose") && it.text.endsWith("church.") })
        // Its segment points at the page it starts on.
        assertEquals(1, (segments.first { it.text.startsWith("The water rose") }.ref as SourceRef.Pdf).page)
    }
}
