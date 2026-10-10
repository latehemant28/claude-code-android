package com.example.hinglishpdf.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.hinglishpdf.data.db.AppDatabase
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.document.BundledFonts
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.PdfExporter
import com.example.hinglishpdf.data.epub.EpubBook
import com.example.hinglishpdf.data.epub.EpubWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Builds the translated file from the pages saved in Room, in the format
 * picked with the Output Format toggle, and writes it to the public
 * Downloads folder:
 *
 *  - EPUB from an EPUB: the same EPUB with its text translated (styles,
 *    images and table of contents kept);
 *  - EPUB from a PDF: a new EPUB 3, one chapter per top-level heading;
 *  - PDF from either: A4 pages with the text flowing across them and the
 *    Noto fonts embedded.
 *
 * Pages not translated yet keep their original text, so a paused book can be
 * exported too.
 */
class BookExporter(
    private val context: Context,
    private val db: AppDatabase,
    private val fonts: BundledFonts,
) {

    data class Saved(val uri: Uri, val displayName: String, val format: DocFormat)

    suspend fun exportToDownloads(book: BookEntity, format: DocFormat): Saved = withContext(Dispatchers.IO) {
        val pages = db.pageDao().pages(book.id)
        val name = "${book.title} (Hindi).${format.extension}"
        val uri = saveToDownloads(name, format.mimeType) { out ->
            when {
                format == DocFormat.PDF -> PdfExporter(fonts).write(out, pages, sourceIsPdf = book.format == DocFormat.PDF)
                book.format == DocFormat.EPUB -> EpubBook.writeTranslated(
                    File(book.sourcePath),
                    out,
                    pages.flatMap { p -> p.translations ?: List(p.sourceBlocks.size) { null } },
                )
                else -> EpubWriter.write(
                    out,
                    title = book.title,
                    sections = pages.map { page ->
                        EpubWriter.Section(
                            page.pageNumber,
                            page.sourceBlocks.mapIndexedNotNull { i, block ->
                                val text = page.translations?.getOrNull(i) ?: block.text
                                if (text.isBlank()) null else block to text
                            },
                        )
                    },
                    pageMarkers = true,
                    fonts = fonts.epubFonts(),
                )
            }
        }
        db.bookDao().setOutput(book.id, uri.toString(), name)
        Saved(uri, name, format)
    }

    /**
     * MediaStore insert into Downloads: no storage permission on Android 10+.
     * The file stays hidden (IS_PENDING) until fully written, and a name clash
     * gets " (1)" appended by the system.
     */
    private fun saveToDownloads(displayName: String, mimeType: String, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not write to Downloads.")
            out.use(write)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
