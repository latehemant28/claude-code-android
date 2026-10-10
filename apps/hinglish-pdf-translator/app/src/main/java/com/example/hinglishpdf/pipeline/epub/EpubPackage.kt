package com.example.hinglishpdf.pipeline.epub

import com.example.hinglishpdf.pipeline.segment.InlineCodec
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.PlaceholderTextBuilder
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * An opened EPUB: its package document (OPF), table of contents (EPUB 2
 * NCX and/or EPUB 3 nav document) and content documents, each parsed once as
 * an XML DOM. [units] lists everything translatable in a fixed order, so the
 * parser and the rebuilder always agree on what unit N is.
 */
internal class EpubPackage(private val zip: ZipFile) {

    val opfPath: String
    val opf: Document
    /** Content documents in reading order (the spine), then the nav document if it is not in the spine. */
    val contentPaths: List<String>
    val ncxPath: String?
    val navPath: String?

    private val documents = mutableMapOf<String, Document>()

    init {
        val container = xml("META-INF/container.xml") ?: throw IOException("Not an EPUB: META-INF/container.xml is missing.")
        opfPath = container.allElements.firstOrNull { it.local() == "rootfile" }?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Not an EPUB: no package document.")
        opf = document(opfPath) ?: throw IOException("EPUB package document is missing.")
        val base = opfPath.substringBeforeLast('/', "")
        val items = opf.allElements.filter { it.local() == "item" }
        val byId = items.associateBy { it.attr("id") }
        val spine = opf.allElements.filter { it.local() == "itemref" }
            .mapNotNull { byId[it.attr("idref")] }
            .filter { it.attr("media-type") in XHTML_TYPES }
            .map { resolve(base, it.attr("href")) }
            .filter { zip.getEntry(it) != null }
            .distinct()
        navPath = items.firstOrNull { "nav" in it.attr("properties").split(' ') }
            ?.let { resolve(base, it.attr("href")) }?.takeIf { zip.getEntry(it) != null }
        val tocId = opf.allElements.firstOrNull { it.local() == "spine" }?.attr("toc").orEmpty()
        ncxPath = (byId[tocId] ?: items.firstOrNull { it.attr("media-type") == NCX_TYPE })
            ?.let { resolve(base, it.attr("href")) }?.takeIf { zip.getEntry(it) != null }
        contentPaths = spine + listOfNotNull(navPath?.takeIf { it !in spine })
    }

    /** dc:title, dc:language, dc:creator... (first value of each). */
    val metadata: Map<String, String>
        get() = opf.allElements
            .filter { it.tagName().startsWith("dc:") && it.text().isNotBlank() }
            .groupBy { it.local() }
            .mapValues { (_, values) -> values.first().text().trim() }

    /** Parsed once and kept, so changes made through [units] are what [serialize] writes. */
    fun document(path: String): Document? = documents.getOrPut(path) { xml(path) ?: return null }

    private val changed = mutableSetOf<String>()

    /** Marks a document as edited, so it is written back. Untouched files are copied byte for byte. */
    fun markChanged(path: String) {
        changed += path
    }

    val changedDocuments: Map<String, Document> get() = documents.filterKeys { it in changed }

    /** Every translatable unit, in the fixed order: titles, TOC labels, then each content document. */
    fun units(): List<EpubUnit> {
        val out = mutableListOf<EpubUnit>()
        opf.allElements.filter { it.tagName() == "dc:title" }.forEach { out += textUnit(opfPath, it, ParagraphRole.TITLE) }
        ncxPath?.let(::document)?.let { ncx ->
            for (text in ncx.allElements.filter { it.local() == "text" }) {
                val inTitle = text.parents().any { it.local() == "doctitle" } // jsoup lower-cases tag names
                out += textUnit(ncxPath, text, if (inTitle) ParagraphRole.TITLE else ParagraphRole.TOC_LABEL)
            }
        }
        for (path in contentPaths) {
            val doc = document(path) ?: continue
            doc.allElements.firstOrNull { it.local() == "head" }
                ?.children()?.firstOrNull { it.local() == "title" }
                ?.let { out += textUnit(path, it, ParagraphRole.TITLE) }
            val body = doc.allElements.firstOrNull { it.local() == "body" } ?: continue
            collect(path, body, out)
            body.allElements.filter { it.local() == "img" && it.attr("alt").isNotBlank() && !insideProtected(it) }.forEach {
                out += EpubUnit.Attribute(
                    role = ParagraphRole.ALT_TEXT,
                    ref = SourceRef.Epub(path, xpath(it), attribute = "alt"),
                    element = it,
                    name = "alt",
                )
            }
        }
        return out.filter { it.hasText }
    }

    fun serialize(doc: Document): String {
        doc.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml).charset(Charsets.UTF_8)
        return doc.outerHtml()
    }

    // ---------------------------------------------------------------- content runs

    private fun textUnit(path: String, element: Element, role: ParagraphRole) =
        EpubUnit.Run(role, SourceRef.Epub(path, xpath(element)), level = 0, nodes = element.childNodes().toList(), owner = element)

    /** Splits a block's content into runs of inline nodes, recursing into nested blocks. */
    private fun collect(path: String, element: Element, out: MutableList<EpubUnit>) {
        if (isProtected(element)) return
        val run = mutableListOf<Node>()
        var runIndex = 0
        fun flush() {
            if (run.isNotEmpty()) {
                val (role, level) = roleOf(element)
                out += EpubUnit.Run(role, SourceRef.Epub(path, xpath(element), run = runIndex), level, run.toList(), element)
                runIndex++
            }
            run.clear()
        }
        for (child in element.childNodes()) {
            if (child is Element && (child.local() in BLOCK_TAGS || (child.local() in BLOCK_PROTECTED))) {
                flush()
                collect(path, child, out)
            } else {
                run += child
            }
        }
        flush()
    }

    private fun roleOf(element: Element): Pair<ParagraphRole, Int> {
        val name = element.local()
        val lineage = listOf(element) + element.parents()
        return when {
            lineage.any { it.local() == "nav" } -> ParagraphRole.TOC_LABEL to 0
            name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> ParagraphRole.HEADING to name[1].digitToInt()
            lineage.any { it.attr("epub:type").split(' ').any { t -> t in NOTE_TYPES } || it.attr("role") == "doc-footnote" } ->
                ParagraphRole.FOOTNOTE to 0
            name == "td" || name == "th" -> ParagraphRole.TABLE_CELL to 0
            name == "caption" || name == "figcaption" -> ParagraphRole.CAPTION to 0
            name == "li" || name == "dt" || name == "dd" ->
                ParagraphRole.LIST_ITEM to (element.parents().count { it.local() == "ul" || it.local() == "ol" } - 1).coerceAtLeast(0)
            lineage.any { it.local() == "blockquote" } -> ParagraphRole.QUOTE to 0
            else -> ParagraphRole.PARAGRAPH to 0
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun xml(path: String): Document? {
        val entry = zip.getEntry(path) ?: return null
        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return Jsoup.parse(text, "", Parser.xmlParser()).also { doc ->
            doc.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        }
    }

    /** [href] (relative to the folder [base]) as a path inside the archive, without its #fragment. */
    fun resolve(base: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayDeque<String>()
        (if (base.isEmpty()) decoded else "$base/$decoded").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    companion object {
        private val XHTML_TYPES = setOf("application/xhtml+xml", "text/html")
        private const val NCX_TYPE = "application/x-dtbncx+xml"
        private val NOTE_TYPES = setOf("footnote", "endnote", "rearnote", "note")

        private val BLOCK_TAGS = setOf(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "li", "ul", "ol", "dl", "dd", "dt", "blockquote", "div",
            "section", "article", "aside", "header", "footer", "main", "nav", "figure", "figcaption", "table",
            "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "address", "center", "hr", "body",
            "hgroup", "details", "summary", "colgroup", "col",
        )

        /** Never translated, whether block or inline: code, scripts, styles, maths and drawings. */
        private val PROTECTED = setOf("code", "pre", "kbd", "samp", "var", "script", "style", "svg", "math", "noscript", "head")
        private val BLOCK_PROTECTED = setOf("pre", "script", "style", "svg", "math", "noscript")

        fun isProtected(element: Element): Boolean =
            element.local() in PROTECTED || element.attr("translate").equals("no", ignoreCase = true)

        fun insideProtected(element: Element): Boolean = (listOf(element) + element.parents()).any { isProtected(it) }

        /** /html[1]/body[1]/div[2]/p[3]: each step counts siblings with the same tag name. */
        fun xpath(element: Element): String {
            val steps = mutableListOf<String>()
            var current: Element? = element
            while (current != null && current.tagName() != "#root") {
                val name = current.tagName()
                val index = current.parent()?.children()?.filter { it.tagName() == name }?.indexOf(current)?.plus(1) ?: 1
                steps += "$name[$index]"
                current = current.parent()
            }
            return steps.asReversed().joinToString("/", prefix = "/")
        }

        fun Element.local(): String = normalName().substringAfter(':')
    }
}

/** One translatable unit of an EPUB, with the live DOM nodes it covers. */
internal sealed interface EpubUnit {
    val role: ParagraphRole
    val ref: SourceRef.Epub
    val level: Int
    val hasText: Boolean

    /** Placeholder text and tags, computed from the current DOM. */
    fun encode(): Encoded

    data class Encoded(val raw: String, val text: String, val tags: List<PlaceholderTag>)

    /** Inline content of one block element (or a title / TOC label element). */
    class Run(
        override val role: ParagraphRole,
        override val ref: SourceRef.Epub,
        override val level: Int,
        val nodes: List<Node>,
        val owner: Element,
    ) : EpubUnit {
        override fun encode(): Encoded {
            val builder = PlaceholderTextBuilder()
            InlineCodec.encode(nodes, builder) { EpubPackage.isProtected(it) }
            val (raw, tags) = builder.result()
            return Encoded(raw, raw.replace(SPACES, " ").trim(), tags)
        }

        override val hasText: Boolean
            get() = com.example.hinglishpdf.pipeline.segment.Placeholders.strip(encode().text).any { it.isLetter() }

        /** Puts [translated] (placeholder text) in place of this run, keeping the whitespace around it. */
        fun replaceWith(translated: String, tags: List<PlaceholderTag>) {
            val raw = encode().raw
            val decoded = InlineCodec.decode(translated, tags)
            val leading = raw.takeWhile { it.isWhitespace() }
            val trailing = raw.takeLastWhile { it.isWhitespace() }.takeIf { raw.isNotBlank() }.orEmpty()
            val anchor = nodes.firstOrNull { it.parent() != null }
            val replacement = buildList {
                if (leading.isNotEmpty()) add(TextNode(leading))
                addAll(decoded)
                if (trailing.isNotEmpty()) add(TextNode(trailing))
            }
            if (anchor != null) {
                replacement.forEach { anchor.before(it) }
                nodes.forEach { it.remove() }
            } else {
                replacement.forEach { owner.appendChild(it) }
            }
        }

        private companion object {
            val SPACES = Regex(" {2,}")
        }
    }

    /** An attribute's text (an image's alt). */
    class Attribute(
        override val role: ParagraphRole,
        override val ref: SourceRef.Epub,
        val element: Element,
        val name: String,
    ) : EpubUnit {
        override val level: Int = 0
        override val hasText: Boolean get() = element.attr(name).any { it.isLetter() }

        override fun encode(): Encoded {
            val builder = PlaceholderTextBuilder()
            builder.text(element.attr(name).replace(Regex("\\s+"), " "))
            val (raw, tags) = builder.result()
            return Encoded(raw, raw.trim(), tags)
        }

        fun replaceWith(translated: String, tags: List<PlaceholderTag>) {
            element.attr(name, InlineCodec.decode(translated, tags).joinToString("") { (it as? TextNode)?.text() ?: it.outerHtml() })
        }
    }
}
