package com.example.hinglishpdf.data

import com.example.hinglishpdf.BuildConfig
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.HinglishPrompt
import com.example.hinglishpdf.data.translate.BlockChunker
import com.example.hinglishpdf.data.translate.TranslationUnit
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.PromptBlockedException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.RequestTimeoutException
import com.google.ai.client.generativeai.type.ResponseStoppedException
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.ServerException
import com.google.ai.client.generativeai.type.UnsupportedUserLocationException
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Models to try, in order of preference. If one is retired (404), out of
 * quota or rate-limited, the next one is used automatically.
 *
 * The first two are the requested ones. Google shut both down on
 * 29 Sept 2025, so the current free Flash models follow them; without those
 * the list could never translate anything.
 */
val GEMINI_MODELS = listOf(
    "gemini-1.5-flash",
    "gemini-1.5-pro",
    "gemini-3.5-flash-lite",
    "gemini-3.8-flash",
)

/** Progress of one page, streamed as it is translated. */
sealed interface PageEvent {
    data class ChunkStarted(val chunk: Int, val chunkCount: Int) : PageEvent

    /** New text from Gemini for the current chunk. */
    data class Token(val text: String) : PageEvent

    /** Gemini asked us to slow down (or the network dropped); retrying after [seconds]. */
    data class Waiting(val seconds: Int, val reason: String) : PageEvent

    /** A chunk is done; [translations] holds every block finished so far. */
    data class ChunkFinished(val chunk: Int, val chunkCount: Int, val translations: List<String?>) : PageEvent

    /** The whole page: one entry per block (null = kept as-is, e.g. code). */
    data class PageFinished(val translations: List<String?>) : PageEvent
}

/** Gemini failures, already sorted by what the app should do about them. */
sealed class GeminiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * Rate limit ([rateLimited]), server hiccup or network drop: wait and try
     * again (a rate-limited model can be swapped for another one first).
     */
    class Transient(
        message: String,
        val retryAfterMillis: Long? = null,
        cause: Throwable? = null,
        val rateLimited: Boolean = false,
    ) : GeminiException(message, cause)

    /** This model is retired, renamed or not offered to this key; another model may work. */
    class ModelUnavailable(val model: String, cause: Throwable? = null) :
        GeminiException("Gemini model \"$model\" is not available", cause)

    /** The free daily quota is used up: stop; the book resumes later from the same page. */
    class DailyQuota(cause: Throwable? = null) : GeminiException(
        "The free Gemini daily quota is used up. Tap Resume tomorrow; the book continues from the same page.",
        cause,
    )

    /** Gemini refused this text (safety filter) or cut its answer short. */
    class Blocked(message: String, cause: Throwable? = null) : GeminiException(message, cause)

    /** Bad API key, unsupported region, no usable model: retrying cannot help. */
    class Fatal(message: String, cause: Throwable? = null) : GeminiException(message, cause)
}

/** The model behind an interface, so the pipeline can be tested without the network. */
fun interface HinglishModel {
    /** Streams the translation of [chunk]; failures are thrown as [GeminiException]. */
    fun translate(chunk: String): Flow<String>
}

/**
 * Google Gemini via the official Android SDK (`com.google.ai.client.generativeai`).
 * The API key defaults to BuildConfig's, which Gradle fills from local.properties;
 * the app passes a key pasted on the phone when the build has none.
 */
class GeminiHinglishModel(
    apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val modelName: String = GEMINI_MODELS.first(),
) : HinglishModel {

    private val model = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig { temperature = 0.2f },
        // Books contain violence, romance, medicine... Don't let the default
        // filters refuse ordinary literature mid-book.
        safetySettings = listOf(
            HarmCategory.HARASSMENT,
            HarmCategory.HATE_SPEECH,
            HarmCategory.SEXUALLY_EXPLICIT,
            HarmCategory.DANGEROUS_CONTENT,
        ).map { SafetySetting(it, BlockThreshold.NONE) },
        requestOptions = RequestOptions(timeout = REQUEST_TIMEOUT_MS),
        // The translation instructions, verbatim; each request then carries
        // only the chunk of text to translate.
        systemInstruction = content { text(HinglishPrompt.SYSTEM_PROMPT) },
    )

    override fun translate(chunk: String): Flow<String> =
        model.generateContentStream(chunk)
            .map { it.text.orEmpty() }
            .catch { throw classify(it, modelName) }

    internal companion object {
        private const val REQUEST_TIMEOUT_MS = 120_000L
        private val PER_DAY = Regex("per[_ ]?day|PerDay|daily", RegexOption.IGNORE_CASE)
        private val NOT_FOUND = Regex(
            "\\b404\\b|NOT_FOUND|is not found|not supported for generateContent|deprecated|no longer available",
            RegexOption.IGNORE_CASE,
        )
        private val RATE_LIMIT = Regex("\\b429\\b|RESOURCE_EXHAUSTED|quota", RegexOption.IGNORE_CASE)
        private val RETRY_IN = Regex("retry in ([0-9.]+)s|\"retryDelay\":\\s*\"([0-9.]+)s\"", RegexOption.IGNORE_CASE)

        /** Sorts an SDK error into what the app should do about it. */
        fun classify(e: Throwable, modelName: String): Throwable = when (e) {
            is CancellationException, is GeminiException -> e
            is QuotaExceededException ->
                if (PER_DAY.containsMatchIn(e.message.orEmpty())) {
                    GeminiException.DailyQuota(e)
                } else {
                    GeminiException.Transient("Gemini rate limit reached", retryAfter(e.message), e, rateLimited = true)
                }
            is InvalidAPIKeyException -> GeminiException.Fatal(
                "Gemini rejected the API key. Check the key (local.properties, or the one saved in the app).", e,
            )
            is UnsupportedUserLocationException ->
                GeminiException.Fatal("The Gemini API is not available in your country or region.", e)
            is PromptBlockedException, is ResponseStoppedException ->
                GeminiException.Blocked(e.message ?: "Gemini declined this text", e)
            is RequestTimeoutException -> GeminiException.Transient("Gemini took too long to answer", null, e)
            is ServerException -> {
                val message = e.message.orEmpty()
                when {
                    NOT_FOUND.containsMatchIn(message) -> GeminiException.ModelUnavailable(modelName, e)
                    PER_DAY.containsMatchIn(message) -> GeminiException.DailyQuota(e)
                    RATE_LIMIT.containsMatchIn(message) ->
                        GeminiException.Transient("Gemini rate limit reached", retryAfter(message), e, rateLimited = true)
                    else -> GeminiException.Transient("Gemini is busy", retryAfter(message), e)
                }
            }
            // No connection, DNS failure, dropped socket... often wrapped by the SDK.
            else -> if (e is IOException || e.cause is IOException) {
                GeminiException.Transient("No internet connection", null, e)
            } else {
                e
            }
        }

        /** Gemini says how long to wait, e.g. "Please retry in 23.4s". */
        fun retryAfter(message: String?): Long? {
            val m = RETRY_IN.find(message.orEmpty()) ?: return null
            val seconds = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDoubleOrNull() ?: return null
            return (seconds * 1000).toLong() + 1_000 // a little margin
        }
    }
}

/**
 * Tries [models] in order and moves on to the next one, silently, when a
 * model fails in a way another model can fix:
 *
 *  - retired / not found (404): skipped for the rest of this app session;
 *  - daily quota used up: skipped for an hour;
 *  - rate-limited (429): skipped until Gemini says it can be retried.
 *
 * The earliest model in the list that is usable is always preferred, so a
 * model comes back into use as soon as its limit resets. Problems no model
 * can fix (bad key, unsupported region, refused text, no internet) are
 * passed straight on. A model that fails after it started answering is not
 * swapped mid-text; the request is retried from scratch instead.
 */
class FallbackGeminiModel(
    private val models: List<String> = GEMINI_MODELS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val clientFor: (String) -> HinglishModel,
) : HinglishModel {

    private enum class Reason { RETIRED, DAILY_QUOTA, RATE_LIMITED }
    private class Skip(val reason: Reason, val until: Long)

    private val clients = mutableMapOf<String, HinglishModel>()
    private val skipped = mutableMapOf<String, Skip>()

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
            } catch (e: GeminiException) {
                if (started) {
                    throw GeminiException.Transient("Gemini stopped mid-answer", null, e)
                }
                when {
                    e is GeminiException.ModelUnavailable -> skip(name, Reason.RETIRED, Long.MAX_VALUE)
                    e is GeminiException.DailyQuota -> skip(name, Reason.DAILY_QUOTA, now() + DAILY_QUOTA_PAUSE_MS)
                    e is GeminiException.Transient && e.rateLimited ->
                        skip(name, Reason.RATE_LIMITED, now() + (e.retryAfterMillis ?: RATE_LIMIT_PAUSE_MS))
                    else -> throw e // bad key, refused text, network: another model won't help
                }
            }
        }
        throw noModelLeft()
    }

    @Synchronized
    private fun client(name: String): HinglishModel = clients.getOrPut(name) { clientFor(name) }

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
    private fun noModelLeft(): GeminiException {
        val waiting = skipped.values.filter { it.reason != Reason.RETIRED }
        return when {
            waiting.isEmpty() -> GeminiException.Fatal(
                "None of the Gemini models are available to this API key: ${models.joinToString()}. " +
                    "Add a current model to GEMINI_MODELS in TranslationRepository.kt.",
            )
            waiting.all { it.reason == Reason.DAILY_QUOTA } -> GeminiException.DailyQuota()
            else -> GeminiException.Transient(
                "Every Gemini model is rate-limited",
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

/**
 * Spaces requests out so the free tier's requests-per-minute limit is rarely
 * hit; slows down after every rate-limit error and speeds back up slowly
 * after successes.
 */
class RequestPacer(
    private val minIntervalMillis: Long = 4_000, // 15 requests a minute at most
    private val maxIntervalMillis: Long = 30_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var intervalMillis = minIntervalMillis
    private var lastStart: Long? = null

    suspend fun awaitTurn() = mutex.withLock {
        lastStart?.let { delay(max(0, it + intervalMillis - now())) }
        lastStart = now()
    }

    fun onRateLimited() {
        intervalMillis = min(maxIntervalMillis, intervalMillis * 2)
    }

    fun onSuccess() {
        intervalMillis = max(minIntervalMillis, intervalMillis * 9 / 10)
    }
}

/**
 * Translates ONE page with Gemini: cuts it into chunks of whole blocks (a
 * normal page is a single request), sends each to Gemini (whose system
 * instruction is the literary-translator prompt),
 * streams the answer, and maps it back onto the page's headings, bullets,
 * numbering and paragraphs.
 *
 * Runs on [Dispatchers.Default]; collectors (the service, then the UI on the
 * main thread) only receive small events.
 */
class TranslationRepository(
    private val model: HinglishModel,
    private val pacer: RequestPacer = RequestPacer(),
    private val maxRetries: Int = 8,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    fun translatePage(blocks: List<DocBlock>): Flow<PageEvent> = flow {
        val result = arrayOfNulls<String>(blocks.size)
        val pieces = mutableMapOf<Int, MutableList<String>>()
        val chunks = BlockChunker.chunk(blocks)

        chunks.forEachIndexed { index, units ->
            emit(PageEvent.ChunkStarted(index + 1, chunks.size))
            val translated = translateChunk(units, events = this, streamTokens = true)

            // Stitch: a block split across chunks is re-joined in order.
            units.forEachIndexed { i, unit ->
                val text = translated[i] ?: unit.text // never lose content
                val parts = pieces.getOrPut(unit.blockIndex) { mutableListOf() }
                if (!unit.continuation) parts.clear()
                parts += text
                result[unit.blockIndex] = parts.joinToString(" ")
            }
            emit(PageEvent.ChunkFinished(index + 1, chunks.size, result.toList()))
        }
        emit(PageEvent.PageFinished(result.toList()))
    }.flowOn(workDispatcher)

    /**
     * One chunk. If the answer cannot be matched block for block, the chunk is
     * halved and each half translated on its own (so a merged paragraph never
     * shifts translations onto the wrong bullet). A block left empty is
     * retried by itself; text Gemini refuses is kept in English.
     */
    private suspend fun translateChunk(
        units: List<TranslationUnit>,
        events: FlowCollector<PageEvent>,
        streamTokens: Boolean,
    ): List<String?> {
        val raw = try {
            request(HinglishPrompt.build(units), events, streamTokens)
        } catch (e: GeminiException.Blocked) {
            return if (units.size == 1) listOf(null) else translateInHalves(units, events)
        }

        val results = HinglishPrompt.parse(raw, units).toMutableList()
        if (units.size > 1 && results.all { it == null }) return translateInHalves(units, events)

        units.forEachIndexed { i, unit ->
            if (units.size > 1 && results[i] == null) {
                results[i] = translateChunk(listOf(unit), events, streamTokens = false).first()
            }
        }
        return results
    }

    private suspend fun translateInHalves(units: List<TranslationUnit>, events: FlowCollector<PageEvent>): List<String?> {
        val half = units.size / 2
        return translateChunk(units.subList(0, half), events, streamTokens = false) +
            translateChunk(units.subList(half, units.size), events, streamTokens = false)
    }

    /** One Gemini request: paced, streamed, retried on rate limits and network errors. */
    private suspend fun request(prompt: String, events: FlowCollector<PageEvent>, streamTokens: Boolean): String {
        var attempt = 0
        while (true) {
            pacer.awaitTurn()
            val out = StringBuilder()
            try {
                model.translate(prompt).collect { piece ->
                    out.append(piece)
                    if (streamTokens) events.emit(PageEvent.Token(piece))
                }
                pacer.onSuccess()
                return out.toString()
            } catch (e: GeminiException.Transient) {
                attempt++
                if (attempt > maxRetries) {
                    throw GeminiException.Fatal(
                        "${e.message}. Gave up after $maxRetries retries; check the connection and tap Resume.", e,
                    )
                }
                pacer.onRateLimited()
                val wait = e.retryAfterMillis ?: backoffMillis(attempt)
                events.emit(PageEvent.Waiting(((wait + 999) / 1000).toInt(), e.message ?: "Retrying"))
                delay(wait)
            }
        }
    }

    /** 15 s, 30 s, 60 s, 2 min, then 5 min at most. */
    private fun backoffMillis(attempt: Int): Long = min(300_000L, 15_000L shl (attempt - 1).coerceAtMost(5))
}
