package com.example.hinglishpdf.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.TranslationEvent
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.DocumentFormatter
import com.example.hinglishpdf.data.document.PdfExporter
import com.example.hinglishpdf.data.document.SourceDocument
import com.example.hinglishpdf.data.epub.EpubBook
import com.example.hinglishpdf.data.llm.LlmTranslator
import com.example.hinglishpdf.data.llm.ModelFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ModelStatus {
    /** Looking for, copying, importing or loading a model. */
    data class Preparing(val message: String, val progress: Float? = null) : ModelStatus
    data object Missing : ModelStatus
    data class Ready(val fileName: String, val backend: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

enum class Phase { Idle, Reading, Translating, Done, Cancelled, Failed }

data class TranslatorUiState(
    val model: ModelStatus = ModelStatus.Preparing("Looking for a model…"),
    val document: SourceDocument? = null,
    val phase: Phase = Phase.Idle,
    val progressLabel: String = "",
    /** 0..1, or null for an indeterminate indicator. */
    val progress: Float? = null,
    /** Translated text per block of [document]; null = not translated yet. */
    val translations: List<String?> = emptyList(),
    /** Model output for the chunk being generated right now. */
    val liveText: String = "",
    val error: String? = null,
    /** One-shot message for a snackbar. */
    val message: String? = null,
) {
    val isBusy: Boolean get() = phase == Phase.Reading || phase == Phase.Translating
    val canSelectDocument: Boolean get() = model is ModelStatus.Ready && !isBusy
    val canChangeModel: Boolean get() = model !is ModelStatus.Preparing && !isBusy
    val hasOutput: Boolean get() = translations.any { it != null }
}

class TranslatorViewModel(
    private val modelFiles: ModelFileManager,
    private val translator: LlmTranslator,
    private val repository: TranslationRepository,
    private val contentResolver: ContentResolver,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(TranslatorUiState())
    val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

    private var modelJob: Job? = null
    private var translationJob: Job? = null

    init {
        prepareModel(importUri = null)
    }

    /** Re-scan storage for a model (e.g. after a download or `adb push`). */
    fun retryModelSearch() = prepareModel(importUri = null)

    /** Copy a model the user picked with the system file picker, then load it. */
    fun importModel(uri: Uri) = prepareModel(importUri = uri)

    private fun prepareModel(importUri: Uri?) {
        if (_state.value.isBusy) return
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            try {
                setModel(ModelStatus.Preparing("Looking for a model…"))
                val file = if (importUri != null) {
                    modelFiles.importModel(importUri) { p -> setModel(ModelStatus.Preparing("Importing model…", p)) }
                } else {
                    modelFiles.findModel { p -> setModel(ModelStatus.Preparing("Copying bundled model…", p)) }
                }
                if (file == null) {
                    setModel(ModelStatus.Missing)
                    return@launch
                }
                setModel(ModelStatus.Preparing("Loading ${file.name} into memory… (up to a minute)"))
                translator.load(file)
                setModel(ModelStatus.Ready(file.name, translator.backendName ?: "CPU"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError included: a model too big for this phone.
                setModel(ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun translate(uri: Uri) {
        if (!_state.value.canSelectDocument) return
        translationJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    document = null, phase = Phase.Reading, progressLabel = "Opening document…",
                    progress = null, translations = emptyList(), liveText = "", error = null,
                )
            }
            try {
                repository.translate(uri).collect(::onEvent)
                _state.update {
                    it.copy(
                        phase = Phase.Done,
                        progressLabel = "Done: ${it.document?.title ?: "document"} is in Hinglish",
                        progress = 1f,
                        liveText = "",
                        translations = withOriginalsFilled(it, upTo = Int.MAX_VALUE),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.Cancelled, progressLabel = "Cancelled", liveText = "") }
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        phase = Phase.Failed,
                        progressLabel = "Translation stopped",
                        liveText = "",
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun cancelTranslation() {
        translationJob?.cancel()
    }

    private fun onEvent(event: TranslationEvent) {
        when (event) {
            is TranslationEvent.Reading -> _state.update {
                it.copy(progressLabel = event.label, progress = event.fraction)
            }

            is TranslationEvent.DocumentReady -> _state.update {
                it.copy(
                    document = event.document,
                    translations = List(event.document.blocks.size) { null },
                )
            }

            is TranslationEvent.ChunkStarted -> _state.update {
                it.copy(
                    phase = Phase.Translating,
                    progressLabel = "Translating chunk ${event.chunkNumber} of ${event.chunkCount}...",
                    progress = (event.chunkNumber - 1).toFloat() / event.chunkCount,
                    liveText = "",
                )
            }

            is TranslationEvent.TextGenerated -> _state.update { it.copy(liveText = it.liveText + event.text) }

            is TranslationEvent.ChunkFinished -> _state.update {
                val updated = it.translations.toMutableList()
                event.blocks.forEach { (index, text) -> updated[index] = text }
                val withNew = it.copy(translations = updated)
                it.copy(
                    translations = withOriginalsFilled(withNew, upTo = event.blocks.keys.max()),
                    progress = event.chunkNumber.toFloat() / event.chunkCount,
                    liveText = "",
                )
            }
        }
    }

    /** Shows code and other untranslatable blocks (as-is) once translation has passed them. */
    private fun withOriginalsFilled(state: TranslatorUiState, upTo: Int): List<String?> {
        val blocks = state.document?.blocks ?: return state.translations
        return state.translations.mapIndexed { i, t ->
            t ?: if (i <= upTo && !blocks[i].isTranslatable) blocks[i].text else null
        }
    }

    /** Plain text with bullets, numbering and paragraph breaks, for the clipboard. */
    fun plainText(): String {
        val s = _state.value
        val blocks = s.document?.blocks ?: return ""
        return DocumentFormatter.toPlainText(blocks, s.translations.map { it ?: "" })
    }

    /** Saves the translation in the source format: EPUB -> EPUB copy, PDF -> new PDF. */
    fun export(target: Uri) {
        val s = _state.value
        val document = s.document ?: return
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(target, "wt")
                        ?: throw IOException("Could not open the destination file.")
                    out.use {
                        when (document.format) {
                            DocFormat.EPUB -> EpubBook.writeTranslated(document.file, it, s.translations)
                            DocFormat.PDF -> PdfExporter.write(it, document.blocks, s.translations)
                        }
                    }
                }
                "Saved"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
            _state.update { it.copy(message = result) }
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun setModel(status: ModelStatus) = _state.update { it.copy(model = status) }

    override fun onCleared() {
        // viewModelScope is already cancelled; release the 1-3 GB model once
        // any in-flight generation has wound down.
        appScope.launch { translator.close() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as HinglishApp
                val translator = LlmTranslator(app)
                TranslatorViewModel(
                    modelFiles = app.modelFileManager,
                    translator = translator,
                    repository = TranslationRepository(app.documentLoader, translator),
                    contentResolver = app.contentResolver,
                    appScope = app.appScope,
                )
            }
        }
    }
}
