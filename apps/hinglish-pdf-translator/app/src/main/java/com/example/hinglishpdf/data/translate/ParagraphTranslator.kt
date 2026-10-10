package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.PageEvent
import com.example.hinglishpdf.data.TranslationRepository
import com.example.hinglishpdf.data.ai.TranslatorException
import com.example.hinglishpdf.pipeline.translate.ChunkUnit
import com.example.hinglishpdf.pipeline.translate.MarkerPrompt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Translates one request of the pipeline: 1 to 3 whole paragraphs with
 * their structure markers, through the same provider, style prompt, pacing
 * and retries as before ([TranslationRepository.ask]).
 *
 * An answer whose markers do not match, a block left empty, or a block with
 * broken placeholders is asked again on its own; what still has broken
 * placeholders is repaired ([MarkerPrompt.repair]); text the provider
 * refuses is kept in the original. Emits [PageEvent.Token] and
 * [PageEvent.Waiting] while working, then [PageEvent.PageFinished] with one
 * translation (placeholder text, or null) per unit.
 */
class ParagraphTranslator(
    private val repository: TranslationRepository,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    fun translate(units: List<ChunkUnit>): Flow<PageEvent> = flow {
        val result = translateUnits(units, this, streamTokens = true)
        emit(PageEvent.PageFinished(result))
        delay(repository.pauseMillis()) // the provider's pace, after every request
    }.flowOn(workDispatcher)

    private suspend fun translateUnits(units: List<ChunkUnit>, events: FlowCollector<PageEvent>, streamTokens: Boolean): List<String?> {
        val raw = try {
            repository.ask(MarkerPrompt.build(units), events, streamTokens)
        } catch (e: TranslatorException.Blocked) {
            return if (units.size == 1) listOf(null) else units.flatMap { translateUnits(listOf(it), events, false) }
        }
        val parsed = MarkerPrompt.parse(raw, units)
        return units.mapIndexed { i, unit ->
            val answer = parsed[i]
            val usable = answer != null && !MarkerPrompt.repair(unit.text, answer).second
            when {
                usable -> answer
                units.size > 1 -> translateUnits(listOf(unit), events, streamTokens = false).single()
                answer == null -> null // nothing usable: the original stays
                else -> MarkerPrompt.repair(unit.text, answer).first
            }
        }
    }
}
