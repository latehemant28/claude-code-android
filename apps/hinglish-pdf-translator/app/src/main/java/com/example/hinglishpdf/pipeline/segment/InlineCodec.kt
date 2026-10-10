package com.example.hinglishpdf.pipeline.segment

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/** A translation whose placeholders can't be put back. */
class PlaceholderException(message: String) : IllegalArgumentException(message)

/**
 * Turns the inline content of an XHTML block into placeholder text, and back.
 *
 * - Text is kept, with runs of whitespace collapsed (as a reader shows them).
 * - An inline element with text (`<b>`, `<em>`, `<a href>`, `<span class>`)
 *   becomes `{n}…{/n}`, its attributes kept in the tag table.
 * - An element without text (`<img>`, `<br>`, an empty anchor) becomes
 *   `[[IMG_n]]`, `[[BR_n]]`..., kept whole.
 * - A protected element (code, script, `translate="no"`...) becomes a
 *   standalone placeholder too, so the model never sees its content.
 *
 * Decoding the source text with its own tags gives the original nodes back;
 * decoding a translation checks that every placeholder is there and nested
 * properly, and fails rather than producing broken markup.
 */
object InlineCodec {

    fun encode(nodes: List<Node>, builder: PlaceholderTextBuilder, isProtected: (Element) -> Boolean) {
        for (node in nodes) encodeNode(node, builder, isProtected)
    }

    private fun encodeNode(node: Node, builder: PlaceholderTextBuilder, isProtected: (Element) -> Boolean) {
        when (node) {
            is TextNode -> builder.text(node.wholeText.replace(WHITESPACE, " "))
            is Element -> when {
                isProtected(node) || !hasText(node) -> builder.standalone(labelOf(node), node.outerHtml())
                else -> {
                    builder.open(node.normalName(), shallowMarkup(node))
                    node.childNodes().forEach { encodeNode(it, builder, isProtected) }
                    builder.close()
                }
            }
            // Comments, processing instructions...: kept as they are, invisible to the model.
            else -> builder.standalone("X", node.outerHtml())
        }
    }

    /**
     * The nodes for [text], rebuilt from [tags]. Throws [PlaceholderException]
     * if a placeholder is unknown, repeated, missing or badly nested.
     */
    fun decode(text: String, tags: List<PlaceholderTag>): List<Node> {
        val byId = tags.associateBy { it.id }
        val used = mutableSetOf<Int>()
        val root = Element("root")
        val stack = ArrayDeque<Pair<Int, Element>>()
        fun parent(): Element = stack.lastOrNull()?.second ?: root

        for (token in Placeholders.tokenize(text)) {
            when (token) {
                is PlaceholderToken.Text -> parent().appendChild(TextNode(token.text))
                is PlaceholderToken.Open -> {
                    val tag = byId[token.id]?.takeIf { it.kind == PlaceholderTag.Kind.PAIRED }
                        ?: throw PlaceholderException("Unknown placeholder {${token.id}}")
                    if (!used.add(token.id)) throw PlaceholderException("{${token.id}} appears twice")
                    val element = parse(tag.markup).filterIsInstance<Element>().firstOrNull()
                        ?: throw PlaceholderException("Can't rebuild {${token.id}}")
                    parent().appendChild(element)
                    stack.addLast(token.id to element)
                }
                is PlaceholderToken.Close -> {
                    val (id, _) = stack.removeLastOrNull() ?: throw PlaceholderException("{/${token.id}} closes nothing")
                    if (id != token.id) throw PlaceholderException("{/${token.id}} closes {$id}")
                }
                is PlaceholderToken.Standalone -> {
                    val tag = byId[token.id]?.takeIf { it.kind == PlaceholderTag.Kind.STANDALONE && it.label == token.label }
                        ?: throw PlaceholderException("Unknown placeholder [[${token.label}_${token.id}]]")
                    if (!used.add(token.id)) throw PlaceholderException("[[${token.label}_${token.id}]] appears twice")
                    if (tag.label == PlaceholderTag.LITERAL) {
                        parent().appendChild(TextNode(tag.markup))
                    } else {
                        parse(tag.markup).forEach { parent().appendChild(it) }
                    }
                }
            }
        }
        stack.lastOrNull()?.let { (id, _) -> throw PlaceholderException("{$id} is never closed") }
        val missing = tags.map { it.id } - used
        if (missing.isNotEmpty()) throw PlaceholderException("Missing placeholders: ${missing.joinToString()}")
        return root.childNodes().toList().onEach { it.remove() }
    }

    /** True if [element] shows any text to a reader. */
    fun hasText(element: Element): Boolean = element.wholeText().isNotBlank()

    private fun labelOf(element: Element): String = element.normalName().substringAfter(':').uppercase()

    /** The element without its children: `<a href="x"></a>`. */
    private fun shallowMarkup(element: Element): String {
        val name = element.tagName()
        return "<$name${element.attributes().html()}></$name>"
    }

    private fun parse(markup: String): List<Node> =
        Jsoup.parse(markup, "", Parser.xmlParser()).childNodes().toList().onEach { it.remove() }

    private val WHITESPACE = Regex("\\s+")
}
