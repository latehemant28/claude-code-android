package com.example.hinglishpdf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.example.hinglishpdf.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The key guides (one per provider) and the "saved" check parse cleanly and draw at every step. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class KeyGuideAnimationsTest {

    private fun render(name: String, res: Int, seconds: Float, progresses: List<Float>, width: Int, height: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = LottieCompositionFactory.fromRawResSync(context, res)
        assertNotNull("$name: ${result.exception}", result.value)
        val composition = result.value!!
        assertTrue("$name: ${composition.warnings}", composition.warnings.isEmpty())
        assertEquals(name, seconds, composition.duration / 1000f, 0.05f)
        for (progress in progresses) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(0xF4, 0xF1, 0xFA))
            LottieDrawable().apply {
                setComposition(composition)
                this.progress = progress
                setBounds(0, 0, width, height)
            }.draw(canvas)
            val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
            assertTrue("$name at $progress drew nothing", pixels.distinct().size > 20)
            val dir = File("build/screenshots/lottie").apply { mkdirs() }
            File(dir, "$name-${(progress * 1000).toInt()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun `each provider has a 15 second guide`() {
        val steps = listOf(0.08f, 0.13f, 0.32f, 0.45f, 0.62f, 0.9f, 0.99f, 1f)
        render("guide-gemini", R.raw.key_guide_gemini, 15f, steps, 400, 260)
        render("guide-openai", R.raw.key_guide_openai, 15f, steps, 400, 260)
        render("guide-anthropic", R.raw.key_guide_anthropic, 15f, steps, 400, 260)
        render("guide-groq", R.raw.key_guide_groq, 15f, steps, 400, 260)
    }

    @Test
    fun `the saved check`() {
        render("key-saved", R.raw.key_saved, 2.5f, listOf(0.15f, 0.3f, 1f), 400, 400)
    }
}
