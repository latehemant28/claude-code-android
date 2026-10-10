package com.example.hinglishpdf.pipeline.pdf

import com.example.hinglishpdf.data.pdf.PdfPasswordProtectedException
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.google.gson.Gson
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
    fun parse(
        file: File,
        tempDir: File?,
        config: PipelineConfig = PipelineConfig(),
        onPage: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
    ): ParsedDocument {
        val memory = MemoryUsageSetting.setupTempFileOnly().let { if (tempDir != null) it.setTempDir(tempDir) else it }
        val document = try {
            PDDocument.load(file, memory)
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordProtectedException()
        }
        return document.use { doc ->
            val pages = readGlyphs(doc, onPage)
            val layout = PdfLayout.analyze(pages, config)
            // Running text joined across pages and columns, before it is cut into sentences.
            val paragraphs = DocumentAssembler(config, layout.hyphenation).assemble(layout.paragraphs)
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
                if (layout.boilerplatePages.isNotEmpty()) put("boilerplatePages", layout.boilerplatePages.joinToString(","))
                // For the skeleton: page sizes and images, and the bookmarks (title, page).
                put("pageGeometry", Gson().toJson(pages.map { p -> PageGeometry(p.page, p.width, p.height, p.imageBoxes) }))
                outline(doc).takeIf { it.isNotEmpty() }?.let { put("outline", Gson().toJson(it)) }
            }
            ParsedDocument(
                title = metadata["title"],
                language = doc.documentCatalog?.language?.takeIf { it.isNotBlank() },
                metadata = metadata,
                hasToc = doc.documentCatalog?.documentOutline?.firstChild != null,
                pageCount = doc.numberOfPages,
                scannedPages = layout.scannedPages,
                needsOcr = layout.needsOcr,
                paragraphs = paragraphs,
                segments = Segmenter.segment(paragraphs),
            )
        }
    }

    /** The PDF's bookmarks, depth first: title and the page it opens. */
    internal fun outline(doc: PDDocument): List<OutlineEntry> {
        val out = mutableListOf<OutlineEntry>()
        fun walk(item: PDOutlineItem?, depth: Int) {
            var current = item
            while (current != null) {
                val page = runCatching { current.findDestinationPage(doc)?.let { doc.pages.indexOf(it) + 1 } }.getOrNull()
                current.title?.takeIf { it.isNotBlank() }?.let { out += OutlineEntry(it.trim(), page ?: 0, depth) }
                walk(current.firstChild, depth + 1)
                current = current.nextSibling
            }
        }
        walk(doc.documentCatalog?.documentOutline?.firstChild, 0)
        return out
    }

    internal fun readGlyphs(doc: PDDocument, onPage: (page: Int, pageCount: Int) -> Unit = { _, _ -> }): List<PdfPageGlyphs> {
        val collector = GlyphCollector()
        val count = doc.numberOfPages
        return (1..count).map { number ->
            val page = doc.getPage(number - 1)
            collector.glyphs.clear()
            collector.imageBoxes.clear()
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
                imageBoxes = if (page.rotation % 360 == 0) collector.imageBoxes.toList() else emptyList(),
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

    /**
     * Every glyph PDFBox decodes, in drawing order (reading order is rebuilt
     * by [PdfLayout]), and where each image is drawn.
     */
    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<PdfGlyph>()
        val imageBoxes = mutableListOf<Box>()

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName
                val image = name?.let { runCatching { resources?.getXObject(it) }.getOrNull() } as? PDImageXObject
                if (image != null) imageBoxes += imageBox()
            }
            super.processOperator(operator, operands)
        }

        /** An image fills the unit square of its transformation; mapped to top-left page coordinates. */
        private fun imageBox(): Box {
            val m = graphicsState.currentTransformationMatrix
            val crop = currentPage.cropBox ?: currentPage.mediaBox
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (u, v) ->
                val x = m.scaleX * u + m.shearX * v + m.translateX - crop.lowerLeftX
                val y = m.shearY * u + m.scaleY * v + m.translateY - crop.lowerLeftY
                x to crop.height - y
            }
            return Box(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
        }

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

/** A page's size and where it draws images, for the skeleton. */
data class PageGeometry(val page: Int, val width: Float, val height: Float, val images: List<com.example.hinglishpdf.pipeline.segment.Box>)

/** A bookmark: its title, the page it opens (0 if none) and its depth. */
data class OutlineEntry(val title: String, val page: Int, val depth: Int)
