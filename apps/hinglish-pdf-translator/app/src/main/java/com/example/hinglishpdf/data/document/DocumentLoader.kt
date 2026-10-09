package com.example.hinglishpdf.data.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.hinglishpdf.data.epub.EpubBook
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class UnsupportedDocumentException : IOException("Only PDF and EPUB files are supported.")

class NoExtractableTextException : IllegalStateException(
    "No text found in this document. If it is a scanned PDF, run it through OCR first.",
)

/**
 * Copies a picked file into private storage (EPUB export rewrites it later,
 * and both parsers need random access), detects PDF vs EPUB from the file's
 * first bytes, and parses it into blocks.
 */
class DocumentLoader(
    private val context: Context,
    private val pdfExtractor: PdfTextExtractor,
) {

    suspend fun load(uri: Uri, onProgress: (label: String, fraction: Float?) -> Unit): SourceDocument =
        withContext(Dispatchers.IO) {
            val name = displayName(uri)
            val dir = File(context.filesDir, "current").apply { deleteRecursively(); mkdirs() }
            val copy = File(dir, "source")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open the selected file.")
            input.use { inp -> copy.outputStream().use { inp.copyTo(it) } }

            val format = detectFormat(copy) ?: throw UnsupportedDocumentException()
            val title = name?.substringBeforeLast('.') ?: "document"
            val blocks = when (format) {
                DocFormat.PDF -> pdfExtractor.readBlocks(copy) { page, count ->
                    onProgress("Reading page $page of $count", page.toFloat() / count)
                }
                DocFormat.EPUB -> EpubBook.read(copy) { chapter, count ->
                    onProgress("Reading chapter $chapter of $count", chapter.toFloat() / count)
                }.blocks
            }
            if (blocks.none { it.isTranslatable }) throw NoExtractableTextException()
            SourceDocument(title, format, blocks, copy)
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
}
