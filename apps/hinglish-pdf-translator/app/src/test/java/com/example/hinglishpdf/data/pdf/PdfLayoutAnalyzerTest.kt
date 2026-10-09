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
}
