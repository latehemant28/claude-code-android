package com.example.hinglishpdf.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class LanguageCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `from and to dropdowns pick languages, and swap them`() {
        var source by mutableStateOf(Language.AUTO_DETECT)
        var target by mutableStateOf(Language.HINDI)
        compose.setContent {
            HinglishPdfTheme {
                Surface {
                    LanguageCard(
                        source, target, { source = it }, { target = it },
                        onSwap = { val s = source; source = target; target = s },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Auto-Detect").assertExists()
        compose.onNodeWithText("Hindi").performClick()
        compose.onNodeWithText("Spanish · Español").performClick()
        assertEquals(Language.SPANISH, target)

        compose.onNodeWithText("Auto-Detect").performClick()
        compose.onNodeWithText("English").performClick()
        assertEquals(Language.ENGLISH, source)
        compose.waitForIdle()
        screenshot("languages")

        compose.onNodeWithContentDescription("Swap languages").performClick()
        assertEquals(Language.SPANISH, source)
        assertEquals(Language.ENGLISH, target)
    }

    private fun screenshot(name: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height / 3, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
