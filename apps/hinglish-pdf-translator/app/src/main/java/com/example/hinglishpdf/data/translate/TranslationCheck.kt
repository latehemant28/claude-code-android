package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.llm.Language

/**
 * Checks one block's translation before it is saved, so a page never ends up
 * with a blank paragraph, a paragraph cut off halfway, or a stretch of the
 * original text the AI copied instead of translating.
 *
 * Short blocks (a name, "Index", "Chapter 3") are not checked: they may
 * rightly stay as they are.
 */
object TranslationCheck {

    enum class Problem {
        /** Nothing came back for this block. */
        MISSING,

        /** The original text, or a long stretch of it, came back untranslated. */
        UNTRANSLATED,

        /** Far too short for the original: the answer stopped part-way. */
        INCOMPLETE,
    }

    /** Blocks with fewer words are not checked for copied text. */
    private const val MIN_WORDS = 4

    /** This many consecutive words found unchanged in the original = copied, not translated. */
    private const val COPIED_RUN = 8

    /** Blocks with fewer words are not checked for length. */
    private const val LENGTH_CHECK_WORDS = 12

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    fun problem(source: String, output: String?, from: Language, to: Language): Problem? {
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return if (source.any(Char::isLetter)) Problem.MISSING else null
        if (from == to) return null

        val sourceWords = words(source)
        if (sourceWords.size < MIN_WORDS) return null
        val outputWords = words(text)

        if (outputWords == sourceWords || copiedRun(sourceWords, outputWords)) return Problem.UNTRANSLATED
        if (!to.latinScript && latinShare(text) > 0.5f && latinShare(source) > 0.8f) return Problem.UNTRANSLATED

        val minRatio = if (to.compactScript) 0.15f else 0.35f
        if (sourceWords.size >= LENGTH_CHECK_WORDS && letters(text) < letters(source) * minRatio) {
            return Problem.INCOMPLETE
        }
        return null
    }

    private fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.toList()

    /** Whether [COPIED_RUN] consecutive words of the original appear unchanged in the answer. */
    private fun copiedRun(source: List<String>, output: List<String>): Boolean {
        if (source.size < COPIED_RUN || output.size < COPIED_RUN) return false
        val runs = source.windowed(COPIED_RUN).filter { run -> run.any { w -> w.any(Char::isLetter) } }.toHashSet()
        return output.windowed(COPIED_RUN).any { it in runs }
    }

    /** Letters, counting vowel signs too (Devanagari's matras are marks, not letters). */
    private fun letters(text: String): Int = text.count { c ->
        c.isLetter() || Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        }
    }

    /** Share of the letters written in the Latin alphabet. */
    private fun latinShare(text: String): Float {
        var letters = 0
        var latin = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            if (Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) latin++
        }
        return if (letters == 0) 0f else latin.toFloat() / letters
    }
}
