@file:Suppress("DEPRECATION") // MediaPipe LLM Inference is superseded by LiteRT-LM; see README.

package com.example.hinglishpdf.data.llm

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutionException

/** Tunables for the on-device model. Match [maxTokens] to how your model was converted. */
data class LlmConfig(
    /**
     * Total token budget (prompt + response) per session. Must not exceed the
     * KV-cache size the model was exported with, e.g. 1280 for files named
     * `..._ekv1280.task`. 2048 suits Gemma 3 1B / Gemma 2B .task bundles.
     */
    val maxTokens: Int = 2048,
    val topK: Int = 40,
    /** Low temperature: we want a faithful translation, not creativity. */
    val temperature: Float = 0.3f,
    val randomSeed: Int = 0,
)

class ChunkTooLargeException(tokens: Int, limit: Int) : IllegalStateException(
    "A chunk needs $tokens prompt tokens but only $limit fit next to the answer. " +
        "Lower the words-per-chunk setting or use a model with a larger context.",
)

/**
 * Wraps MediaPipe's [LlmInference] engine.
 *
 * - [load] is slow (seconds) and memory-hungry (1-3 GB), so do it once and keep it.
 * - [translate] runs every chunk in a fresh [LlmInferenceSession], so earlier
 *   chunks never eat into the context window of later ones.
 * - Only one generation runs at a time; a [Mutex] serialises callers.
 */
class LlmTranslator(
    private val context: Context,
    private val config: LlmConfig = LlmConfig(),
) {
    private val mutex = Mutex()
    private var engine: LlmInference? = null

    /** Which backend actually loaded, for display. */
    var backend: LlmInference.Backend? = null
        private set

    /** Loads [modelFile], preferring the GPU and falling back to the CPU. */
    suspend fun load(modelFile: File) = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            backend = null

            var lastError: Throwable? = null
            for (candidate in listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)) {
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(config.maxTokens)
                        .setMaxTopK(config.topK)
                        .setPreferredBackend(candidate)
                        .build()
                    engine = LlmInference.createFromOptions(context, options)
                    backend = candidate
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load model on $candidate", e)
                    lastError = e
                }
            }
            throw IllegalStateException(
                "Could not load ${modelFile.name}: ${lastError?.message}",
                lastError,
            )
        }
    }

    /**
     * Translates one chunk, emitting the response piece by piece as the model
     * generates it. Collect sequentially; cancelling the collector stops generation.
     */
    fun translate(chunk: String): Flow<String> = channelFlow {
        mutex.withLock {
            val llm = engine ?: throw IllegalStateException("The model is not loaded yet.")
            val session = LlmInferenceSession.createFromOptions(
                llm,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(config.topK)
                    .setTemperature(config.temperature)
                    .setRandomSeed(config.randomSeed)
                    .build(),
            )
            val finished = CompletableDeferred<Unit>()
            var generating = false
            try {
                val prompt = HinglishPrompt.build(chunk)

                // Keep at least ~55% of the budget for the answer: Hinglish output
                // is usually as long as, or a bit longer than, the English input.
                val promptLimit = (config.maxTokens * 0.45).toInt()
                val promptTokens = session.sizeInTokens(prompt)
                if (promptTokens > promptLimit) throw ChunkTooLargeException(promptTokens, promptLimit)

                session.addQueryChunk(prompt)
                val future = session.generateResponseAsync { partial, done ->
                    // Called on MediaPipe's worker thread; trySend is thread-safe
                    // and never drops because the channel below is unbounded.
                    if (partial.isNotEmpty()) trySend(partial)
                    if (done) finished.complete(Unit)
                }
                future.addListener({
                    try {
                        future.get()
                        finished.complete(Unit)
                    } catch (e: ExecutionException) {
                        finished.completeExceptionally(e.cause ?: e)
                    } catch (e: Exception) {
                        finished.complete(Unit)
                    }
                }, MoreExecutors.directExecutor())
                generating = true

                finished.await()
            } catch (e: CancellationException) {
                // Stop native generation and wait for it to wind down before the
                // session is closed; closing mid-generation can crash natively.
                runCatching { session.cancelGenerateResponseAsync() }
                withContext(NonCancellable) {
                    withTimeoutOrNull(CANCEL_TIMEOUT_MS) { finished.join() }
                }
                throw e
            } finally {
                if (!generating || finished.isCompleted) {
                    session.close()
                } else {
                    // Native generation ignored the cancel request within the timeout.
                    Log.w(TAG, "Generation still running; leaving session open")
                }
            }
        }
    }
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.Default)

    /** Frees the engine (and its 1-3 GB) once any running generation has finished. */
    suspend fun close() = mutex.withLock {
        engine?.close()
        engine = null
        backend = null
    }

    private companion object {
        const val TAG = "LlmTranslator"
        const val CANCEL_TIMEOUT_MS = 10_000L
    }
}
