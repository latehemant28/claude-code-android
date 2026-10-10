package com.example.hinglishpdf.pipeline.assemble

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.Placeholders

/**
 * Decides whether "word-" at the end of a line (or page) and the word
 * starting the next one are a single word broken for printing (the hyphen
 * goes) or a real hyphenated compound (the hyphen stays). No dictionary is
 * bundled; the evidence comes from two sources:
 *
 * 1. the book's own vocabulary: "contraptions" written whole anywhere in the
 *    book means "contrap-" + "tions" joins; "well-known" written mid-line
 *    means the hyphen in "well-" + "known" is real;
 * 2. standalone words: [PipelineConfig.commonStandaloneWords] plus every
 *    word the book itself uses on its own often enough. A continuation that
 *    is such a word ("known", "based") keeps the hyphen.
 *
 * In order: whole word in the book → join; compound in the book → keep;
 * continuation in lower case and not a standalone word → join; otherwise
 * keep. A soft hyphen (U+00AD) always joins and is handled by the callers.
 */
class Hyphenation(
    private val vocabulary: Set<String>,
    private val compounds: Set<String>,
    private val standalone: Set<String>,
) {

    /** True: [left] + [right] is one word (drop the hyphen). False: keep "left-right". */
    fun joins(left: String, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val l = left.lowercase()
        val r = right.lowercase()
        return when {
            (l + r) in vocabulary -> true
            "$l-$r" in compounds -> false
            right.first().isLowerCase() && r !in standalone -> true
            else -> false
        }
    }

    /**
     * Joins [before] (ending in "word-") and [after] at a break. Null if
     * [before] does not end in a hyphenated word or [after] does not start
     * with a letter; otherwise the joined text, with or without the hyphen.
     */
    fun join(before: String, after: String): String? {
        val end = TRAILING.find(before) ?: return null
        val next = LEADING.find(after) ?: return null
        val left = end.groupValues[1]
        val right = next.value
        return if (joins(left, right)) before.substring(0, end.range.first) + left + after else before + after
    }

    companion object {
        /** No evidence: only the lower-case rule applies. */
        val NONE = Hyphenation(emptySet(), emptySet(), emptySet())

        /** "transla-" at the very end: group 1 is the word part. */
        private val TRAILING = Regex("([\\p{L}\\p{M}]+)[-‐]$")
        private val LEADING = Regex("^[\\p{L}\\p{M}]+")
        private val TOKEN = Regex("[\\p{L}\\p{M}]+(?:[-‐][\\p{L}\\p{M}]+)*[-‐]?")

        /**
         * Learns the book's vocabulary from its [lines] in reading order. A
         * word cut at a line end, and the piece that continues it on the next
         * line, are left out: they are fragments, not words.
         */
        fun build(lines: Sequence<String>, config: PipelineConfig): Hyphenation {
            val vocabulary = HashSet<String>()
            val compounds = HashSet<String>()
            val counts = HashMap<String, Int>()
            var continues = false
            for (line in lines) {
                val text = Placeholders.strip(line).replace("\u00AD", "").trimEnd()
                val tokens = TOKEN.findAll(text).map { it.value }.toMutableList()
                val cut = TRAILING.containsMatchIn(text)
                if (continues && tokens.isNotEmpty()) tokens.removeAt(0)
                if (cut && tokens.isNotEmpty()) tokens.removeAt(tokens.lastIndex)
                continues = cut
                for (token in tokens) {
                    val word = token.lowercase().replace('‐', '-')
                    if ('-' in word) {
                        compounds += word
                    } else {
                        vocabulary += word
                        counts[word] = (counts[word] ?: 0) + 1
                    }
                }
            }
            val standalone = config.standaloneWords + counts.filterValues { it >= config.standaloneMinCount }.keys
            return Hyphenation(vocabulary, compounds, standalone)
        }
    }
}
