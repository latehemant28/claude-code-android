package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The main screen with the real ViewModel: progressive disclosure, the key sheet, Read Now. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = HinglishApp::class)
class DashboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
    fun `clean dashboard, key sheet with advanced settings folded, read now`() {
        val app = ApplicationProvider.getApplicationContext<HinglishApp>()
        app.preferences.acceptTerms()
        val blocks = listOf(DocBlock(BlockKind.HEADING, "Chapter 1", level = 1), DocBlock(BlockKind.PARAGRAPH, "It was a cold morning."))
        runBlocking {
            val id = app.db.bookDao().insert(
                BookEntity(title = "Cold Morning", format = DocFormat.PDF, sourcePath = "/none.pdf", pageCount = 1, status = BookStatus.COMPLETED),
            )
            app.db.pageDao().insertAll(
                listOf(PageEntity(id, 1, 595f, 842f, blocks, translations = listOf("अध्याय 1", "वो एक ठंडी सुबह थी।"), translatedText = "x", translatedAt = 1)),
            )
        }
        lateinit var vm: TranslatorViewModel
        compose.setContent {
            HinglishPdfTheme {
                vm = viewModel(factory = TranslatorViewModel.Factory)
                TranslatorScreen(vm)
            }
        }

        // No key: the highlighted banner instead of provider / key / model fields.
        compose.onNodeWithText("⚠️ API Key Required - Tap to Configure").assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        // "Translate a book" sits right below the Output Format card.
        val format = compose.onNodeWithText("Output Format:").fetchSemanticsNode().boundsInRoot
        val translate = compose.onNodeWithText("Translate a book into Hindi").fetchSemanticsNode().boundsInRoot
        assertTrue(translate.top > format.bottom && translate.top - format.bottom < 200)
        compose.onNodeWithText("Read Now").assertIsDisplayed()
        screenshot("dashboard-no-key")

        // The banner opens the settings sheet; the model name is folded away.
        compose.onNodeWithText("⚠️ API Key Required - Tap to Configure").performClick()
        compose.onNodeWithText("AI provider & API key").assertIsDisplayed()
        compose.onNodeWithText("Get API Key").assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        screenshot("provider-sheet")
        compose.onNodeWithText("Advanced Settings").performClick()
        compose.onNodeWithText("Model (optional)").assertIsDisplayed()
        screenshot("provider-sheet-advanced")

        // With a key the banner is a quiet one-liner.
        compose.runOnUiThread {
            vm.saveApiKey(AIProvider.GROQ, "gsk_" + "x".repeat(40))
            vm.selectProvider(AIProvider.GROQ)
        }
        compose.waitForIdle()
        compose.onNodeWithText("🤖 Using: Groq").assertIsDisplayed()
        screenshot("dashboard-ready")

        // Read Now writes a private reading copy and opens the reader.
        compose.onNodeWithText("Read Now").performClick()
        try {
            compose.waitUntil(10_000) {
                // Lets the export's result (from the IO thread) reach Robolectric's main looper.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.reader.value != null
            }
        } catch (e: Throwable) {
            throw AssertionError("reader not opened; state=${vm.state.value.message} opening=${vm.state.value.openingBookId}", e)
        }
        val document = vm.reader.value!!
        assertEquals(DocFormat.EPUB, document.format)
        assertTrue(document.file.isFile && document.file.length() > 0)
    }
}
