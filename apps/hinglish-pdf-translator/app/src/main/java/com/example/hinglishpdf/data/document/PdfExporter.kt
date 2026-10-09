package com.example.hinglishpdf.data.document

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.hinglishpdf.data.db.PageEntity
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Writes the translated book as a new PDF with EXACTLY one output page per
 * source page, at the source page's size: page 45 of the output is page 45
 * of the original. Each page keeps its structure (sized headings, bulleted
 * and numbered lists with their nesting, quotes, paragraphs) and carries its
 * page number in the footer.
 *
 * Hinglish usually runs longer than English, so text that would overflow is
 * shrunk to fit its page instead of spilling onto the next one.
 */
object PdfExporter {

    private const val A4_WIDTH = 595f
    private const val A4_HEIGHT = 842f
    private const val MIN_SCALE = 0.45f

    fun write(out: OutputStream, pages: List<PageEntity>) {
        val pdf = PdfDocument()
        try {
            for (page in pages) writePage(pdf, page)
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun writePage(pdf: PdfDocument, page: PageEntity) {
        val width = if (page.width > 0f) page.width else A4_WIDTH
        val height = if (page.height > 0f) page.height else A4_HEIGHT
        val info = PdfDocument.PageInfo.Builder(width.roundToInt(), height.roundToInt(), page.pageNumber).create()
        val pdfPage = pdf.startPage(info)
        val canvas = pdfPage.canvas

        val margin = width * 0.08f
        val contentWidth = width - 2 * margin
        val contentHeight = height - 2 * margin
        val unit = width / A4_WIDTH // scale type with the page size

        val blocks = page.sourceBlocks.mapIndexedNotNull { i, block ->
            val text = page.translations?.getOrNull(i) ?: block.text
            if (text.isBlank()) null else block to text
        }

        if (blocks.isEmpty()) {
            note(canvas, "(No text on this page of the original)", width, height, unit)
        } else {
            // Largest scale at which the whole page fits.
            var scale = 1f
            var laidOut = layout(blocks, contentWidth, unit, scale)
            while (laidOut.sumOf { (it.height + it.spaceBefore).toDouble() } > contentHeight && scale > MIN_SCALE) {
                scale -= 0.05f
                laidOut = layout(blocks, contentWidth, unit, scale)
            }

            var y = margin
            for (item in laidOut) {
                y += item.spaceBefore
                item.marker?.let { (marker, paint) ->
                    val baseline = y + item.layout.getLineBaseline(0)
                    canvas.drawText(marker, margin + item.indent - paint.measureText("$marker "), baseline, paint)
                }
                canvas.save()
                canvas.translate(margin + item.indent, y)
                item.layout.draw(canvas)
                canvas.restore()
                y += item.height
            }
        }

        // Footer page number, so pages can be matched with the original at a glance.
        val footer = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f * unit
            color = Color.GRAY
        }
        val label = page.pageNumber.toString()
        canvas.drawText(label, (width - footer.measureText(label)) / 2, height - margin / 2, footer)

        pdf.finishPage(pdfPage)
    }

    private class Item(
        val layout: StaticLayout,
        val indent: Float,
        val marker: Pair<String, TextPaint>?,
        val spaceBefore: Float,
    ) {
        val height: Float get() = layout.height.toFloat()
    }

    private fun layout(blocks: List<Pair<DocBlock, String>>, contentWidth: Float, unit: Float, scale: Float): List<Item> {
        val body = 11f * unit * scale
        val indentStep = 18f * unit * scale
        var previous: DocBlock? = null
        return blocks.map { (block, text) ->
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            var indent = 0f
            var marker: String? = null
            when (block.kind) {
                BlockKind.HEADING -> {
                    paint.textSize = body * when (block.level) { 1 -> 1.8f; 2 -> 1.45f; 3 -> 1.25f; else -> 1.1f }
                    paint.typeface = Typeface.DEFAULT_BOLD
                }
                BlockKind.BULLET, BlockKind.NUMBERED -> {
                    paint.textSize = body
                    indent = indentStep * (block.level + 1)
                    marker = if (block.kind == BlockKind.BULLET) bulletFor(block.level) else block.marker
                }
                BlockKind.QUOTE -> {
                    paint.textSize = body
                    paint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    indent = indentStep
                }
                BlockKind.CODE -> {
                    paint.textSize = body * 0.85f
                    paint.typeface = Typeface.MONOSPACE
                }
                BlockKind.PARAGRAPH -> paint.textSize = body
            }

            val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
            val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
            val spaceBefore = when {
                previous == null -> 0f
                block.kind == BlockKind.HEADING -> paint.textSize * 0.8f
                isList && prevIsList -> body * 0.25f
                else -> body * 0.7f
            }
            previous = block

            val width = (contentWidth - indent).toInt().coerceAtLeast(1)
            val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
            Item(staticLayout, indent, marker?.let { it to TextPaint(paint) }, spaceBefore)
        }
    }

    private fun note(canvas: android.graphics.Canvas, text: String, width: Float, height: Float, unit: Float) {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * unit
            color = Color.GRAY
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        canvas.drawText(text, (width - paint.measureText(text)) / 2, height / 2, paint)
    }
}
