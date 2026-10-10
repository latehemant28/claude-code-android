package com.example.hinglishpdf.pipeline.segment

/**
 * One sentence of a paragraph: [start] until [end] in the paragraph's
 * placeholder text, followed by [separator] (the whitespace before the next
 * sentence; empty for the last one).
 */
data class Sentence(val text: String, val start: Int, val end: Int, val separator: String)

/**
 * Splits a paragraph (placeholder text) into sentences. It errs on the side
 * of not splitting: a sentence that stays joined to the next is harmless, a
 * wrong cut breaks the translation. So it does not split
 * - after abbreviations (Mr., Dr., e.g., etc., Fig., U.S.) or initials (J. K.),
 * - inside numbers (3.14, 1.5.2) or anything not followed by a space,
 * - inside quoted dialogue ("Stop. Wait here," she said.),
 * - before a lower-case word ("Wait!" he said.),
 * - inside a paired placeholder ({1}One. Two.{/1} stays one segment).
 * Sentence ends: . ! ? … and the Devanagari danda । ॥.
 */
object SentenceSegmenter {

    private const val TERMINALS = ".!?…।॥"
    private const val CLOSERS = "\"'”’»)]}›」』"
    private const val OPENERS = "\"'“‘«([{‹「『¿¡"

    /** Lower-case forms of abbreviations that are followed by a full stop but don't end a sentence. */
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "mx", "dr", "prof", "sr", "jr", "st", "mt", "ft", "rev", "hon", "gen", "col",
        "capt", "lt", "sgt", "maj", "gov", "pres", "sen", "rep", "fr", "messrs", "mme", "mlle",
        "vs", "etc", "e.g", "i.e", "cf", "al", "approx", "ca", "viz", "esp", "incl", "dept", "est",
        "inc", "ltd", "co", "corp", "bros", "ed", "eds", "trans", "op", "cit", "ibid",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
        "a.m", "p.m", "u.s", "u.k", "u.n", "b.c", "a.d", "ph.d", "m.d", "b.a", "m.a",
    )

    /** Abbreviations only when a number follows ("No. 5", "p. 12", "Fig. 3"), so "Say no. Then..." still splits. */
    private val NUMBER_ABBREVIATIONS = setOf(
        "no", "nos", "vol", "vols", "pp", "p", "ch", "chap", "sec", "fig", "figs", "eq", "ref", "nr", "art", "para",
    )

    fun split(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()
        val tokens = Placeholders.tokenize(text)
        val placeholders = tokens.filter { it !is PlaceholderToken.Text }.associateBy { it.start }
        val cuts = mutableListOf<Pair<Int, Int>>() // (end of sentence, start of next)
        var doubleQuotes = 0
        var singleQuotes = 0

        var i = 0
        while (i < text.length) {
            val placeholder = placeholders[i]
            if (placeholder != null) {
                i = placeholder.end
                continue
            }
            val c = text[i]
            when {
                c == '“' -> doubleQuotes++
                c == '”' -> doubleQuotes = (doubleQuotes - 1).coerceAtLeast(0)
                c == '"' -> if (opensQuote(text, i)) doubleQuotes++ else doubleQuotes = (doubleQuotes - 1).coerceAtLeast(0)
                c == '‘' && opensQuote(text, i) -> singleQuotes++
                c == '’' && singleQuotes > 0 && closesQuote(text, i) -> singleQuotes--
                c in TERMINALS -> {
                    val cut = boundaryAfter(text, placeholders, i, doubleQuotes, singleQuotes)
                    if (cut != null) {
                        cuts += cut
                        doubleQuotes = 0
                        singleQuotes = 0
                        i = cut.second
                        continue
                    }
                    // Skip the rest of a run like "?!" or "...", so it is judged once.
                    while (i + 1 < text.length && text[i + 1] in TERMINALS) i++
                }
            }
            i++
        }

        // A cut inside a paired placeholder would leave both halves unbalanced.
        val kept = cuts.filter { (end, _) -> Placeholders.openDepthAfter(tokens, end) == 0 }

        val sentences = mutableListOf<Sentence>()
        var start = text.indexOfFirst { !it.isWhitespace() }
        for ((end, next) in kept) {
            sentences += Sentence(text.substring(start, end), start, end, text.substring(end, next))
            start = next
        }
        val last = text.indexOfLast { !it.isWhitespace() } + 1
        if (start < last) sentences += Sentence(text.substring(start, last), start, last, "")
        return sentences
    }

    /**
     * If the terminal at [at] ends a sentence: (end of this sentence, start of
     * the next). The sentence keeps trailing closers: quotes, brackets,
     * closing placeholders and attached standalone ones (a footnote mark).
     */
    private fun boundaryAfter(
        text: String,
        placeholders: Map<Int, PlaceholderToken>,
        at: Int,
        doubleQuotes: Int,
        singleQuotes: Int,
    ): Pair<Int, Int>? {
        var end = at + 1
        while (end < text.length && text[end] in TERMINALS) end++
        if (text[at] == '.' && end - at == 1 && isAbbreviation(text, at)) return null

        var doubles = doubleQuotes
        var singles = singleQuotes
        while (end < text.length) {
            val placeholder = placeholders[end]
            if (placeholder != null && placeholder !is PlaceholderToken.Open) {
                end = placeholder.end
                continue
            }
            val c = text[end]
            if (c !in CLOSERS) break
            when (c) {
                '”', '"' -> doubles = (doubles - 1).coerceAtLeast(0)
                '’', '\'' -> singles = (singles - 1).coerceAtLeast(0)
            }
            end++
        }
        if (doubles > 0 || singles > 0) return null // still inside quoted dialogue

        var next = end
        while (next < text.length && text[next].isWhitespace()) next++
        if (next == end || next >= text.length) return null // no space after it, or nothing follows
        if (!startsSentence(text, placeholders, next)) return null
        return end to next
    }

    private fun startsSentence(text: String, placeholders: Map<Int, PlaceholderToken>, from: Int): Boolean {
        var i = from
        while (i < text.length) {
            val placeholder = placeholders[i]
            if (placeholder != null) {
                if (placeholder is PlaceholderToken.Standalone) return true
                i = placeholder.end
                continue
            }
            val c = text[i]
            if (c in OPENERS) {
                i++
                continue
            }
            // Upper case, digits, and letters of scripts without case (Devanagari, CJK...).
            return c.isDigit() || (c.isLetter() && !c.isLowerCase())
        }
        return false
    }

    private fun isAbbreviation(text: String, dot: Int): Boolean {
        var start = dot
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '.')) start--
        val word = text.substring(start, dot)
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isUpperCase()) return true // an initial: J. K. Rowling
        val lower = word.lowercase()
        if (lower in NUMBER_ABBREVIATIONS) {
            val next = text.drop(dot + 1).trimStart()
            return next.firstOrNull()?.isDigit() == true
        }
        return lower in ABBREVIATIONS || INITIALISM.matches(word)
    }

    private val INITIALISM = Regex("(?:[A-Za-z]\\.)+[A-Za-z]")

    private fun opensQuote(text: String, i: Int): Boolean =
        i == 0 || text[i - 1].isWhitespace() || text[i - 1] in OPENERS || text[i - 1] == '—' || text[i - 1] == '}'

    private fun closesQuote(text: String, i: Int): Boolean =
        i + 1 >= text.length || !text[i + 1].isLetter()
}
