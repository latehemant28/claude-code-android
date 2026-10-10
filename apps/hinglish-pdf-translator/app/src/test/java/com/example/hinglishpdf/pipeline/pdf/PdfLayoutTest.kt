package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout analysis on synthetic pages: every glyph placed by hand, 0.5 em wide, words 0.3 em apart. */
class PdfLayoutTest {

    private class PageBuilder(val number: Int, val width: Float = 600f, val height: Float = 800f) {
        val glyphs = mutableListOf<PdfGlyph>()
        var images = 0

        /** Writes [text] from [x] on [baseline]; returns the x after it. */
        fun text(text: String, x: Float, baseline: Float, size: Float = 10f, bold: Boolean = false, italic: Boolean = false, font: String = "Serif"): Float {
            var at = x
            for (c in text) {
                if (c == ' ') {
                    at += 0.3f * size
                } else {
                    glyphs += PdfGlyph(c.toString(), at, baseline, 0.5f * size, size, font, bold, italic)
                    at += 0.5f * size
                }
            }
            return at
        }

        fun build() = PdfPageGlyphs(number, width, height, glyphs.toList(), images)
    }

    private fun page(number: Int = 1, block: PageBuilder.() -> Unit) = PageBuilder(number).apply(block).build()

    private fun texts(result: PdfLayoutResult) = result.paragraphs.map { it.role to it.text }

    @Test
    fun `headings and paragraphs, with hyphenated words rejoined`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("Chapter One", 50f, 80f, size = 20f, bold = true)
                    text("The first line of a long transla-", 50f, 120f)
                    text("tion runs on here. An Anglo-", 50f, 132f)
                    text("Saxon king ruled.", 50f, 144f)
                    text("A second paragraph starts after a gap.", 50f, 175f)
                },
            ),
        )
        assertEquals(
            listOf(
                ParagraphRole.HEADING to "Chapter One",
                ParagraphRole.PARAGRAPH to "The first line of a long translation runs on here. An Anglo-Saxon king ruled.",
                ParagraphRole.PARAGRAPH to "A second paragraph starts after a gap.",
            ),
            texts(result),
        )
        assertEquals(1, result.paragraphs[0].level)
        assertEquals(10f, result.paragraphs[1].style!!.size)
        assertEquals(mapOf(1 to PageKind.TEXT), result.pageKinds)
    }

    @Test
    fun `two columns are read column by column under a full-width title`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("A Title Spanning Both Columns Of This Page Here And There", 50f, 60f, size = 16f)
                    for (i in 0 until 4) {
                        text("Left column line number ${i + 1} goes", 50f, 100f + i * 12, size = 10f)
                        text("Right column line number ${i + 1} goes", 320f, 100f + i * 12, size = 10f)
                    }
                },
            ),
        )
        val paragraphs = texts(result)
        assertEquals(ParagraphRole.HEADING, paragraphs[0].first)
        assertEquals(
            "Left column line number 1 goes Left column line number 2 goes Left column line number 3 goes Left column line number 4 goes",
            paragraphs[1].second,
        )
        assertTrue(paragraphs[2].second.startsWith("Right column line number 1 goes"))
        assertEquals(mapOf(1 to 2), result.columns)
    }

    @Test
    fun `a table becomes one unit per cell, and numbers alone are not translated`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("Some text before the table of people.", 50f, 80f)
                    val rows = listOf(listOf("Name", "Age", "City"), listOf("Ravi", "34", "Pune"), listOf("Asha", "29", "Delhi"))
                    rows.forEachIndexed { r, row -> row.forEachIndexed { c, cell -> text(cell, 50f + c * 120f, 110f + r * 14f) } }
                },
            ),
        )
        assertEquals(1, result.tables)
        val cells = result.paragraphs.filter { it.role == ParagraphRole.TABLE_CELL }
        assertEquals(listOf("Name", "Age", "City", "Ravi", "34", "Pune", "Asha", "29", "Delhi"), cells.map { it.text })
        val segments = Segmenter.segment(result.paragraphs)
        assertFalse(segments.any { it.text == "34" })
        assertTrue(segments.any { it.text == "Ravi" })
    }

    @Test
    fun `running headers, footers and page numbers are dropped`() {
        val pages = (1..4).map { n ->
            page(n) {
                text("THE BOOK TITLE", 250f, 30f, size = 9f)
                text("Body text of page $n is here.", 50f, 100f)
                text("$n", 295f, 780f, size = 9f)
            }
        }
        val result = PdfLayout.analyze(pages)
        assertEquals((1..4).map { "Body text of page $it is here." }, result.paragraphs.map { it.text })
        assertEquals(8, result.droppedLines)
    }

    @Test
    fun `footnote marks become placeholders and footnotes their own paragraphs`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    val end = text("He quoted the old book.", 50f, 100f)
                    text("1", end + 0.5f, 96f, size = 6f) // raised, small: a footnote mark
                    text("Then he left the room quietly.", 50f, 112f)
                    text("1 The old book is lost now.", 50f, 740f, size = 8f)
                },
            ),
        )
        assertEquals(
            listOf(
                ParagraphRole.PARAGRAPH to "He quoted the old book.[[SUP_1]] Then he left the room quietly.",
                ParagraphRole.FOOTNOTE to "1 The old book is lost now.",
            ),
            texts(result),
        )
        // The mark stays with its sentence.
        assertEquals(
            listOf("He quoted the old book.[[SUP_1]]", "Then he left the room quietly."),
            Segmenter.segment(result.paragraphs).filter { it.paragraph == 0 }.map { it.text },
        )
    }

    @Test
    fun `bold and italic words inside a paragraph become placeholders`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    var x = text("This is", 50f, 100f)
                    x = text(" very", x, 100f, bold = true)
                    x = text(" important and", x, 100f)
                    x = text(" quite", x, 100f, italic = true)
                    text(" rare.", x, 100f)
                },
            ),
        )
        val paragraph = result.paragraphs.single()
        assertEquals("This is {1}very{/1} important and {2}quite{/2} rare.", paragraph.text)
        assertEquals(listOf("<b></b>", "<i></i>"), paragraph.tags.map { it.markup })
    }

    @Test
    fun `each segment keeps the box of the lines it was on`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("First sentence here. Second one", 50f, 100f)
                    text("continues on the next line and", 50f, 112f)
                    text("ends on the third.", 50f, 124f)
                },
            ),
        )
        val segments = Segmenter.segment(result.paragraphs)
        val first = segments[0].ref as SourceRef.Pdf
        val second = segments[1].ref as SourceRef.Pdf
        assertEquals("First sentence here.", segments[0].text)
        assertEquals(1, first.page)
        assertTrue("first sentence on line 1 only: ${first.box}", first.box.y1 < 105f)
        assertTrue("second sentence over all three lines: ${second.box}", second.box.y0 < 95f && second.box.y1 > 120f)
    }

    @Test
    fun `scanned pages are detected and a mostly scanned book needs ocr`() {
        val scan1 = PageBuilder(1).apply { images = 1 }.build()
        val scan2 = PageBuilder(2).apply { images = 1 }.build()
        val text = page(3) { text("A normal page of real text.", 50f, 100f) }
        val garbage = PageBuilder(4).apply {
            images = 1
            text("", 50f, 100f)
        }.build()
        val blank = PageBuilder(5).build()
        val result = PdfLayout.analyze(listOf(scan1, scan2, text, garbage, blank))
        assertEquals(
            mapOf(1 to PageKind.SCANNED, 2 to PageKind.SCANNED, 3 to PageKind.TEXT, 4 to PageKind.SCANNED, 5 to PageKind.BLANK),
            result.pageKinds,
        )
        assertEquals(listOf(1, 2, 4), result.scannedPages)
        assertTrue(result.needsOcr)
        assertEquals(listOf("A normal page of real text."), result.paragraphs.map { it.text })

        assertFalse(PdfLayout.analyze(listOf(text, scan1, page(2) { text("More real text on page two.", 50f, 100f) })).needsOcr)
    }

    @Test
    fun `bulleted and numbered items are list items`() {
        val result = PdfLayout.analyze(
            listOf(
                page {
                    text("• Bring a coat", 50f, 100f)
                    text("• Leave early", 50f, 112f)
                    text("1. Then walk", 50f, 124f)
                },
            ),
        )
        assertEquals(
            listOf(ParagraphRole.LIST_ITEM to "Bring a coat", ParagraphRole.LIST_ITEM to "Leave early", ParagraphRole.LIST_ITEM to "1. Then walk"),
            texts(result),
        )
    }
}
