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
     * The translation prompt, embedded exactly as specified (including the
     * trailing space after "script."), with the {Source_Language},
     * {Target_Language} and {Target_Script} placeholders.
     */
    val TEMPLATE: String = listOf(
        "You are an expert, context-aware translator translating {Source_Language} books into natural, " +
            "conversational {Target_Language} using the {Target_Script} script. ",
        "",
        "<rules>",
        "1. Script & Tone: Use the {Target_Script} script. Keep it natural, modern, and easy to read, exactly like " +
            "how modern native speakers communicate in daily life.",
        "2. Context-Awareness (Crucial): Analyze the genre and tone of the text. Automatically adapt the pronouns, " +
            "honorifics, and formality style based on the context (e.g., use formal/respectful phrasing for " +
            "philosophical dialogues, and friendly/casual phrasing for modern fiction).",
        "3. Vocabulary: Do NOT use highly archaic, purely academic, or strictly literal translations. Use commonly " +
            "accepted loan words where appropriate for modern readers. You can leave highly technical or specific " +
            "global terms in the English script if they lack a natural equivalent.",
        "4. Formatting & Labels: Maintain exact formatting, paragraphs, and bullet points. Do NOT translate speaker " +
            "labels, character names, or structural tags (e.g., keep 'YOUTH:', 'PHILOSOPHER:', 'Chapter 1' exactly " +
            "as they are in the original text).",
        "5. Output: Output ONLY the translated text. Never add conversational filler, introductions, or explanations.",
        "</rules>",
    ).joinToString("\n")

    /**
     * Appended after [TEMPLATE]: how the answer must be laid out so it maps
     * back onto the source block by block, and that every word is translated.
     */
    val OUTPUT_CONTRACT: String = listOf(
        "OUTPUT CONTRACT:",
        "1. 100% TRANSLATION: Translate every heading, sentence, list item and quotation into {Target_Language}, " +
            "from the first word to the last. Never stop early and never leave a sentence in the original language.",
        "2. SAME BLOCKS: The text is split into blocks separated by blank lines. Answer with exactly the same " +
            "blocks, in the same order, separated by blank lines. Never merge two blocks or split one; keep each " +
            "block's leading marker (#, -, 1., >).",
        "3. SHORT BLOCKS COUNT TOO: A block that is only a heading, a chapter title or a single line is still " +
            "translated and kept as its own block.",
    ).joinToString("\n")

    /** Added for a second try when an answer was missing, cut short or partly untranslated. */
    val STRICT_REMINDER: String =
        "IMPORTANT: An earlier answer for this text was incomplete or left parts in the original language. " +
            "This time translate ALL of it into {Target_Language}, every sentence, and answer with the same blocks."

    /** The system prompt for a book translated from [source] (possibly Auto-Detect) into [target]. */
    fun system(source: Language, target: Language, strict: Boolean = false): String =
        listOfNotNull(TEMPLATE, OUTPUT_CONTRACT, STRICT_REMINDER.takeIf { strict }).joinToString("\n\n")
            .replace("{Source_Language}", source.sourcePromptName)
            .replace("{Target_Language}", target.promptName)
            .replace("{Target_Script}", target.script)

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
