package com.example.hinglishpdf.data.text

/**
 * Word counting and sentence-aware splitting of text into pieces small enough
 * for an on-device LLM (see BlockChunker for the 100-150 word micro-chunks).
 *
 * Chunks follow natural boundaries where possible, in this order:
 *  1. whole paragraphs are packed together while they fit,
 *  2. a paragraph that is too long is split into sentences,
 *  3. a sentence that is still too long is cut into fixed word windows.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-tested on the JVM.
 */
object TextChunker {

    const val DEFAULT_MAX_WORDS = 400

    private val WHITESPACE = Regex("\\s+")
    private val PARAGRAPH_BREAK = Regex("\\n[ \\t]*\\n")

    // Sentence ends: Latin punctuation plus the Devanagari danda (। and ॥).
    private val SENTENCE_END = Regex("(?<=[.!?।॥])\\s+")

    // "transla-\ntion" -> "translation", but "On-\nDevice" -> "On-Device":
    // a capital after the break means the hyphen is part of a compound.
    private val HYPHENATED_LINE_BREAK = Regex("(\\p{L})-\\n(\\p{Ll})")
    private val COMPOUND_LINE_BREAK = Regex("(\\p{L})-\\n(\\p{Lu})")

    /**
     * @param text raw text, e.g. every PDF page joined with blank lines.
     * @param maxWords hard upper bound of whitespace-separated words per chunk.
     * @return non-empty chunks in reading order; empty if [text] has no words.
     */
    fun chunk(text: String, maxWords: Int = DEFAULT_MAX_WORDS): List<String> {
        require(maxWords > 0) { "maxWords must be positive, was $maxWords" }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0

        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
                currentWords = 0
            }
        }

        for (paragraph in paragraphs(text)) {
            split(paragraph, maxWords).forEachIndexed { index, piece ->
                val words = countWords(piece)
                if (currentWords + words > maxWords) flush()
                if (current.isNotEmpty()) {
                    // Keep paragraph structure; pieces of one paragraph join with a space.
                    current.append(if (index == 0) "\n\n" else " ")
                }
                current.append(piece)
                currentWords += words
            }
        }
        flush()
        return chunks
    }

    fun countWords(text: String): Int =
        if (text.isBlank()) 0 else text.trim().split(WHITESPACE).size

    /** Normalises PDF line wrapping and returns non-blank, single-line paragraphs. */
    internal fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u000C", "\n\n") // form feed = page break
            .replace(HYPHENATED_LINE_BREAK, "$1$2")
            .replace(COMPOUND_LINE_BREAK, "$1-$2")
            .split(PARAGRAPH_BREAK)
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }

    /** Breaks one paragraph into pieces of at most [maxWords] words each. */
    fun split(paragraph: String, maxWords: Int): List<String> {
        if (countWords(paragraph) <= maxWords) return listOf(paragraph.trim())

        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0

        for (sentence in paragraph.split(SENTENCE_END)) {
            val sentenceWords = countWords(sentence)
            if (sentenceWords == 0) continue

            if (sentenceWords > maxWords) {
                // A run-on "sentence" (tables, lists without punctuation): hard-split.
                if (current.isNotEmpty()) {
                    pieces += current.toString()
                    current.clear()
                    currentWords = 0
                }
                sentence.trim().split(WHITESPACE)
                    .chunked(maxWords)
                    .mapTo(pieces) { it.joinToString(" ") }
                continue
            }

            if (currentWords + sentenceWords > maxWords) {
                pieces += current.toString()
                current.clear()
                currentWords = 0
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence.trim())
            currentWords += sentenceWords
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }
}
