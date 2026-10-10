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
 * Owns the one loaded model (Qwen 2.5 1.5B on the GPU via MediaPipe).
 *
 * Loading is slow (seconds) and puts ~1.6 GB of weights on the GPU, so it
 * happens once and the engine is reused for every micro-chunk. A [Mutex] makes
 * sure only one generation runs at a time and that the model is never closed
 * in the middle of one.
 */
class LlmTranslator(private val context: Context) {

    private val mutex = Mutex()
    private var engine: LlmEngine? = null

    val backendName: String? get() = engine?.backendName

    val contextTokens: Int get() = engine?.contextTokens ?: 1280

    suspend fun load(modelFile: File, allowCpu: Boolean) = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            engine = MediaPipeEngine.create(context, modelFile, allowCpu)
        }
    }

    /** For tests: use an already-created engine. */
    internal suspend fun useEngine(engine: LlmEngine) = mutex.withLock { this.engine = engine }

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
