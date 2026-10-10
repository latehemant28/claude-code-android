package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit

/**
 * The translation prompt (English → conversational Devanagari Hindi) and the
 * parser that maps Gemini's answer back onto the chunk's headings, bullets
 * and paragraphs.
 *
 * [SYSTEM_PROMPT] is embedded exactly as specified and set once as the
 * model's system instruction. Each request then carries only the chunk, as
 * plain Markdown, one block per paragraph (`## Heading`, `- bullet`,
 * `2. step`), so the answer can be matched back block by block.
 */
object HinglishPrompt {

    /**
     * Embedded exactly as specified (line breaks and spacing included). The
     * specified text ended without closing the <examples> tag (it looks cut
     * off), so the closing </examples> line is the one addition.
     */
    val SYSTEM_PROMPT = """
        You are an expert, context-aware translator translating English books into natural, conversational Hindi using the Devanagari script (हिंदी). 

        <rules>
        1. Script & Tone: Use the Devanagari script. Keep it casual, modern, and easy to read, exactly like how modern urban Indians speak.
        2. Context-Awareness (Crucial): Analyze the genre and tone of the text. Automatically adapt the pronouns (आप vs तुम) and style based on the context (e.g., use 'आप' for philosophical/respectful dialogues, and 'तुम' for friendly/casual fiction).
        3. Vocabulary: Do NOT use highly formal, pure, or academic Hindi words (avoid 'विकल्प', 'निर्णय', 'समावेश'). Use common English words written in Devanagari (e.g., 'ऑप्शन', 'डिसाइड', 'सिस्टम', 'प्लान'). You can also leave highly technical words in the English script.
        4. Formatting & Labels: Maintain exact formatting, paragraphs, and bullet points. Do NOT translate speaker labels, character names, or structural tags (e.g., keep 'YOUTH:', 'PHILOSOPHER:', 'Chapter 1' exactly as they are in English).
        5. Output: Output ONLY the translated text. Never add conversational filler, introductions, or explanations.
        </rules>

        <examples>
        English: Believe me, he had no other option, so he decided to plan this.
        Modern Hindi: मेरा यकीन मानिए, उसके पास कोई और ऑप्शन नहीं था, इसलिए उसने यह प्लान डिसाइड किया।

        English: The system architecture is quite complex, but we can optimize the database queries easily.
        Modern Hindi: सिस्टम आर्किटेक्चर थोड़ा कॉम्प्लेक्स है, लेकिन हम डेटाबेस क्वेरीज को आसानी से ऑप्टिमाइज़ कर सकते हैं।
        </examples>
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

    /**
     * Removes what the model sometimes wraps around the translation: a code
     * fence, echoed tags, a "Here is..." line, or a leading "Modern Hindi:"
     * label (the prompt's examples use one). Speaker labels such as "YOUTH:"
     * are part of the text and are kept.
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
     * Example sentences in prompts are often quoted, so Gemini sometimes
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
    private val LABEL = Regex("^((modern |good )?(hindi|hinglish)|translation)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val HEADING_MARK = Regex("^#{1,6}\\s*")
    private val BULLET_MARK = Regex("^[-*•●◦▪]\\s+")
    private val NUMBER_MARK = Regex("^\\(?\\d{1,3}[.)]\\s+")
    private val QUOTE_MARK = Regex("^>\\s*")
}
