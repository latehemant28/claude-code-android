package com.example.hinglishpdf.data.llm

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ModelStatus {
    /** Looking for, copying, importing or loading a model. */
    data class Preparing(val message: String, val progress: Float? = null) : ModelStatus
    data object Missing : ModelStatus
    data class Ready(val fileName: String, val backend: String) : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * Finds and loads the model once for the whole app, so the screen and the
 * background service share the same 2 GB engine instead of loading it twice.
 */
class ModelController(
    private val files: ModelFileManager,
    private val translator: LlmTranslator,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Preparing("Looking for a model…"))
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    /** Re-scan storage (or import [importUri]) and load the result. */
    fun refresh(importUri: Uri? = null): Job = scope.launch { mutex.withLock { load(importUri) } }

    /** Loads the model if it is not loaded yet; throws if there is none. */
    suspend fun ensureLoaded() {
        mutex.withLock {
            if (_status.value !is ModelStatus.Ready) load(importUri = null)
        }
        when (val s = _status.value) {
            is ModelStatus.Ready -> Unit
            is ModelStatus.Failed -> throw IllegalStateException(s.message)
            else -> throw IllegalStateException("No AI model on this phone yet. Import one in the app first.")
        }
    }

    private suspend fun load(importUri: Uri?) {
        try {
            _status.value = ModelStatus.Preparing("Looking for a model…")
            val file = if (importUri != null) {
                files.importModel(importUri) { p -> _status.value = ModelStatus.Preparing("Importing model…", p) }
            } else {
                files.findModel { p -> _status.value = ModelStatus.Preparing("Unpacking built-in model…", p) }
            }
            if (file == null) {
                _status.value = ModelStatus.Missing
                return
            }
            _status.value = ModelStatus.Preparing("Loading ${file.name} into memory… (up to a minute)")
            translator.load(file)
            _status.value = ModelStatus.Ready(file.name, translator.backendName ?: "CPU")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // OutOfMemoryError included: a model too big for this phone.
            _status.value = ModelStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
