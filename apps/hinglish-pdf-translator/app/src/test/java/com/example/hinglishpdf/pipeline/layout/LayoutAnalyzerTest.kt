package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.LayoutWeights
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.layout.ReadingOrderMetric.ReadingOrder
import com.example.hinglishpdf.pipeline.pdf.page
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.TableCellRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 1 output: blocks with one role each, columns, tables, reading order measured with the ICDAR 2013 metric. */
class LayoutAnalyzerTest {

    private val left = listOf(
        listOf("The river rose all through the", "night until the lower streets", "were deep under the water."),
        listOf("In the morning the people came", "down the hill to look at what", "the flood had left behind."),
    )
    private val right = listOf(
        listOf("Nobody in the town could say", "when it had last been this bad,", "not even the oldest of them."),
        listOf("By noon the water was going", "down again and the shops were", "opening one by one."),
    )

    private fun success(truth: List<Pair<String, Box>>, page: PageLayout) =
        ReadingOrderMetric.evaluate(ReadingOrder(truth), ReadingOrderMetric.of(page)).success

    /** A title over two columns whose paragraph gaps line up across the page. */
    private fun twoColumns(truth: MutableList<Pair<String, Box>>) = page {
        truth += "title" to block(listOf("A Title Spanning Both Columns Of The Whole Page"), 50f, 60f, size = 16f)
        truth += "L1" to block(left[0], 50f, 100f)
        truth += "L2" to block(left[1], 50f, 150f)
        truth += "R1" to block(right[0], 320f, 100f)
        truth += "R2" to block(right[1], 320f, 150f)
    }

    @Test
    fun `two columns under a title are read column by column even when their paragraph gaps line up`() {
        val truth = mutableListOf<Pair<String, Box>>()
        val page = LayoutAnalyzer.analyze(listOf(twoColumns(truth))).pages.single()
        assertEquals(1.0, success(truth, page), 0.0)
        assertEquals(2, page.columns)
        assertEquals(listOf(0, 1, 1, 2, 2), page.blocks.map { it.column })
        assertEquals(listOf(BlockRole.HEADING) + List(4) { BlockRole.BODY }, page.blocks.map { it.role })

        // Without merging aligned bands the page would be read row by row: measurably worse.
        val unmerged = LayoutAnalyzer.analyze(listOf(twoColumns(mutableListOf())), PipelineConfig(xyMergeAlignedBands = false)).pages.single()
        assertTrue(success(truth, unmerged) < 1.0)
    }

    @Test
    fun `a full-width figure between two column regions is read in bands - top columns, then bottom columns`() {
        val truth = mutableListOf<Pair<String, Box>>()
        val page = LayoutAnalyzer.analyze(
            listOf(
                page {
                    truth += "TL" to block(left[0], 50f, 100f)
                    truth += "TR" to block(right[0], 320f, 100f)
                    image(Box(50f, 180f, 520f, 300f))
                    truth += "BL" to block(listOf("In the morning the people came", "down the hill to look at what", "the flood had left behind them", "and then they went home."), 50f, 340f)
                    truth += "BR" to block(listOf("By noon the water was going", "down again and the shops were", "opening their doors one by one", "and the day went on."), 320f, 340f)
                },
            ),
        ).pages.single()
        assertEquals(1.0, success(truth, page), 0.0)
    }

    @Test
    fun `three columns between full-width paragraphs`() {
        val truth = mutableListOf<Pair<String, Box>>()
        val page = LayoutAnalyzer.analyze(
            listOf(
                page {
                    truth += "above" to block(listOf("The paragraph above the columns spans the whole width of the text block here."), 50f, 80f)
                    truth += "c1" to block(listOf("First column text", "goes down its own", "narrow strip and", "ends right here."), 50f, 110f)
                    truth += "c2" to block(listOf("Second column text", "follows on below", "in the middle and", "ends here too."), 200f, 110f)
                    truth += "c3" to block(listOf("Third column text", "sits on the right", "and is read last", "of the three."), 350f, 110f)
                    truth += "below" to block(listOf("The paragraph below the columns spans the whole width of the text block too."), 50f, 180f)
                },
            ),
        ).pages.single()
        assertEquals(1.0, success(truth, page), 0.0)
        assertEquals(3, page.columns)
        assertEquals(3, page.blocks.map { it.columnId }.filter { it.isNotEmpty() }.distinct().size)
    }

    @Test
    fun `footnotes are read after the rest of their page`() {
        val truth = mutableListOf<Pair<String, Box>>()
        val page = LayoutAnalyzer.analyze(
            listOf(
                page {
                    truth += "L" to block(left[0], 50f, 100f)
                    truth += "R" to block(right[0], 320f, 100f)
                    truth += "note" to block(listOf("1 The flood of 1910 was worse."), 50f, 700f, size = 8f)
                },
            ),
        ).pages.single()
        assertEquals(listOf(BlockRole.BODY, BlockRole.BODY, BlockRole.FOOTNOTE), page.blocks.map { it.role })
        assertEquals(1.0, success(truth, page), 0.0)
    }

    @Test
    fun `a table is read where it stands, cell by cell with row and column`() {
        val page = LayoutAnalyzer.analyze(
            listOf(
                page {
                    text("Some text before the table of people in the town.", 50f, 80f)
                    val rows = listOf(listOf("Name", "Age", "City"), listOf("Ravi", "34", "Pune"), listOf("Asha", "29", "Delhi"))
                    rows.forEachIndexed { r, row -> row.forEachIndexed { c, cell -> text(cell, 50f + c * 120f, 110f + r * 14f) } }
                    text("Some text after the table, at the end of the page.", 50f, 180f)
                },
            ),
        ).pages.single()
        assertEquals(1, page.tables)
        val cells = page.blocks.filter { it.role == BlockRole.TABLE_CELL }
        assertEquals((0..2).flatMap { r -> (0..2).map { c -> TableCellRef(0, r, c) } }, cells.map { it.cell })
        assertEquals((1..9).toList(), cells.map { it.readingIndex })
        assertEquals(10, page.blocks.last().readingIndex)
    }

    @Test
    fun `every block has exactly one role, the best scoring one, and furniture is kept apart`() {
        val pages = (1..4).map { n ->
            page(n) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                block(left[0], 50f, 100f)
                text("$n", 295f, 785f, size = 9f)
            }
        }
        val layout = LayoutAnalyzer.analyze(pages)
        for (page in layout.pages) {
            assertEquals(listOf(BlockRole.HEADER, BlockRole.PAGE_NUMBER), page.furniture.map { it.role })
            assertTrue(page.furniture.all { it.readingIndex == -1 })
            assertEquals(listOf(BlockRole.BODY), page.blocks.map { it.role })
            for (block in page.blocks + page.furniture) {
                assertEquals(block.scores.maxBy { it.value }.key, block.role)
            }
        }
        val ids = layout.pages.flatMap { p -> (p.blocks + p.furniture).map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the weights come from the config`() {
        val pages = (1..4).map { n -> page(n) { text("A BOOK OF RIVERS", 250f, 30f, size = 9f); block(left[0], 50f, 100f) } }
        val noRepetition = PipelineConfig(weights = LayoutWeights(repetition = 0f))
        val page = LayoutAnalyzer.analyze(pages, noRepetition).pages.first()
        assertTrue(page.furniture.isEmpty())
        assertEquals("A BOOK OF RIVERS", page.blocks.first().text)
    }

    @Test
    fun `font tiers and heading levels`() {
        val page = LayoutAnalyzer.analyze(
            listOf(
                page {
                    text("Part One", 50f, 70f, size = 24f)
                    text("The Flood", 50f, 110f, size = 16f)
                    block(left[0], 50f, 140f)
                },
            ),
        ).pages.single()
        assertEquals(listOf(1, 2, 0), page.blocks.map { it.fontTier })
        assertEquals(listOf(1, 2, 0), page.blocks.map { it.level })
    }

    @Test
    fun `captions by an image next to them, and text drawn inside the image`() {
        val page = LayoutAnalyzer.analyze(
            listOf(
                page {
                    block(left[0], 50f, 100f)
                    image(Box(50f, 150f, 300f, 300f))
                    text("Inside the picture", 80f, 200f, size = 8f)
                    text("The river at its highest, in 1910.", 50f, 312f, size = 9f)
                },
            ),
        ).pages.single()
        val captions = page.blocks.filter { it.role == BlockRole.CAPTION }
        assertEquals(listOf("Inside the picture", "The river at its highest, in 1910."), captions.map { it.text })
        assertEquals(PipelineConfig().weights.insideFigure, captions[0].scores.getValue(BlockRole.CAPTION))
        assertEquals(PipelineConfig().weights.nearImage, captions[1].scores.getValue(BlockRole.CAPTION))
    }
}
