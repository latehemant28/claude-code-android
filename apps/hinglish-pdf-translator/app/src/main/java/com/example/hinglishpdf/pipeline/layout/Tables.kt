package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.Box
import kotlin.math.abs
import kotlin.math.roundToInt

/** A table found on a page: its cells with row and column (0-based), row-major. */
class Table(val cells: List<Triple<TextLine, Int, Int>>) {
    val box: Box get() = cells.map { it.first.box }.reduce(Box::union)
}

/**
 * Tables: at least three rows (two if there are three or more columns) of
 * short pieces side by side whose left edges line up (two or more aligned
 * x-positions). Cells are narrow on average, which tells a table from
 * columns of body text, and so does flow: in columns of prose a piece often
 * runs on into the one below it (no closing punctuation, then a lower-case
 * start). Columns are the clusters of the cells' left edges.
 */
object TableDetector {

    fun detect(lines: List<TextLine>, config: PipelineConfig): List<Table> {
        val textWidth = (lines.maxOfOrNull { it.box.x1 } ?: 0f) - (lines.minOfOrNull { it.box.x0 } ?: 0f)
        if (textWidth <= 0f) return emptyList()
        val rows = lines.groupBy { (it.baseline / 2f).roundToInt() }.toSortedMap().values.map { row -> row.sortedBy { it.box.x0 } }
        val out = mutableListOf<Table>()
        var block = mutableListOf<List<TextLine>>()
        fun isRow(row: List<TextLine>) = row.size >= 2 && row.all { it.box.width < textWidth * config.tableCellMaxRatio }
        fun aligned(a: List<TextLine>, b: List<TextLine>) = a.count { x -> b.any { abs(it.box.x0 - x.box.x0) <= ALIGN } } >= 2
        fun close() {
            val columns = block.maxOfOrNull { it.size } ?: 0
            val cells = block.flatten()
            val narrow = cells.isNotEmpty() && cells.sumOf { it.box.width.toDouble() } / cells.size < textWidth * config.tableCellMeanRatio
            val enoughRows = block.size >= 3 || (block.size >= 2 && columns >= 3)
            if (narrow && enoughRows && flow(block, config) < config.tableMaxFlow) out += table(block)
            block = mutableListOf()
        }
        for (row in rows) {
            val previous = block.lastOrNull()
            when {
                !isRow(row) -> close()
                previous == null -> block += row
                aligned(previous, row) && row.first().box.y0 - previous.first().box.y1 < 2.5f * row.first().size -> block += row
                else -> {
                    close()
                    block += row
                }
            }
        }
        close()
        return out
    }

    /** Share of vertically neighbouring cells where the upper one runs on into the lower one. */
    private fun flow(block: List<List<TextLine>>, config: PipelineConfig): Float {
        var pairs = 0
        var flowing = 0
        for ((upper, lower) in block.zipWithNext()) {
            for (cell in upper) {
                val below = lower.firstOrNull { abs(it.box.x0 - cell.box.x0) <= ALIGN } ?: continue
                pairs++
                val next = below.text.trimStart().firstOrNull()
                if (!config.endsTerminally(cell.text) && next != null && next.isLowerCase()) flowing++
            }
        }
        return if (pairs == 0) 0f else flowing.toFloat() / pairs
    }

    private fun table(rows: List<List<TextLine>>): Table {
        // Columns: clusters of left edges across the whole table.
        val edges = mutableListOf<Float>()
        for (x in rows.flatten().map { it.box.x0 }.sorted()) if (edges.none { abs(it - x) <= ALIGN }) edges += x
        return Table(
            rows.flatMapIndexed { r, row ->
                row.map { cell -> Triple(cell, r, edges.indices.minBy { abs(edges[it] - cell.box.x0) }) }
            },
        )
    }

    private const val ALIGN = 4f
}
