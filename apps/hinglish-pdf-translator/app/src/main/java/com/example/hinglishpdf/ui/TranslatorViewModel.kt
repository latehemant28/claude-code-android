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
import com.example.hinglishpdf.data.llm.ModelStatus
import com.example.hinglishpdf.data.translate.TargetLanguage
import com.example.hinglishpdf.service.LiveStatus
import com.example.hinglishpdf.service.TranslationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class TranslatorUiState(
    val model: ModelStatus = ModelStatus.Preparing("Looking for a model…"),
    val language: TargetLanguage = TargetLanguage.HINGLISH,
    val books: List<BookWithProgress> = emptyList(),
    val selectedBookId: Long? = null,
    val live: LiveStatus = LiveStatus(),
    val importing: Boolean = false,
    /** One-shot message for a snackbar. */
    val message: String? = null,
) {
    val selected: BookWithProgress?
        get() = books.firstOrNull { it.book.id == selectedBookId } ?: books.firstOrNull()

    val canAddBook: Boolean get() = model is ModelStatus.Ready && !importing

    fun isRunning(book: BookEntity) = live.running && live.bookId == book.id
}

private data class LocalState(
    val language: TargetLanguage = TargetLanguage.HINGLISH,
    val selectedBookId: Long? = null,
    val importing: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TranslatorViewModel(private val app: HinglishApp) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<TranslatorUiState> = combine(
        app.modelController.status,
        app.db.bookDao().observeAll(),
        app.monitor.status,
        local,
    ) { model, books, live, l ->
        TranslatorUiState(model, l.language, books, l.selectedBookId, live, l.importing, l.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslatorUiState())

    /** Translated pages of the selected book, straight from Room (updates as each page is saved). */
    val pages: StateFlow<List<PageEntity>> = state
        .map { it.selected?.book?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else app.db.pageDao().observeTranslated(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        app.modelController.refresh()
        // After a crash, a force-stop or a reboot: pick up where we stopped.
        viewModelScope.launch {
            if (!app.monitor.status.value.running && app.db.bookDao().nextResumable() != null) {
                TranslationService.start(app)
            }
        }
    }

    fun setLanguage(language: TargetLanguage) = local.update { it.copy(language = language) }

    fun select(bookId: Long) = local.update { it.copy(selectedBookId = bookId) }

    fun importModel(uri: Uri) = app.modelController.refresh(uri)

    fun rescanModel() = app.modelController.refresh()

    /** Adds the picked book to the queue and starts the background service. */
    fun addBook(uri: Uri) {
        if (!state.value.canAddBook) return
        local.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val imported = app.importer.copyIn(uri)
                val id = app.db.bookDao().insert(
                    BookEntity(
                        title = imported.title,
                        format = imported.format,
                        sourcePath = imported.file.absolutePath,
                        language = local.value.language,
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
        viewModelScope.launch {
            app.db.bookDao().setStatus(book.id, BookStatus.QUEUED)
            TranslationService.start(app)
        }
    }

    /** Writes the book (translated so far) to Downloads. */
    fun saveToDownloads(book: BookEntity) {
        app.appScope.launch {
            val message = try {
                "Saved to Downloads: ${app.exporter.exportToDownloads(book).displayName}"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            showMessage(message)
        }
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
        val Factory = viewModelFactory {
            initializer { TranslatorViewModel(this[APPLICATION_KEY] as HinglishApp) }
        }
    }
}
