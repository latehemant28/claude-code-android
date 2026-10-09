package com.example.hinglishpdf.data.document

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * Writes translated blocks as a new A4 PDF with the original structure:
 * sized headings, bulleted and numbered lists with their nesting, quotes and
 * paragraphs. (A PDF's exact page layout cannot be reproduced with different
 * text, so this rebuilds the document rather than editing the original.)
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595 // A4 in points
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 56f
    private const val INDENT = 18f
    private const val BODY_SIZE = 11f

    fun write(out: OutputStream, blocks: List<DocBlock>, translations: List<String?>) {
        val pdf = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let(pdf::finishPage)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN
        }
        newPage()

        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        var previous: DocBlock? = null

        blocks.forEachIndexed { index, block ->
            val text = translations.getOrNull(index) ?: block.text
            if (text.isBlank()) return@forEachIndexed

            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
            var indent = 0f
            var marker: String? = null
            when (block.kind) {
                BlockKind.HEADING -> {
                    paint.textSize = when (block.level) { 1 -> 20f; 2 -> 16f; 3 -> 14f; else -> 12f }
                    paint.typeface = Typeface.DEFAULT_BOLD
                }
                BlockKind.BULLET, BlockKind.NUMBERED -> {
                    paint.textSize = BODY_SIZE
                    indent = INDENT * (block.level + 1)
                    marker = if (block.kind == BlockKind.BULLET) bulletFor(block.level) else block.marker
                }
                BlockKind.QUOTE -> {
                    paint.textSize = BODY_SIZE
                    paint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    indent = INDENT
                }
                BlockKind.CODE -> {
                    paint.textSize = 9.5f
                    paint.typeface = Typeface.MONOSPACE
                }
                BlockKind.PARAGRAPH -> paint.textSize = BODY_SIZE
            }

            val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
            val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
            y += when {
                previous == null -> 0f
                block.kind == BlockKind.HEADING -> paint.textSize * 0.9f
                isList && prevIsList -> 3f
                else -> 8f
            }

            val width = (contentWidth - indent).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .build()

            for (line in 0 until layout.lineCount) {
                val lineHeight = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (y + lineHeight > PAGE_HEIGHT - MARGIN) newPage()
                val baseline = y + (layout.getLineBaseline(line) - layout.getLineTop(line))
                val canvas = page!!.canvas
                if (line == 0 && marker != null) {
                    val markerPaint = TextPaint(paint)
                    val markerWidth = markerPaint.measureText("$marker ")
                    canvas.drawText(marker, MARGIN + indent - markerWidth, baseline, markerPaint)
                }
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(line)
                canvas.drawText(text, start, end, MARGIN + indent, baseline, paint)
                y += lineHeight
            }
            previous = block
        }

        page?.let(pdf::finishPage)
        pdf.writeTo(out)
        pdf.close()
    }
}
