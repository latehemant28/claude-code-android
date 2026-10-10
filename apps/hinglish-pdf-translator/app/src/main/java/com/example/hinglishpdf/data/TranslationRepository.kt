package com.example.hinglishpdf.data

import com.example.hinglishpdf.data.ai.AITranslator
import com.example.hinglishpdf.data.ai.TranslatorException
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.llm.TranslationPrompt
import com.example.hinglishpdf.data.translate.BlockChunker
import com.example.hinglishpdf.data.translate.TranslationUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

/** Progress of one page, streamed as it is translated. */
sealed interface PageEvent {
    data class ChunkStarted(val chunk: Int, val chunkCount: Int) : PageEvent

    /** New text from the AI for the current chunk. */
    data class Token(val text: String) : PageEvent

    /**
     * Waiting before trying again: [seconds] left, counting down once a second.
     * [rateLimited]: the provider's per-minute limit ("Pausing for 60s to
     * refresh limit..."); otherwise a dropped connection or a busy server.
     */
    data class Waiting(val seconds: Int, val reason: String, val rateLimited: Boolean = false) : PageEvent

    /** The provider failed for good (no credit, bad key, daily quota...); another one with a saved key took over. */
    data class ProviderSwitched(val provider: String, val reason: String) : PageEvent

    /** A chunk is done; [translations] holds every block finished so far. */
    data class ChunkFinished(val chunk: Int, val chunkCount: Int, val translations: List<String?>) : PageEvent

    /** The whole page: one entry per block (null = kept as-is, e.g. code). */
    data class PageFinished(val translations: List<String?>) : PageEvent
}

/**
 * Spaces request starts out so a free tier's requests-per-minute limit is
 * rarely hit (the spacing is asked each time: it depends on the provider
 * selected); slows down further after every rate-limit error and speeds
 * back up slowly after successes.
 */
class RequestPacer(
    private val minIntervalMillis: () -> Long = { 4_000 },
    private val maxExtraMillis: Long = 30_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private var extraMillis = 0L
    private var lastStart: Long? = null

    suspend fun awaitTurn() = mutex.withLock {
        lastStart?.let { delay(max(0, it + minIntervalMillis() + extraMillis - now())) }
        lastStart = now()
    }

    fun onRateLimited() {
        extraMillis = min(maxExtraMillis, max(2_000, extraMillis * 2))
    }

    fun onSuccess() {
        extraMillis = extraMillis * 9 / 10
    }
}

/**
 * Smart fallback: when the provider in use fails for good (out of credit,
 * bad key, daily quota used up, or still failing after every retry), switch
 * to another provider the user has saved a key for, and carry on.
 */
fun interface ProviderFailover {
    /** Switches provider after [error]; returns the new provider's name, or null if none is left. */
    fun switchAfter(error: TranslatorException): String?
}

/**
 * Translates ONE page with the selected AI provider: cuts it into chunks of
 * whole blocks (a normal page is a single request), sends each to the
 * [AITranslator] strategy with the dynamic system prompt
 * ([TranslationPrompt.system], filled with the book's From / To languages),
 * streams the answer, and maps it back onto the page's headings, bullets,
 * numbering and paragraphs.
 *
 * Runs on [Dispatchers.Default]; collectors (the service, then the UI on the
 * main thread) only receive small events.
 */
class TranslationRepository(
    private val model: AITranslator,
    private val pacer: RequestPacer = RequestPacer(),
    /** Retries for network drops and server errors; rate limits are retried without limit. */
    private val maxRetries: Int = 8,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Fixed pause after every translated chunk; asked each time, because it
     * depends on the provider selected (Gemini's free tier: ~13 requests a minute).
     */
    private val chunkPauseMillis: () -> Long = { CHUNK_PAUSE_MS },
    /** Shortest wait after a rate-limit (429) answer before trying again. */
    private val rateLimitWaitMillis: () -> Long = { RATE_LIMIT_WAIT_MS },
    /** Takes over when a provider fails for good; null = the book stops instead. */
    private val failover: ProviderFailover? = null,
) {

    fun translatePage(
        blocks: List<DocBlock>,
        source: Language = Language.AUTO_DETECT,
        target: Language = Language.HINDI,
    ): Flow<PageEvent> = flow {
        val languages = Languages(source, target, TranslationPrompt.system(source, target))
        val result = arrayOfNulls<String>(blocks.size)
        val pieces = mutableMapOf<Int, MutableList<String>>()
        val chunks = BlockChunker.chunk(blocks)

        chunks.forEachIndexed { index, units ->
            emit(PageEvent.ChunkStarted(index + 1, chunks.size))
            val translated = translateChunk(units, languages, events = this, streamTokens = true)

            // Stitch: a block split across chunks is re-joined in order.
            units.forEachIndexed { i, unit ->
                val text = translated[i] ?: unit.text // never lose content
                val parts = pieces.getOrPut(unit.blockIndex) { mutableListOf() }
                if (!unit.continuation) parts.clear()
                parts += text
                result[unit.blockIndex] = parts.joinToString(" ")
            }
            emit(PageEvent.ChunkFinished(index + 1, chunks.size, result.toList()))

            // Pace the free tier: a hard pause after every chunk, including the
            // last one of a page, so the next request never comes too soon.
            delay(chunkPauseMillis())
        }
        emit(PageEvent.PageFinished(result.toList()))
    }.flowOn(workDispatcher)

    /**
     * One chunk. If the answer cannot be matched block for block, the chunk is
     * halved and each half translated on its own (so a merged paragraph never
     * shifts translations onto the wrong bullet). A block left empty is
     * retried by itself; text the AI refuses is kept in English.
     */
    private suspend fun translateChunk(
        units: List<TranslationUnit>,
        languages: Languages,
        events: FlowCollector<PageEvent>,
        streamTokens: Boolean,
    ): List<String?> {
        val raw = try {
            request(languages.systemPrompt, TranslationPrompt.build(units), events, streamTokens)
        } catch (e: TranslatorException.Blocked) {
            return if (units.size == 1) listOf(null) else translateInHalves(units, languages, events)
        }

        val results = TranslationPrompt.parse(raw, units, languages.target).toMutableList()
        if (units.size > 1 && results.all { it == null }) return translateInHalves(units, languages, events)

        units.forEachIndexed { i, unit ->
            if (units.size > 1 && results[i] == null) {
                results[i] = translateChunk(listOf(unit), languages, events, streamTokens = false).first()
            }
        }
        return results
    }

    private suspend fun translateInHalves(
        units: List<TranslationUnit>,
        languages: Languages,
        events: FlowCollector<PageEvent>,
    ): List<String?> {
        val half = units.size / 2
        return translateChunk(units.subList(0, half), languages, events, streamTokens = false) +
            translateChunk(units.subList(half, units.size), languages, events, streamTokens = false)
    }

    /** The book's language pair and the system prompt built from it once per page. */
    private class Languages(val source: Language, val target: Language, val systemPrompt: String)

    /** One AI request: paced, streamed, retried on rate limits and network errors. */
    private suspend fun request(
        systemPrompt: String,
        prompt: String,
        events: FlowCollector<PageEvent>,
        streamTokens: Boolean,
    ): String {
        var attempt = 0
        while (true) {
            pacer.awaitTurn()
            val out = StringBuilder()
            try {
                model.translate(systemPrompt, prompt).collect { piece ->
                    out.append(piece)
                    if (streamTokens) events.emit(PageEvent.Token(piece))
                }
                pacer.onSuccess()
                return out.toString()
            } catch (e: TranslatorException.Transient) {
                if (e.rateLimited) {
                    // 429: pause a full minute (longer if asked) to let the limit
                    // refresh, then try again, for as long as it takes. Never stop
                    // the book for it.
                    pacer.onRateLimited()
                    val wait = max(rateLimitWaitMillis(), e.retryAfterMillis ?: 0L)
                    countDown(wait, e.message ?: "Rate limit", rateLimited = true, events)
                    continue
                }
                attempt++
                if (attempt > maxRetries) {
                    switchOrThrow(
                        TranslatorException.Fatal(
                            "${e.message}. Gave up after $maxRetries retries; check the connection and tap Resume.", e,
                        ),
                        events,
                    )
                    attempt = 0
                    continue
                }
                countDown(e.retryAfterMillis ?: backoffMillis(attempt), e.message ?: "Retrying", rateLimited = false, events)
            } catch (e: TranslatorException.Fatal) {
                switchOrThrow(e, events)
                attempt = 0
            } catch (e: TranslatorException.DailyQuota) {
                switchOrThrow(e, events)
                attempt = 0
            }
        }
    }

    /** Hands over to another provider with a saved key, or rethrows [error] when there is none. */
    private suspend fun switchOrThrow(error: TranslatorException, events: FlowCollector<PageEvent>) {
        val next = failover?.switchAfter(error) ?: throw error
        events.emit(PageEvent.ProviderSwitched(next, error.message.orEmpty()))
    }

    /** Waits [millis], telling the screen how many seconds are left once a second. */
    private suspend fun countDown(millis: Long, reason: String, rateLimited: Boolean, events: FlowCollector<PageEvent>) {
        var left = millis
        while (left > 0) {
            events.emit(PageEvent.Waiting(((left + 999) / 1000).toInt(), reason, rateLimited))
            val step = min(left, 1_000L)
            delay(step)
            left -= step
        }
    }

    /** 15 s, 30 s, 60 s, 2 min, then 5 min at most (network drops and server errors). */
    private fun backoffMillis(attempt: Int): Long = min(300_000L, 15_000L shl (attempt - 1).coerceAtMost(5))

    private companion object {
        const val CHUNK_PAUSE_MS = 4_500L
        const val RATE_LIMIT_WAIT_MS = 60_000L
    }
}
