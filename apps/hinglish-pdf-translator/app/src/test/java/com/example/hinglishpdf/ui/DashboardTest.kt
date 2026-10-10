package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
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

/** The main screen with the real ViewModel: the three tabs, the key sheet, the library, Read. */
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

    /** Dialogs have their own window: draws the latest one. */
    private fun screenshotDialog(name: String) {
        compose.waitForIdle()
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun addFinishedBook() {
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
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test
    fun `dashboard, key sheet, library and settings`() {
        addFinishedBook()
        lateinit var vm: TranslatorViewModel
        compose.setContent {
            HinglishPdfTheme {
                vm = viewModel(factory = TranslatorViewModel.Factory)
                TranslatorScreen(vm)
            }
        }

        // The dashboard: its title, the badge, and three numbered steps, all on one screen.
        compose.onNodeWithText(DASHBOARD_TITLE).assertIsDisplayed()
        compose.onNodeWithText(BYOK_BADGE_TEXT).assertIsDisplayed()
        compose.onNodeWithText("⚠️ API Key Required - Tap to Configure").assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        listOf("AI engine", "Language", "Choose a book", "Select PDF / EPUB").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Translate", "Library", "Settings").forEach { tab(it).assertIsDisplayed() }
        screenshot("dashboard-no-key")

        // The engine card opens the settings sheet; the model name is folded away.
        compose.onNodeWithText("⚠️ API Key Required - Tap to Configure").performClick()
        compose.onNodeWithText("AI provider & API key").assertIsDisplayed()
        compose.onNodeWithText("Get API Key").assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        screenshot("provider-sheet")
        compose.onNodeWithText("Advanced Settings").performClick()
        compose.onNodeWithText("Model (optional)").assertIsDisplayed()
        screenshot("provider-sheet-advanced")

        // With a key: the provider and a green "Connected" light.
        compose.runOnUiThread {
            vm.saveApiKey(AIProvider.GROQ, "gsk_" + "x".repeat(40))
            vm.selectProvider(AIProvider.GROQ)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Groq").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        screenshot("dashboard-ready")

        // Choosing a file first asks for consent to send its text to the AI.
        compose.onNodeWithText("Select PDF / EPUB").performClick()
        compose.onNodeWithText("Your file's text will be sent to AI").assertIsDisplayed()
        compose.onNodeWithText("Agree & choose file").assertIsNotEnabled()
        screenshotDialog("upload-consent")
        compose.onNodeWithText(uploadConsentText(AIProvider.GROQ)).performClick()
        compose.onNodeWithText("Agree & choose file").assertIsEnabled().performClick()
        val picker = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, picker?.action)
        compose.onNodeWithText("Your file's text will be sent to AI").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToKey("current")
        compose.onNodeWithText("Read Now").assertIsDisplayed()
        screenshot("dashboard-latest-book")

        // Library: the book as a card with its progress, one main button and a ⋮ menu.
        tab("Library").performClick()
        compose.onNodeWithText("1 book · 1 translated").assertIsDisplayed()
        compose.onNodeWithText("1 of 1 pages · Done").assertIsDisplayed()
        compose.onNodeWithText("Read").assertIsDisplayed()
        screenshot("library")
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Save EPUB to Downloads").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete “Cold Morning”?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        // Settings: "Choose Your AI Engine" with its drawn chip, and each provider's key status.
        tab("Settings").performClick()
        compose.onNodeWithText(ENGINE_TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("AI engine chip").assertIsDisplayed()
        compose.onNodeWithText("In use").assertIsDisplayed()
        screenshot("settings")

        // Read (in the library) writes a private reading copy and opens the reader.
        tab("Library").performClick()
        compose.onNodeWithText("Read").performClick()
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

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun `dark theme`() {
        addFinishedBook()
        compose.setContent { HinglishPdfTheme { TranslatorScreen(viewModel(factory = TranslatorViewModel.Factory)) } }
        compose.onNodeWithText(DASHBOARD_TITLE).assertIsDisplayed()
        screenshot("dashboard-dark")
        tab("Library").performClick()
        compose.onNodeWithText("Read").assertIsDisplayed()
        screenshot("library-dark")
        tab("Settings").performClick()
        screenshot("settings-dark")
    }
}
