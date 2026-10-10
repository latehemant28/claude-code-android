package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.Box

/**
 * Reading order by recursive X-Y cut (Nagy & Seth; see O'Gorman & Kasturi,
 * ch. 4). A region is cut along vertical whitespace into columns, read left
 * to right, or else along horizontal whitespace into bands, read top to
 * bottom, recursively; what cannot be cut is read row by row. Born-digital
 * book pages are Manhattan layouts without skew, which is where X-Y cut is
 * at its best, and its tree is the reading order.
 *
 * Two refinements for books: a column cut needs [PipelineConfig.xyMinColumnItems]
 * items on each side (a lone note beside a paragraph is not a column), and
 * bands whose gutters line up are merged back into one column region before
 * they are cut, so a paragraph gap that happens to fall at the same height in
 * both columns does not make the page read row by row
 * ([PipelineConfig.xyMergeAlignedBands]).
 *
 * Items are anything with a box: lines, tables, figures.
 */
object XyCut {

    class Item<T>(val box: Box, val value: T)

    /**
     * @param column 0 outside any column region, else the 1-based column at
     *   the innermost column cut.
     * @param columnId the path of column cuts down to the item ("" outside any).
     */
    data class Placed<T>(val value: T, val box: Box, val column: Int, val columnId: String)

    class Result<T>(val order: List<Placed<T>>, val columns: Int)

    fun <T> order(items: List<Item<T>>, bodySize: Float, config: PipelineConfig): Result<T> {
        val cutter = Cutter<T>(
            columnGap = maxOf(config.minGutter, config.xyColumnGapEm * bodySize),
            bandGap = config.xyBandGapEm * bodySize,
            minColumnItems = config.xyMinColumnItems.coerceAtLeast(1),
            mergeBands = config.xyMergeAlignedBands,
        )
        cutter.cut(items, column = 0, columnId = "")
        return Result(cutter.out, cutter.columns)
    }

    private class Cutter<T>(val columnGap: Float, val bandGap: Float, val minColumnItems: Int, val mergeBands: Boolean) {
        val out = mutableListOf<Placed<T>>()
        var columns = 1

        fun cut(items: List<Item<T>>, column: Int, columnId: String) {
            if (items.size <= 1) return leaf(items, column, columnId)
            val gutters = gutters(items)
            if (gutters.isNotEmpty()) {
                val groups = splitAt(items, gutters, vertical = true)
                columns = maxOf(columns, groups.size)
                groups.forEachIndexed { i, group -> cut(group, i + 1, "$columnId/${i + 1}") }
                return
            }
            var bands = splitAt(items, gaps(items, vertical = false, min = bandGap), vertical = false)
            if (mergeBands) bands = mergeAligned(bands)
            if (bands.size <= 1) return leaf(items, column, columnId)
            bands.forEach { cut(it, column, columnId) }
        }

        /** Vertical gaps wide enough, with enough items on each side. */
        fun gutters(items: List<Item<T>>): List<Pair<Float, Float>> =
            gaps(items, vertical = true, min = columnGap).filter { (g0, g1) ->
                items.count { it.box.x1 <= g0 + EPS } >= minColumnItems && items.count { it.box.x0 >= g1 - EPS } >= minColumnItems
            }

        /** Whitespace in the projection of [items] on the x axis ([vertical] gaps) or the y axis. */
        fun gaps(items: List<Item<T>>, vertical: Boolean, min: Float): List<Pair<Float, Float>> {
            val spans = items.map { if (vertical) it.box.x0 to it.box.x1 else it.box.y0 to it.box.y1 }.sortedBy { it.first }
            val out = mutableListOf<Pair<Float, Float>>()
            var end = spans.first().second
            for ((s, e) in spans.drop(1)) {
                if (s - end >= min) out += end to s
                end = maxOf(end, e)
            }
            return out
        }

        fun splitAt(items: List<Item<T>>, gaps: List<Pair<Float, Float>>, vertical: Boolean): List<List<Item<T>>> {
            if (gaps.isEmpty()) return listOf(items)
            val groups = List(gaps.size + 1) { mutableListOf<Item<T>>() }
            for (item in items) {
                val start = if (vertical) item.box.x0 else item.box.y0
                groups[gaps.count { (_, g1) -> start >= g1 - EPS }] += item
            }
            return groups.filter { it.isNotEmpty() }
        }

        /** Consecutive bands whose gutters overlap by at least a gutter's width become one region. */
        fun mergeAligned(bands: List<List<Item<T>>>): List<List<Item<T>>> {
            val out = mutableListOf<MutableList<Item<T>>>()
            var previousGutters: List<Pair<Float, Float>> = emptyList()
            for (band in bands) {
                val gutters = gutters(band)
                val aligned = out.isNotEmpty() && gutters.any { (a0, a1) ->
                    previousGutters.any { (b0, b1) -> minOf(a1, b1) - maxOf(a0, b0) >= columnGap }
                }
                if (aligned) {
                    out.last() += band
                    previousGutters = gutters(out.last())
                } else {
                    out += band.toMutableList()
                    previousGutters = gutters
                }
            }
            return out
        }

        /** What cannot be cut is read row by row, left to right in a row. */
        fun leaf(items: List<Item<T>>, column: Int, columnId: String) {
            val sorted = items.sortedBy { it.box.y0 }
            val rows = mutableListOf<MutableList<Item<T>>>()
            for (item in sorted) {
                val row = rows.lastOrNull()
                val first = row?.first()
                if (first != null && item.box.y0 < first.box.y0 + 0.5f * minOf(first.box.height, item.box.height)) row += item else rows += mutableListOf(item)
            }
            rows.forEach { row -> row.sortedBy { it.box.x0 }.forEach { out += Placed(it.value, it.box, column, columnId) } }
        }
    }

    private const val EPS = 1f
}
