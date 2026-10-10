package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit

/**
 * The prompt sent with every chunk, and the parser that maps Gemini's answer
 * back onto the chunk's headings, bullets and paragraphs.
 *
 * The prompt is embedded exactly as specified; the chunk replaces
 * [CHUNK_PLACEHOLDER] inside the <input> tags. The chunk itself is written as
 * plain Markdown, one block per paragraph (`## Heading`, `- bullet`,
 * `2. step`), so the answer can be matched back block by block.
 */
object HinglishPrompt {

    const val CHUNK_PLACEHOLDER = "[INSERT TEXT CHUNK HERE]"

    /** Embedded exactly as specified (line breaks and spacing included). */
    const val SYSTEM_PROMPT =
        "You are a highly accurate English to Hinglish translator. You only translate the text inside the <input> tags. Do not repeat the examples. \n" +
            "\n" +
            "<rules>\n" +
            "1. Translate to natural, modern Hinglish (Hindi in Roman script).\n" +
            "2. Keep it casual like a WhatsApp chat between modern urban Indians.\n" +
            "3. Keep English words for technical terms, concepts, verbs, or common objects (e.g., 'discussion', 'trauma', 'system').\n" +
            "4. Never use pure Hindi/Devanagari words like 'samavesh' or 'vyatirikta'.\n" +
            "5. Output ONLY the final translation. Do not add any extra text or repeat the prompt.\n" +
            "</rules>\n" +
            "\n" +
            "<examples>\n" +
            "English: Let's catch up later to finalize the project details.\n" +
            "Hinglish: Baad mein catch up karte hain taaki project details finalize kar sakein.\n" +
            "</examples>\n" +
            "\n" +
            "Translate the following text:\n" +
            "<input>\n" +
            CHUNK_PLACEHOLDER + "\n" +
            "</input>"

    fun build(units: List<TranslationUnit>): String =
        SYSTEM_PROMPT.replace(CHUNK_PLACEHOLDER, chunkText(units))

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
     * fence, echoed <input>/<output> tags, a "Here is..." line, or a leading
     * "Hinglish:" label (the prompt's example uses one).
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
        return t.removeSurrounding("**").trim()
    }

    private val NEWLINES = Regex("\\s*\\n\\s*")
    private val BLANK_LINE = Regex("\\n[ \\t]*\\n")
    private val STARTS_BLOCK = Regex("^(#{1,6}\\s|[-*•]\\s|\\(?\\d{1,3}[.)]\\s|>\\s?)")
    private val CODE_FENCE = Regex("^```[a-zA-Z]*\\s*$", RegexOption.MULTILINE)
    private val XML_TAG = Regex("</?(input|output|translation)>", RegexOption.IGNORE_CASE)
    private val PREAMBLE = Regex("^(here is|here's|sure)[^\\n]*:\\s*\\n", RegexOption.IGNORE_CASE)
    private val LABEL = Regex("^(hinglish|translation)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val HEADING_MARK = Regex("^#{1,6}\\s*")
    private val BULLET_MARK = Regex("^[-*•●◦▪]\\s+")
    private val NUMBER_MARK = Regex("^\\(?\\d{1,3}[.)]\\s+")
    private val QUOTE_MARK = Regex("^>\\s*")
}
