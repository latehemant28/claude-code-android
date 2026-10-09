package com.example.hinglishpdf.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.TranslationEvent
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.llm.LlmTranslator
import com.example.hinglishpdf.data.llm.ModelFileManager
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ModelStatus {
    /** Looking for, copying, importing or loading a model. */
    data class Preparing(val message: String, val progress: Float? = null) : ModelStatus
    data object Missing : ModelStatus
    data class Ready(val fileName: String, val backend: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

enum class Phase { Idle, Extracting, Translating, Done, Cancelled, Failed }

data class TranslatorUiState(
    val model: ModelStatus = ModelStatus.Preparing("Looking for a model…"),
    val pdfName: String? = null,
    val phase: Phase = Phase.Idle,
    val progressLabel: String = "",
    /** 0..1, or null for an indeterminate indicator. */
    val progress: Float? = null,
    /**
     * Translation output, one entry per chunk. Kept as a list so streaming
     * only rebuilds the last (small) string, not the whole document.
     */
    val translatedChunks: List<String> = emptyList(),
    val error: String? = null,
) {
    val isBusy: Boolean get() = phase == Phase.Extracting || phase == Phase.Translating
    val canSelectPdf: Boolean get() = model is ModelStatus.Ready && !isBusy
    val canChangeModel: Boolean get() = model !is ModelStatus.Preparing && !isBusy
    val fullText: String get() = translatedChunks.filter { it.isNotBlank() }.joinToString("\n\n")
}

class TranslatorViewModel(
    private val modelFiles: ModelFileManager,
    private val extractor: PdfTextExtractor,
    private val translator: LlmTranslator,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val repository = TranslationRepository(extractor, translator)

    private val _state = MutableStateFlow(TranslatorUiState())
    val state: StateFlow<TranslatorUiState> = _state.asStateFlow()

    private var modelJob: Job? = null
    private var translationJob: Job? = null

    init {
        prepareModel(importUri = null)
    }

    /** Re-scan storage for a model (e.g. after an `adb push`). */
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
                    modelFiles.importModel(importUri) { p ->
                        setModel(ModelStatus.Preparing("Importing model…", p))
                    }
                } else {
                    modelFiles.findModel { p ->
                        setModel(ModelStatus.Preparing("Copying bundled model…", p))
                    }
                }
                if (file == null) {
                    setModel(ModelStatus.Missing)
                    return@launch
                }
                setModel(ModelStatus.Preparing("Loading ${file.name} into memory…"))
                translator.load(file)
                setModel(ModelStatus.Ready(file.name, translator.backend?.name ?: "CPU"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError included: a model too big for this phone.
                setModel(ModelStatus.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun translatePdf(uri: Uri) {
        if (!_state.value.canSelectPdf) return
        translationJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    pdfName = runCatching { extractor.displayName(uri) }.getOrNull(),
                    phase = Phase.Extracting,
                    progressLabel = "Opening PDF…",
                    progress = null,
                    translatedChunks = emptyList(),
                    error = null,
                )
            }

            val current = StringBuilder()
            try {
                repository.translatePdf(uri).collect { event -> onEvent(event, current) }
                val chunkCount = _state.value.translatedChunks.size
                _state.update {
                    it.copy(
                        phase = Phase.Done,
                        progressLabel = "Done: translated $chunkCount chunk${if (chunkCount == 1) "" else "s"}",
                        progress = 1f,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.Cancelled, progressLabel = "Cancelled") }
                throw e
            } catch (e: Throwable) {
                _state.update {
                    it.copy(
                        phase = Phase.Failed,
                        progressLabel = "Translation stopped",
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun cancelTranslation() {
        translationJob?.cancel()
    }

    private fun onEvent(event: TranslationEvent, current: StringBuilder) {
        when (event) {
            is TranslationEvent.ExtractingPage -> _state.update {
                it.copy(
                    progressLabel = "Extracting text: page ${event.page} of ${event.pageCount}",
                    progress = event.page.toFloat() / event.pageCount,
                )
            }

            is TranslationEvent.ChunkStarted -> {
                current.clear()
                _state.update {
                    it.copy(
                        phase = Phase.Translating,
                        progressLabel = "Translating chunk ${event.chunkNumber} of ${event.chunkCount}...",
                        progress = (event.chunkNumber - 1).toFloat() / event.chunkCount,
                        translatedChunks = it.translatedChunks + "",
                    )
                }
            }

            is TranslationEvent.TextGenerated -> {
                current.append(event.text)
                val text = current.toString().trimStart()
                _state.update { it.copy(translatedChunks = it.translatedChunks.dropLast(1) + text) }
            }

            is TranslationEvent.ChunkFinished -> {
                val text = current.toString().trim()
                _state.update {
                    it.copy(
                        progress = event.chunkNumber.toFloat() / event.chunkCount,
                        translatedChunks = it.translatedChunks.dropLast(1) + text,
                    )
                }
            }
        }
    }

    private fun setModel(status: ModelStatus) = _state.update { it.copy(model = status) }

    override fun onCleared() {
        // viewModelScope is already cancelled; release the 1-3 GB engine once
        // any in-flight generation has wound down.
        appScope.launch { translator.close() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as HinglishApp
                TranslatorViewModel(
                    modelFiles = app.modelFileManager,
                    extractor = app.pdfTextExtractor,
                    translator = LlmTranslator(app),
                    appScope = app.appScope,
                )
            }
        }
    }
}
