package com.example.hinglishpdf.ui.listen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.voice.Voice
import com.example.hinglishpdf.ui.LocalHindi
import com.example.hinglishpdf.ui.Say
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** "Listen": big buttons that read the translated book aloud. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ListenScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeVoice : Voice {
        val spoken = mutableListOf<String>()
        override val finished = MutableSharedFlow<String>(extraBufferCapacity = 4)
        override fun speak(text: String, language: Language, id: String) {
            spoken += text
        }
        override fun stop() = Unit
        override fun hasVoice(language: Language) = true
        override fun setRate(rate: Float) = Unit
    }

    @Test
    fun `play reads the book aloud from where it stopped, next moves on`() {
        val voice = FakeVoice()
        val positions = mutableListOf<Int>()
        val document = ListenDocument(
            3, "ठंडी सुबह", Language.HINDI,
            listOf(Paragraph(1, "अध्याय 1", heading = true), Paragraph(1, "वो एक ठंडी सुबह थी।"), Paragraph(2, "सब लोग देर से आए।")),
        )
        compose.setContent {
            HinglishPdfTheme {
                CompositionLocalProvider(LocalHindi provides true) {
                    ListenScreen(document, voice, start = 1, rate = 1f, onRate = {}, onPosition = { positions += it }, onClose = {})
                }
            }
        }
        compose.onNodeWithText("वो एक ठंडी सुबह थी।").assertIsDisplayed()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithText(Say.next.hi).assertIsDisplayed()
        save("listen")

        compose.onNodeWithContentDescription("Play").performClick()
        assertEquals("वो एक ठंडी सुबह थी।", voice.spoken.last())
        compose.onNodeWithContentDescription(Say.next.hi).performClick()
        assertEquals("सब लोग देर से आए।", voice.spoken.last())
        assertEquals(listOf(2), positions)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
