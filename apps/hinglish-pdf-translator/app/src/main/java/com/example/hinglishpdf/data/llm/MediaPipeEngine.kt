@file:Suppress("DEPRECATION") // MediaPipe LLM Inference is superseded by LiteRT-LM; kept for .task models.

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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * Runs Qwen 2.5 1.5B Instruct (`.task`) with MediaPipe's LLM Inference API on
 * the GPU delegate (OpenCL). Every micro-chunk gets a fresh
 * [LlmInferenceSession]: no chat history, so the context never fills up, and
 * the engine itself (weights on the GPU) is created once and reused.
 */
class MediaPipeEngine private constructor(
    private val llm: LlmInference,
    override val backendName: String,
    override val contextTokens: Int,
) : LlmEngine {

    override fun generate(prompt: String): Flow<String> = channelFlow {
        val session = LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(TOP_K)
                .setTemperature(TEMPERATURE)
                .setRandomSeed(0)
                .build(),
        )
        val finished = CompletableDeferred<Unit>()
        var generating = false
        try {
            // Leave room for the answer: Hinglish runs a little longer than
            // the English it comes from.
            val promptLimit = contextTokens - OUTPUT_RESERVE_TOKENS
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
                Log.w(TAG, "Generation ignored the cancel request; leaving session open")
            }
        }
    }
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.Default)

    override fun close() = llm.close()

    companion object {
        private const val TAG = "MediaPipeEngine"
        private const val TOP_K = 40

        /** Low temperature: faithful, consistent translation. */
        const val TEMPERATURE = 0.2f
        private const val CANCEL_TIMEOUT_MS = 10_000L

        /** A 150-word micro-chunk translates to ~250-400 tokens. */
        private const val OUTPUT_RESERVE_TOKENS = 500

        /**
         * Default token budget when the file name does not state one. Must not
         * exceed the KV cache the model was exported with (ekv1280 -> 1280).
         */
        private const val DEFAULT_CONTEXT = 1280

        /**
         * Loads [file] on the GPU, and only on the GPU: the backend is fixed to
         * [LlmInference.Backend.GPU] (MediaPipe's OpenCL LLM engine) and there is
         * no CPU path. A phone whose GPU cannot run the model gets a clear error.
         * Slow (seconds); call off the main thread.
         */
        fun create(context: Context, file: File): MediaPipeEngine {
            val maxTokens = contextFromFileName(file.name) ?: DEFAULT_CONTEXT
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(TOP_K)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()
            return try {
                MediaPipeEngine(LlmInference.createFromOptions(context, options), "GPU", maxTokens)
            } catch (e: Exception) {
                Log.w(TAG, "Could not load ${file.name} on the GPU", e)
                throw IllegalStateException(
                    "This phone's GPU could not run ${file.name} (${e.message}). " +
                        "The app runs the model on the GPU only, so it needs a phone with OpenCL GPU " +
                        "support (most recent Snapdragon, Dimensity and Exynos phones).",
                    e,
                )
            }
        }
    }
}
