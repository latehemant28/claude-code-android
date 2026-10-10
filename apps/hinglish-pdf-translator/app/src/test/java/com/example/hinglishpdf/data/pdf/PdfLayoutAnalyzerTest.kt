package com.example.hinglishpdf.data.pdf

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLayoutAnalyzerTest {

    private var y = 60f
    private fun line(text: String, x: Float = 72f, size: Float = 11f, bold: Boolean = false, gap: Float = 14f, page: Int = 1) =
        PdfLine(text, page, x, (y + gap).also { y = it }, size, bold, 842f)

    @Test
    fun `headings, nested bullets, numbering and paragraphs are recovered`() {
        val lines = listOf(
            line("User Guide", size = 22f, bold = true),
            line("This guide explains how the app works and why", gap = 30f),
            line("each step matters for your data."),
            line("Installation", size = 15f, bold = true, gap = 28f),
            line("• Download the installer from the", gap = 22f),
            line("official website.", x = 84f),
            line("◦ Check the file size.", x = 90f),
            line("• Run the installer."),
            line("1. Open Settings.", gap = 22f),
            line("2) Choose a language."),
            line("Next steps", bold = true, gap = 24f),
            line("Restart the phone.", gap = 18f),
            // A footer page number in the bottom 8% of the page is dropped.
            PdfLine("12", page = 1, x = 300f, y = 800f, fontSize = 9f, bold = false, pageHeight = 842f),
        )
        val expected = listOf(
            DocBlock(BlockKind.HEADING, "User Guide", level = 1),
            DocBlock(BlockKind.PARAGRAPH, "This guide explains how the app works and why each step matters for your data."),
            DocBlock(BlockKind.HEADING, "Installation", level = 2),
            DocBlock(BlockKind.BULLET, "Download the installer from the official website.", level = 0),
            DocBlock(BlockKind.BULLET, "Check the file size.", level = 1),
            DocBlock(BlockKind.BULLET, "Run the installer.", level = 0),
            DocBlock(BlockKind.NUMBERED, "Open Settings.", level = 0, marker = "1."),
            DocBlock(BlockKind.NUMBERED, "Choose a language.", level = 0, marker = "2)"),
            DocBlock(BlockKind.HEADING, "Next steps", level = 3),
            DocBlock(BlockKind.PARAGRAPH, "Restart the phone."),
        )
        assertEquals(expected, PdfLayoutAnalyzer.analyze(lines))
    }

    @Test
    fun `paragraphs split on spacing and rejoin hyphenated words`() {
        val lines = listOf(
            line("The first para-"),
            line("graph ends here."),
            line("A second paragraph starts after a gap.", gap = 26f),
        )
        assertEquals(
            listOf(
                DocBlock(BlockKind.PARAGRAPH, "The first paragraph ends here."),
                DocBlock(BlockKind.PARAGRAPH, "A second paragraph starts after a gap."),
            ),
            PdfLayoutAnalyzer.analyze(lines),
        )
    }

    @Test
    fun `a paragraph continues across a page break`() {
        val lines = listOf(
            line("This sentence runs on to the"),
            PdfLine("next page without stopping.", page = 2, x = 72f, y = 80f, fontSize = 11f, bold = false, pageHeight = 842f),
        )
        assertEquals(
            listOf(DocBlock(BlockKind.PARAGRAPH, "This sentence runs on to the next page without stopping.")),
            PdfLayoutAnalyzer.analyze(lines),
        )
    }

    @Test
    fun `page mode never merges across a page break and keeps blank pages`() {
        val lines = listOf(
            PdfLine("Chapter One", 1, 72f, 80f, 20f, true, 842f),
            PdfLine("This sentence runs on to the", 1, 72f, 120f, 11f, false, 842f),
            PdfLine("next page without stopping.", 3, 72f, 80f, 11f, false, 842f),
            PdfLine("• A bullet on page three", 3, 72f, 100f, 11f, false, 842f),
        )
        val pages = PdfLayoutAnalyzer.analyzeByPage(lines, pageCount = 3)
        assertEquals(3, pages.size)
        assertEquals(
            listOf(
                DocBlock(BlockKind.HEADING, "Chapter One", level = 1),
                DocBlock(BlockKind.PARAGRAPH, "This sentence runs on to the"),
            ),
            pages[0],
        )
        assertEquals(emptyList<DocBlock>(), pages[1]) // e.g. a full-page picture
        assertEquals(
            listOf(
                DocBlock(BlockKind.PARAGRAPH, "next page without stopping."),
                DocBlock(BlockKind.BULLET, "A bullet on page three", level = 0),
            ),
            pages[2],
        )
    }

    @Test
    fun `a paragraph's short last line ends it, even without spacing or indent`() {
        val lines = listOf(
            line("He looked at the long road ahead and thought").copy(right = 520f),
            line("about home.").copy(right = 140f),
            line("She did not answer. The wind was cold and the").copy(right = 522f),
            line("night was very long indeed.").copy(right = 260f),
            line("“Come,” he said.").copy(right = 160f),
            line("They walked on together until the town").copy(right = 521f),
        )
        assertEquals(
            listOf(
                "He looked at the long road ahead and thought about home.",
                "She did not answer. The wind was cold and the night was very long indeed.",
                "“Come,” he said.",
                "They walked on together until the town",
            ),
            PdfLayoutAnalyzer.analyze(lines).map { it.text },
        )
    }

    @Test
    fun `chapter titles survive running-header removal and pages are never falsely blank`() {
        // Every page: the running header (the chapter title, small, in the top margin) and a page number.
        fun page(n: Int, body: String?) = listOfNotNull(
            PdfLine("32 Historical examples", n, 72f, 40f, 9f, false, 842f),
            body?.let { PdfLine(it, n, 72f, 200f, 11f, false, 842f) },
            PdfLine("$n", n, 300f, 810f, 9f, false, 842f),
        )
        val lines =
            // The chapter's opening page: the same title, but large and bold.
            listOf(
                PdfLine("32 Historical examples", 1, 72f, 60f, 22f, true, 842f),
                PdfLine("The first example comes from Rome.", 1, 72f, 200f, 11f, false, 842f),
                PdfLine("1", 1, 300f, 810f, 9f, false, 842f),
            ) +
                page(2, "Body text on page two.") +
                page(3, "Body text on page three.") +
                page(4, null) + // nothing on it but the header line and its number
                page(5, "Body text on page five.")

        val pages = PdfLayoutAnalyzer.analyzeByPage(lines, pageCount = 5)
        assertEquals(
            listOf(
                DocBlock(BlockKind.HEADING, "32 Historical examples", level = 1),
                DocBlock(BlockKind.PARAGRAPH, "The first example comes from Rome."),
            ),
            pages[0],
        )
        assertEquals(listOf("Body text on page two."), pages[1].map { it.text }) // header and number dropped
        assertEquals(listOf("32 Historical examples"), pages[3].map { it.text }) // kept, not blank
    }
}
