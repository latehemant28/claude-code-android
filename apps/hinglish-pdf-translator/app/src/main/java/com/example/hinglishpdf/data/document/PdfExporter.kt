package com.example.hinglishpdf.data.document

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.hinglishpdf.data.db.PageEntity
import java.io.OutputStream

/**
 * Writes the translated book as a PDF on standard A4 pages. The text flows
 * from page to page with proper line breaking ([PdfPaginator]) instead of
 * being shrunk to fit, and is drawn with the bundled Noto fonts, which the
 * PDF embeds, so Devanagari renders correctly in any PDF viewer.
 *
 * Structure is kept: sized bold headings, bulleted and numbered lists with
 * their nesting and original markers, quotes, code. For a PDF source, a small
 * "— 45 —" marks where each page of the original begins; for an EPUB
 * source, each top-level heading (a chapter) starts a new page.
 */
class PdfExporter(private val fonts: BundledFonts) {

    /** Where the translated text of one original page or section comes from. */
    class Section(val pageNumber: Int, val blocks: List<Pair<DocBlock, String>>)

    fun write(out: OutputStream, pages: List<PageEntity>, sourceIsPdf: Boolean) {
        val sections = pages.map { page ->
            Section(
                page.pageNumber,
                page.sourceBlocks.mapIndexedNotNull { i, block ->
                    val text = page.translations?.getOrNull(i) ?: block.text
                    if (text.isBlank()) null else block to text
                },
            )
        }
        writeSections(out, sections, sourceIsPdf)
    }

    fun writeSections(out: OutputStream, sections: List<Section>, sourceIsPdf: Boolean) {
        val items = layout(sections, sourceIsPdf)
        val pages = PdfPaginator.paginate(items.map { it.measured }, CONTENT_HEIGHT)
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, slices ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                for (slice in slices) draw(page.canvas, items[slice.block], slice)
                footer(page.canvas, index + 1)
                pdf.finishPage(page)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    // ------------------------------------------------------------- layout

    private class Item(
        val layout: StaticLayout,
        val indent: Float,
        val marker: Pair<String, TextPaint>?,
        val quoteBar: Boolean,
        val measured: PdfPaginator.Measured,
    )

    private fun layout(sections: List<Section>, sourceIsPdf: Boolean): List<Item> {
        val items = mutableListOf<Item>()
        var previous: DocBlock? = null
        for (section in sections) {
            if (sourceIsPdf) {
                items += pageMarker(section.pageNumber, first = items.isEmpty())
                previous = null
                if (section.blocks.isEmpty()) items += note("(इस पेज पर कोई टेक्स्ट नहीं है)")
            }
            for ((block, text) in section.blocks) {
                items += item(block, text, previous, chapterBreak = !sourceIsPdf && items.isNotEmpty())
                previous = block
            }
        }
        if (items.isEmpty()) items += note("(कोई टेक्स्ट नहीं)")
        return items
    }

    private fun item(block: DocBlock, text: String, previous: DocBlock?, chapterBreak: Boolean): Item {
        val paint = paint(BODY_SIZE)
        var indent = 0f
        var marker: String? = null
        var lineSpacing = BODY_LINE_SPACING
        when (block.kind) {
            BlockKind.HEADING -> {
                paint.textSize = BODY_SIZE * when (block.level) { 1 -> 1.7f; 2 -> 1.4f; 3 -> 1.2f; else -> 1.1f }
                paint.typeface = fonts.bold
                lineSpacing = HEADING_LINE_SPACING
            }
            BlockKind.BULLET, BlockKind.NUMBERED -> {
                indent = INDENT * (block.level.coerceIn(0, 6) + 1)
                marker = if (block.kind == BlockKind.BULLET) bulletFor(block.level) else block.marker
            }
            BlockKind.QUOTE -> {
                indent = INDENT
                paint.textSkewX = -0.2f // italic, drawn from the upright Devanagari font
                paint.color = Color.rgb(0x33, 0x33, 0x33)
            }
            BlockKind.CODE -> {
                paint.textSize = BODY_SIZE * 0.85f
                paint.typeface = Typeface.MONOSPACE
                lineSpacing = 1.2f
            }
            BlockKind.PARAGRAPH -> Unit
        }

        val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
        val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
        val spaceBefore = when {
            previous == null -> BODY_SIZE * 0.5f
            block.kind == BlockKind.HEADING -> paint.textSize * 0.9f
            isList && prevIsList -> BODY_SIZE * 0.3f
            else -> BODY_SIZE * 0.75f
        }
        val layout = staticLayout(text, paint, CONTENT_WIDTH - indent, lineSpacing)
        return Item(
            layout = layout,
            indent = indent,
            marker = marker?.let { it to TextPaint(paint).apply { textSkewX = 0f } },
            quoteBar = block.kind == BlockKind.QUOTE,
            measured = measure(
                layout, spaceBefore,
                pageBreakBefore = chapterBreak && block.kind == BlockKind.HEADING && block.level == 1,
                keepWithNext = block.kind == BlockKind.HEADING,
            ),
        )
    }

    /** "— 45 —": where page 45 of the original starts. */
    private fun pageMarker(pageNumber: Int, first: Boolean): Item {
        val paint = paint(8f).apply { color = Color.rgb(0x99, 0x99, 0x99) }
        val layout = staticLayout("— $pageNumber —", paint, CONTENT_WIDTH, 1.2f, Layout.Alignment.ALIGN_CENTER)
        return Item(layout, 0f, null, false, measure(layout, if (first) 0f else BODY_SIZE, keepWithNext = true))
    }

    private fun note(text: String): Item {
        val paint = paint(BODY_SIZE * 0.85f).apply { color = Color.GRAY; textSkewX = -0.2f }
        val layout = staticLayout(text, paint, CONTENT_WIDTH, BODY_LINE_SPACING, Layout.Alignment.ALIGN_CENTER)
        return Item(layout, 0f, null, false, measure(layout, BODY_SIZE * 0.5f))
    }

    private fun paint(size: Float) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = fonts.regular
    }

    private fun staticLayout(
        text: String,
        paint: TextPaint,
        width: Float,
        lineSpacing: Float,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(0f, lineSpacing) // Devanagari needs room above and below for matras
            .setIncludePad(true)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private fun measure(
        layout: StaticLayout,
        spaceBefore: Float,
        pageBreakBefore: Boolean = false,
        keepWithNext: Boolean = false,
    ) = PdfPaginator.Measured(
        spaceBefore = spaceBefore,
        lineTops = FloatArray(layout.lineCount) { layout.getLineTop(it).toFloat() },
        lineBottoms = FloatArray(layout.lineCount) { layout.getLineBottom(it).toFloat() },
        pageBreakBefore = pageBreakBefore,
        keepWithNext = keepWithNext,
    )

    // ------------------------------------------------------------- drawing

    private fun draw(canvas: Canvas, item: Item, slice: PdfPaginator.Slice) {
        val layout = item.layout
        val x = MARGIN_X + item.indent
        val top = MARGIN_TOP + slice.y
        val firstTop = layout.getLineTop(slice.fromLine).toFloat()
        val lastBottom = layout.getLineBottom(slice.toLine - 1).toFloat()

        if (slice.fromLine == 0) {
            item.marker?.let { (marker, paint) ->
                val baseline = top + layout.getLineBaseline(0)
                canvas.drawText(marker, x - paint.measureText("$marker "), baseline, paint)
            }
        }
        if (item.quoteBar) {
            val bar = Paint().apply { color = Color.rgb(0xBB, 0xBB, 0xBB) }
            canvas.drawRect(x - INDENT * 0.6f, top, x - INDENT * 0.6f + 2f, top + lastBottom - firstTop, bar)
        }
        canvas.save()
        canvas.translate(x, top - firstTop)
        canvas.clipRect(-1f, firstTop, layout.width.toFloat() + 1f, lastBottom) // only this page's lines
        layout.draw(canvas)
        canvas.restore()
    }

    private fun footer(canvas: Canvas, number: Int) {
        val paint = paint(9f).apply { color = Color.GRAY }
        val label = number.toString()
        canvas.drawText(label, (PAGE_WIDTH - paint.measureText(label)) / 2, PAGE_HEIGHT - MARGIN_BOTTOM / 2, paint)
    }

    private companion object {
        // A4 in PDF points.
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN_X = 56f
        const val MARGIN_TOP = 56f
        const val MARGIN_BOTTOM = 64f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN_X
        const val CONTENT_HEIGHT = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM

        const val BODY_SIZE = 11.5f
        const val BODY_LINE_SPACING = 1.3f
        const val HEADING_LINE_SPACING = 1.15f
        const val INDENT = 18f
    }
}
