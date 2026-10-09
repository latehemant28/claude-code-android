package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.HinglishPrompt
import com.example.hinglishpdf.data.llm.LlmTranslator
import kotlinx.coroutines.flow.fold

/**
 * Translates ONE page: the whole page goes to the model in a single prompt
 * (split only if it would not fit the model's context), and the answer is
 * mapped back onto the page's headings, bullets, numbering and paragraphs.
 */
class PageTranslator(private val translator: LlmTranslator) {

    /**
     * @return the translation of each block of [blocks] (null for blocks that
     *   are not translated, such as code or a lone page number).
     * @param onLiveText the model's raw output so far, for a live preview.
     */
    suspend fun translatePage(
        blocks: List<DocBlock>,
        language: TargetLanguage,
        onLiveText: (String) -> Unit = {},
    ): List<String?> {
        val result = arrayOfNulls<String>(blocks.size)
        val parts = BlockChunker.chunk(blocks, BlockChunker.wordsFor(translator.contextTokens))
        val pieces = mutableMapOf<Int, MutableList<String>>()
        val live = StringBuilder()

        for (units in parts) {
            val raw = translator.generate(HinglishPrompt.build(units, language)).fold(StringBuilder()) { acc, piece ->
                acc.append(piece)
                live.append(piece)
                onLiveText(live.toString())
                acc
            }
            live.append('\n')
            val results = HinglishPrompt.parse(raw.toString(), units).toMutableList()

            // Lines the model skipped or wrote in Devanagari: retry each on its own.
            if (units.size > 1) {
                units.forEachIndexed { i, unit ->
                    val r = results[i]
                    if (r == null || HinglishPrompt.containsDevanagari(r)) {
                        results[i] = translateSingle(unit, language) ?: r
                    }
                }
            }

            units.forEachIndexed { i, unit ->
                // Fall back to the original text rather than losing content.
                val text = results[i] ?: unit.text
                val blockPieces = pieces.getOrPut(unit.blockIndex) { mutableListOf() }
                if (!unit.continuation) blockPieces.clear()
                blockPieces += text
                result[unit.blockIndex] = blockPieces.joinToString(" ")
            }
        }
        return result.toList()
    }

    private suspend fun translateSingle(unit: TranslationUnit, language: TargetLanguage): String? {
        val units = listOf(unit)
        val raw = translator.generate(HinglishPrompt.build(units, language))
            .fold(StringBuilder()) { acc, piece -> acc.append(piece) }
        return HinglishPrompt.parse(raw.toString(), units).first()
    }
}
