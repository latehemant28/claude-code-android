package com.example.hinglishpdf

import android.app.Application
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
import android.util.Log
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.translate.ParagraphTranslator
import com.example.hinglishpdf.pipeline.DocumentParser
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.PipelineStore
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

    val db by lazy { AppDatabase.create(this) }

    /** The pipeline's parser (paragraphs, sentence segments, source map) and where its results are kept. */
    val documentParser by lazy { DocumentParser(cacheDir) { pipelineConfig } }

    /** Thresholds and patterns of the parser, assembler and chunker (assets/pipeline-config.json). */
    val pipelineConfig by lazy { PipelineConfig.load(this) }
    val pipelineStore by lazy { PipelineStore(db.pipelineDao()) }
    val translationRepository by lazy {
        TranslationRepository(
            model = AITranslator { chunk -> translator().translate(chunk) },
            // Paced for the provider selected now, so switching provider applies at once.
            chunkPauseMillis = { providers.state.value.provider.chunkPauseMillis },
            rateLimitWaitMillis = { providers.state.value.provider.rateLimitWaitMillis },
        )
    }

    /** "OpenAI · gpt-4.1-mini": the provider and model that answered last; null before the first page. */
    val activeModel = MutableStateFlow<String?>(null)

    private data class EngineKey(val provider: AIProvider, val apiKey: String, val models: List<String>, val customUrl: String)

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
        val key = EngineKey(provider, settings.key, provider.models(settings.customModel(provider)), settings.customUrl)
        engine?.let { (k, e) -> if (k == key) return e }
        val fallback = FallbackTranslator(key.models, provider.displayName) { model ->
            provider.createTranslator(key.apiKey, model, key.customUrl)
        }
        engineWatcher?.cancel()
        engineWatcher = appScope.launch {
            fallback.activeModel.collect { if (it != null) activeModel.value = "${provider.displayName} · $it" }
        }
        return fallback.also { engine = key to it }
    }

    /** Reading font and text size chosen in the "Aa" sheet. */
    val readerSettings by lazy { ReaderSettingsStore(this) }

    /** Translates the pipeline's paragraph chunks through [translationRepository]. */
    val paragraphTranslator by lazy { ParagraphTranslator(translationRepository) }

    private val parseLock = Mutex()

    /**
     * The book's pipeline parse: the stored one, or a new one when there is
     * none or an older parser made it ([onReanalyse] is told first). A parse
     * the book's pages were built from is kept whatever its version, so the
     * pages stay valid. Throws if the file cannot be parsed.
     */
    suspend fun pipelineDocument(
        book: BookEntity,
        onReanalyse: () -> Unit = {},
        onProgress: (label: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): ParsedDocument = parseLock.withLock {
        val stored = db.pipelineDao().document(book.id)
        val current = stored != null && (stored.parserVersion >= PipelineStore.PARSER_VERSION || db.pageDao().pipelinePageCount(book.id) > 0)
        if (current) pipelineStore.load(book.id)?.let { return@withLock it }
        if (stored != null) onReanalyse()
        val parsed = withContext(Dispatchers.IO) { documentParser.parse(File(book.sourcePath), book.format, onProgress) }
        pipelineStore.save(book.id, parsed)
        parsed
    }

    /** Books whose stored parse an older parser made, to re-analyse (their translations are not touched). */
    suspend fun outdatedParses(): List<Long> =
        db.pipelineDao().outdated(PipelineStore.PARSER_VERSION).filter { db.pageDao().pipelinePageCount(it) == 0 }

    /**
     * Re-analyses [bookIds] in the background. A book the new parser cannot
     * read loses only its outdated parse (it is made again when needed);
     * the book, its pages and its translations stay.
     */
    fun reanalyse(bookIds: List<Long>) = appScope.launch(Dispatchers.IO) {
        for (id in bookIds) {
            val book = db.bookDao().get(id) ?: continue
            try {
                pipelineDocument(book)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HinglishApp", "Re-analysis of book $id failed", e)
                db.pipelineDao().deleteParagraphs(id)
                db.pipelineDao().deleteDocument(id)
            }
        }
    }

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
}
