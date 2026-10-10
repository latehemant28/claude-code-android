package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.hinglishpdf.ui.onboarding.OnboardingScreen
import com.example.hinglishpdf.ui.onboarding.OnboardingText
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The 3-step tutorial: exact texts, Next → Next → "Let's Get Started!" closes it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class OnboardingScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Screenshots for a human to look at (build/screenshots), not compared. */
    private fun screenshot(name: String) {
        // Lottie loads its animation on a background thread: give it a moment to arrive.
        repeat(15) {
            Thread.sleep(50)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `three slides with the specified text, then the main screen`() {
        var finished = 0
        compose.setContent { HinglishPdfTheme { OnboardingScreen(onFinish = { finished++ }) } }

        compose.onNodeWithText(OnboardingText.TITLE_1).assertIsDisplayed()
        compose.onNodeWithText(OnboardingText.DESCRIPTION_1).assertIsDisplayed()
        screenshot("onboarding-1")

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(OnboardingText.TITLE_2).assertIsDisplayed()
        compose.onNodeWithText(OnboardingText.DESCRIPTION_2).assertIsDisplayed()
        screenshot("onboarding-2")

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(OnboardingText.TITLE_3).assertIsDisplayed()
        for (line in OnboardingText.DESCRIPTION_3.lines()) compose.onNodeWithText(line).assertExists()
        compose.onNodeWithText(OnboardingText.BOTTOM_3).assertExists()
        assertFalse(compose.onAllNodesWithTextExists("Skip"))
        screenshot("onboarding-3")

        // The last button looks like the others (solid white), just with its own label.
        compose.onNodeWithText("Next").assertDoesNotExist()
        assertEquals(0, finished)
        compose.onNodeWithText(OnboardingText.START).performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `skip closes the tutorial`() {
        var finished = false
        compose.setContent { HinglishPdfTheme { OnboardingScreen(onFinish = { finished = true }) } }
        compose.onNodeWithText("Skip").performClick()
        assertTrue(finished)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextExists(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
