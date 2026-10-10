package com.example.hinglishpdf

import android.app.Application
import com.example.hinglishpdf.data.ProviderFailover
import com.example.hinglishpdf.data.RequestPacer
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.ai.AITranslator
import com.example.hinglishpdf.data.ai.FallbackTranslator
import com.example.hinglishpdf.data.db.AppDatabase
import com.example.hinglishpdf.data.document.BookImporter
import com.example.hinglishpdf.data.export.BookExporter
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import com.example.hinglishpdf.data.document.BundledFonts
import com.example.hinglishpdf.data.settings.AppPreferences
import com.example.hinglishpdf.data.settings.ProviderSettings
import com.example.hinglishpdf.data.voice.AndroidVoice
import com.example.hinglishpdf.data.voice.Voice
import com.example.hinglishpdf.service.Notifications
import com.example.hinglishpdf.service.TranslationMonitor
import com.example.hinglishpdf.ui.reader.ReaderSettingsStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-wide singletons. The screen and the background service live in the same
 * process and share one database, one translation engine and one live status.
 */
class HinglishApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The AI provider picked in the app, and its API key (BuildConfig's for Gemini, or one pasted in). */
    val providers by lazy { ProviderSettings(this) }
    val translatorConfigured: Boolean get() = providers.state.value.configured

    /** Output format (PDF / EPUB) and Terms of Use acceptance. */
    val preferences by lazy { AppPreferences(this) }

    /** The phone's text-to-speech voice: spoken help, and books read aloud. */
    val voice: Voice by lazy { AndroidVoice(this) }

    val db by lazy { AppDatabase.create(this) }
    val translationRepository by lazy {
        TranslationRepository(
            model = AITranslator { systemPrompt, chunk -> translator().translate(systemPrompt, chunk) },
            // Paced for the provider selected now, so switching provider applies at once.
            pacer = RequestPacer(minIntervalMillis = { providers.state.value.provider.chunkPauseMillis }),
            chunkPauseMillis = { providers.state.value.provider.chunkPauseMillis },
            rateLimitWaitMillis = { providers.state.value.provider.rateLimitWaitMillis },
            failover = failover,
        )
    }

    /** Providers that failed for good in this app session (out of credit, bad key...): not switched back to. */
    private val failedProviders = mutableSetOf<AIProvider>()

    /**
     * Smart fallback: the provider in use failed for good, so continue with
     * the next one the user has a key for (free tiers first). The selection
     * changes in the app too, so the user sees which engine is working.
     */
    private val failover = ProviderFailover {
        synchronized(failedProviders) {
            val settings = providers.state.value
            failedProviders += settings.provider
            val next = FAILOVER_ORDER.firstOrNull { it !in failedProviders && settings.key(it).isNotBlank() }
                ?: return@ProviderFailover null
            providers.select(next)
            next.displayName
        }
    }

    /** After the user changes keys or providers, every provider gets a fresh chance. */
    fun resetFailover() = synchronized(failedProviders) { failedProviders.clear() }

    /** "OpenAI · gpt-4.1-mini": the provider and model that answered last; null before the first page. */
    val activeModel = MutableStateFlow<String?>(null)

    private data class EngineKey(val provider: AIProvider, val apiKey: String, val models: List<String>)

    /**
     * One fallback engine (models tried in order) for the selected provider,
     * built from that provider's [AITranslator] strategy; rebuilt only when
     * the provider, key or model changes.
     */
    private var engine: Pair<EngineKey, FallbackTranslator>? = null
    private var engineWatcher: Job? = null

    @Synchronized
    private fun translator(): FallbackTranslator {
        val settings = providers.state.value
        val provider = settings.provider
        val key = EngineKey(provider, settings.key, provider.models(settings.customModel(provider)))
        engine?.let { (k, e) -> if (k == key) return e }
        val fallback = FallbackTranslator(key.models, provider.displayName) { model ->
            provider.createTranslator(key.apiKey, model)
        }
        engineWatcher?.cancel()
        engineWatcher = appScope.launch {
            fallback.activeModel.collect { if (it != null) activeModel.value = "${provider.displayName} · $it" }
        }
        return fallback.also { engine = key to it }
    }

    /** Reading font and text size chosen in the "Aa" sheet. */
    val readerSettings by lazy { ReaderSettingsStore(this) }

    val importer by lazy { BookImporter(this, PdfTextExtractor(this)) }
    val exporter by lazy { BookExporter(this, db, BundledFonts(assets)) }
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

    private companion object {
        /** Gemini first (a generous free tier), then the other free tier, then the paid ones. */
        val FAILOVER_ORDER = listOf(AIProvider.GEMINI, AIProvider.GROQ, AIProvider.OPENAI, AIProvider.ANTHROPIC)
    }
}
