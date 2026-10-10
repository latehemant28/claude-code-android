package com.example.hinglishpdf.data.db

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `page structure survives the database round trip`() {
        val blocks = listOf(
            DocBlock(BlockKind.HEADING, "Setup \"guide\"", level = 2),
            DocBlock(BlockKind.NUMBERED, "Step\nwith newline", level = 1, marker = "a)"),
            DocBlock(BlockKind.PARAGRAPH, "Unicode: नमस्ते • ✓"),
        )
        assertEquals(blocks, converters.jsonToBlocks(converters.blocksToJson(blocks)))
    }

    @Test
    fun `translations keep nulls, and a missing translation stays NULL`() {
        val translations = listOf("Ek", null, "Teen")
        assertEquals(translations, converters.jsonToStrings(converters.stringsToJson(translations)))
        assertNull(converters.stringsToJson(null)) // NULL column = page not translated yet
    }
}
