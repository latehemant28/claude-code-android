package com.example.hinglishpdf.ui

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.settings.ProviderSettings
import com.example.hinglishpdf.ui.reader.ReaderDocument
import com.example.hinglishpdf.ui.reader.ReaderFont
import com.example.hinglishpdf.ui.reader.ReaderStyle
import com.example.hinglishpdf.service.LiveStatus
import com.example.hinglishpdf.service.TranslationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** The three bottom tabs. */
enum class AppTab { TRANSLATE, LIBRARY, SETTINGS }

data class TranslatorUiState(
    /** The AI provider picked in the app, with each provider's key and model. */
    val providers: ProviderSettings.State,
    /** "OpenAI · gpt-4.1-mini": what answered last; null until the first page (picked automatically). */
    val activeModel: String? = null,
    /** What "Save to Downloads" writes. */
    val outputFormat: DocFormat = DocFormat.EPUB,
    /** "From" / "To" for the next book added. */
    val sourceLanguage: Language = Language.AUTO_DETECT,
    val targetLanguage: Language = Language.HINDI,
    /** When the Terms of Use were accepted; null until then (no translation may start). */
    val termsAcceptedAt: Long? = null,
    val books: List<BookWithProgress> = emptyList(),
    val selectedBookId: Long? = null,
    val live: LiveStatus = LiveStatus(),
    val importing: Boolean = false,
    /** The book whose reading copy is being prepared ("Read Now"). */
    val openingBookId: Long? = null,
    /** One-shot message for a snackbar. */
    val message: String? = null,
) {
    val selected: BookWithProgress?
        get() = books.firstOrNull { it.book.id == selectedBookId } ?: books.firstOrNull()

    val provider: AIProvider get() = providers.provider

    /** False until the selected provider has an API key. */
    val configured: Boolean get() = providers.configured

    val termsAccepted: Boolean get() = termsAcceptedAt != null

    /** From and To must differ (Auto-Detect can be anything). */
    val languagesValid: Boolean get() = sourceLanguage != targetLanguage

    val canAddBook: Boolean get() = configured && languagesValid && !importing

    fun isRunning(book: BookEntity) = live.running && live.bookId == book.id
}

private data class Settings(
    val providers: ProviderSettings.State,
    val activeModel: String?,
    val outputFormat: DocFormat,
    val termsAcceptedAt: Long?,
    val languages: Pair<Language, Language>,
)

/** The page in progress, ready to draw: finished blocks plus the streaming micro-chunk. */
data class LivePage(
    val page: Int,
    val chunk: Int,
    val chunkCount: Int,
    /** Finished blocks of this page, with their structure. */
    val blocks: List<Pair<com.example.hinglishpdf.data.document.DocBlock, String>>,
    /** The chunk being written right now (Markdown, as the AI streams it). */
    val streaming: String,
) {
    companion object {
        fun from(s: LiveStatus) = LivePage(
            page = s.page,
            chunk = s.chunk,
            chunkCount = s.chunkCount,
            blocks = s.pageBlocks.mapIndexedNotNull { i, b -> s.pageTranslations.getOrNull(i)?.let { b to it } },
            streaming = s.liveText.trim(),
        )
    }
}

private data class LocalState(
    val selectedBookId: Long? = null,
    val importing: Boolean = false,
    val openingBookId: Long? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * [saved] is the process-death-proof part of the UI state: which sheet or
 * screen was open (the API key sheet, its in-app browser, the reader), so a
 * user who was killed in the background while fetching an OTP comes back to
 * the very screen they left.
 */
class TranslatorViewModel(
    private val app: HinglishApp,
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    private val settings = combine(
        app.providers.state,
        app.activeModel,
        app.preferences.outputFormat,
        app.preferences.termsAcceptedAt,
        combine(app.preferences.sourceLanguage, app.preferences.targetLanguage, ::Pair),
        ::Settings,
    )

    val state: StateFlow<TranslatorUiState> = combine(
        settings,
        app.db.bookDao().observeAll(),
        // Only the coarse status here; the per-token text has its own flow below.
        app.monitor.status.map { it.copy(liveText = "", pageTranslations = emptyList()) }.distinctUntilChanged(),
        local,
    ) { s, books, live, l ->
        TranslatorUiState(
            providers = s.providers,
            activeModel = s.activeModel,
            outputFormat = s.outputFormat,
            termsAcceptedAt = s.termsAcceptedAt,
            sourceLanguage = s.languages.first,
            targetLanguage = s.languages.second,
            books = books,
            selectedBookId = l.selectedBookId,
            live = live,
            importing = l.importing,
            openingBookId = l.openingBookId,
            message = l.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TranslatorUiState(
            providers = app.providers.state.value,
            outputFormat = app.preferences.outputFormat.value,
            termsAcceptedAt = app.preferences.termsAcceptedAt.value,
            sourceLanguage = app.preferences.sourceLanguage.value,
            targetLanguage = app.preferences.targetLanguage.value,
        ),
    )

    /**
     * The page being translated, streamed chunk by chunk from the AI. The
     * text is prepared on Dispatchers.Default (conflated, so a slow frame
     * never backs up the stream) and delivered to Compose on the main thread
     * through viewModelScope.
     */
    val livePage: StateFlow<LivePage?> = app.monitor.status
        .map { s -> if (!s.running || s.pageBlocks.isEmpty()) null else LivePage.from(s) }
        .conflate()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Translated pages of the selected book, straight from Room (updates as each page is saved). */
    val pages: StateFlow<List<PageEntity>> = state
        .map { it.selected?.book?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else app.db.pageDao().observeTranslated(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // After a crash, a force-stop or a reboot: pick up where we stopped.
        viewModelScope.launch {
            if (app.translatorConfigured && app.preferences.termsAccepted && !app.monitor.status.value.running &&
                app.db.bookDao().nextResumable() != null
            ) {
                TranslationService.start(app)
            }
        }
    }

    fun select(bookId: Long) = local.update { it.copy(selectedBookId = bookId) }

    /** Font and size for translated text, from the "Aa" sheet; saved on the phone. */
    val readerStyle: StateFlow<ReaderStyle> = app.readerSettings.style

    fun setReaderFont(font: ReaderFont) = app.readerSettings.setFont(font)

    fun setReaderTextSize(sp: Float) = app.readerSettings.setTextSize(sp)

    /** Picks the AI provider (Gemini, OpenAI, Claude, Groq) used for the next request. */
    fun selectProvider(provider: AIProvider) {
        app.providers.select(provider)
        app.resetFailover()
    }

    /** Stores a pasted key on this phone, for [provider] only. */
    fun saveApiKey(provider: AIProvider, key: String) {
        app.providers.saveKey(provider, key)
        app.resetFailover()
        closeProviderSheet()
        _keySaved.value = provider
    }

    /**
     * A key captured automatically (copied in the in-app browser, or found on
     * the clipboard): saved at once for the provider it belongs to, which
     * becomes the selected one; the sheet closes and the success screen shows.
     */
    fun autoSaveKey(provider: AIProvider, key: String) {
        app.providers.saveKey(provider, key)
        app.resetFailover()
        if (!state.value.live.running) app.providers.select(provider)
        closeProviderSheet()
        _keySaved.value = provider
    }

    private val _keySaved = MutableStateFlow<AIProvider?>(null)

    /** Set right after a key is saved: shows "✅ API Key Saved Successfully!". */
    val keySaved: StateFlow<AIProvider?> = _keySaved.asStateFlow()

    fun keySavedShown() {
        _keySaved.value = null
    }

    fun removeApiKey(provider: AIProvider) = app.providers.clearKey(provider)

    /** A model to try before the provider's defaults; blank = automatic. */
    fun setModel(provider: AIProvider, model: String) {
        app.providers.setModel(provider, model)
        showMessage(if (model.isBlank()) "Model picked automatically" else "Model set to ${model.trim()}")
    }

    fun setOutputFormat(format: DocFormat) = app.preferences.setOutputFormat(format)

    /** "From" for books added from now on (a book keeps the pair it was added with). */
    fun setSourceLanguage(language: Language) = app.preferences.setSourceLanguage(language)

    /** "To" for books added from now on. */
    fun setTargetLanguage(language: Language) = app.preferences.setTargetLanguage(language)

    /** ⇄: From becomes To and To becomes From (not possible from Auto-Detect). */
    fun swapLanguages() {
        val from = app.preferences.sourceLanguage.value
        val to = app.preferences.targetLanguage.value
        if (from == Language.AUTO_DETECT) return
        app.preferences.setSourceLanguage(to)
        app.preferences.setTargetLanguage(from)
    }

    /** The user ticked the box and tapped "I Agree". */
    fun acceptTerms() = app.preferences.acceptTerms()

    /** Adds the picked book to the queue and starts the background service. */
    fun addBook(uri: Uri) {
        if (!state.value.canAddBook || !app.preferences.termsAccepted) return
        local.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val imported = app.importer.copyIn(uri)
                val id = app.db.bookDao().insert(
                    BookEntity(
                        title = imported.title,
                        format = imported.format,
                        sourcePath = imported.file.absolutePath,
                        // The book keeps this pair even if the dropdowns change later.
                        sourceLanguage = state.value.sourceLanguage.code,
                        language = state.value.targetLanguage.code,
                    ),
                )
                local.update { it.copy(selectedBookId = id) }
                TranslationService.start(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: "Could not open the book")
            } finally {
                local.update { it.copy(importing = false) }
            }
        }
    }

    fun pause() = TranslationService.pause(app)

    /** Continues from the first page without a saved translation. */
    fun resume(book: BookEntity) {
        if (!app.preferences.termsAccepted) return
        viewModelScope.launch {
            app.db.bookDao().setStatus(book.id, BookStatus.QUEUED)
            TranslationService.start(app)
        }
    }

    /** Writes the book (translated so far) to Downloads, in the format picked with the toggle. */
    fun saveToDownloads(book: BookEntity) {
        val format = state.value.outputFormat
        app.appScope.launch {
            val message = try {
                "Saved to Downloads: ${app.exporter.exportToDownloads(book, format).displayName}"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            showMessage(message)
        }
    }

    private val _reader = MutableStateFlow(restoreReader())

    /**
     * The book open in the in-app reader, if any. It survives rotation with the
     * ViewModel and process death through [saved] (the exported copy stays in
     * the app's files, so it is simply reopened).
     */
    val reader: StateFlow<ReaderDocument?> = _reader.asStateFlow()

    private fun openReader(document: ReaderDocument?) {
        _reader.value = document
        saved[KEY_READER] = document?.let {
            Bundle().apply {
                putLong("book", it.bookId)
                putString("title", it.title)
                putString("path", it.file.path)
                putString("format", it.format.name)
            }
        }
    }

    private fun restoreReader(): ReaderDocument? {
        val bundle = saved.get<Bundle>(KEY_READER) ?: return null
        val file = File(bundle.getString("path") ?: return null)
        val format = DocFormat.entries.firstOrNull { it.name == bundle.getString("format") }
        if (!file.isFile || format == null) return null
        return ReaderDocument(bundle.getLong("book"), bundle.getString("title").orEmpty(), file, format)
    }

    val readerDark: StateFlow<Boolean> = app.preferences.readerDark
    val readerScale: StateFlow<Float> = app.preferences.readerScale

    fun setReaderDark(dark: Boolean) = app.preferences.setReaderDark(dark)

    fun setReaderScale(scale: Float) = app.preferences.setReaderScale(scale)

    /** "Read Now": writes a private copy in the chosen output format and opens it in the reader. */
    fun readNow(book: BookEntity) {
        if (local.value.openingBookId != null) return
        val format = state.value.outputFormat
        local.update { it.copy(openingBookId = book.id) }
        viewModelScope.launch {
            try {
                val file = app.exporter.exportForReading(book, format)
                openReader(ReaderDocument(book.id, book.title, file, format))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Could not open the book: ${e.message}")
            } finally {
                local.update { it.copy(openingBookId = null) }
            }
        }
    }

    fun closeReader() = openReader(null)

    /** The "AI provider & API key" sheet (status banner, menu, or Translate without a key). */
    val providerSheet: StateFlow<Boolean> = saved.getStateFlow(KEY_PROVIDER_SHEET, false)

    /** The bottom tab on screen (kept through process death, like the sheets). */
    val tab: StateFlow<Int> = saved.getStateFlow(KEY_TAB, AppTab.TRANSLATE.ordinal)

    fun selectTab(tab: AppTab) {
        saved[KEY_TAB] = tab.ordinal
    }

    /** The in-app "Get API Key" browser, opened from that sheet. */
    val keyBrowser: StateFlow<Boolean> = saved.getStateFlow(KEY_KEY_BROWSER, false)

    fun showProviderSheet(show: Boolean) {
        if (show) saved[KEY_PROVIDER_SHEET] = true else closeProviderSheet()
    }

    fun showKeyBrowser(show: Boolean) {
        saved[KEY_KEY_BROWSER] = show
    }

    private fun closeProviderSheet() {
        saved[KEY_KEY_BROWSER] = false
        saved[KEY_PROVIDER_SHEET] = false
    }

    fun delete(book: BookEntity) {
        if (state.value.isRunning(book)) return
        viewModelScope.launch {
            app.db.bookDao().delete(book.id) // pages go with it (ON DELETE CASCADE)
            File(book.sourcePath).delete()
            local.update { if (it.selectedBookId == book.id) it.copy(selectedBookId = null) else it }
        }
    }

    /** All translated pages as plain text, with bullets, numbering and page breaks. */
    fun plainText(): String =
        pages.value.joinToString("\n\n") { page -> "— ${page.pageNumber} —\n\n${page.translatedText.orEmpty()}" }

    fun showMessage(text: String) = local.update { it.copy(message = text) }

    fun messageShown() = local.update { it.copy(message = null) }

    companion object {
        const val KEY_PROVIDER_SHEET = "provider_sheet"
        const val KEY_KEY_BROWSER = "key_browser"
        const val KEY_READER = "reader"
        const val KEY_TAB = "tab"

        val Factory = viewModelFactory {
            initializer { TranslatorViewModel(this[APPLICATION_KEY] as HinglishApp, createSavedStateHandle()) }
        }
    }
}
