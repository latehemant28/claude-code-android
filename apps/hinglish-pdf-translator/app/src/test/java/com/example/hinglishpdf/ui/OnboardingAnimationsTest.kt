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

/** The onboarding's Lottie files parse cleanly and draw something at every stage of the loop. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingAnimationsTest {

    @Test
    fun `animations parse without warnings and render`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((name, res) in listOf("book" to R.raw.onboarding_book, "chip" to R.raw.onboarding_chip, "badge" to R.raw.onboarding_badge)) {
            val result = LottieCompositionFactory.fromRawResSync(context, res)
            assertNotNull("$name: ${result.exception}", result.value)
            val composition = result.value!!
            assertTrue("$name: ${composition.warnings}", composition.warnings.isEmpty())
            assertEquals(4f, composition.duration / 1000f, 0.01f) // a 4 s loop

            for (progress in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f)) {
                val drawable = LottieDrawable().apply {
                    setComposition(composition)
                    this.progress = progress
                }
                val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(0x1C, 0x0B, 0x4B))
                drawable.setBounds(0, 0, 400, 400)
                drawable.draw(canvas)
                val pixels = IntArray(400 * 400).also { bitmap.getPixels(it, 0, 400, 0, 0, 400, 400) }
                assertTrue("$name at $progress drew nothing", pixels.distinct().size > 50)
                val dir = File("build/screenshots/lottie").apply { mkdirs() }
                File(dir, "$name-${(progress * 100).toInt()}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
