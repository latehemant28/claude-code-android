package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.data.pdf.PdfPasswordProtectedException
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.Writer

/**
 * Reads a PDF with PDFBox into positioned glyphs, page by page, and hands
 * them to [PdfLayout]. Pages without a usable text layer are listed as
 * scanned; a mostly scanned book [ParsedDocument.needsOcr]. Requires
 * `PDFBoxResourceLoader.init(context)` once (see HinglishApp).
 */
object PdfParser {

    /** [tempDir]: where PDFBox may buffer a large document instead of the heap. */
    fun parse(file: File, tempDir: File?, onPage: (page: Int, pageCount: Int) -> Unit = { _, _ -> }): ParsedDocument {
        val memory = MemoryUsageSetting.setupTempFileOnly().let { if (tempDir != null) it.setTempDir(tempDir) else it }
        val document = try {
            PDDocument.load(file, memory)
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordProtectedException()
        }
        return document.use { doc ->
            val pages = readGlyphs(doc, onPage)
            val layout = PdfLayout.analyze(pages)
            val info = doc.documentInformation
            val metadata = buildMap {
                info?.title?.takeIf { it.isNotBlank() }?.let { put("title", it.trim()) }
                info?.author?.takeIf { it.isNotBlank() }?.let { put("creator", it.trim()) }
                info?.subject?.takeIf { it.isNotBlank() }?.let { put("subject", it.trim()) }
                info?.keywords?.takeIf { it.isNotBlank() }?.let { put("keywords", it.trim()) }
                info?.producer?.takeIf { it.isNotBlank() }?.let { put("producer", it.trim()) }
                put("droppedRunningLines", layout.droppedLines.toString())
                put("tables", layout.tables.toString())
                if (layout.columns.isNotEmpty()) put("multiColumnPages", layout.columns.keys.joinToString(","))
            }
            ParsedDocument(
                title = metadata["title"],
                language = doc.documentCatalog?.language?.takeIf { it.isNotBlank() },
                metadata = metadata,
                hasToc = doc.documentCatalog?.documentOutline?.firstChild != null,
                pageCount = doc.numberOfPages,
                scannedPages = layout.scannedPages,
                needsOcr = layout.needsOcr,
                paragraphs = layout.paragraphs,
                segments = Segmenter.segment(layout.paragraphs),
            )
        }
    }

    internal fun readGlyphs(doc: PDDocument, onPage: (page: Int, pageCount: Int) -> Unit = { _, _ -> }): List<PdfPageGlyphs> {
        val collector = GlyphCollector()
        val count = doc.numberOfPages
        return (1..count).map { number ->
            val page = doc.getPage(number - 1)
            collector.glyphs.clear()
            collector.startPage = number
            collector.endPage = number
            collector.writeText(doc, NullWriter)
            onPage(number, count)
            val box = page.cropBox ?: page.mediaBox
            val rotated = page.rotation % 180 != 0
            PdfPageGlyphs(
                page = number,
                width = if (rotated) box.height else box.width,
                height = if (rotated) box.width else box.height,
                glyphs = collector.glyphs.toList(),
                images = imageCount(page),
            )
        }
    }

    /** Images drawn by the page itself or by forms it uses (one level deep is enough to spot a scan). */
    private fun imageCount(page: PDPage): Int {
        fun count(resources: PDResources?, depth: Int): Int {
            if (resources == null) return 0
            return resources.xObjectNames.sumOf { name ->
                when (val x = runCatching { resources.getXObject(name) }.getOrNull()) {
                    is PDImageXObject -> 1
                    is PDFormXObject -> if (depth < 2) count(x.resources, depth + 1) else 0
                    else -> 0
                }
            }
        }
        return count(page.resources, 0)
    }

    /** Every glyph PDFBox decodes, in drawing order (reading order is rebuilt by [PdfLayout]). */
    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<PdfGlyph>()

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode ?: return
            val font = text.font
            val name = font?.name.orEmpty()
            val lower = name.lowercase()
            val descriptor = font?.fontDescriptor
            val size = text.fontSizeInPt.takeIf { it > 0.5f } ?: text.heightDir
            // "Fake bold" draws each glyph twice, a hair apart: keep one.
            val duplicate = glyphs.takeLast(4).any {
                it.text == unicode && kotlin.math.abs(it.x - text.xDirAdj) < 0.6f && kotlin.math.abs(it.baseline - text.yDirAdj) < 0.6f
            }
            if (duplicate) return
            glyphs += PdfGlyph(
                text = unicode,
                x = text.xDirAdj,
                baseline = text.yDirAdj,
                width = text.widthDirAdj,
                size = size,
                font = name.substringAfter('+'), // "ABCDEF+Garamond-Bold" → "Garamond-Bold"
                bold = "bold" in lower || "black" in lower || "heavy" in lower || "semibold" in lower ||
                    descriptor?.isForceBold == true || (descriptor?.fontWeight ?: 0f) >= 600f,
                italic = "italic" in lower || "oblique" in lower || descriptor?.isItalic == true,
            )
        }
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
