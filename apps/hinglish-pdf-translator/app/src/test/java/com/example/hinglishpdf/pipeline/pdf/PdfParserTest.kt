package com.example.hinglishpdf.pipeline.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A real PDF written with PDFBox, read back through [PdfParser]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PdfParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private fun PDPageContentStream.line(font: PDFont, size: Float, x: Float, yFromTop: Float, text: String) {
        beginText()
        setFont(font, size)
        newLineAtOffset(x, PDRectangle.A4.height - yFromTop)
        showText(text)
        endText()
    }

    private fun write(scannedPages: Int): File {
        val file = temp.newFile("book.pdf")
        PDDocument().use { doc ->
            val regular = PDType1Font.HELVETICA
            val bold = PDType1Font.HELVETICA_BOLD
            // Page 1: a heading and two paragraphs, one with a hyphenated word.
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    s.line(bold, 20f, 60f, 80f, "Chapter One")
                    s.line(regular, 11f, 60f, 120f, "Mr. Smith opened the old transla-")
                    s.line(regular, 11f, 60f, 134f, "tion and read it. It was quiet.")
                    s.line(regular, 11f, 60f, 170f, "A second paragraph begins here.")
                }
            }
            // Page 2: two columns.
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    // The left column ends its paragraph, so the right one is a paragraph of its own.
                    val left = listOf("Left text of the page starts", "and runs on from line", "to line down the column", "until it is done.")
                    val right = listOf("Right text of the page", "goes on in the same", "way through the second", "column of the page")
                    for (i in 0 until 4) {
                        s.line(regular, 11f, 60f, 100f + i * 14, left[i])
                        s.line(regular, 11f, 320f, 100f + i * 14, right[i])
                    }
                }
            }
            // Scanned pages: only a picture.
            repeat(scannedPages) {
                PDPage(PDRectangle.A4).also { page ->
                    doc.addPage(page)
                    val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                    val image = LosslessFactory.createFromImage(doc, bitmap)
                    PDPageContentStream(doc, page).use { s -> s.drawImage(image, 50f, 50f, 400f, 600f) }
                }
            }
            doc.documentInformation.title = "The Test Book"
            doc.save(file)
        }
        return file
    }

    @Test
    fun `text pages are parsed into ordered paragraphs and segments`() {
        val doc = PdfParser.parse(write(scannedPages = 1), temp.root)
        assertEquals("The Test Book", doc.title)
        assertEquals(3, doc.pageCount)
        assertEquals(listOf(3), doc.scannedPages)
        assertFalse(doc.needsOcr)

        val page1 = doc.paragraphs.filter { (it.ref as com.example.hinglishpdf.pipeline.segment.SourceRef.Pdf).page == 1 }
        assertEquals(
            listOf(
                ParagraphRole.HEADING to "Chapter One",
                ParagraphRole.PARAGRAPH to "Mr. Smith opened the old translation and read it. It was quiet.",
                ParagraphRole.PARAGRAPH to "A second paragraph begins here.",
            ),
            page1.map { it.role to it.text },
        )
        val page2 = doc.paragraphs.filter { (it.ref as com.example.hinglishpdf.pipeline.segment.SourceRef.Pdf).page == 2 }.map { it.text }
        assertEquals("Left text of the page starts and runs on from line to line down the column until it is done.", page2.first())
        assertEquals("Right text of the page goes on in the same way through the second column of the page", page2.last())
        assertEquals("2", doc.metadata["multiColumnPages"])

        assertEquals(
            listOf("Mr. Smith opened the old translation and read it.", "It was quiet."),
            doc.segments.filter { it.paragraph == 1 }.map { it.text },
        )
    }

    @Test
    fun `images are located on the page, top-left origin like the text`() {
        PDDocument.load(write(scannedPages = 1)).use { doc ->
            val scan = PdfParser.readGlyphs(doc).last()
            // Drawn at x 50, y 50 from the bottom, 400 x 600, on an A4 page (842 pt high).
            val box = scan.imageBoxes.single()
            assertEquals(50f, box.x0, 0.5f)
            assertEquals(450f, box.x1, 0.5f)
            assertEquals(PDRectangle.A4.height - 650f, box.y0, 0.5f)
            assertEquals(PDRectangle.A4.height - 50f, box.y1, 0.5f)
        }
    }

    @Test
    fun `a mostly scanned pdf needs ocr`() {
        val doc = PdfParser.parse(write(scannedPages = 3), temp.root)
        assertEquals(listOf(3, 4, 5), doc.scannedPages)
        assertTrue(doc.needsOcr)
    }
}
