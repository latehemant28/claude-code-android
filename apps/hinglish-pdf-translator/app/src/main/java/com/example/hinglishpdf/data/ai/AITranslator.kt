package com.example.hinglishpdf.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * The translation strategy: one implementation per AI provider
 * ([GeminiTranslator], [OpenAITranslator], [AnthropicTranslator],
 * [GroqTranslator], and the OpenAI-compatible ones in MoreProviders.kt),
 * each with its own endpoint, headers and JSON. The
 * pipeline only sees this interface, so it can also be tested without the
 * network.
 *
 * Every implementation sends the same system prompt (HinglishPrompt) as its
 * provider's system instruction, and the chunk of text as the user message.
 */
fun interface AITranslator {
    /** Streams the translation of [chunk]; failures are thrown as [TranslatorException]. */
    fun translate(chunk: String): Flow<String>
}

/** Provider failures, already sorted by what the app should do about them. */
sealed class TranslatorException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * Rate limit ([rateLimited]), server hiccup or network drop: wait and try
     * again (a rate-limited model can be swapped for another one first).
     */
    class Transient(
        message: String,
        val retryAfterMillis: Long? = null,
        cause: Throwable? = null,
        val rateLimited: Boolean = false,
    ) : TranslatorException(message, cause)

    /** This model is retired, renamed or not offered to this key; another model may work. */
    class ModelUnavailable(val model: String, cause: Throwable? = null) :
        TranslatorException("Model \"$model\" is not available", cause)

    /** The daily quota is used up: stop; the book resumes later from the same page. */
    class DailyQuota(
        message: String = "The daily quota of this API key is used up. Tap Resume later; " +
            "the book continues from the same page.",
        cause: Throwable? = null,
    ) : TranslatorException(message, cause)

    /** The provider refused this text (safety filter), or it is too long for one request. */
    class Blocked(message: String, cause: Throwable? = null) : TranslatorException(message, cause)

    /** Bad API key, no credit, unsupported region, no usable model: retrying cannot help. */
    class Fatal(message: String, cause: Throwable? = null) : TranslatorException(message, cause)
}

/**
 * Tries [models] in order and moves on to the next one, silently, when a
 * model fails in a way another model can fix:
 *
 *  - retired / not found, or no quota at all for this key ("limit: 0"):
 *    skipped for the rest of this app session;
 *  - daily quota used up: skipped for an hour;
 *  - rate-limited (429): skipped until the provider says it can be retried.
 *
 * The earliest model in the list that is usable is always preferred, so a
 * model comes back into use as soon as its limit resets. Problems no model
 * can fix (bad key, no credit, region, refused text, no internet) are passed
 * straight on. A model that fails after it started answering is not swapped
 * mid-text; the request is retried from scratch instead.
 */
class FallbackTranslator(
    private val models: List<String>,
    private val providerName: String = "AI",
    private val now: () -> Long = { System.currentTimeMillis() },
    private val clientFor: (String) -> AITranslator,
) : AITranslator {

    private enum class Reason { RETIRED, DAILY_QUOTA, RATE_LIMITED }
    private class Skip(val reason: Reason, val until: Long)

    private val clients = mutableMapOf<String, AITranslator>()
    private val skipped = mutableMapOf<String, Skip>()
    private var lastRateLimit: String? = null

    private val _activeModel = MutableStateFlow<String?>(null)

    /** The model that answered the last request (null until the first one). */
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    override fun translate(chunk: String): Flow<String> = flow {
        for (name in models) {
            if (!isUsable(name)) continue
            var started = false
            try {
                client(name).translate(chunk).collect { piece ->
                    started = true
                    emit(piece)
                }
                _activeModel.value = name
                return@flow
            } catch (e: TranslatorException) {
                // A refusal stays a refusal (the text is kept in English); any
                // other failure after text arrived is retried from scratch.
                if (started && e !is TranslatorException.Blocked) {
                    throw TranslatorException.Transient("$providerName stopped mid-answer", null, e)
                }
                when {
                    e is TranslatorException.ModelUnavailable -> skip(name, Reason.RETIRED, Long.MAX_VALUE)
                    e is TranslatorException.DailyQuota -> skip(name, Reason.DAILY_QUOTA, now() + DAILY_QUOTA_PAUSE_MS)
                    e is TranslatorException.Transient && e.rateLimited -> {
                        lastRateLimit = e.message
                        skip(name, Reason.RATE_LIMITED, now() + (e.retryAfterMillis ?: RATE_LIMIT_PAUSE_MS))
                    }
                    else -> throw e // bad key, refused text, network: another model won't help
                }
            }
        }
        throw noModelLeft()
    }

    @Synchronized
    private fun client(name: String): AITranslator = clients.getOrPut(name) { clientFor(name) }

    @Synchronized
    private fun isUsable(name: String): Boolean {
        val skip = skipped[name] ?: return true
        if (now() < skip.until) return false
        skipped.remove(name)
        return true
    }

    @Synchronized
    private fun skip(name: String, reason: Reason, until: Long) {
        skipped[name] = Skip(reason, until)
    }

    @Synchronized
    private fun noModelLeft(): TranslatorException {
        val waiting = skipped.values.filter { it.reason != Reason.RETIRED }
        return when {
            waiting.isEmpty() -> TranslatorException.Fatal(
                "None of these $providerName models are available to this API key: ${models.joinToString()}. " +
                    "They are retired, or this key has no quota for them (\"limit: 0\"). Check the key, " +
                    "or type a current model name in the Model field.",
            )
            waiting.all { it.reason == Reason.DAILY_QUOTA } -> TranslatorException.DailyQuota()
            else -> TranslatorException.Transient(
                "Every $providerName model is rate-limited" + (lastRateLimit?.let { "; last: $it" } ?: ""),
                retryAfterMillis = (waiting.minOf { it.until } - now()).coerceAtLeast(1_000),
                rateLimited = true,
            )
        }
    }

    private companion object {
        const val DAILY_QUOTA_PAUSE_MS = 60 * 60_000L
        const val RATE_LIMIT_PAUSE_MS = 60_000L
    }
}
