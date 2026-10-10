package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.example.hinglishpdf.service.LivePhase
import com.example.hinglishpdf.service.LiveStatus
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The main screen and the project screen, driven like a user would, with the real ViewModel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = HinglishApp::class)
class MainScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<HinglishApp>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show() = compose.setContent {
        HinglishPdfTheme { TranslatorScreen(viewModel(factory = TranslatorViewModel.Factory)) }
    }

    private fun book(title: String, status: BookStatus, pages: Int, translated: Int, error: String? = null, output: String? = null, createdAt: Long): Long = runBlocking {
        val id = app.db.bookDao().insert(
            BookEntity(
                title = title, format = DocFormat.PDF, sourcePath = "/none.pdf", pageCount = pages, status = status,
                error = error, outputUri = output?.let { "content://downloads/1" }, outputName = output, createdAt = createdAt,
            ),
        )
        if (pages > 0) {
            app.db.pageDao().insertAll(
                (1..pages).map { n ->
                    PageEntity(
                        id, n, 595f, 842f, listOf(DocBlock(BlockKind.PARAGRAPH, "Page $n text.")),
                        translations = if (n <= translated) listOf("पेज $n") else null,
                        translatedText = if (n <= translated) "पेज $n" else null,
                    )
                },
            )
        }
        id
    }

    /** Waits for [text] to be on screen (lists load from Room in the background), scrolling to it. */
    private fun seen(text: String, substring: Boolean = true) {
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = substring)) }.isSuccess
        }
        compose.onNodeWithText(text, substring = substring).assertIsDisplayed()
    }

    @Test
    fun `without an AI, choosing a book explains what to do first`() {
        show()
        compose.onNodeWithText("Connect an AI in step 1 first.").assertIsDisplayed()
        compose.onNodeWithText("Select PDF / EPUB").assertIsEnabled().performClick() // never greyed out without a reason
        compose.onNodeWithText("First connect an AI in step 1", substring = true).assertIsDisplayed()
        screenshot("ux1-main-no-key")
    }

    @Test
    fun `pasted keys are cleaned and a bad one says why`() {
        show()
        val field = compose.onNodeWithText("Google Gemini API key")
        field.performTextInput("short")
        compose.onNodeWithText("That looks too short", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Save key").assertIsNotEnabled()
        field.performTextReplacement("GEMINI_API_KEY=\"AIzaSyD-abcdefghijklmnopqrstuvwx\"")
        compose.onNodeWithText("Save key").assertIsEnabled().performClick()
        assertEquals("AIzaSyD-abcdefghijklmnopqrstuvwx", app.providers.state.value.key(AIProvider.GEMINI))
        compose.onNodeWithText("Google Gemini API key saved on this phone").assertIsDisplayed()
    }

    @Test
    fun `book cards show where each book is and its next action`() {
        app.providers.saveKey(AIProvider.GEMINI, "AIza" + "x".repeat(35))
        book("Done Book", BookStatus.COMPLETED, 3, 3, output = "Done Book (Hindi).epub", createdAt = 4)
        book("Paused Book", BookStatus.PAUSED, 10, 4, createdAt = 3)
        book("Broken Key Book", BookStatus.FAILED, 10, 2, error = "Gemini rejected the API key (API key not valid). Check the key in the app or in local.properties.", createdAt = 2)
        val running = book("Running Book", BookStatus.TRANSLATING, 300, 45, createdAt = 1)
        app.monitor.update {
            LiveStatus(
                running = true, bookId = running, label = "Translating page 46 of 300...", phase = LivePhase.TRANSLATING,
                done = 45, total = 300, phaseStartedAt = System.currentTimeMillis() - 120_000, doneAtStart = 41,
            )
        }
        show()
        seen("Done · saved to Downloads")
        seen("Open file")
        seen("Paused · tap Resume to continue")
        seen("Needs your attention: translated step")
        seen("Check the API key")
        seen("Translating · 45 of 300 pages")
        // 4 pages in 2 minutes, 255 to go: 2 h 8 min at that pace, shown generously.
        seen("About 3 h left")
        seen("Pause", substring = false)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Your books"))
        screenshot("ux1-main-books")
    }

    @Test
    fun `a stuck book opens with its stage map and guidance, and the guidance leads to the fix`() {
        app.providers.saveKey(AIProvider.GEMINI, "AIza" + "x".repeat(35))
        book("Broken Key Book", BookStatus.FAILED, 10, 2, error = "Gemini rejected the API key (API key not valid). Check the key in the app or in local.properties.", createdAt = 1)
        show()
        seen("Broken Key Book")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("The AI didn't accept your key").assertIsDisplayed()
        compose.onNodeWithText("Your 2 translated pages are kept.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Step 3, Translated: needs attention. Stopped at 2 of 10 pages").assertIsDisplayed()
        compose.onNodeWithContentDescription("Step 4, Checked: not available yet. Quality checks come in a later update").assertIsDisplayed()
        screenshot("ux1-project-needs-attention")
        compose.onNodeWithText("Details").performClick() // the raw message, folded away
        compose.onNodeWithText("API key not valid", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Check the API key").performClick()
        compose.onNodeWithText("Paste the key again in step 1", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Connect an AI").assertIsDisplayed() // back on the main screen, at step 1
    }

    @Test
    fun `deleting can be undone, and the book is only removed when the message goes away`() {
        val id = book("Keep Me", BookStatus.COMPLETED, 2, 2, output = "Keep Me.epub", createdAt = 1)
        show()
        seen("Keep Me")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Deleted “Keep Me”", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Keep Me").assertDoesNotExist()
        assertNotNull(runBlocking { app.db.bookDao().get(id) }) // not gone yet
        compose.onNodeWithText("Undo").performClick()
        seen("Keep Me")

        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithContentDescription("Dismiss").performClick() // the message goes away: now it is deleted
        compose.waitUntil(5_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runBlocking { app.db.bookDao().get(id) } == null
        }
        assertNull(runBlocking { app.db.bookDao().get(id) })
    }
}
