package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.text.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockChunkerTest {

    private fun words(n: Int, p: String = "w") = (1..n).joinToString(" ") { "$p$it" }

    @Test
    fun `blocks are packed whole and never exceed the limit`() {
        val blocks = (1..20).map { DocBlock(BlockKind.PARAGRAPH, words(15, "b$it") + ".") }
        val chunks = BlockChunker.chunk(blocks, maxWords = 50, maxLines = 30)
        assertTrue(chunks.all { c -> c.sumOf { TextChunker.countWords(it.text) } <= 50 })
        assertEquals((0 until 20).toList(), chunks.flatten().map { it.blockIndex })
        assertTrue(chunks.flatten().none { it.continuation })
    }

    @Test
    fun `an oversized block is split into marked continuation pieces`() {
        val long = (1..6).joinToString(" ") { words(10, "s$it") + "." }
        val chunks = BlockChunker.chunk(listOf(DocBlock(BlockKind.BULLET, long)), maxWords = 25)
        val units = chunks.flatten()
        assertTrue(units.size > 1)
        assertEquals(false, units.first().continuation)
        assertTrue(units.drop(1).all { it.continuation && it.blockIndex == 0 })
        assertEquals(long, units.joinToString(" ") { it.text })
    }

    @Test
    fun `code and empty blocks are not sent to the model`() {
        val blocks = listOf(
            DocBlock(BlockKind.CODE, "val x = 1"),
            DocBlock(BlockKind.PARAGRAPH, "12345"),
            DocBlock(BlockKind.PARAGRAPH, "Real text here."),
        )
        assertEquals(listOf(2), BlockChunker.chunk(blocks, 100).flatten().map { it.blockIndex })
    }

    @Test
    fun `line count per chunk is capped`() {
        val blocks = (1..70).map { DocBlock(BlockKind.BULLET, "item $it") }
        val chunks = BlockChunker.chunk(blocks, maxWords = 1000, maxLines = 30)
        assertEquals(listOf(30, 30, 10), chunks.map { it.size })
    }

    @Test
    fun `a normal page is one chunk, a huge one is split at block boundaries`() {
        val page = (1..12).map { DocBlock(BlockKind.PARAGRAPH, words(45, "p$it") + ".") } // 540 words
        assertEquals(listOf(12), BlockChunker.chunk(page).map { it.size })

        val huge = (1..30).map { DocBlock(BlockKind.PARAGRAPH, words(45, "h$it") + ".") } // 1350 words
        val chunks = BlockChunker.chunk(huge)
        assertEquals(listOf(17, 13), chunks.map { it.size })
        assertTrue(chunks.all { c -> c.sumOf { TextChunker.countWords(it.text) } <= BlockChunker.CHUNK_WORDS })

        val bullets = (1..100).map { DocBlock(BlockKind.BULLET, "item $it") }
        assertEquals(listOf(40, 40, 20), BlockChunker.chunk(bullets).map { it.size })
    }

    @Test
    fun `epub sections keep every block once, in order`() {
        val blocks = listOf(
            DocBlock(BlockKind.HEADING, words(3), level = 1),
            DocBlock(BlockKind.PARAGRAPH, words(30)),
            DocBlock(BlockKind.CODE, "x = 1"),
            DocBlock(BlockKind.BULLET, words(30)),
            DocBlock(BlockKind.PARAGRAPH, words(90)),
        )
        val sections = BlockChunker.sections(blocks, maxWords = 64)
        assertEquals(blocks, sections.flatten())
        assertEquals(listOf(3, 1, 1), sections.map { it.size })
    }
}
