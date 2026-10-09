package com.example.hinglishpdf.data.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the loaded model and picks the runtime from the file type:
 * `.litertlm` -> LiteRT-LM, `.task`/`.bin` -> MediaPipe.
 *
 * Loading is slow (seconds) and memory-hungry (1-3 GB), so do it once and keep
 * it. A [Mutex] makes sure only one generation runs at a time and that the
 * model is never closed in the middle of one.
 */
class LlmTranslator(private val context: Context) {

    private val mutex = Mutex()
    private var engine: LlmEngine? = null

    val backendName: String? get() = engine?.backendName

    /** Token budget of the loaded model; decides how much text goes in each prompt. */
    val contextTokens: Int get() = engine?.contextTokens ?: 2048

    suspend fun load(modelFile: File) = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            engine = if (modelFile.name.endsWith(".litertlm", ignoreCase = true)) {
                LiteRtLmEngine.create(context, modelFile)
            } else {
                MediaPipeEngine.create(context, modelFile)
            }
        }
    }

    /** Streams the model's response to [prompt]. Collect sequentially. */
    fun generate(prompt: String): Flow<String> = flow {
        mutex.withLock {
            val loaded = engine ?: throw IllegalStateException("The model is not loaded yet.")
            loaded.generate(prompt).collect { emit(it) }
        }
    }

    /** Frees the model once any running generation has finished. */
    suspend fun close() = mutex.withLock {
        engine?.close()
        engine = null
    }
}
