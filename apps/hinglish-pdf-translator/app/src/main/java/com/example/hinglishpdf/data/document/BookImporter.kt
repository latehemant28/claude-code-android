package com.example.hinglishpdf.data.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.epub.EpubBook
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import com.example.hinglishpdf.data.translate.BlockChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class UnsupportedDocumentException : IOException("Only PDF and EPUB files are supported.")

class NoExtractableTextException : IllegalStateException(
    "No text found in this book. If it is a scanned PDF, run it through OCR first.",
)

/**
 * Brings a picked book into the app: a private copy of the file (the picker's
 * URI may stop being readable after a reboot, and resuming must not depend on
 * it), then its pages with their structure.
 */
class BookImporter(
    private val context: Context,
    private val pdfExtractor: PdfTextExtractor,
) {

    data class Imported(val file: File, val format: DocFormat, val title: String)

    /** Fast: copies the file and detects PDF vs EPUB from its first bytes. */
    suspend fun copyIn(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val copy = File(dir, UUID.randomUUID().toString())
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected file.")
            input.use { inp -> copy.outputStream().use { inp.copyTo(it) } }
            val format = detectFormat(copy) ?: throw UnsupportedDocumentException()
            Imported(copy, format, name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Book")
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
    }

    /**
     * Slow (seconds to a few minutes for a 300-page book): extracts every
     * page. PDF pages map 1:1 to source pages; EPUBs, which have no pages,
     * are cut into page-sized sections of whole blocks.
     */
    suspend fun readPages(book: BookEntity, onProgress: (label: String, done: Int, total: Int) -> Unit): List<PageEntity> {
        val file = File(book.sourcePath)
        val pages = when (book.format) {
            DocFormat.PDF -> pdfExtractor.readPages(file) { page, count ->
                onProgress("Reading page $page of $count", page, count)
            }.mapIndexed { i, p -> PageEntity(book.id, i + 1, p.width, p.height, p.blocks) }

            DocFormat.EPUB -> withContext(Dispatchers.IO) {
                val content = EpubBook.read(file) { chapter, count ->
                    onProgress("Reading chapter $chapter of $count", chapter, count)
                }
                BlockChunker.sections(content.blocks, EPUB_SECTION_WORDS)
                    .mapIndexed { i, blocks -> PageEntity(book.id, i + 1, 0f, 0f, blocks) }
            }
        }
        if (pages.none { page -> page.sourceBlocks.any { it.isTranslatable } }) throw NoExtractableTextException()
        return pages
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun detectFormat(file: File): DocFormat? {
        val head = ByteArray(5)
        val read = file.inputStream().use { it.read(head) }
        return when {
            read >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "%PDF" -> DocFormat.PDF
            read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> DocFormat.EPUB
            else -> null
        }
    }

    companion object {
        /** About one printed page of a typical book. */
        const val EPUB_SECTION_WORDS = 350
    }
}
