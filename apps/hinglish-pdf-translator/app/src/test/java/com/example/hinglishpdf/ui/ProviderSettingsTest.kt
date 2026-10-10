package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.settings.ProviderSettings
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The sheet's content: Get API Key and the key field up front, the model folded under Advanced Settings. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ProviderSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height * 3 / 4, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `advanced settings hide the model name until opened`() {
        val settings = ProviderSettings(ApplicationProvider.getApplicationContext()).state.value
        compose.setContent {
            HinglishPdfTheme {
                Surface { ProviderSettingsContent(settings, null, false, {}, { _, _ -> }, {}, { _, _ -> }) }
            }
        }
        compose.onNodeWithText("Get API Key").assertIsDisplayed()
        compose.onNodeWithText("Model (optional)").assertDoesNotExist()
        compose.onNodeWithText("Advanced Settings").performClick()
        compose.onNodeWithText("Model (optional)").assertIsDisplayed()
        screenshot("provider-settings-content")
    }
}
