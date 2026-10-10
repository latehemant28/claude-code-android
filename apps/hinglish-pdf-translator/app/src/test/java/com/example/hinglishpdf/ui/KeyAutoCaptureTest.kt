package com.example.hinglishpdf.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** The "Auto-Magic" key flow: copy → captured → saved screen, with no reading or pasting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class KeyAutoCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val groqKey = "gsk_" + "Ab3d".repeat(13)

    /** Lets Lottie's background loading finish, then draws the top dialog window. */
    private fun screenshotDialog(name: String) {
        repeat(15) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `copying a key in the browser captures it at once, for the right provider`() {
        var captured: Pair<AIProvider, String>? = null
        compose.setContent {
            HinglishPdfTheme {
                ApiKeyBrowser(AIProvider.GEMINI, onKeyCaptured = { p, k -> captured = p to k }, onClose = {})
            }
        }
        compose.onNodeWithContentDescription("Watch video tutorial").assertExists()
        compose.onNodeWithContentDescription("Tap the create key button, then tap Copy").assertExists()
        screenshotDialog("key-browser-hint")

        // The floating ▶: the video guide on top, the website below.
        compose.onNodeWithContentDescription("Watch video tutorial").performClick()
        compose.onNodeWithContentDescription("Close video").assertExists()
        screenshotDialog("key-browser-video")

        // A Groq key copied while Gemini is selected: captured for Groq, clipboard wiped.
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("key", groqKey)) }
        compose.waitForIdle()
        assertEquals(AIProvider.GROQ to groqKey, captured)
        assertTrue(clipboard.primaryClip?.getItemAt(0)?.text.isNullOrEmpty())
    }

    @Test
    fun `ordinary copied text is not taken for a key`() {
        var captured: Pair<AIProvider, String>? = null
        compose.setContent {
            HinglishPdfTheme { ApiKeyBrowser(AIProvider.OPENAI, onKeyCaptured = { p, k -> captured = p to k }, onClose = {}) }
        }
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("text", "Create new secret key")) }
        compose.waitForIdle()
        assertNull(captured)
    }

    @Test
    fun `the success screen celebrates, then closes itself`() {
        var done = false
        compose.setContent { HinglishPdfTheme { KeySavedCelebration(AIProvider.GEMINI) { done = true } } }
        compose.onNodeWithText(KEY_SAVED_TITLE).assertExists()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(900)
        screenshotDialog("key-saved")
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertTrue(done)
    }
}
