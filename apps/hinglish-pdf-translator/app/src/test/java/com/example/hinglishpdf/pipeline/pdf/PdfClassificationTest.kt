package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per block classifier and layout rule of section A. */
class PdfClassificationTest {

    private fun PdfLayoutResult.on(page: Int) = paragraphs.filter { (it.ref as SourceRef.Pdf).page == page }
    private fun PdfLayoutResult.body() = paragraphs.filter { it.translatable }.map { it.text }
    private fun PdfLayoutResult.roleOf(text: String) = paragraphs.single { it.text == text }.role

    private val prose = listOf(
        "Rain fell on the old town all through the long night and",
        "the river rose until the lower streets were under water, so",
        "the people carried what they could up to the higher ground.",
    )

    @Test
    fun `running heads repeated on one side of the spread are headers, counted per side`() {
        // 20 pages. Odd pages carry the book title; even pages the chapter title, which
        // changes after page 6. "THE FIRST CHAPTER" is on only 3 of 20 pages (too few
        // overall) but on 3 of the 10 even pages, which is enough.
        val pages = (1..20).map { n ->
            page(n) {
                val head = when {
                    n % 2 == 1 -> "A BOOK OF RIVERS"
                    n <= 6 -> "THE FIRST CHAPTER"
                    else -> "THE SECOND CHAPTER"
                }
                text(head, 250f, 30f, size = 9f)
                lines(prose, 50f, 100f)
            }
        }
        val result = PdfLayout.analyze(pages)
        val headers = result.paragraphs.filter { it.role == ParagraphRole.HEADER }
        assertEquals(20, headers.size)
        assertEquals(3, headers.count { it.text == "THE FIRST CHAPTER" })
        assertFalse(result.body().any { "CHAPTER" in it || "RIVERS" in it })
    }

    @Test
    fun `a chapter's running head on consecutive pages of one side is a header`() {
        // Repetition share set too high to catch it: the run of 3 even pages still does.
        val config = PipelineConfig(repetitionShare = 0.9f)
        val pages = (1..12).map { n ->
            page(n) {
                if (n in setOf(4, 6, 8)) text("ON MAPS", 250f, 30f, size = 9f)
                lines(prose, 50f, 100f)
            }
        }
        val result = PdfLayout.analyze(pages, config)
        assertEquals(3, result.paragraphs.count { it.role == ParagraphRole.HEADER && it.text == "ON MAPS" })
    }

    @Test
    fun `margin lines shaped like running heads are headers or footers, a one-line footnote is not`() {
        // Each running head is different (no repetition) but matches "^\d+\s+\S.{0,40}$"
        // or "^.{0,40}\s+\d+$", and its number moves with the page.
        val pages = (1..3).map { n ->
            page(n) {
                when (n) {
                    1 -> text("11 A Tale of Rivers", 50f, 30f, size = 9f)
                    2 -> text("Another Story 12", 300f, 30f, size = 9f)
                    else -> text("13 The Last Lake", 50f, 30f, size = 9f)
                }
                lines(prose, 50f, 100f)
                if (n == 2) text("1 See the notes at the end of the book.", 50f, 770f, size = 8f)
                text("${n + 10}", 295f, 785f, size = 9f)
            }
        }
        val result = PdfLayout.analyze(pages)
        assertEquals(ParagraphRole.HEADER, result.roleOf("11 A Tale of Rivers"))
        assertEquals(ParagraphRole.HEADER, result.roleOf("Another Story 12"))
        assertEquals(ParagraphRole.HEADER, result.roleOf("13 The Last Lake"))
        assertEquals(ParagraphRole.PAGE_NUMBER, result.roleOf("12"))
        assertEquals(ParagraphRole.FOOTNOTE, result.roleOf("1 See the notes at the end of the book."))
    }

    @Test
    fun `pages left blank on purpose, printer's slugs and pages with only a number are boilerplate`() {
        val pages = listOf(
            page(1) { lines(prose, 50f, 100f) },
            page(2) {
                text("This page intentionally left blank", 200f, 400f)
                text("2", 295f, 785f, size = 9f)
            },
            page(3) { text("3", 295f, 785f, size = 9f) },
            page(4) {
                lines(prose, 50f, 100f)
                text("Rivers_Final.indd 4", 50f, 790f, size = 6f)
            },
        )
        val result = PdfLayout.analyze(pages)
        assertEquals(listOf(2, 3), result.boilerplatePages)
        assertEquals(PageKind.BOILERPLATE, result.pageKinds[2])
        assertEquals(ParagraphRole.BOILERPLATE, result.roleOf("This page intentionally left blank"))
        assertEquals(ParagraphRole.BOILERPLATE, result.roleOf("Rivers_Final.indd 4"))
        assertFalse(result.paragraphs.single { it.role == ParagraphRole.BOILERPLATE && "blank" in it.text }.translatable)
        assertEquals(2, result.body().size)
        assertFalse(result.needsOcr)
    }

    @Test
    fun `boilerplate patterns come from the config`() {
        val config = PipelineConfig(boilerplatePagePatterns = listOf("^intentionally empty$"))
        val result = PdfLayout.analyze(listOf(page(1) { text("Intentionally empty", 200f, 400f) }), config)
        assertEquals(listOf(1), result.boilerplatePages)
    }

    @Test
    fun `a gap over 1·3 line spacings starts a paragraph, a smaller one does not`() {
        // Full-width lines, so no other rule applies. Spacing 12: 15 is 1.25x, 17 is 1.42x.
        val line = "word word word word word word word word word word"
        val result = PdfLayout.analyze(
            listOf(
                page {
                    var y = 100f
                    for (gap in listOf(12f, 12f, 15f, 12f, 17f, 12f, 12f)) {
                        text(line, 50f, y)
                        y += gap
                    }
                    text(line, 50f, y)
                },
            ),
        )
        // Lines 1-5 (gap 15 kept inside), then lines 6-8 after the gap of 17.
        assertEquals(listOf(5, 3), result.paragraphs.map { it.text.split(" ").size / 10 })
    }

    @Test
    fun `a first-line indent starts a paragraph`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    lines(prose.take(2), 50f, 100f) // ends without a full stop: only the indent marks the break
                    lines(listOf("Then the water fell back again and left a", "brown line along every wall in the town."), 65f, 124f)
                },
            ),
        )
        assertEquals(2, result.paragraphs.size)
        assertTrue(result.paragraphs[1].text.startsWith("Then the water"))
    }

    @Test
    fun `list items - one block per item with its bullet as marker, hanging lines included, nesting by indent`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("You will need these things for the walk:", 50f, 100f)
                    text("• a coat that keeps out the rain, even when it", 50f, 112f)
                    text("blows hard from the sea", 60f, 124f)
                    text("• a map", 50f, 136f)
                    text("◦ printed, not on a phone", 70f, 148f)
                    text("1. Leave before dawn.", 50f, 160f)
                    text("And then the walk itself begins, which is a long story.", 50f, 172f)
                },
            ),
        )
        val items = result.paragraphs.filter { it.role == ParagraphRole.LIST_ITEM }
        assertEquals(
            listOf("a coat that keeps out the rain, even when it blows hard from the sea", "a map", "printed, not on a phone", "1. Leave before dawn."),
            items.map { it.text },
        )
        assertEquals(listOf("•", "•", "◦", null), items.map { it.marker })
        assertEquals(listOf(0, 0, 1, 0), items.map { it.level })
        // The flush-left line after the list is not part of the last item.
        assertEquals(ParagraphRole.PARAGRAPH, result.paragraphs.last().role)
    }

    @Test
    fun `a three-column region under single-column text is read column by column, never merged into lines`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("The paragraph above the columns spans the whole width of the text block here.", 50f, 80f)
                    val columns = listOf(
                        listOf("First column text", "goes down its own", "narrow strip and", "ends right here."),
                        listOf("Second column text", "follows on below", "in the middle and", "ends here too."),
                        listOf("Third column text", "sits on the right", "and is read last", "of the three."),
                    )
                    columns.forEachIndexed { c, column -> column.forEachIndexed { i, t -> text(t, 50f + c * 150f, 110f + i * 12) } }
                    text("The paragraph below the columns spans the whole width of the text block too.", 50f, 180f)
                },
            ),
        )
        assertEquals(
            listOf(
                0 to "The paragraph above the columns spans the whole width of the text block here.",
                1 to "First column text goes down its own narrow strip and ends right here.",
                2 to "Second column text follows on below in the middle and ends here too.",
                3 to "Third column text sits on the right and is read last of the three.",
                0 to "The paragraph below the columns spans the whole width of the text block too.",
            ),
            result.paragraphs.map { it.column to it.text },
        )
        assertEquals(mapOf(1 to 3), result.columns)
        assertEquals(0, result.tables) // prose runs on down each column: not a table
    }

    @Test
    fun `columns of entries that do not run on (addresses) keep one block per line`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    val left = listOf("Head Office", "12 Main Road", "Mumbai 400001", "India")
                    val right = listOf("Branch Office", "45 Park Street", "Kolkata 700016", "India")
                    // Baselines staggered, so the rows are not a table.
                    left.forEachIndexed { i, t -> text(t, 50f, 100f + i * 12) }
                    right.forEachIndexed { i, t -> text(t, 300f, 106f + i * 12) }
                },
            ),
        )
        assertEquals(listOf("Head Office", "12 Main Road", "Mumbai 400001", "India", "Branch Office", "45 Park Street", "Kolkata 700016", "India"), result.paragraphs.map { it.text })
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2, 2), result.paragraphs.map { it.column })
    }

    @Test
    fun `font tiers - body 0, larger sizes 1, 2 from the largest down, smaller -1`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("Part One", 50f, 60f, size = 24f)
                    text("The Flood", 50f, 100f, size = 16f)
                    lines(prose, 50f, 130f)
                    lines(prose, 50f, 180f)
                    text("1 A note in small type at the foot of the page.", 50f, 700f, size = 8f)
                },
            ),
        )
        assertEquals(listOf(1, 2, 0, 0, -1), result.paragraphs.map { it.tier })
        assertEquals(listOf(ParagraphRole.HEADING, ParagraphRole.HEADING), result.paragraphs.take(2).map { it.role })
    }

    @Test
    fun `captions - by pattern, by an image just above, and text drawn inside a figure`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    lines(prose, 50f, 100f)
                    image(Box(50f, 150f, 300f, 300f))
                    text("Inside the picture", 80f, 200f, size = 8f)
                    text("The river at its highest, in 1910.", 50f, 312f, size = 9f)
                    lines(prose, 50f, 350f)
                    text("Figure 2: The town after the water fell.", 50f, 420f)
                },
            ),
        )
        assertEquals(ParagraphRole.CAPTION, result.roleOf("The river at its highest, in 1910."))
        assertEquals(ParagraphRole.CAPTION, result.roleOf("Inside the picture"))
        assertEquals(ParagraphRole.CAPTION, result.roleOf("Figure 2: The town after the water fell."))
        assertEquals(2, result.paragraphs.count { it.role == ParagraphRole.PARAGRAPH })
    }

    @Test
    fun `a page-sized background image does not turn the page into a figure`() {
        val result = PdfLayout.analyze(listOf(page { image(Box(0f, 0f, 600f, 800f)); lines(prose, 50f, 100f) }))
        assertEquals(listOf(ParagraphRole.PARAGRAPH), result.paragraphs.map { it.role })
    }

    @Test
    fun `hyphens at line ends - the book's own words decide`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    lines(
                        listOf(
                            "The engineers built strange contrap-", // no evidence, lower case: joined
                            "tions for the well-known fair. Every well-", // "well-known" seen mid-line: kept
                            "known name came at half-", // "time" stands alone twice in the book: kept
                            "time, and rainwater ran off the roof. Some rain-", // "rainwater" seen whole: joined
                            "water stood in pools. Time and time again they came.",
                        ),
                        50f,
                        100f,
                    )
                },
            ),
        )
        assertEquals(
            "The engineers built strange contraptions for the well-known fair. Every well-known name came at half-time, " +
                "and rainwater ran off the roof. Some rainwater stood in pools. Time and time again they came.",
            result.paragraphs.single().text,
        )
    }

    @Test
    fun `page numbers in their usual forms`() {
        val pages = listOf("7", "- 8 -", "Page 9", "x", "11 of 300").mapIndexed { i, number ->
            page(i + 1) {
                lines(prose, 50f, 100f)
                text(number, 280f, 785f, size = 9f)
            }
        }
        val result = PdfLayout.analyze(pages)
        assertEquals(5, result.paragraphs.count { it.role == ParagraphRole.PAGE_NUMBER })
    }
}
