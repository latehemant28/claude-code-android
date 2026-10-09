package com.example.hinglishpdf.data.pdf

import android.content.Context
import com.example.hinglishpdf.data.document.DocBlock
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer

class PdfPasswordProtectedException : IOException("This PDF is password-protected.")

/** One source page: its size in points and its structure. Blank/image pages have no blocks. */
data class PdfPage(val width: Float, val height: Float, val blocks: List<DocBlock>)

/**
 * Reads a PDF strictly page by page into structured blocks (headings,
 * bulleted/numbered items, paragraphs).
 *
 * PDFBox reports every line with its glyph positions and fonts; those are
 * handed to [PdfLayoutAnalyzer], which works out the structure.
 * Requires `PDFBoxResourceLoader.init(context)` once at startup (see HinglishApp).
 */
class PdfTextExtractor(private val context: Context) {

    suspend fun readPages(file: File, onPage: (page: Int, pageCount: Int) -> Unit): List<PdfPage> =
        withContext(Dispatchers.IO) {
            // Buffer the parsed document in a temp file instead of the Java heap,
            // so a 300-page book does not compete with the LLM for memory.
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
            val document = try {
                PDDocument.load(file, memory)
            } catch (e: InvalidPasswordException) {
                throw PdfPasswordProtectedException()
            }

            document.use { doc ->
                val collector = LineCollector()
                val pageCount = doc.numberOfPages
                val sizes = ArrayList<Pair<Float, Float>>(pageCount)
                for (page in 1..pageCount) {
                    currentCoroutineContext().ensureActive()
                    val box = doc.getPage(page - 1).mediaBox
                    sizes += box.width to box.height
                    collector.startPage = page
                    collector.endPage = page
                    collector.writeText(doc, NullWriter)
                    onPage(page, pageCount)
                }
                PdfLayoutAnalyzer.analyzeByPage(collector.lines, pageCount)
                    .mapIndexed { i, blocks -> PdfPage(sizes[i].first, sizes[i].second, blocks) }
            }
        }

    /** Captures each output line of [PDFTextStripper] together with its layout. */
    private class LineCollector : PDFTextStripper() {
        val lines = mutableListOf<PdfLine>()

        private val text = StringBuilder()
        private val positions = mutableListOf<TextPosition>()
        private var pageNumber = 0
        private var pageHeight = 0f

        init {
            sortByPosition = true // reading order for multi-column and positioned text
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            pageNumber++
            pageHeight = page.mediaBox.height
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            this.text.append(text)
            positions += textPositions
        }

        override fun writeWordSeparator() {
            text.append(' ')
        }

        override fun writeLineSeparator() = flushLine()

        override fun endPage(page: PDPage) {
            flushLine()
            super.endPage(page)
        }

        private fun flushLine() {
            val content = text.toString().trim()
            if (content.isNotEmpty() && positions.isNotEmpty()) {
                val glyphs = positions.filter { !it.unicode.isNullOrBlank() }.ifEmpty { positions }
                lines += PdfLine(
                    text = content,
                    page = pageNumber,
                    x = glyphs.minOf { it.xDirAdj },
                    y = glyphs.maxOf { it.yDirAdj },
                    fontSize = glyphs.map { if (it.fontSizeInPt > 0f) it.fontSizeInPt else it.heightDir }
                        .sorted()[glyphs.size / 2],
                    bold = glyphs.count(::isBold) * 2 > glyphs.size,
                    pageHeight = pageHeight,
                )
            }
            text.clear()
            positions.clear()
        }

        private fun isBold(p: TextPosition): Boolean {
            val font = p.font ?: return false
            val name = font.name.orEmpty().lowercase()
            if ("bold" in name || "black" in name || "heavy" in name || "semibold" in name) return true
            val descriptor = font.fontDescriptor ?: return false
            return descriptor.isForceBold || descriptor.fontWeight >= 600f
        }
    }

    private object NullWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
