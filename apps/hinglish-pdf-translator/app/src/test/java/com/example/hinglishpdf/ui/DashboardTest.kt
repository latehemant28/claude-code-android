package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
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

        // Hindi, in pictures: the title, the badge, the spoken-help button and big tiles.
        compose.onNodeWithText(DASHBOARD_TITLE).assertIsDisplayed()
        compose.onNodeWithText(BYOK_BADGE_TEXT).assertIsDisplayed()
        compose.onNodeWithText(Say.help.hi).assertIsDisplayed()
        compose.onNodeWithText(Say.keyTitle.hi).assertIsDisplayed()
        compose.onNodeWithText("हिन्दी").assertIsDisplayed()
        compose.onNodeWithText(Say.chooseBook.hi).assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        listOf(Say.tabTranslate, Say.tabBooks, Say.tabSettings).forEach { tab(it.hi).assertIsDisplayed() }
        assertTrue(compose.onAllNodesWithContentDescription("Listen to this").fetchSemanticsNodes().size >= 3)
        screenshot("dashboard-no-key")

        // The key tile opens the settings sheet (for the helper); the model name is folded away.
        compose.onNodeWithText(Say.keyTitle.hi).performClick()
        compose.onNodeWithText("AI provider & API key").assertIsDisplayed()
        compose.onNodeWithText("Get API Key").assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        screenshot("provider-sheet")
        compose.onNodeWithText("Advanced Settings").performClick()
        compose.onNodeWithText("Model (optional)").assertIsDisplayed()
        screenshot("provider-sheet-advanced")

        // With a key: a green tick, the provider and "ready".
        compose.runOnUiThread {
            vm.saveApiKey(AIProvider.GROQ, "gsk_" + "x".repeat(40))
            vm.selectProvider(AIProvider.GROQ)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Groq").assertIsDisplayed()
        compose.onNodeWithText(Say.keyReady.hi).assertIsDisplayed()
        screenshot("dashboard-ready")

        // Choosing a book first asks, in words, pictures and voice: green ✅ or red ❌.
        val started = org.robolectric.Shadows.shadowOf(compose.activity)
        while (started.nextStartedActivity != null) Unit // e.g. the notification permission request
        compose.onNodeWithText(Say.chooseBook.hi).performClick()
        compose.onNodeWithText(Say.consentTitle.hi).assertIsDisplayed()
        compose.onNodeWithText(uploadConsentText(AIProvider.GROQ).hi).assertExists()
        screenshotDialog("upload-consent")
        compose.onNodeWithText(Say.no.hi).performClick()
        compose.onNodeWithText(Say.consentTitle.hi).assertDoesNotExist()
        assertEquals(null, started.nextStartedActivity) // No: nothing picked
        compose.onNodeWithText(Say.chooseBook.hi).performClick()
        compose.onNodeWithText(Say.yesSend.hi).performClick()
        val picker = started.nextStartedActivity
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, picker?.action)
        compose.onNodeWithText(Say.consentTitle.hi).assertDoesNotExist()

        // The latest book: a progress ring and big Listen / Read buttons.
        compose.onNode(hasScrollAction()).performScrollToKey("current")
        compose.onNodeWithText(Say.listen.hi).assertIsDisplayed()
        compose.onNodeWithText(Say.read.hi).assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        screenshot("dashboard-latest-book")

        // My books: the same big buttons; the rest in the ⋮ menu.
        tab(Say.tabBooks.hi).performClick()
        compose.onNodeWithText("📚 1   ✅ 1").assertIsDisplayed()
        compose.onNodeWithText(Say.listen.hi).assertIsDisplayed()
        screenshot("library")
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Save EPUB to Downloads").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete “Cold Morning”?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        // Settings (for the helper): spoken help on / off, "Choose Your AI Engine", From / To.
        tab(Say.tabSettings.hi).performClick()
        compose.onNodeWithText(Say.spokenHelp.hi).assertIsDisplayed()
        compose.onNodeWithText(ENGINE_TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription("AI engine chip").assertIsDisplayed()
        compose.onNodeWithText("In use").assertIsDisplayed()
        screenshot("settings")

        // Listen: the translated pages, part by part, ready to be read aloud.
        tab(Say.tabBooks.hi).performClick()
        compose.onNodeWithText(Say.listen.hi).performClick()
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.listening.value != null
        }
        assertEquals(listOf("अध्याय 1", "वो एक ठंडी सुबह थी।"), vm.listening.value!!.paragraphs.map { it.text })
        compose.runOnUiThread { vm.closeListen() }

        // Read writes a private reading copy and opens the reader.
        compose.onNodeWithText(Say.read.hi).performClick()
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
        tab(Say.tabBooks.hi).performClick()
        compose.onNodeWithText(Say.listen.hi).assertIsDisplayed()
        screenshot("library-dark")
        tab(Say.tabSettings.hi).performClick()
        screenshot("settings-dark")
    }
}
