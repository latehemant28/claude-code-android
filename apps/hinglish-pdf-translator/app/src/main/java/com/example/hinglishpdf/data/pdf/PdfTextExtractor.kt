package com.example.hinglishpdf.data.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/** Text of a single PDF page, 1-based. */
data class PageText(val pageNumber: Int, val pageCount: Int, val text: String)

class PdfPasswordProtectedException : IOException("This PDF is password-protected.")

/**
 * Extracts text from a PDF chosen via the Storage Access Framework,
 * one page at a time, on [Dispatchers.IO].
 *
 * Requires `PDFBoxResourceLoader.init(context)` once at startup (see HinglishApp).
 */
class PdfTextExtractor(private val context: Context) {

    /** The file name the user sees in the picker, e.g. "report.pdf". */
    fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun extractPages(uri: Uri): Flow<PageText> = flow {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected file.")

        // Buffer the parsed document in a temp file instead of the Java heap,
        // so large PDFs do not compete with the LLM for memory.
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

        val document = try {
            input.use { PDDocument.load(it, memory) }
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordProtectedException()
        }

        document.use { doc ->
            val pageCount = doc.numberOfPages
            val stripper = PDFTextStripper().apply {
                // Reading order for multi-column layouts and positioned text.
                sortByPosition = true
                // Mark paragraph ends with a blank line so the chunker can see them.
                paragraphEnd = "\n\n"
            }

            for (page in 1..pageCount) {
                currentCoroutineContext().ensureActive()
                stripper.startPage = page
                stripper.endPage = page
                emit(PageText(page, pageCount, stripper.getText(doc)))
            }
        }
    }.flowOn(Dispatchers.IO)
}
