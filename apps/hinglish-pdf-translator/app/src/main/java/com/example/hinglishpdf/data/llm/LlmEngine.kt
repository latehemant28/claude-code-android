package com.example.hinglishpdf.data.llm

import kotlinx.coroutines.flow.Flow

/** A loaded on-device model that turns one prompt into a streamed response. */
interface LlmEngine {
    /** "GPU" or "CPU", for display. */
    val backendName: String

    /** Prompt + response token budget of one generation. */
    val contextTokens: Int

    /**
     * Generates a response in a fresh context (no memory of earlier prompts),
     * emitting text pieces as they are produced. Cancelling the collector stops
     * generation. Callers must not run two generations at once.
     */
    fun generate(prompt: String): Flow<String>

    fun close()
}

class ChunkTooLargeException(tokens: Int, limit: Int) : IllegalStateException(
    "A chunk needs $tokens prompt tokens but only $limit fit next to the answer. " +
        "Use a model with a larger context.",
)

/**
 * KV-cache size encoded in converted model names, e.g. `..._ekv1280.task` -> 1280.
 * Returns null when the name does not say.
 */
internal fun contextFromFileName(name: String): Int? =
    Regex("ekv(\\d{3,6})", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
