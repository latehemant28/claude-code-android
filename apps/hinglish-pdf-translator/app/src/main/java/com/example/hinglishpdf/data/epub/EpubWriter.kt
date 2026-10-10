package com.example.hinglishpdf.data.epub

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.ExportLabels
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a new EPUB 3 from translated text (used when the source is a PDF,
 * which has no EPUB to copy). The text is split into XHTML chapter files at
 * the book's top-level headings (or every [PAGES_PER_CHAPTER] pages when it
 * has none), with:
 *
 *  - UTF-8 throughout and `xml:lang` set to the target language (plus
 *    `dir="rtl"` for Arabic or Urdu), so readers pick the right font and
 *    shape the script correctly; Noto Sans Devanagari is embedded for
 *    Devanagari languages;
 *  - headings, nested bulleted and numbered lists (original markers kept),
 *    quotes and code as real XHTML;
 *  - a navigation document (table of contents) plus a legacy toc.ncx, and,
 *    for a PDF source, a page list that maps to the original page numbers.
 *
 * Pure JVM (java.util.zip), so it is unit-tested without Android.
 */
object EpubWriter {

    /** The translated text of one original page (PDF) or section (EPUB). */
    class Section(val pageNumber: Int, val blocks: List<Pair<DocBlock, String>>)

    private class Chapter(val file: String, val title: String, val body: String, val pages: List<Int>)

    const val PAGES_PER_CHAPTER = 20
    private const val MAX_PAGES_PER_CHAPTER = 40

    fun write(
        out: OutputStream,
        title: String,
        sections: List<Section>,
        pageMarkers: Boolean,
        fonts: Map<String, ByteArray> = emptyMap(),
        language: String = "hi",
        rightToLeft: Boolean = false,
        labels: ExportLabels = ExportLabels.HINDI,
        identifier: String = "urn:uuid:${UUID.randomUUID()}",
        modified: Instant = Instant.now(),
    ) {
        val dir = if (rightToLeft) "rtl" else "ltr"
        val chapters = chapters(sections, pageMarkers, language, dir, labels)
        ZipOutputStream(out).use { zip ->
            // The EPUB spec requires "mimetype" first and uncompressed.
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()

            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))

            put("META-INF/container.xml", CONTAINER)
            put("OEBPS/content.opf", opf(title, language, dir, identifier, modified, chapters, fonts.keys))
            put("OEBPS/nav.xhtml", nav(title, language, dir, labels, chapters, pageMarkers))
            put("OEBPS/toc.ncx", ncx(title, identifier, chapters))
            put("OEBPS/style.css", css(fonts.keys))
            fonts.forEach { (name, bytes) -> put("OEBPS/fonts/$name", bytes) }
            chapters.forEach { put("OEBPS/${it.file}", it.body) }
        }
    }

    // ------------------------------------------------------------ chapters

    private fun chapters(
        sections: List<Section>,
        pageMarkers: Boolean,
        language: String,
        dir: String,
        labels: ExportLabels,
    ): List<Chapter> {
        val chapterLevel = chapterHeadingLevel(sections)
        // Group sections; a top-level heading starts a new chapter.
        val groups = mutableListOf<MutableList<Pair<Int, List<Pair<DocBlock, String>>>>>()
        val titles = mutableListOf<String?>()
        var pagesInGroup = 0

        fun startGroup(title: String?) {
            groups += mutableListOf<Pair<Int, List<Pair<DocBlock, String>>>>()
            titles += title
            pagesInGroup = 0
        }

        for (section in sections) {
            val headingAt = if (chapterLevel == null) -1 else section.blocks.indexOfFirst { (b, _) ->
                b.kind == BlockKind.HEADING && b.level == chapterLevel
            }
            val full = groups.isEmpty() ||
                (chapterLevel == null && pagesInGroup >= PAGES_PER_CHAPTER) ||
                pagesInGroup >= MAX_PAGES_PER_CHAPTER
            when {
                headingAt >= 0 -> {
                    // Text before the heading still belongs to the previous chapter.
                    if (headingAt > 0) {
                        if (groups.isEmpty()) startGroup(null)
                        groups.last() += section.pageNumber to section.blocks.subList(0, headingAt)
                    }
                    startGroup(section.blocks[headingAt].second)
                    // The page marker goes with the first part of the page.
                    val page = if (headingAt > 0) -1 else section.pageNumber
                    groups.last() += page to section.blocks.subList(headingAt, section.blocks.size)
                }
                full -> {
                    startGroup(null)
                    groups.last() += section.pageNumber to section.blocks
                }
                else -> groups.last() += section.pageNumber to section.blocks
            }
            pagesInGroup++
        }
        if (groups.isEmpty()) startGroup(null)

        // A part numbered -1 continues a page whose marker went with the part before it.
        return groups.mapIndexed { i, parts ->
            val file = "chapter-%03d.xhtml".format(i + 1)
            val pages = parts.map { it.first }.filter { it > 0 }
            val title = titles[i] ?: pageRangeTitle(parts, i, labels)
            val body = StringBuilder()
            for ((page, blocks) in parts) {
                if (pageMarkers && page > 0) {
                    body.append("<span epub:type=\"pagebreak\" role=\"doc-pagebreak\" id=\"page-$page\" aria-label=\"$page\"></span>\n")
                }
                body.append(XhtmlBlocks.render(blocks))
            }
            if (body.isEmpty()) body.append("<p></p>\n")
            Chapter(file, title, page(clean(title), language, dir, body.toString()), pages)
        }
    }

    /**
     * The highest heading level that marks out a sensible number of chapters
     * (a book title used once, or a heading on every page, does not).
     */
    private fun chapterHeadingLevel(sections: List<Section>): Int? {
        val headings = sections.flatMap { s -> s.blocks.map { it.first }.filter { it.kind == BlockKind.HEADING } }
        val most = sections.size / 2 + 2
        return headings.map { it.level }.distinct().sorted().firstOrNull { level ->
            headings.count { it.level == level } in 2..most
        }
    }

    /** "Pages 21–40" ("पृष्ठ 21–40") for a chapter that has no heading of its own. */
    private fun pageRangeTitle(
        parts: List<Pair<Int, List<Pair<DocBlock, String>>>>,
        index: Int,
        labels: ExportLabels,
    ): String {
        val pages = parts.map { it.first }.filter { it > 0 }
        if (pages.isEmpty()) return labels.part(index + 1)
        return labels.pages(pages.first(), pages.last())
    }

    // ---------------------------------------------------------------- files

    private fun page(title: String, language: String, dir: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language" lang="$language" dir="$dir">
<head>
<meta charset="UTF-8"/>
<title>${XhtmlBlocks.escape(title)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
$body</body>
</html>
"""

    private fun opf(
        title: String,
        language: String,
        dir: String,
        identifier: String,
        modified: Instant,
        chapters: List<Chapter>,
        fonts: Set<String>,
    ): String {
        val manifest = StringBuilder()
        manifest.append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""").append('\n')
        manifest.append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""").append('\n')
        manifest.append("""    <item id="css" href="style.css" media-type="text/css"/>""").append('\n')
        fonts.forEachIndexed { i, name ->
            manifest.append("""    <item id="font-${i + 1}" href="fonts/${XhtmlBlocks.escape(name)}" media-type="font/ttf"/>""").append('\n')
        }
        chapters.forEachIndexed { i, c ->
            manifest.append("""    <item id="c${i + 1}" href="${c.file}" media-type="application/xhtml+xml"/>""").append('\n')
        }
        val spine = chapters.indices.joinToString("\n") { """    <itemref idref="c${it + 1}"/>""" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="$language">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">${XhtmlBlocks.escape(identifier)}</dc:identifier>
    <dc:title>${XhtmlBlocks.escape(clean(title))}</dc:title>
    <dc:language>$language</dc:language>
    <meta property="dcterms:modified">${modified.truncatedTo(ChronoUnit.SECONDS)}</meta>
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine toc="ncx" page-progression-direction="$dir">
$spine
  </spine>
</package>
"""
    }

    private fun nav(
        title: String,
        language: String,
        dir: String,
        labels: ExportLabels,
        chapters: List<Chapter>,
        pageMarkers: Boolean,
    ): String {
        val toc = chapters.joinToString("\n") {
            """      <li><a href="${it.file}">${XhtmlBlocks.escape(clean(it.title))}</a></li>"""
        }
        val pageList = if (!pageMarkers) {
            ""
        } else {
            val items = chapters.flatMap { c -> c.pages.map { p -> """      <li><a href="${c.file}#page-$p">$p</a></li>""" } }
            if (items.isEmpty()) "" else "\n  <nav epub:type=\"page-list\" hidden=\"hidden\">\n    <ol>\n${items.joinToString("\n")}\n    </ol>\n  </nav>"
        }
        return page(
            clean(title),
            language,
            dir,
            """  <nav epub:type="toc" id="toc">
    <h1>${XhtmlBlocks.escape(labels.contents)}</h1>
    <ol>
$toc
    </ol>
  </nav>$pageList
""",
        )
    }

    private fun ncx(title: String, identifier: String, chapters: List<Chapter>): String {
        val points = chapters.mapIndexed { i, c ->
            """    <navPoint id="np-${i + 1}" playOrder="${i + 1}">
      <navLabel><text>${XhtmlBlocks.escape(clean(c.title))}</text></navLabel>
      <content src="${c.file}"/>
    </navPoint>"""
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${XhtmlBlocks.escape(identifier)}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${XhtmlBlocks.escape(clean(title))}</text></docTitle>
  <navMap>
$points
  </navMap>
</ncx>
"""
    }

    private fun css(fonts: Set<String>): String {
        val faces = fonts.joinToString("") { name ->
            val weight = if (name.contains("Bold", ignoreCase = true)) "bold" else "normal"
            "@font-face {\n  font-family: \"Noto Sans Devanagari\";\n  font-weight: $weight;\n  font-style: normal;\n  src: url(\"fonts/$name\");\n}\n"
        }
        return faces + """
body {
  font-family: "Noto Sans Devanagari", sans-serif;
  line-height: 1.6;
  margin: 0 0.5em;
}
h1, h2, h3, h4, h5, h6 { line-height: 1.35; margin: 1.2em 0 0.5em; }
p { margin: 0 0 0.7em; }
ul, ol { margin: 0 0 0.7em; padding-left: 1.4em; }
li { margin: 0.2em 0; }
ol.marked { list-style: none; padding-left: 1.4em; }
ol.marked > li > .marker { display: inline-block; min-width: 1.6em; margin-left: -1.6em; }
blockquote { margin: 0 0 0.7em 1em; padding-left: 0.8em; border-left: 3px solid #bbbbbb; font-style: italic; }
pre { white-space: pre-wrap; font-family: monospace; font-size: 0.85em; }
"""
    }

    /** XML 1.0 forbids most control characters, which PDF text sometimes contains. */
    internal fun clean(text: String): String = XhtmlBlocks.clean(text)

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
}

/** Blocks → XHTML: headings, paragraphs, properly nested lists, quotes, code. */
internal object XhtmlBlocks {

    fun render(blocks: List<Pair<DocBlock, String>>): String {
        val out = StringBuilder()
        var i = 0
        while (i < blocks.size) {
            val kind = blocks[i].first.kind
            var j = i
            when (kind) {
                BlockKind.BULLET, BlockKind.NUMBERED -> {
                    while (j < blocks.size && blocks[j].first.kind.let { it == BlockKind.BULLET || it == BlockKind.NUMBERED }) j++
                    list(blocks.subList(i, j), out)
                }
                BlockKind.QUOTE -> {
                    while (j < blocks.size && blocks[j].first.kind == BlockKind.QUOTE) j++
                    out.append("<blockquote>\n")
                    blocks.subList(i, j).forEach { (_, text) -> out.append("<p>").append(escape(text)).append("</p>\n") }
                    out.append("</blockquote>\n")
                }
                else -> {
                    j = i + 1
                    val (block, text) = blocks[i]
                    when (kind) {
                        BlockKind.HEADING -> {
                            val h = "h${block.level.coerceIn(1, 6)}"
                            out.append("<$h>").append(escape(text)).append("</$h>\n")
                        }
                        BlockKind.CODE -> out.append("<pre>").append(escape(text)).append("</pre>\n")
                        else -> out.append("<p>").append(escape(text)).append("</p>\n")
                    }
                }
            }
            i = j
        }
        return out.toString()
    }

    /**
     * Flat list items with depths → nested `<ul>`/`<ol>`: a deeper item opens
     * a list inside the open `<li>`, as XHTML requires. Numbered items keep
     * their original marker ("2.", "a)", "(iv)").
     */
    private fun list(items: List<Pair<DocBlock, String>>, out: StringBuilder) {
        val open = ArrayDeque<String>() // tags of open lists, each with an open <li>
        for ((block, text) in items) {
            val tag = if (block.kind == BlockKind.NUMBERED) "ol" else "ul"
            val level = block.level.coerceIn(0, open.size) // at most one level deeper
            while (open.size > level + 1) out.append("</li>\n</${open.removeLast()}>\n")
            if (open.size == level + 1) {
                if (open.last() == tag) {
                    out.append("</li>\n")
                } else {
                    out.append("</li>\n</${open.removeLast()}>\n")
                }
            }
            if (open.size == level) {
                out.append(if (tag == "ol") "<ol class=\"marked\">\n" else "<ul>\n")
                open.addLast(tag)
            }
            out.append("<li>")
            if (tag == "ol") out.append("<span class=\"marker\">").append(escape(block.marker)).append("</span> ")
            out.append(escape(text))
        }
        while (open.isNotEmpty()) out.append("</li>\n</${open.removeLast()}>\n")
    }

    fun escape(text: String): String {
        val cleaned = clean(text)
        val out = StringBuilder(cleaned.length + 16)
        for (c in cleaned) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Drops characters XML 1.0 does not allow (control characters, unpaired surrogates). */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ok = cp == 0x9 || cp == 0xA || cp == 0xD ||
                cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
            if (ok) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
