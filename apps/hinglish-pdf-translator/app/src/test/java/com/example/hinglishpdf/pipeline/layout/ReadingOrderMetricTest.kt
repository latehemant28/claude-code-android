package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.layout.ReadingOrderMetric.Group
import com.example.hinglishpdf.pipeline.layout.ReadingOrderMetric.ReadingOrder
import com.example.hinglishpdf.pipeline.layout.ReadingOrderMetric.Region
import com.example.hinglishpdf.pipeline.layout.ReadingOrderMetric.Relation
import com.example.hinglishpdf.pipeline.segment.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ICDAR 2013 reading-order measure itself (Clausner et al.). */
class ReadingOrderMetricTest {

    private fun box(row: Int, col: Int = 0) = Box(col * 300f, row * 100f, col * 300f + 250f, row * 100f + 80f)

    @Test
    fun `the same order scores 100 percent`() {
        val truth = ReadingOrder(listOf("a" to box(0), "b" to box(1), "c" to box(2)))
        val result = ReadingOrderMetric.evaluate(truth, ReadingOrder(listOf("x" to box(0), "y" to box(1), "z" to box(2))))
        assertEquals(0.0, result.error, 0.0)
        assertEquals(1.0, result.success, 0.0)
    }

    @Test
    fun `two regions swapped - penalties from the paper's matrix, success from equations 1 and 2`() {
        val truth = ReadingOrder(listOf("a" to box(0), "b" to box(1), "c" to box(2)))
        val detected = ReadingOrder(listOf("b" to box(1), "a" to box(0), "c" to box(2)))
        val result = ReadingOrderMetric.evaluate(truth, detected)
        // b→a against b←a: 40; b -x- c against b→c: 20; a→c against a -x- c: 20.
        assertEquals(80.0, result.error, 1e-9)
        assertEquals(1.0 / (80.0 / 60.0 + 1.0), result.success, 1e-9)
    }

    @Test
    fun `relation types follow the tree`() {
        val tree = Group(true, listOf(Region("r1"), Group(false, listOf(Region("r2"), Region("r3"))), Region("r4")))
        assertEquals(Relation.SOMEWHERE_BEFORE, ReadingOrderMetric.relation(tree, "r1", "r2"))
        assertEquals(Relation.SOMEWHERE_AFTER, ReadingOrderMetric.relation(tree, "r2", "r1"))
        assertEquals(Relation.UNORDERED, ReadingOrderMetric.relation(tree, "r2", "r3"))
        assertEquals(Relation.NOT_DIRECT, ReadingOrderMetric.relation(tree, "r1", "r4"))
        assertEquals(Relation.NOT_DEFINED, ReadingOrderMetric.relation(tree, "r1", "header"))
        val sequence = Group(true, listOf(Region("a"), Region("b")))
        assertEquals(Relation.DIRECT_SUCCESSOR, ReadingOrderMetric.relation(sequence, "a", "b"))
        assertEquals(Relation.DIRECT_PREDECESSOR, ReadingOrderMetric.relation(sequence, "b", "a"))
    }

    @Test
    fun `a region the analysis split in two is compared through its overlap`() {
        val truth = ReadingOrder(listOf("p" to Box(0f, 0f, 250f, 180f), "q" to box(2)))
        val detected = ReadingOrder(listOf("p1" to box(0), "p2" to box(1), "q" to box(2)))
        // p1 and p2 both stand for p: only p1 -x- q, where the truth has p → q, costs (20).
        val result = ReadingOrderMetric.evaluate(truth, detected)
        assertEquals(20.0, result.error, 1e-9)
        assertEquals(1, result.penalties.size)
    }

    @Test
    fun `reading two columns row by row scores far below reading them column by column`() {
        val truth = ReadingOrder((0..3).map { "L$it" to box(it, 0) } + (0..3).map { "R$it" to box(it, 1) })
        val rowByRow = ReadingOrder((0..3).flatMap { listOf("L$it" to box(it, 0), "R$it" to box(it, 1)) })
        val score = ReadingOrderMetric.evaluate(truth, rowByRow).success
        assertTrue("row by row: $score", score < 0.5)
    }
}
