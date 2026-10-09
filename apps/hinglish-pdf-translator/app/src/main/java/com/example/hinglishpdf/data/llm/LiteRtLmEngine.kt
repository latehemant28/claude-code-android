package com.example.hinglishpdf.data.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Runs `.litertlm` models with Google's LiteRT-LM runtime, the successor of
 * MediaPipe's LLM Inference API. This is the engine for Llama 3.2 3B Instruct.
 */
class LiteRtLmEngine private constructor(
    private val engine: Engine,
    override val backendName: String,
    override val contextTokens: Int,
) : LlmEngine {

    override fun generate(prompt: String): Flow<String> = flow {
        // A new conversation per prompt: no history, so the context stays empty.
        val conversation = engine.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(TOP_K, TOP_P, TEMPERATURE, SEED)),
        )
        try {
            val received = StringBuilder()
            conversation.sendMessageAsync(prompt).collect { message ->
                val text = message.text()
                // Messages are streamed as deltas; tolerate a runtime that sends
                // the cumulative text instead.
                val delta = if (received.isNotEmpty() && text.length > received.length &&
                    text.startsWith(received)
                ) {
                    text.substring(received.length)
                } else {
                    text
                }
                received.append(delta)
                if (delta.isNotEmpty()) emit(delta)
            }
        } catch (e: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } finally {
            conversation.close()
        }
    }.flowOn(Dispatchers.Default)

    override fun close() = engine.close()

    companion object {
        private const val TAG = "LiteRtLmEngine"
        private const val TOP_K = 40
        private const val TOP_P = 0.95
        private const val TEMPERATURE = 0.3 // faithful translation, not creativity
        private const val SEED = 0

        /** Llama 3.2 3B LiteRT-LM builds ship a 4096-token KV cache. */
        private const val DEFAULT_CONTEXT = 4096

        /** Loads [file], preferring the GPU and falling back to the CPU. Slow; call off the main thread. */
        fun create(context: Context, file: File): LiteRtLmEngine {
            var lastError: Throwable? = null
            for ((name, backend) in listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())) {
                try {
                    val engine = Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = backend,
                            cacheDir = context.cacheDir.absolutePath,
                        ),
                    )
                    engine.initialize()
                    val ctx = contextFromFileName(file.name) ?: DEFAULT_CONTEXT
                    return LiteRtLmEngine(engine, name, ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load ${file.name} on $name", e)
                    lastError = e
                }
            }
            throw IllegalStateException("Could not load ${file.name}: ${lastError?.message}", lastError)
        }

        private fun Message.text(): String =
            contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }
}
