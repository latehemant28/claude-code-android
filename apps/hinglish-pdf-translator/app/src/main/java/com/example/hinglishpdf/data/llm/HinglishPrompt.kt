package com.example.hinglishpdf.data.llm

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.translate.TranslationUnit

/**
 * Builds the translation prompt and parses the model's answer.
 *
 * Every block goes in as one line tagged with an ID and a Markdown-style
 * marker (`[3] ## Heading`, `[4] - bullet`, `[5] 2. step`). The model answers
 * line by line with the same IDs, so each translation can be matched back to
 * its block even if the model drops a marker or a line. The original
 * markers are re-applied by the app, never trusted from the model.
 */
object HinglishPrompt {

    val GUIDELINES = """
        Translate the document lines below into Hinglish.

        Guidelines:
        - Vocabulary: Keep technical terms, proper nouns, and industry jargon in English. Translate everyday verbs, connectors, and descriptive words into Roman Hindi (e.g., kaam, lekin, zaroori, samajh).
        - Script: Use 100% English (Latin) letters. Do NOT use the Devanagari script.
        - Flow: The output should read like a natural conversation between modern bilingual speakers (e.g., "Yeh process start karne ke liye, aapko next button par click karna hoga").
        - Formatting: Strictly maintain the original structure, including headings, bullet points, numbering, and paragraph breaks.

        Output rules:
        - Every input line starts with an ID like [7]. Write exactly one output line for every input line, starting with the same ID, in the same order. Never merge, split, skip or add lines.
        - Keep the marker after the ID (#, -, 1., >) exactly as it is.
        - Output ONLY the translated lines, with no introduction or notes.

        Example input:
        [1] ## Getting Started
        [2] - Click the Next button to continue the setup.
        [3] This step is important because it saves your settings.

        Example output:
        [1] ## Getting Started
        [2] - Setup continue karne ke liye Next button par click karein.
        [3] Yeh step zaroori hai kyunki isse aapki settings save hoti hain.
    """.trimIndent()

    fun build(units: List<TranslationUnit>): String = buildString {
        append(GUIDELINES)
        append("\n\nInput:\n")
        units.forEachIndexed { i, unit -> append(line(i + 1, unit)).append('\n') }
        append("\nOutput:")
    }

    private fun line(id: Int, unit: TranslationUnit): String {
        val text = unit.text.replace(NEWLINES, " ")
        val marker = if (unit.continuation) "" else when (unit.kind) {
            BlockKind.HEADING -> "#".repeat(unit.level.coerceIn(1, 6)) + " "
            BlockKind.BULLET -> "  ".repeat(unit.level) + "- "
            BlockKind.NUMBERED -> "  ".repeat(unit.level) + unit.marker + " "
            BlockKind.QUOTE -> "> "
            BlockKind.PARAGRAPH, BlockKind.CODE -> ""
        }
        return "[$id] $marker$text"
    }

    /**
     * Maps the model's answer back to units: result[i] is the translation of
     * units[i], or null if the model skipped it. Untagged lines that follow a
     * tagged one are treated as its wrapped continuation.
     */
    fun parse(raw: String, units: List<TranslationUnit>): List<String?> {
        val result = arrayOfNulls<String>(units.size)
        var lastId: Int? = null
        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val match = TAG.find(line)
            if (match != null) {
                val id = match.groupValues[1].toInt()
                lastId = if (id in 1..units.size && result[id - 1] == null) {
                    result[id - 1] = clean(match.groupValues[2], units[id - 1])
                    id
                } else {
                    null
                }
            } else if (lastId != null) {
                result[lastId - 1] = result[lastId - 1] + " " + clean(line, units[lastId - 1])
            }
        }
        // A single line answered without its tag is still the translation.
        if (units.size == 1 && result[0] == null) {
            result[0] = clean(raw.lines().filter { it.isNotBlank() }.joinToString(" ").trim(), units[0])
        }
        return result.map { it?.trim()?.takeIf(String::isNotEmpty) }
    }

    fun containsDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    /** Strips the markers the model echoed back, since the app re-applies the originals. */
    private fun clean(text: String, unit: TranslationUnit): String {
        var t = text.trim().removeSurrounding("**").trim()
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
    private val TAG = Regex("^\\[(\\d{1,4})]\\s*(.*)$")
    private val HEADING_MARK = Regex("^#{1,6}\\s*")
    private val BULLET_MARK = Regex("^[-*•●◦▪]\\s+")
    private val NUMBER_MARK = Regex("^\\(?\\d{1,3}[.)]\\s+")
    private val QUOTE_MARK = Regex("^>\\s*")
}
