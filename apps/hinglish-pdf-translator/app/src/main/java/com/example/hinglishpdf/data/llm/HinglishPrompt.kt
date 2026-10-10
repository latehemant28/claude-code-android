package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit

/**
 * The translation prompt and the parser that maps Gemini's answer back onto
 * the chunk's headings, bullets and paragraphs.
 *
 * [SYSTEM_PROMPT] is embedded exactly as specified and set once as the
 * model's system instruction. Each request then carries only the chunk, as
 * plain Markdown, one block per paragraph (`## Heading`, `- bullet`,
 * `2. step`), so the answer can be matched back block by block.
 */
object HinglishPrompt {

    /** Embedded exactly as specified (line breaks and spacing included). */
    val SYSTEM_PROMPT = """
        You are a literary translator who converts English text into natural Hinglish (Hindi written in Roman script, mixed with common English words), the way an educated Indian would narrate a story aloud.

        RULES:
        1. Do NOT translate word by word. Read the full sentence, understand its meaning, then retell it naturally in Hinglish.
        2. Keep the original tense and narrative voice. If the source is in past tense, use tha/thi/the throughout. Never switch to present tense.
        3. Language mix:
           - Keep English only for words people commonly use in daily Hinglish (for example: philosopher, simple, happiness, city, life).
           - Use simple, natural Hindi for everything else. Avoid heavy or bookish Hindi.
           - Do not leave literary or abstract words in English if a natural Hindi word exists (for example, use "uljha hua" instead of "chaotic", "bekaar" instead of "absurd").
        4. Idioms: never translate idioms literally. Use the natural Hindi equivalent.
           Example: "heart of the matter" becomes "asli baat", and "anxious eyes" becomes "ghabrayi hui aankhen".
        5. Keep the literary tone and rhythm of the original. Use short, flowing sentences. Do not make it sound like casual chat or a text message.
        6. Do not add, remove or explain anything. Keep all the meaning and details of the original.
        7. Use Roman script only. No Devanagari.
        8. Output only the translation, with no notes, headings or commentary.

        STYLE EXAMPLE:
        English: "A young man who was dissatisfied with life went to visit this philosopher."
        Good Hinglish: "Zindagi se naakhush ek naujawan us philosopher se milne gaya."
        Bad Hinglish: "Life se dissatisfied ek young man is philosopher ko visit karne gaya."
    """.trimIndent()

    /** The user message for one chunk: the chunk itself (the instructions are in [SYSTEM_PROMPT]). */
    fun build(units: List<TranslationUnit>): String = chunkText(units)

    /** The chunk as Markdown: one block per paragraph, original markers kept. */
    fun chunkText(units: List<TranslationUnit>): String =
        units.joinToString("\n\n") { unit -> marker(unit) + unit.text.replace(NEWLINES, " ").trim() }

    private fun marker(unit: TranslationUnit): String = if (unit.continuation) "" else when (unit.kind) {
        BlockKind.HEADING -> "#".repeat(unit.level.coerceIn(1, 6)) + " "
        BlockKind.BULLET -> "  ".repeat(unit.level) + "- "
        BlockKind.NUMBERED -> "  ".repeat(unit.level) + unit.marker + " "
        BlockKind.QUOTE -> "> "
        BlockKind.PARAGRAPH, BlockKind.CODE -> ""
    }

    /**
     * Maps the answer back to units: result[i] is the translation of units[i].
     *
     * The answer is split into blocks at blank lines and at lines that start a
     * new heading, bullet or numbered item. When the block count matches the
     * chunk, block i is unit i. When it does not (the model merged or split
     * paragraphs), every entry is null and the caller re-translates the chunk
     * in smaller parts, so nothing is ever misaligned. A single-unit chunk
     * always gets the whole answer.
     */
    fun parse(raw: String, units: List<TranslationUnit>): List<String?> {
        val blocks = splitBlocks(stripFiller(raw))
        if (units.size == 1) {
            val text = blocks.joinToString(" ") { stripMarker(it, units[0]) }.trim()
            return listOf(text.takeIf { it.isNotEmpty() })
        }
        if (blocks.size != units.size) return List(units.size) { null }
        return blocks.mapIndexed { i, block -> stripMarker(block, units[i]).trim().takeIf { it.isNotEmpty() } }
    }

    fun containsDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    /**
     * Removes what the model sometimes wraps around the translation: a code
     * fence, echoed tags, a "Here is..." line, or a leading "Hinglish:" /
     * "Good Hinglish:" label (the prompt's style example uses one).
     */
    private fun stripFiller(raw: String): String {
        var text = raw.replace("\r\n", "\n").trim()
        text = text.replace(CODE_FENCE, "")
        text = text.replace(XML_TAG, "")
        text = text.trim().replace(PREAMBLE, "")
        text = text.replace(LABEL, "")
        return text.trim()
    }

    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        for (paragraph in text.split(BLANK_LINE)) {
            val current = StringBuilder()
            for (line in paragraph.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (current.isNotEmpty() && STARTS_BLOCK.containsMatchIn(trimmed)) {
                    blocks += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(trimmed.replace(LABEL, ""))
            }
            if (current.isNotEmpty()) blocks += current.toString()
        }
        return blocks
    }

    /** The app re-applies the original markers, so drop whatever the model echoed. */
    private fun stripMarker(block: String, unit: TranslationUnit): String {
        var t = block.trim()
        if (!unit.continuation) {
            t = when (unit.kind) {
                BlockKind.HEADING -> t.replace(HEADING_MARK, "")
                BlockKind.BULLET -> t.replace(BULLET_MARK, "")
                BlockKind.NUMBERED -> if (unit.marker.isNotEmpty() && t.startsWith(unit.marker + " ")) {
                    t.removePrefix(unit.marker).trimStart()
                } else {
                    t.replace(NUMBER_MARK, "")
                }
                BlockKind.QUOTE -> t.replace(QUOTE_MARK, "")
                BlockKind.PARAGRAPH, BlockKind.CODE -> t
            }
        }
        return unquote(t.removeSurrounding("**").trim(), unit)
    }

    /**
     * The style example puts its sentences in quotes, so Gemini sometimes
     * wraps a whole answer in them. Remove those quotes unless the original
     * text was itself a quotation.
     */
    private fun unquote(text: String, unit: TranslationUnit): String {
        if (text.length < 2 || text.first() !in OPEN_QUOTES || text.last() !in CLOSE_QUOTES) return text
        if (unit.text.trimStart().firstOrNull() in OPEN_QUOTES) return text
        return text.substring(1, text.length - 1).trim()
    }

    private val OPEN_QUOTES = setOf('"', '“')
    private val CLOSE_QUOTES = setOf('"', '”')
    private val NEWLINES = Regex("\\s*\\n\\s*")
    private val BLANK_LINE = Regex("\\n[ \\t]*\\n")
    private val STARTS_BLOCK = Regex("^(#{1,6}\\s|[-*•]\\s|\\(?\\d{1,3}[.)]\\s|>\\s?)")
    private val CODE_FENCE = Regex("^```[a-zA-Z]*\\s*$", RegexOption.MULTILINE)
    private val XML_TAG = Regex("</?(input|output|translation)>", RegexOption.IGNORE_CASE)
    private val PREAMBLE = Regex("^(here is|here's|sure)[^\\n]*:\\s*\\n", RegexOption.IGNORE_CASE)
    private val LABEL = Regex("^((good )?hinglish|translation)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val HEADING_MARK = Regex("^#{1,6}\\s*")
    private val BULLET_MARK = Regex("^[-*•●◦▪]\\s+")
    private val NUMBER_MARK = Regex("^\\(?\\d{1,3}[.)]\\s+")
    private val QUOTE_MARK = Regex("^>\\s*")
}
