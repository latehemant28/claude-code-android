package com.example.hinglishpdf.data.document

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFormatterTest {

    @Test
    fun `plain text keeps headings, bullets, numbering and paragraph breaks`() {
        val blocks = listOf(
            DocBlock(BlockKind.HEADING, "Setup", level = 1),
            DocBlock(BlockKind.PARAGRAPH, "Intro."),
            DocBlock(BlockKind.BULLET, "One", level = 0),
            DocBlock(BlockKind.BULLET, "Nested", level = 1),
            DocBlock(BlockKind.NUMBERED, "Step", marker = "1."),
            DocBlock(BlockKind.PARAGRAPH, "End."),
        )
        val translations = listOf("Setup karna", "Shuruaat.", "Ek", "Andar wala", "Kadam", null)
        assertEquals(
            "Setup karna\n\nShuruaat.\n\n• Ek\n    ◦ Andar wala\n1. Kadam\n\nEnd.",
            DocumentFormatter.toPlainText(blocks, translations),
        )
    }
}
