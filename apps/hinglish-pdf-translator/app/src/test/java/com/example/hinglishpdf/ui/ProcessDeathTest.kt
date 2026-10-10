package com.example.hinglishpdf.ui

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The app killed in the background while the user fetches an OTP: on return
 * the key sheet, its browser and the page they were on are all back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HinglishApp::class)
class ProcessDeathTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val otpPage = "https://accounts.google.com/v3/signin/challenge/otp"

    @Test
    fun `the browser resumes on the page it was on, not the provider's home page`() {
        val before = WebView(compose.activity)
        val state = BrowserState()
        state.restoreInto(before, home = AIProvider.GEMINI.keyPageUrl)
        assertEquals(AIProvider.GEMINI.keyPageUrl, shadowOf(before).lastLoadedUrl)
        before.loadUrl(otpPage)

        // The activity is destroyed: the state goes into the saved-instance Bundle...
        val bundle = with(BrowserState.Saver) { SaverScope { true }.save(state) }!!
        val parcel = Parcel.obtain()
        try {
            // ...which must survive being written out of the process.
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            val revived = parcel.readBundle(javaClass.classLoader)!!

            val after = WebView(compose.activity)
            BrowserState.Saver.restore(revived)!!.restoreInto(after, home = AIProvider.GEMINI.keyPageUrl)
            assertEquals(otpPage, after.url ?: shadowOf(after).lastLoadedUrl)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `a closed browser keeps the last page for when it comes back`() {
        val state = BrowserState()
        val first = WebView(compose.activity)
        state.restoreInto(first, home = AIProvider.OPENAI.keyPageUrl)
        first.loadUrl(otpPage)
        state.detach(first)

        val second = WebView(compose.activity)
        state.restoreInto(second, home = AIProvider.OPENAI.keyPageUrl)
        assertEquals(otpPage, second.url ?: shadowOf(second).lastLoadedUrl)
    }

    @Test
    fun `a recreated view model reopens the key sheet and its browser`() {
        val app = ApplicationProvider.getApplicationContext<HinglishApp>()
        val saved = SavedStateHandle()
        val first = TranslatorViewModel(app, saved)
        first.showProviderSheet(true)
        first.showKeyBrowser(true)

        // Process death: only the SavedStateHandle's contents come back.
        val revived = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val second = TranslatorViewModel(app, revived)
        assertTrue(second.providerSheet.value)
        assertTrue(second.keyBrowser.value)

        // Saving the key closes both, so they don't pop up again next time.
        second.saveApiKey(AIProvider.GROQ, "gsk_" + "Ab3d".repeat(13))
        assertFalse(revived.get<Boolean>(TranslatorViewModel.KEY_PROVIDER_SHEET)!!)
        assertFalse(revived.get<Boolean>(TranslatorViewModel.KEY_KEY_BROWSER)!!)
    }

    @Test
    fun `dismissing the sheet also forgets the browser`() {
        val app = ApplicationProvider.getApplicationContext<HinglishApp>()
        val vm = TranslatorViewModel(app, SavedStateHandle(mapOf("provider_sheet" to true, "key_browser" to true)))
        vm.showProviderSheet(false)
        assertFalse(vm.providerSheet.value)
        assertFalse(vm.keyBrowser.value)
    }

    @Test
    fun `the reader reopens the book if its copy is still there`() {
        val app = ApplicationProvider.getApplicationContext<HinglishApp>()
        val copy = File(app.filesDir, "reading.epub").apply { writeText("epub") }
        fun saved(path: String) = SavedStateHandle(
            mapOf(
                TranslatorViewModel.KEY_READER to Bundle().apply {
                    putLong("book", 7)
                    putString("title", "Cold Morning")
                    putString("path", path)
                    putString("format", DocFormat.EPUB.name)
                },
            ),
        )
        val reader = TranslatorViewModel(app, saved(copy.path)).reader.value
        assertNotNull(reader)
        assertEquals(7L, reader!!.bookId)
        assertEquals(DocFormat.EPUB, reader.format)
        assertNull(TranslatorViewModel(app, saved(File(app.filesDir, "gone.pdf").path)).reader.value)
    }

    @Test
    fun `the header pops the page out to the phone's own browser`() {
        compose.setContent {
            HinglishPdfTheme { ApiKeyBrowser(AIProvider.ANTHROPIC, onKeyCaptured = { _, _ -> }, onClose = {}) }
        }
        compose.onNodeWithContentDescription("Open in Chrome / External Browser").performClick()
        compose.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<HinglishApp>()
        val started = shadowOf(compose.activity).nextStartedActivity ?: shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(AIProvider.ANTHROPIC.keyPageUrl, started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
