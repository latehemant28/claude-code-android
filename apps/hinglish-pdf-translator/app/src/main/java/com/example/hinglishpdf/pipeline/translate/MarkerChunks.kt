package com.example.hinglishpdf.pipeline.translate

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderProblem
import com.example.hinglishpdf.pipeline.segment.Placeholders
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SentenceSegmenter

/**
 * One block of a translation request: a whole assembled paragraph, or, for a
 * paragraph longer than a request may be, a run of its sentences.
 *
 * @param paragraph index of the paragraph in the parsed document.
 * @param piece which run of sentences this is (0 for a whole paragraph), of [pieces].
 * @param newColumn the paragraph starts a new column: its block carries {COL}.
 */
data class ChunkUnit(
    val paragraph: Int,
    val piece: Int,
    val pieces: Int,
    val text: String,
    val role: ParagraphRole,
    val level: Int,
    val newColumn: Boolean,
    val words: Int,
) {
    /** The structure marker the model must copy back: {P}, {LI}, {H1}...{H6}, {Q}, {CAP}... */
    val marker: String get() = "{${MarkerPrompt.markerName(role, level)}}"
}

/**
 * Cuts the assembled paragraph stream into requests: 1 to
 * [PipelineConfig.chunkMaxParagraphs] whole paragraphs and at most
 * [PipelineConfig.chunkMaxWords] words, so every request ends at a sentence
 * boundary. Never by page, never mid-sentence: a paragraph longer than the
 * word limit goes alone, cut between sentences (one sentence longer than the
 * limit stays whole).
 */
object ParagraphChunker {

    /** The translatable paragraphs among [include] (all if null), in order, as request units. */
    fun units(paragraphs: List<ParsedParagraph>, config: PipelineConfig, include: Set<Int>? = null): List<ChunkUnit> {
        val out = mutableListOf<ChunkUnit>()
        var column = 0
        paragraphs.forEachIndexed { index, p ->
            if (!p.translatable) return@forEachIndexed
            val newColumn = p.column > 0 && p.column != column
            column = p.column
            if (include != null && index !in include) return@forEachIndexed
            val pieces = pieces(p, config.chunkMaxWords)
            pieces.forEachIndexed { k, text ->
                out += ChunkUnit(index, k, pieces.size, text, p.role, p.level, newColumn && k == 0, Segmenter.wordCount(text))
            }
        }
        return out
    }

    fun chunks(units: List<ChunkUnit>, config: PipelineConfig): List<List<ChunkUnit>> {
        val chunks = mutableListOf<List<ChunkUnit>>()
        val current = mutableListOf<ChunkUnit>()
        var words = 0
        for (unit in units) {
            val full = current.size >= config.chunkMaxParagraphs.coerceAtLeast(1) || words + unit.words > config.chunkMaxWords
            if (current.isNotEmpty() && (full || unit.pieces > 1)) {
                chunks += current.toList()
                current.clear()
                words = 0
            }
            current += unit
            words += unit.words
            if (unit.pieces > 1) { // a piece of a long paragraph goes alone
                chunks += current.toList()
                current.clear()
                words = 0
            }
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return chunks
    }

    /** The paragraph whole, or its sentences packed into runs of at most [maxWords] words. */
    private fun pieces(p: ParsedParagraph, maxWords: Int): List<String> {
        val text = p.text.trim()
        if (Segmenter.wordCount(text) <= maxWords || !p.role.splitsIntoSentences) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var words = 0
        for (sentence in SentenceSegmenter.split(text)) {
            val w = Segmenter.wordCount(sentence.text)
            if (current.isNotEmpty() && words + w > maxWords) {
                out += current.toString().trim()
                current.clear()
                words = 0
            }
            current.append(sentence.text).append(sentence.separator.ifEmpty { " " })
            words += w
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }
}

/**
 * The user message for one request and the reading of its answer. The style
 * prompt (the system instruction) is not touched: this message carries the
 * locked technical rules, then the blocks, one per line, each starting with
 * its structure marker ({P} paragraph, {LI} list item, {H1}-{H6} heading,
 * {Q} quotation, {CAP} caption, {FN} footnote, {TD} table cell, {TOC}
 * contents entry, {T} title, {ALT} image description, {X} other; {COL} before
 * the first block of a new column). The model copies the markers back, which
 * is how each answer is matched to its block.
 */
object MarkerPrompt {

    /** The locked technical layer (as specified), plus how the markers work. */
    val TECHNICAL_RULES = """
        <TECHNICAL_RULES>
        - Copy placeholders like {1}, {/1}, [[IMG_3]] exactly, in order. Never translate, merge, or delete them.
        - Keep numbers, dates, currency, URLs, emails accurate.
        - Use glossary terms exactly. They override the "no formal words" rule.
        - Keep personal names spelled the same way every time.
        - Do not add, skip, or shorten anything.
        - Leave code, formulas, ISBNs, and page numbers unchanged.
        - Return only the translated segments, one per input segment, with the same IDs.
        </TECHNICAL_RULES>
        <STRUCTURE_MARKERS>
        - Each input segment is one line that starts with a marker: {P}, {LI}, {H1} to {H6}, {Q}, {CAP}, {FN}, {TD}, {TOC}, {T}, {ALT} or {X}. {COL} before a marker starts a new column. The markers are the IDs.
        - Start each translated segment with the same marker(s), in the same order, one segment per line. Never merge, split, drop or add a segment.
        </STRUCTURE_MARKERS>
    """.trimIndent()

    fun markerName(role: ParagraphRole, level: Int): String = when (role) {
        ParagraphRole.PARAGRAPH -> "P"
        ParagraphRole.LIST_ITEM -> "LI"
        ParagraphRole.HEADING -> "H${level.coerceIn(1, 6)}"
        ParagraphRole.QUOTE -> "Q"
        ParagraphRole.CAPTION -> "CAP"
        ParagraphRole.FOOTNOTE -> "FN"
        ParagraphRole.TABLE_CELL -> "TD"
        ParagraphRole.TOC_LABEL -> "TOC"
        ParagraphRole.TITLE -> "T"
        ParagraphRole.ALT_TEXT -> "ALT"
        else -> "X"
    }

    fun build(units: List<ChunkUnit>): String =
        TECHNICAL_RULES + "\n\n" + units.joinToString("\n") { unit ->
            (if (unit.newColumn) COLUMN else "") + unit.marker + " " + unit.text.replace(NEWLINES, " ").trim()
        }

    /**
     * Maps an answer back to its units: result[i] is the translation of
     * units[i], placeholders as the model wrote them. The role markers found
     * must be exactly the ones sent, in order; otherwise every entry is null
     * and the caller asks again block by block. A one-block request takes
     * the whole answer, markers or not.
     */
    fun parse(raw: String, units: List<ChunkUnit>): List<String?> {
        val text = clean(raw)
        val found = ROLE_MARKER.findAll(text).toList()
        if (units.size == 1 && found.size != 1) {
            return listOf(stripMarkers(text).replace(NEWLINES, " ").trim().takeIf { it.isNotEmpty() })
        }
        if (found.map { it.value } != units.map { it.marker }) return List(units.size) { null }
        return found.mapIndexed { i, m ->
            val end = found.getOrNull(i + 1)?.range?.first ?: text.length
            stripMarkers(text.substring(m.range.last + 1, end)).replace(NEWLINES, " ").trim().takeIf { it.isNotEmpty() }
        }
    }

    /** Structure markers in [text] ({COL} included), in order: for checking a whole answer. */
    fun markers(text: String): List<String> = ANY_MARKER.findAll(text).map { it.value }.toList()

    /** [text] without structure markers (live text on screen, a one-block answer). */
    fun stripMarkers(text: String): String = text.replace(ANY_MARKER_WITH_SPACE, "")

    /**
     * A translation whose placeholders the rebuilder can use: every one of
     * the source's, once, properly nested (a different order is fine; word
     * order changes in translation). Otherwise the placeholders are taken
     * out and the source's appended at the end, paired ones empty, so the
     * text is kept and the structure is too, if not the exact place of a
     * bold word. Returns the text and whether it had to be repaired.
     */
    fun repair(source: String, translation: String): Pair<String, Boolean> {
        val problems = Placeholders.check(source, translation).filterNot { it is PlaceholderProblem.Reordered }
        if (problems.isEmpty()) return translation to false
        val tokens = Placeholders.sequence(source).joinToString("")
        val text = Placeholders.strip(translation).replace(SPACES, " ").trim()
        return (if (tokens.isEmpty()) text else "$text $tokens") to true
    }

    /** What models wrap around answers: code fences, reasoning, "Here is..." lines, echoed rules. */
    private fun clean(raw: String): String {
        var text = raw.replace("\r\n", "\n")
        text = text.replace(THINKING, "").replace(CODE_FENCE, "").replace(ECHOED_RULES, "")
        text = text.trim().replace(PREAMBLE, "")
        return text.trim()
    }

    private const val COLUMN = "{COL}"
    private const val ROLES = "P|LI|H[1-6]|Q|CAP|FN|TD|TOC|T|ALT|X"
    private val ROLE_MARKER = Regex("\\{(?:$ROLES)\\}")
    private val ANY_MARKER = Regex("\\{(?:COL|$ROLES)\\}")
    private val ANY_MARKER_WITH_SPACE = Regex("\\{(?:COL|$ROLES)\\}[ \\t]?")
    private val NEWLINES = Regex("\\s*\\n\\s*")
    private val SPACES = Regex("\\s+")
    private val THINKING = Regex("<think(ing)?>.*?</think(ing)?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CODE_FENCE = Regex("^```[a-zA-Z]*\\s*$", RegexOption.MULTILINE)
    private val ECHOED_RULES = Regex("<(TECHNICAL_RULES|STRUCTURE_MARKERS)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL)
    private val PREAMBLE = Regex("^(here is|here's|sure)[^\\n]*:\\s*\\n", RegexOption.IGNORE_CASE)
}
