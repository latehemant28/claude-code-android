package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit

/**
 * The translation prompt and the parser that maps the AI's answer back onto
 * the chunk's headings, bullets and paragraphs.
 *
 * [system] fills the specified prompt with the book's From / To languages;
 * it is sent as each provider's system prompt. Each request then carries
 * only the chunk, as plain Markdown, one block per paragraph (`## Heading`,
 * `- bullet`, `2. step`), so the answer can be matched back block by block.
 */
object TranslationPrompt {

    /**
     * Embedded exactly as specified, line for line (including the trailing
     * spaces after "translator." and "archaic Spanish)."), with the
     * {sourceLanguage} and {targetLanguage} placeholders.
     */
    val TEMPLATE: String = listOf(
        "You are a master literary and context-aware translator. ",
        "Translate the following text from {sourceLanguage} to {targetLanguage}.",
        "",
        "CRITICAL TONE & STYLE RULES:",
        "1. CONTEXT FIRST, TRANSLATE SECOND: Before translating, internally analyze the entire paragraph to grasp " +
            "the underlying meaning, philosophy, and context. Ensure the core essence of the message is preserved.",
        "2. MODERN & CONVERSATIONAL: Do NOT use rigid, archaic, or overly formal textbook vocabulary " +
            "(e.g., avoid pure formal Sanskritized Hindi; avoid archaic Spanish). ",
        "3. NATURAL FLOW: Translate the text using contemporary, everyday language—exactly how educated native " +
            "speakers of {targetLanguage} converse in modern times.",
        "4. EMOTION SENSE-FOR-SENSE: Translate sense-for-sense, not word-for-word. Adapt idioms and humor so they " +
            "make sense in {targetLanguage} without losing the original meaning.",
        "",
        "FORMATTING RULES:",
        "1. STRICT RETENTION: Maintain all original line breaks, bullet points, paragraphs, and markdown.",
        "2. DO NOT TRANSLATE TAGS: Never translate character names, speaker labels, or structural tags. " +
            "Leave them in their original script.",
        "3. COMPLETENESS: Do not skip or summarize any part of the text. Return only the translated text, " +
            "with zero conversational filler from your side.",
    ).joinToString("\n")

    /** The system prompt for a book translated from [source] (possibly Auto-Detect) into [target]. */
    fun system(source: Language, target: Language): String = TEMPLATE
        .replace("{sourceLanguage}", source.promptName)
        .replace("{targetLanguage}", target.promptName)

    /** The user message for one chunk: the chunk itself (the instructions are in [system]). */
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
    fun parse(raw: String, units: List<TranslationUnit>, target: Language? = null): List<String?> {
        val label = labelFor(target)
        val blocks = splitBlocks(stripFiller(raw, label), label)
        if (units.size == 1) {
            val text = blocks.joinToString(" ") { stripMarker(it, units[0]) }.trim()
            return listOf(text.takeIf { it.isNotEmpty() })
        }
        if (blocks.size != units.size) return List(units.size) { null }
        return blocks.mapIndexed { i, block -> stripMarker(block, units[i]).trim().takeIf { it.isNotEmpty() } }
    }

    /**
     * Removes what the model sometimes wraps around the translation: a code
     * fence, echoed tags, a "Here is..." line, or a leading "Hindi:" /
     * "Translation:" style label. Speaker labels such as "YOUTH:" are part of
     * the text and are kept.
     */
    private fun stripFiller(raw: String, label: Regex): String {
        var text = raw.replace("\r\n", "\n").trim()
        text = text.replace(CODE_FENCE, "")
        text = text.replace(XML_TAG, "")
        text = text.trim().replace(PREAMBLE, "")
        text = text.replace(label, "")
        return text.trim()
    }

    private fun splitBlocks(text: String, label: Regex): List<String> {
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
                current.append(trimmed.replace(label, ""))
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
     * Example sentences in prompts are often quoted, so models sometimes
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

    /** "Hindi:", "Modern Spanish:", "Español:", "Translation:" at the start of an answer. */
    private fun labelFor(target: Language?): Regex {
        val names = listOfNotNull("hindi", "hinglish", target?.englishName, target?.nativeName)
            .joinToString("|") { Regex.escape(it) }
        return Regex("^((modern |good |natural )?($names)( translation)?|translation)\\s*:\\s*", RegexOption.IGNORE_CASE)
    }

    private val HEADING_MARK = Regex("^#{1,6}\\s*")
    private val BULLET_MARK = Regex("^[-*•●◦▪]\\s+")
    private val NUMBER_MARK = Regex("^\\(?\\d{1,3}[.)]\\s+")
    private val QUOTE_MARK = Regex("^>\\s*")
}
