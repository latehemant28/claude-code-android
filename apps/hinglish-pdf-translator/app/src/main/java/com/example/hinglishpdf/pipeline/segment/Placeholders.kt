package com.example.hinglishpdf.pipeline.segment

/**
 * What a placeholder stands for. Paired placeholders wrap text
 * (`{1}bold{/1}`) and come from inline tags such as `<b>` or `<a href>`;
 * standalone ones (`[[IMG_3]]`, `[[BR_4]]`, `[[CODE_5]]`) replace an element
 * the model must not touch at all.
 *
 * @param id unique within its paragraph, numbered in document order.
 * @param label the standalone token's label ("IMG", "BR", "CODE", "SUP"...);
 *   the element name for paired tags.
 * @param markup how to rebuild it: for a paired tag, the element with no
 *   children (`<a href="x"></a>`); for a standalone one, the whole original
 *   markup (or, for [LITERAL], the original text).
 */
data class PlaceholderTag(
    val id: Int,
    val kind: Kind,
    val label: String,
    val markup: String,
) {
    enum class Kind { PAIRED, STANDALONE }

    /** The token(s) that stand for this tag in segment text. */
    val open: String get() = if (kind == Kind.PAIRED) "{$id}" else "[[${label}_$id]]"
    val close: String? get() = if (kind == Kind.PAIRED) "{/$id}" else null

    companion object {
        /** Source text that merely looks like a placeholder ("{1}" in a maths book) is protected as this label. */
        const val LITERAL = "TXT"
    }
}

/** One piece of placeholder text. */
sealed interface PlaceholderToken {
    val start: Int
    val end: Int

    data class Text(val text: String, override val start: Int, override val end: Int) : PlaceholderToken
    data class Open(val id: Int, override val start: Int, override val end: Int) : PlaceholderToken
    data class Close(val id: Int, override val start: Int, override val end: Int) : PlaceholderToken
    data class Standalone(val label: String, val id: Int, override val start: Int, override val end: Int) : PlaceholderToken
}

/** A placeholder problem in a translation, compared with its source. */
sealed interface PlaceholderProblem {
    data class Missing(val token: String) : PlaceholderProblem
    data class Extra(val token: String) : PlaceholderProblem
    data class Reordered(val expected: List<String>, val actual: List<String>) : PlaceholderProblem
    data class Unbalanced(val detail: String) : PlaceholderProblem
}

/**
 * The placeholder syntax shared by every format: tokenizing, checking a
 * translation against its source, and the forms used for hashing and
 * counting. Pure Kotlin.
 */
object Placeholders {

    /** `{12}`, `{/12}` or `[[IMG_12]]`. */
    val TOKEN = Regex("\\{(/?)(\\d+)\\}|\\[\\[([A-Z][A-Z0-9]*)_(\\d+)]]")

    fun tokenize(text: String): List<PlaceholderToken> {
        val out = mutableListOf<PlaceholderToken>()
        var at = 0
        for (m in TOKEN.findAll(text)) {
            if (m.range.first > at) out += PlaceholderToken.Text(text.substring(at, m.range.first), at, m.range.first)
            val end = m.range.last + 1
            out += when {
                m.groupValues[3].isNotEmpty() ->
                    PlaceholderToken.Standalone(m.groupValues[3], m.groupValues[4].toInt(), m.range.first, end)
                m.groupValues[1] == "/" -> PlaceholderToken.Close(m.groupValues[2].toInt(), m.range.first, end)
                else -> PlaceholderToken.Open(m.groupValues[2].toInt(), m.range.first, end)
            }
            at = end
        }
        if (at < text.length) out += PlaceholderToken.Text(text.substring(at), at, text.length)
        return out
    }

    /** The placeholder tokens of [text], in order ("{1}", "[[IMG_2]]", "{/1}"). */
    fun sequence(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

    /** [text] without any placeholder: what a reader sees, for counting words and characters. */
    fun strip(text: String): String = text.replace(TOKEN, "")

    /**
     * [text] with its placeholders numbered 1, 2, 3... in order of
     * appearance, so the same sentence hashes the same wherever it occurs.
     */
    fun renumber(text: String): String {
        val ids = mutableMapOf<Int, Int>()
        return TOKEN.replace(text) { m ->
            if (m.groupValues[3].isNotEmpty()) {
                val id = ids.getOrPut(m.groupValues[4].toInt()) { ids.size + 1 }
                "[[${m.groupValues[3]}_$id]]"
            } else {
                val id = ids.getOrPut(m.groupValues[2].toInt()) { ids.size + 1 }
                "{${m.groupValues[1]}$id}"
            }
        }
    }

    /** [text] with every placeholder id moved by [offset] (joining paragraphs keeps ids unique). */
    fun shift(text: String, offset: Int): String {
        if (offset == 0) return text
        return TOKEN.replace(text) { m ->
            if (m.groupValues[3].isNotEmpty()) {
                "[[${m.groupValues[3]}_${m.groupValues[4].toInt() + offset}]]"
            } else {
                "{${m.groupValues[1]}${m.groupValues[2].toInt() + offset}}"
            }
        }
    }

    /**
     * Compares a translation's placeholders with its source's: each one must
     * be there exactly once, in the same order, and paired ones properly
     * nested. Empty when the translation is fine.
     */
    fun check(source: String, target: String): List<PlaceholderProblem> {
        val expected = sequence(source)
        val actual = sequence(target)
        val problems = mutableListOf<PlaceholderProblem>()
        val expectedCounts = expected.groupingBy { it }.eachCount()
        val actualCounts = actual.groupingBy { it }.eachCount()
        for ((token, count) in expectedCounts) {
            repeat(count - (actualCounts[token] ?: 0)) { problems += PlaceholderProblem.Missing(token) }
        }
        for ((token, count) in actualCounts) {
            repeat(count - (expectedCounts[token] ?: 0)) { problems += PlaceholderProblem.Extra(token) }
        }
        if (problems.isEmpty() && expected != actual) problems += PlaceholderProblem.Reordered(expected, actual)
        balanceProblem(target)?.let { problems += it }
        return problems
    }

    /** Null if every `{n}` is closed by its own `{/n}`, innermost first. */
    fun balanceProblem(text: String): PlaceholderProblem.Unbalanced? {
        val open = ArrayDeque<Int>()
        for (token in tokenize(text)) {
            when (token) {
                is PlaceholderToken.Open -> open.addLast(token.id)
                is PlaceholderToken.Close -> {
                    val top = open.removeLastOrNull()
                    if (top != token.id) {
                        return PlaceholderProblem.Unbalanced(
                            if (top == null) "{/${token.id}} closes nothing" else "{/${token.id}} closes {$top}",
                        )
                    }
                }
                else -> Unit
            }
        }
        return open.lastOrNull()?.let { PlaceholderProblem.Unbalanced("{$it} is never closed") }
    }

    /** Paired placeholders still open at each character position's end, for keeping cuts outside tags. */
    internal fun openDepthAfter(tokens: List<PlaceholderToken>, position: Int): Int {
        var depth = 0
        for (token in tokens) {
            if (token.end > position) break
            when (token) {
                is PlaceholderToken.Open -> depth++
                is PlaceholderToken.Close -> depth--
                else -> Unit
            }
        }
        return depth
    }
}

/**
 * Builds placeholder text and its tag table at the same time, numbering tags
 * in document order. Used by every parser (EPUB from the DOM, PDF from font
 * runs), so they all produce the same syntax.
 */
class PlaceholderTextBuilder(private var nextId: Int = 1) {
    private val text = StringBuilder()
    private val tags = mutableListOf<PlaceholderTag>()
    private val open = ArrayDeque<PlaceholderTag>()

    val length: Int get() = text.length

    /** Adds reader-visible text; anything in it that looks like a placeholder is protected. */
    fun text(value: String) {
        var at = 0
        for (m in Placeholders.TOKEN.findAll(value)) {
            text.append(value, at, m.range.first)
            standalone(PlaceholderTag.LITERAL, m.value)
            at = m.range.last + 1
        }
        text.append(value, at, value.length)
    }

    fun open(label: String, markup: String) {
        val tag = PlaceholderTag(nextId++, PlaceholderTag.Kind.PAIRED, label, markup)
        tags += tag
        open.addLast(tag)
        text.append(tag.open)
    }

    fun close() {
        val tag = open.removeLastOrNull() ?: error("close() without open()")
        text.append(tag.close)
    }

    fun standalone(label: String, markup: String) {
        val tag = PlaceholderTag(nextId++, PlaceholderTag.Kind.STANDALONE, label.uppercase().filter(Char::isLetterOrDigit).ifEmpty { "X" }, markup)
        tags += tag
        text.append(tag.open)
    }

    /** The placeholder text and its tags; every paired tag must be closed. */
    fun result(): Pair<String, List<PlaceholderTag>> {
        check(open.isEmpty()) { "unclosed placeholder" }
        return text.toString() to tags.toList()
    }
}
