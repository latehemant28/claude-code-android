package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit

/**
 * The prompt sent with every micro-chunk, and the parser that maps the
 * model's answer back onto the chunk's headings, bullets and paragraphs.
 *
 * The system prompt is embedded exactly as specified; the micro-chunk replaces
 * [CHUNK_PLACEHOLDER]. The chunk itself is written as plain Markdown, one block
 * per paragraph (`## Heading`, `- bullet`, `2. step`), which the prompt's
 * "strictly preserve the original document's formatting, paragraphs, and
 * bullet points" rule asks the model to keep.
 */
object HinglishPrompt {

    const val CHUNK_PLACEHOLDER = "[INSERT MICRO-CHUNK HERE]"

    /** Embedded exactly as specified (line breaks and spacing included). */
    const val SYSTEM_PROMPT =
        "You are an expert at translating English to natural, modern conversational Hinglish (Hindi written in Roman script). \n" +
            "\n" +
            "CORE RULES:\n" +
            "- Do NOT use highly formal or pure Hindi words (like 'samavesh', 'vyatirikta', 'prabhavshali').\n" +
            "- Keep it casual, modern, and easy to read, exactly like a WhatsApp chat between modern urban Indians.\n" +
            "- Keep English words for technical terms, concepts, verbs, or common objects (e.g., 'discussion', 'points', 'exception', 'philosophy', 'system', 'optimize').\n" +
            "- Strictly preserve the original document's formatting, paragraphs, and bullet points.\n" +
            "- Output ONLY the translated text without any extra conversational filler.\n" +
            "\n" +
            "MODERN HINGLISH EXAMPLES:\n" +
            "English: First, we need to plan the discussion points. You believe people can change.\n" +
            "Hinglish: Pehle, hume discussion ke points plan karne honge. Aapko lagta hai ki log badal sakte hain.\n" +
            "\n" +
            "English: The system architecture is quite complex, but we can optimize the database queries easily.\n" +
            "Hinglish: System architecture thoda complex hai, lekin hum database queries ko easily optimize kar sakte hain.\n" +
            "\n" +
            "English: Let's catch up later to finalize the project details and fix the bugs.\n" +
            "Hinglish: Baad mein catch up karte hain taaki project details finalize kar sakein aur bugs fix kar dein.\n" +
            "\n" +
            "Translate this text in the exact same casual style:\n" +
            CHUNK_PLACEHOLDER

    fun build(units: List<TranslationUnit>): String =
        SYSTEM_PROMPT.replace(CHUNK_PLACEHOLDER, chunkText(units))

    /** The micro-chunk as Markdown: one block per paragraph, original markers kept. */
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
     * paragraphs), every entry is null and the caller re-translates the units
     * one at a time, so nothing is ever misaligned. A single-unit chunk always
     * gets the whole answer.
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

    /** Removes a leading "Hinglish:" label (the few-shot examples use one) and a "Here is..." line. */
    private fun stripFiller(raw: String): String {
        var text = raw.replace("\r\n", "\n").trim()
        text = text.replace(PREAMBLE, "")
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
    private val PREAMBLE = Regex("^(here is|here's|sure)[^\\n]*:\\s*\\n", RegexOption.IGNORE_CASE)
    private val LABEL = Regex("^(hinglish|translation)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val HEADING_MARK = Regex("^#{1,6}\\s*")
    private val BULLET_MARK = Regex("^[-*•●◦▪]\\s+")
    private val NUMBER_MARK = Regex("^\\(?\\d{1,3}[.)]\\s+")
    private val QUOTE_MARK = Regex("^>\\s*")
}
