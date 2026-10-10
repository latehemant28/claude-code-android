package com.example.hinglishpdf.data.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real PDFs through PDFBox: every page's text stays on its own page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PdfTextExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a page with no content stream does not shift later pages`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        PDFBoxResourceLoader.init(context)
        val file = tmp.newFile("book.pdf")
        PDDocument().use { doc ->
            fun page(vararg lines: Pair<Float, String>) = PDPage().also { page ->
                doc.addPage(page)
                PDPageContentStream(doc, page).use { out ->
                    for ((size, text) in lines) {
                        out.beginText()
                        out.setFont(PDType1Font.HELVETICA, size)
                        out.newLineAtOffset(72f, 700f - lines.indexOf(size to text) * 40f)
                        out.showText(text)
                        out.endText()
                    }
                }
            }
            page(11f to "The first page has some text.")
            doc.addPage(PDPage()) // a blank page: no content stream at all
            page(22f to "32 Historical examples", 11f to "The third page starts a chapter.")
            doc.save(file)
        }

        val pages = runBlocking { PdfTextExtractor(context).readPages(file) { _, _ -> } }
        assertEquals(3, pages.size)
        assertEquals(listOf("The first page has some text."), pages[0].blocks.map { it.text })
        assertEquals(emptyList<String>(), pages[1].blocks.map { it.text })
        assertEquals(listOf("32 Historical examples", "The third page starts a chapter."), pages[2].blocks.map { it.text })
    }
}
