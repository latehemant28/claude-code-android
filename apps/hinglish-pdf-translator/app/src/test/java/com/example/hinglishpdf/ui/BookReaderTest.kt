package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.PdfExporter
import com.example.hinglishpdf.data.epub.EpubWriter
import com.example.hinglishpdf.ui.reader.BookReaderScreen
import com.example.hinglishpdf.ui.reader.ReaderDocument
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "Read Now": the in-app reader's EPUB mode and its controls. (Robolectric has
 * no working PdfDocument / PdfRenderer, so the PDF mode is not run here.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class BookReaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sections = listOf(
        PdfExporter.Section(1, listOf(
            DocBlock(BlockKind.HEADING, "Chapter", level = 1) to "अध्याय 1: शुरुआत",
            DocBlock(BlockKind.PARAGRAPH, "p") to "वो एक ठंडी सुबह थी। सब लोग देर से आए, लेकिन किसी को कोई फ़र्क नहीं पड़ा।",
            DocBlock(BlockKind.BULLET, "b") to "कोट लाओ",
            DocBlock(BlockKind.BULLET, "b", level = 1) to "गरम वाला",
        )),
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `epub chapters can be paged through`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(context.cacheDir, "read.epub")
        val chapters = (1..3).map { n ->
            EpubWriter.Section(n, listOf(DocBlock(BlockKind.HEADING, "h", level = 1) to "अध्याय $n", DocBlock(BlockKind.PARAGRAPH, "p") to "पाठ $n"))
        }
        file.outputStream().use { EpubWriter.write(it, "Book", chapters, pageMarkers = true) }

        compose.setContent {
            HinglishPdfTheme {
                BookReaderScreen(ReaderDocument(2, "Book", file, DocFormat.EPUB), false, 1f, {}, {}, {})
            }
        }
        compose.onNodeWithText("Chapter 1 of 3").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Chapter 2 of 3").assertIsDisplayed()
        compose.onNodeWithText("Aa").performClick()
        compose.onNodeWithText("Text size").assertIsDisplayed()
        assertEquals(1, compose.onAllNodes(androidx.compose.ui.test.hasText("Dark mode")).fetchSemanticsNodes().size)
    }
}
