package com.example.hinglishpdf.data.document

/**
 * Splits measured blocks of text into pages: whole lines only, a block may
 * continue on the next page, and the usual typesetting rules apply:
 *
 *  - space before a block is dropped at the top of a page;
 *  - a heading is never left alone at the bottom of a page (it moves on
 *    with the first lines of what follows it);
 *  - no single first line of a paragraph at the bottom of a page, and no
 *    single last line at the top of the next one;
 *  - [Measured.pageBreakBefore] starts a new page (chapters).
 *
 * Pure Kotlin: the PDF renderer measures with StaticLayout, the tests with numbers.
 */
object PdfPaginator {

    /** A block, measured: [lineTops]/[lineBottoms] are relative to the block's own top. */
    class Measured(
        val spaceBefore: Float,
        val lineTops: FloatArray,
        val lineBottoms: FloatArray,
        val pageBreakBefore: Boolean = false,
        val keepWithNext: Boolean = false,
    ) {
        val lineCount: Int get() = lineTops.size
        fun height(from: Int, to: Int): Float = if (to <= from) 0f else lineBottoms[to - 1] - lineTops[from]
    }

    /** Lines [fromLine], [toLine]) of block [block], drawn with the top of [fromLine] at [y] on the page. */
    data class Slice(val block: Int, val fromLine: Int, val toLine: Int, val y: Float)

    fun paginate(blocks: List<Measured>, pageHeight: Float): List<List<Slice>> {
        val pages = mutableListOf(mutableListOf<Slice>())
        var y = 0f

        fun newPage() {
            if (pages.last().isNotEmpty()) pages += mutableListOf<Slice>()
            y = 0f
        }

        blocks.forEachIndexed { index, block ->
            if (block.pageBreakBefore && y > 0f) newPage()
            val n = block.lineCount
            if (n == 0) return@forEachIndexed
            var space = if (y == 0f) 0f else block.spaceBefore

            if (block.keepWithNext && y > 0f) {
                val next = blocks.getOrNull(index + 1)?.takeIf { it.lineCount > 0 && !it.pageBreakBefore }
                val nextNeed = next?.let { it.spaceBefore + it.height(0, minOf(2, it.lineCount)) } ?: 0f
                if (y + space + block.height(0, n) + nextNeed > pageHeight) {
                    newPage()
                    space = 0f
                }
            }

            var line = 0
            while (line < n) {
                val top = y + space
                var end = line
                while (end < n && top + block.height(line, end + 1) <= pageHeight) end++
                when {
                    end == line && y == 0f -> end = line + 1 // a line taller than a page: place it anyway
                    end == line -> { newPage(); space = 0f; continue }
                    // A lone first line at the bottom: move the paragraph to the next page.
                    end < n && line == 0 && end == 1 && n >= 3 && y > 0f -> { newPage(); space = 0f; continue }
                    // A lone last line at the top of the next page: take one more line over.
                    end < n && n - end == 1 && end - line >= 3 -> end--
                }
                pages.last() += Slice(index, line, end, top)
                y = top + block.height(line, end)
                line = end
                if (line < n) {
                    newPage()
                    space = 0f
                }
            }
        }
        if (pages.size > 1 && pages.last().isEmpty()) pages.removeAt(pages.lastIndex)
        return pages
    }
}
