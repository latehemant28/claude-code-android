package com.example.hinglishpdf.data.epub

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reads an EPUB into structured blocks, and writes a translated copy of it.
 *
 * Text is located the same deterministic way when reading and writing, so
 * translation N always goes back into the exact element it came from. The
 * output keeps every chapter file, stylesheet, image, the table of contents
 * and the list/heading markup; only the text changes. Inline formatting
 * inside a translated run (a bold word mid-sentence, a link) is flattened,
 * because the model rewrites the sentence as a whole.
 *
 * Pure JVM (java.util.zip + jsoup), so it is unit-tested without Android.
 */
object EpubBook {

    data class Content(val title: String?, val blocks: List<DocBlock>)

    fun read(file: File, onChapter: (chapter: Int, chapterCount: Int) -> Unit = { _, _ -> }): Content =
        ZipFile(file).use { zip ->
            val spine = readSpine(zip)
            val blocks = mutableListOf<DocBlock>()
            spine.chapters.forEachIndexed { i, path ->
                val doc = parseChapter(zip, path) ?: return@forEachIndexed
                units(doc).mapTo(blocks) { it.block }
                onChapter(i + 1, spine.chapters.size)
            }
            Content(spine.title, blocks)
        }

    /**
     * Writes [source] to [target] with each block's text replaced by
     * [translations] (aligned with the blocks returned by [read]; null keeps
     * the original text). Chapters and the package are re-labelled as
     * [language] (and right-to-left if [rightToLeft]), so readers pick the
     * right font and shape the script correctly.
     */
    fun writeTranslated(
        source: File,
        target: OutputStream,
        translations: List<String?>,
        language: String = "hi",
        rightToLeft: Boolean = false,
    ) {
        ZipFile(source).use { zip ->
            val spine = readSpine(zip)
            val chapters = spine.chapters.toSet()
            val rewritten = mutableMapOf<String, ByteArray>()
            var index = 0
            for (path in spine.chapters) {
                val doc = parseChapter(zip, path) ?: continue
                for (unit in units(doc)) {
                    val translation = translations.getOrNull(index++)
                    if (translation != null && unit.block.isTranslatable) replaceRun(unit.nodes, translation)
                }
                doc.allElements.firstOrNull { it.localName() == "html" }?.let { html ->
                    html.attr("xml:lang", language)
                    if (html.hasAttr("lang")) html.attr("lang", language) // XHTML 1.1 (EPUB 2) has no "lang"
                    html.attr("dir", if (rightToLeft) "rtl" else "ltr")
                }
                rewritten[path] = serialize(doc).toByteArray(Charsets.UTF_8)
            }
            readXml(zip, spine.opfPath)?.let { opf ->
                opf.allElements.filter { it.localName() == "language" }.forEach { it.text(language) }
                rewritten[spine.opfPath] = serialize(opf).toByteArray(Charsets.UTF_8)
            }
            if (index != translations.size) {
                throw IOException("EPUB changed since it was read ($index blocks, expected ${translations.size}).")
            }

            ZipOutputStream(target).use { out ->
                // The EPUB spec requires "mimetype" first and uncompressed.
                val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                out.putNextEntry(
                    ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = mimetype.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(mimetype) }.value
                    },
                )
                out.write(mimetype)
                out.closeEntry()

                for (entry in zip.entries()) {
                    if (entry.name == "mimetype" || entry.isDirectory) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    val replacement = if (entry.name in chapters || entry.name == spine.opfPath) rewritten[entry.name] else null
                    if (replacement != null) {
                        out.write(replacement)
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
    }

    /** The chapter files in reading (spine) order, as paths inside the zip (for the in-app reader). */
    fun chapterPaths(zip: ZipFile): List<String> = readSpine(zip).chapters

    // ---------------------------------------------------------------- spine

    private data class Spine(val title: String?, val chapters: List<String>, val opfPath: String)

    private fun readSpine(zip: ZipFile): Spine {
        val container = readXml(zip, "META-INF/container.xml")
            ?: throw IOException("Not an EPUB: META-INF/container.xml is missing.")
        val opfPath = container.allElements.firstOrNull { it.localName() == "rootfile" }
            ?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Not an EPUB: no package document.")
        val opf = readXml(zip, opfPath) ?: throw IOException("EPUB package document is missing.")
        val baseDir = opfPath.substringBeforeLast('/', "")

        val items = opf.allElements.filter { it.localName() == "item" }
            .associate { it.attr("id") to it }
        val chapters = opf.allElements.filter { it.localName() == "itemref" }
            .mapNotNull { items[it.attr("idref")] }
            .filter { it.attr("media-type") in setOf("application/xhtml+xml", "text/html") }
            .map { resolve(baseDir, it.attr("href")) }
            .filter { zip.getEntry(it) != null }
            .distinct()
        val title = opf.allElements.firstOrNull { it.localName() == "title" }?.text()?.takeIf { it.isNotBlank() }
        return Spine(title, chapters, opfPath)
    }

    private fun resolve(baseDir: String, href: String): String {
        val decoded = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val parts = ArrayDeque<String>()
        (if (baseDir.isEmpty()) decoded else "$baseDir/$decoded").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun readXml(zip: ZipFile, path: String): Document? {
        val entry = zip.getEntry(path) ?: return null
        val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        return Jsoup.parse(xml, "", Parser.xmlParser())
    }

    private fun parseChapter(zip: ZipFile, path: String): Document? = readXml(zip, path)

    private fun serialize(doc: Document): String {
        doc.outputSettings()
            .prettyPrint(false)
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
        return doc.outerHtml()
    }

    // ---------------------------------------------------------------- blocks

    /** A translatable run of inline content inside one block element. */
    internal class TextRun(val block: DocBlock, val nodes: List<Node>)

    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BLOCK_TAGS = HEADINGS + setOf(
        "p", "li", "ul", "ol", "dl", "dd", "dt", "blockquote", "div", "section", "article",
        "aside", "header", "footer", "main", "nav", "figure", "figcaption", "table", "thead",
        "tbody", "tfoot", "tr", "td", "th", "caption", "address", "center", "hr", "body",
        "pre", "hgroup", "details", "summary",
    )
    private val SKIP_TAGS = setOf("head", "script", "style", "svg", "math", "img", "rt", "rp")

    internal fun units(doc: Document): List<TextRun> {
        val body = doc.allElements.firstOrNull { it.localName() == "body" } ?: return emptyList()
        val out = mutableListOf<TextRun>()
        collect(body, out)
        return out
    }

    private fun collect(element: Element, out: MutableList<TextRun>) {
        if (element.localName() == "pre") {
            val text = element.wholeText()
            if (text.isNotBlank()) out += TextRun(DocBlock(BlockKind.CODE, text.trimEnd()), element.childNodes().toList())
            return
        }
        val run = mutableListOf<Node>()
        var runIndex = 0
        fun flush() {
            val text = run.joinToString("") { textOf(it) }.replace(WHITESPACE, " ").trim()
            if (text.isNotEmpty()) {
                out += TextRun(blockFor(element, text, firstRun = runIndex == 0), run.toList())
                runIndex++
            }
            run.clear()
        }
        for (child in element.childNodes()) {
            if (child is Element) {
                val tag = child.localName()
                when {
                    tag in SKIP_TAGS -> run += child // kept in place, contributes no text
                    tag in BLOCK_TAGS -> {
                        flush()
                        collect(child, out)
                    }
                    else -> run += child
                }
            } else {
                run += child
            }
        }
        flush()
    }

    private fun blockFor(element: Element, text: String, firstRun: Boolean): DocBlock {
        val tag = element.localName()
        if (tag in HEADINGS) return DocBlock(BlockKind.HEADING, text, level = tag[1].digitToInt())
        if (tag == "li" && firstRun) {
            val depth = element.parents().count { it.localName() == "ul" || it.localName() == "ol" } - 1
            val list = element.parent()
            if (list?.localName() == "ol") {
                val position = list.children().filter { it.localName() == "li" }.indexOf(element)
                val start = list.attr("start").toIntOrNull() ?: 1
                return DocBlock(
                    BlockKind.NUMBERED, text,
                    level = depth.coerceAtLeast(0),
                    marker = olMarker(start + position, list.attr("type")),
                )
            }
            return DocBlock(BlockKind.BULLET, text, level = depth.coerceAtLeast(0))
        }
        if (tag == "blockquote" || element.parents().any { it.localName() == "blockquote" }) {
            return DocBlock(BlockKind.QUOTE, text)
        }
        return DocBlock(BlockKind.PARAGRAPH, text)
    }

    private fun olMarker(n: Int, type: String): String = when (type) {
        "a" -> ('a' + (n - 1) % 26) + "."
        "A" -> ('A' + (n - 1) % 26) + "."
        "i" -> roman(n).lowercase() + "."
        "I" -> roman(n) + "."
        else -> "$n."
    }

    private fun roman(n: Int): String {
        var rest = n
        val out = StringBuilder()
        for ((value, numeral) in listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )) {
            while (rest >= value) { out.append(numeral); rest -= value }
        }
        return out.toString()
    }

    /**
     * Swaps a run's text for [translation]. If the whole run is wrapped in one
     * inline element (`<p><strong>Title</strong></p>`), the wrapper is kept.
     */
    private fun replaceRun(nodes: List<Node>, translation: String) {
        var run = nodes
        while (true) {
            val meaningful = run.filter { textOf(it).isNotBlank() }
            val only = meaningful.singleOrNull()
            if (only is Element && only.localName() !in SKIP_TAGS && only.childNodeSize() > 0) {
                run = only.childNodes().toList()
            } else {
                break
            }
        }
        val anchor = run.firstOrNull { it.parent() != null } ?: return
        anchor.before(TextNode(translation))
        run.filter { it !is Element || it.localName() !in SKIP_TAGS }.forEach { it.remove() }
    }

    private fun textOf(node: Node): String = when (node) {
        is TextNode -> node.wholeText
        is Element -> if (node.localName() in SKIP_TAGS) "" else node.wholeText()
        else -> ""
    }

    private fun Element.localName(): String = normalName().substringAfter(':')

    private val WHITESPACE = Regex("\\s+")
}
