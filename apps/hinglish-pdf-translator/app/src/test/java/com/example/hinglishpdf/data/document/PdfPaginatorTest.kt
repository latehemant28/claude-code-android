package com.example.hinglishpdf.data.document

import com.example.hinglishpdf.data.document.PdfPaginator.Measured
import com.example.hinglishpdf.data.document.PdfPaginator.Slice
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPaginatorTest {

    /** [lines] lines of 10 pt each. */
    private fun block(lines: Int, spaceBefore: Float = 5f, breakBefore: Boolean = false, heading: Boolean = false) =
        Measured(
            spaceBefore,
            FloatArray(lines) { it * 10f },
            FloatArray(lines) { (it + 1) * 10f },
            pageBreakBefore = breakBefore,
            keepWithNext = heading,
        )

    @Test
    fun `blocks flow onto the next page line by line`() {
        // Page: 100 pt = 10 lines. 6 lines, then 8 lines (5 pt gap).
        val pages = PdfPaginator.paginate(listOf(block(6), block(8)), 100f)
        assertEquals(
            listOf(
                listOf(Slice(0, 0, 6, 0f), Slice(1, 0, 3, 65f)), // 3 lines fit after the gap
                listOf(Slice(1, 3, 8, 0f)), // the rest continues at the top, no gap
            ),
            pages,
        )
    }

    @Test
    fun `a heading moves to the next page with its paragraph`() {
        val pages = PdfPaginator.paginate(listOf(block(8), block(1, heading = true), block(5)), 100f)
        assertEquals(listOf(Slice(0, 0, 8, 0f)), pages[0])
        assertEquals(listOf(Slice(1, 0, 1, 0f), Slice(2, 0, 5, 15f)), pages[1])
    }

    @Test
    fun `no lone first line at the bottom, no lone last line at the top`() {
        // 8 lines used + gap: only 1 line of the next paragraph would fit.
        val orphan = PdfPaginator.paginate(listOf(block(8), block(4)), 100f)
        assertEquals(listOf(Slice(1, 0, 4, 0f)), orphan[1])

        // 9 of 10 lines would fit, leaving 1: move one more line over.
        val widow = PdfPaginator.paginate(listOf(block(10, spaceBefore = 0f)), 90f)
        assertEquals(listOf(Slice(0, 0, 8, 0f)), widow[0])
        assertEquals(listOf(Slice(0, 8, 10, 0f)), widow[1])
    }

    @Test
    fun `chapters start on a new page`() {
        val pages = PdfPaginator.paginate(listOf(block(2), block(1, breakBefore = true, heading = true), block(2)), 100f)
        assertEquals(2, pages.size)
        assertEquals(Slice(1, 0, 1, 0f), pages[1][0])
    }

    @Test
    fun `an empty book is one empty page`() {
        assertEquals(listOf(emptyList<Slice>()), PdfPaginator.paginate(emptyList(), 100f))
    }
}
