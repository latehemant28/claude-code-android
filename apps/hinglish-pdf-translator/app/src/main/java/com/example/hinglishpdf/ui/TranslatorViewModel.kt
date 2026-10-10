package com.example.hinglishpdf.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.settings.ProviderSettings
import com.example.hinglishpdf.pipeline.segment.Placeholders
import com.example.hinglishpdf.pipeline.translate.MarkerPrompt
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
import com.example.hinglishpdf.service.LivePhase
import com.example.hinglishpdf.ui.status.ErrorGuide
import com.example.hinglishpdf.ui.status.Eta
import com.example.hinglishpdf.ui.status.Guidance
import com.example.hinglishpdf.ui.status.ProjectFacts
import com.example.hinglishpdf.ui.status.ProjectStatus
import com.example.hinglishpdf.ui.status.ProjectStatusView

data class TranslatorUiState(
    /** The AI provider picked in the app, with each provider's key and model. */
    val providers: ProviderSettings.State,
    /** "OpenAI · gpt-4.1-mini": what answered last; null until the first page (picked automatically). */
    val activeModel: String? = null,
    /** What "Save to Downloads" writes. */
    val outputFormat: DocFormat = DocFormat.EPUB,
    /** When the Terms of Use were accepted; null until then (no translation may start). */
    val termsAcceptedAt: Long? = null,
    val books: List<BookWithProgress> = emptyList(),
    val selectedBookId: Long? = null,
    val live: LiveStatus = LiveStatus(),
    val importing: Boolean = false,
    /** One-shot message for a snackbar, optionally with an action (Undo). */
    val message: UiMessage? = null,
    /** Books whose PDF is scanned (they need OCR). */
    val needsOcr: Set<Long> = emptySet(),
    /** Immediate feedback while something the user tapped is under way. */
    val savingBookId: Long? = null,
    val pausingBookId: Long? = null,
    /** Why a picked file could not be added, as guidance. */
    val importProblem: Guidance? = null,
) {
    val selected: BookWithProgress?
        get() = books.firstOrNull { it.book.id == selectedBookId } ?: books.firstOrNull()

    val provider: AIProvider get() = providers.provider

    /** False until the selected provider has an API key. */
    val configured: Boolean get() = providers.configured

    val termsAccepted: Boolean get() = termsAcceptedAt != null

    val canAddBook: Boolean get() = configured && !importing

    fun isRunning(book: BookEntity) = live.running && live.bookId == book.id

    /** What the stage map of [entry] is drawn from. */
    fun facts(entry: BookWithProgress): ProjectFacts {
        val book = entry.book
        val running = isRunning(book)
        return ProjectFacts(
            status = book.status,
            pageCount = book.pageCount,
            translatedPages = entry.translatedPages,
            unitName = book.unitName,
            livePhase = if (running) live.phase ?: LivePhase.STARTING else null,
            waiting = running && live.waiting,
            outputName = book.outputName.takeIf { book.status == BookStatus.COMPLETED || book.outputUri != null },
            needsOcr = book.id in needsOcr,
        )
    }

    fun status(entry: BookWithProgress): ProjectStatusView = ProjectStatus.of(facts(entry))

    /** "About 25 min left" for the work in progress on [book]; null when nothing is running. */
    fun timeLeft(book: BookEntity, now: Long = System.currentTimeMillis()): String? {
        if (!isRunning(book) || live.waiting) return null
        return when (live.phase) {
            LivePhase.ANALYSING, LivePhase.READING, LivePhase.TRANSLATING ->
                Eta.format(Eta.remainingMillis(live.doneAtStart, live.done, live.total, live.phaseStartedAt, now))
            LivePhase.SAVING -> "Usually under a minute"
            else -> null
        }
    }
}

/** A snackbar message; [action] (e.g. "Undo") runs [onAction], otherwise [onTimeout] runs when it goes away. */
data class UiMessage(
    val text: String,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
    val onTimeout: (() -> Unit)? = null,
)

private data class Settings(
    val providers: ProviderSettings.State,
    val activeModel: String?,
    val outputFormat: DocFormat,
    val termsAcceptedAt: Long?,
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
            blocks = s.pageBlocks.mapIndexedNotNull { i, b -> s.pageTranslations.getOrNull(i)?.let { b to b.plain(it) } },
            // Pipeline requests stream structure markers and placeholders: show only the words.
            streaming = MarkerPrompt.stripMarkers(Placeholders.strip(s.liveText)).trim(),
        )
    }
}

private data class LocalState(
    val selectedBookId: Long? = null,
    val importing: Boolean = false,
    val message: UiMessage? = null,
    val savingBookId: Long? = null,
    val pausingBookId: Long? = null,
    val importProblem: Guidance? = null,
    /** Removed from the list, deleted for good when the Undo snackbar goes away. */
    val pendingDeleteId: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatorViewModel(private val app: HinglishApp) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    private val settings = combine(
        app.providers.state,
        app.activeModel,
        app.preferences.outputFormat,
        app.preferences.termsAcceptedAt,
        ::Settings,
    )

    val state: StateFlow<TranslatorUiState> = combine(
        settings,
        app.db.bookDao().observeAll(),
        // Only the coarse status here; the per-token text has its own flow below.
        app.monitor.status.map { it.copy(liveText = "", pageTranslations = emptyList()) }.distinctUntilChanged(),
        local,
        app.db.pipelineDao().observeNeedsOcr(),
    ) { s, books, live, l, ocr ->
        TranslatorUiState(
            providers = s.providers,
            activeModel = s.activeModel,
            outputFormat = s.outputFormat,
            termsAcceptedAt = s.termsAcceptedAt,
            books = books.filter { it.book.id != l.pendingDeleteId },
            selectedBookId = l.selectedBookId,
            live = live,
            importing = l.importing,
            message = l.message,
            needsOcr = ocr.toSet(),
            savingBookId = l.savingBookId,
            // "Pausing…" until the service has actually stopped.
            pausingBookId = l.pausingBookId?.takeIf { live.running && live.bookId == it },
            importProblem = l.importProblem,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TranslatorUiState(
            providers = app.providers.state.value,
            outputFormat = app.preferences.outputFormat.value,
            termsAcceptedAt = app.preferences.termsAcceptedAt.value,
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
        // After a parser upgrade: say so, then re-analyse the books in the background.
        viewModelScope.launch {
            val outdated = app.outdatedParses()
            if (outdated.isNotEmpty()) {
                showMessage(reanalyseNotice(outdated.size))
                app.reanalyse(outdated)
            }
        }
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
    fun selectProvider(provider: AIProvider) = app.providers.select(provider)

    /** Stores a pasted key on this phone, for [provider] only. */
    fun saveApiKey(provider: AIProvider, key: String) {
        app.providers.saveKey(provider, key)
        showMessage("${provider.displayName} API key saved on this phone")
    }

    /** The Custom (OpenAI-compatible) provider: its address, key and model, kept on this phone. */
    fun saveCustomProvider(url: String, key: String, model: String) {
        app.providers.saveCustom(url, key, model)
        showMessage("Custom AI saved on this phone")
    }

    fun removeApiKey(provider: AIProvider) = app.providers.clearKey(provider)

    /** A model to try before the provider's defaults; blank = automatic. */
    fun setModel(provider: AIProvider, model: String) {
        app.providers.setModel(provider, model)
        showMessage(if (model.isBlank()) "Model picked automatically" else "Model set to ${model.trim()}")
    }

    fun setOutputFormat(format: DocFormat) = app.preferences.setOutputFormat(format)

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
                    ),
                )
                local.update { it.copy(selectedBookId = id) }
                TranslationService.start(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(importProblem = ErrorGuide.of(e.message ?: "Could not open the selected file.")) }
            } finally {
                local.update { it.copy(importing = false) }
            }
        }
    }

    fun importProblemShown() = local.update { it.copy(importProblem = null) }

    fun pause() {
        local.update { it.copy(pausingBookId = state.value.live.bookId) }
        TranslationService.pause(app)
    }

    /** Continues from the first page without a saved translation. */
    fun resume(book: BookEntity) {
        if (!app.preferences.termsAccepted) return
        val entry = state.value.books.firstOrNull { it.book.id == book.id }
        val next = (entry?.translatedPages ?: 0) + 1
        showMessage(if (book.pageCount > 0) "Continuing from ${book.unitName} $next…" else "Starting…")
        viewModelScope.launch {
            app.db.bookDao().setStatus(book.id, BookStatus.QUEUED)
            TranslationService.start(app)
        }
    }

    /** Writes the book (translated so far) to Downloads, in the format picked with the toggle. */
    fun saveToDownloads(book: BookEntity) {
        val format = state.value.outputFormat
        local.update { it.copy(savingBookId = book.id) } // "Saving…" at once
        app.appScope.launch {
            val message = try {
                "Saved to Downloads: ${app.exporter.exportToDownloads(book, format).displayName}"
            } catch (e: Exception) {
                val guide = ErrorGuide.of("Could not write to Downloads. ${e.message.orEmpty()}")
                "${guide.title}. ${guide.explanation}"
            } finally {
                local.update { it.copy(savingBookId = null) }
            }
            showMessage(message)
        }
    }

    /**
     * Removes the book from the list at once and offers Undo; it is deleted
     * for good only when the message goes away. A book being translated must
     * be paused first (said so, rather than silently ignored).
     */
    fun requestDelete(book: BookEntity) {
        if (state.value.isRunning(book)) {
            showMessage("Pause the translation first, then delete the book.")
            return
        }
        local.value.pendingDeleteId?.let { previous -> commitDelete(previous) }
        local.update {
            it.copy(
                pendingDeleteId = book.id,
                selectedBookId = if (it.selectedBookId == book.id) null else it.selectedBookId,
                message = UiMessage(
                    text = "Deleted “${book.title}”. Files saved in Downloads stay.",
                    action = "Undo",
                    onAction = { undoDelete(book.id) },
                    onTimeout = { commitDelete(book.id) },
                ),
            )
        }
    }

    private fun undoDelete(bookId: Long) = local.update { if (it.pendingDeleteId == bookId) it.copy(pendingDeleteId = null) else it }

    private fun commitDelete(bookId: Long) {
        if (local.value.pendingDeleteId != bookId) return
        local.update { it.copy(pendingDeleteId = null) }
        viewModelScope.launch {
            val book = app.db.bookDao().get(bookId) ?: return@launch
            app.db.bookDao().delete(book.id) // pages and the parse go with it (ON DELETE CASCADE)
            File(book.sourcePath).delete()
        }
    }

    /** All translated pages as plain text, with bullets, numbering and page breaks. */
    fun plainText(): String =
        pages.value.joinToString("\n\n") { page -> "— ${page.pageNumber} —\n\n${page.translatedText.orEmpty()}" }

    fun showMessage(text: String) = local.update { it.copy(message = UiMessage(text)) }

    /** Clears [shown] once it has gone away (a newer message is left alone). */
    fun messageShown(shown: UiMessage) = local.update { if (it.message === shown) it.copy(message = null) else it }

    companion object {
        /** Shown before books are re-analysed after a parser upgrade (never silently). */
        fun reanalyseNotice(books: Int): String =
            "Re-analysing $books ${if (books == 1) "book" else "books"} with the improved parser; your translations are kept"

        val Factory = viewModelFactory {
            initializer { TranslatorViewModel(this[APPLICATION_KEY] as HinglishApp) }
        }
    }
}
