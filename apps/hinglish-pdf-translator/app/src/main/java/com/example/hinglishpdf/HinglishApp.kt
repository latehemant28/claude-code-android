package com.example.hinglishpdf

import android.app.Application
import com.example.hinglishpdf.data.FallbackGeminiModel
import com.example.hinglishpdf.data.GeminiHinglishModel
import com.example.hinglishpdf.data.HinglishModel
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.db.AppDatabase
import com.example.hinglishpdf.data.document.BookImporter
import com.example.hinglishpdf.data.export.BookExporter
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import com.example.hinglishpdf.data.settings.GeminiKeyStore
import com.example.hinglishpdf.service.Notifications
import com.example.hinglishpdf.service.TranslationMonitor
import com.example.hinglishpdf.ui.reader.ReaderSettingsStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-wide singletons. The screen and the background service live in the same
 * process and share one database, one Gemini client and one live status.
 */
class HinglishApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** BuildConfig's key (from local.properties), or one pasted into the app. */
    val geminiKey by lazy { GeminiKeyStore(this) }
    val geminiConfigured: Boolean get() = geminiKey.key.value.isNotBlank()

    val db by lazy { AppDatabase.create(this) }
    val translationRepository by lazy { TranslationRepository(HinglishModel { chunk -> gemini().translate(chunk) }) }

    /** The Gemini model that answered last (shown in the app); null before the first page. */
    val activeGeminiModel = MutableStateFlow<String?>(null)

    /** One fallback engine per key (models tried in GEMINI_MODELS order); recreated only if the key changes. */
    private var geminiClient: Pair<String, FallbackGeminiModel>? = null

    @Synchronized
    private fun gemini(): FallbackGeminiModel {
        val key = geminiKey.key.value
        geminiClient?.let { (k, client) -> if (k == key) return client }
        val engine = FallbackGeminiModel { model -> GeminiHinglishModel(apiKey = key, modelName = model) }
        appScope.launch { engine.activeModel.collect { if (it != null) activeGeminiModel.value = it } }
        return engine.also { geminiClient = key to it }
    }

    /** Reading font and text size chosen in the "Aa" sheet. */
    val readerSettings by lazy { ReaderSettingsStore(this) }

    val importer by lazy { BookImporter(this, PdfTextExtractor(this)) }
    val exporter by lazy { BookExporter(this, db) }
    val monitor = TranslationMonitor()

    override fun onCreate() {
        super.onCreate()
        // PDFBox needs its font/encoding resources before the first PDF is parsed.
        PDFBoxResourceLoader.init(this)
        Notifications.createChannels(this)
        // Versions before 2.0 kept a 1.6 GB on-device model here; free that space.
        appScope.launch(Dispatchers.IO) {
            File(filesDir, "models").deleteRecursively()
            getExternalFilesDir("models")?.deleteRecursively()
        }
    }
}
