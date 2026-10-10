package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** First launch: the target-language popup, then the spotlight tour of the main screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = HinglishApp::class)
class FirstLaunchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(view: android.view.View, name: String) {
        repeat(6) {
            Thread.sleep(30)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `the language popup saves the target language`() {
        val app = ApplicationProvider.getApplicationContext<HinglishApp>()
        var done = false
        compose.setContent {
            HinglishPdfTheme {
                TranslatorScreen(viewModel(factory = TranslatorViewModel.Factory), languagePrompt = true, onLanguagePromptDone = { done = true })
            }
        }
        compose.onNodeWithText("Select your Target Language").assertExists()
        save(ShadowDialog.getLatestDialog().window!!.decorView, "first-launch-language")
        compose.onNodeWithText("Spanish").performClick()
        assertTrue(done)
        assertEquals(Language.SPANISH, app.preferences.targetLanguage.value)
    }

    @Test
    fun `the spotlight tour highlights language, key, upload in turn`() {
        var done = false
        compose.setContent {
            HinglishPdfTheme {
                TranslatorScreen(viewModel(factory = TranslatorViewModel.Factory), tour = true, onTourDone = { done = true })
            }
        }
        compose.onNodeWithText(BYOK_BADGE_TEXT).assertExists()
        for ((i, step) in TourStep.entries.withIndex()) {
            compose.onNodeWithText(step.title).assertExists()
            compose.onNodeWithText("${i + 1} / 3").assertExists()
            save(compose.activity.window.decorView, "tour-${i + 1}")
            assertFalse(done)
            compose.onRoot().performTouchInput { click(center) }
            compose.waitForIdle()
        }
        assertTrue(done)
    }
}
