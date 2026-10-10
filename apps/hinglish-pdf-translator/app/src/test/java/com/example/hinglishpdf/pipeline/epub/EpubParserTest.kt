package com.example.hinglishpdf.pipeline.epub

import com.adobe.epubcheck.api.EPUBLocation
import com.adobe.epubcheck.api.EpubCheck
import com.adobe.epubcheck.messages.Message
import com.adobe.epubcheck.util.DefaultReportImpl
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.PlaceholderException
import com.example.hinglishpdf.pipeline.segment.PlaceholderToken
import com.example.hinglishpdf.pipeline.segment.Placeholders
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    private val css = "p { text-indent: 1em; } .ref { color: blue; }".toByteArray()

    private val chapter1 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">
<head><title>Chapter One</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
<section epub:type="chapter">
<h1>Chapter One</h1>
<p>Mr. Smith walked in. He said <b>hello</b> to <a class="ref" href="chapter2.xhtml">the <i>old</i> man</a>.<a epub:type="noteref" href="#n1">1</a></p>
<p><img src="images/pic.png" alt="A red door"/>The door was open. “Come in,” she said.</p>
<blockquote><p>To be or not to be.</p></blockquote>
<ul><li>First item</li><li>Second item<ul><li>Nested item</li></ul></li></ul>
<table><tr><th>Name</th><td>42</td></tr></table>
<p>Run <code>ls -l</code> with <span translate="no">Acme™</span> today.</p>
<pre>code block stays</pre>
<div translate="no"><p>Keep this English.</p></div>
<script>var s = "Do not translate";</script>
<aside epub:type="footnote" id="n1"><p>A footnote here.</p></aside>
</section>
</body>
</html>"""

    private val chapter2 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head><title>Chapter Two</title></head>
<body><h1>Chapter Two</h1><p>The end.</p></body>
</html>"""

    private val nav = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">
<head><title>Contents</title></head>
<body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol><li><a href="chapter1.xhtml">Chapter One</a></li><li><a href="chapter2.xhtml">Chapter Two</a></li></ol></nav></body>
</html>"""

    private val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="en">
<head><meta name="dtb:uid" content="urn:uuid:12345678-1234-1234-1234-123456789abc"/></head>
<docTitle><text>The Test Book</text></docTitle>
<navMap>
<navPoint id="n1" playOrder="1"><navLabel><text>Chapter One</text></navLabel><content src="chapter1.xhtml"/></navPoint>
<navPoint id="n2" playOrder="2"><navLabel><text>Chapter Two</text></navLabel><content src="chapter2.xhtml"/></navPoint>
</navMap>
</ncx>"""

    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:12345678-1234-1234-1234-123456789abc</dc:identifier>
<dc:title>The Test Book</dc:title>
<dc:language>en</dc:language>
<dc:creator>A. Author</dc:creator>
<meta property="dcterms:modified">2026-10-10T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml" properties="scripted"/>
<item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
<item id="css" href="style.css" media-type="text/css"/>
<item id="pic" href="images/pic.png" media-type="image/png"/>
</manifest>
<spine toc="ncx"><itemref idref="ch1"/><itemref idref="ch2"/></spine>
</package>"""

    private fun book(): File {
        val file = temp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype); zip.closeEntry()
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            put("OEBPS/toc.ncx", ncx.toByteArray())
            put("OEBPS/nav.xhtml", nav.toByteArray())
            put("OEBPS/chapter1.xhtml", chapter1.toByteArray())
            put("OEBPS/chapter2.xhtml", chapter2.toByteArray())
            put("OEBPS/style.css", css)
            put("OEBPS/images/pic.png", png)
        }
        return file
    }

    private fun epubcheck(file: File): Pair<Int, String> {
        val log = StringBuilder()
        val report = object : DefaultReportImpl(file.name) {
            override fun message(message: Message, location: EPUBLocation?, vararg args: Any?) {
                log.append("${message.severity} ${message.id}: ${message.getMessage(*args)} at $location\n")
                super.message(message, location, *args)
            }
        }
        EpubCheck(file, report).doValidate()
        return report.errorCount + report.fatalErrorCount + report.warningCount to log.toString()
    }

    /** A stand-in "translation": the text upper-cased, placeholders untouched. */
    private fun shout(text: String) = Placeholders.tokenize(text).joinToString("") {
        when (it) {
            is PlaceholderToken.Text -> it.text.uppercase()
            else -> text.substring(it.start, it.end)
        }
    }

    private fun ZipFile.bytes(name: String) = getInputStream(getEntry(name)).use { it.readBytes() }
    private fun ZipFile.text(name: String) = bytes(name).toString(Charsets.UTF_8)

    @Test
    fun `the fixture itself is a valid epub`() {
        val (problems, log) = epubcheck(book())
        assertEquals("EPUBCheck:\n$log", 0, problems)
    }

    @Test
    fun `titles, toc labels, chapter text and alt text are found with their roles`() {
        val doc = EpubParser.parse(book())
        val paragraphs = doc.paragraphs.map { it.role to it.text }
        assertEquals("The Test Book", doc.title)
        assertEquals("en", doc.language)
        assertEquals("A. Author", doc.metadata["creator"])
        assertTrue(doc.hasToc)
        assertEquals(3, doc.pageCount) // two chapters and the nav document

        assertEquals(
            listOf(
                ParagraphRole.TITLE to "The Test Book",           // OPF dc:title
                ParagraphRole.TITLE to "The Test Book",           // NCX docTitle
                ParagraphRole.TOC_LABEL to "Chapter One",         // NCX navLabels
                ParagraphRole.TOC_LABEL to "Chapter Two",
                ParagraphRole.TITLE to "Chapter One",             // chapter 1 <title>
                ParagraphRole.HEADING to "Chapter One",
                ParagraphRole.PARAGRAPH to "Mr. Smith walked in. He said {1}hello{/1} to {2}the {3}old{/3} man{/2}.{4}1{/4}",
                ParagraphRole.PARAGRAPH to "[[IMG_1]]The door was open. “Come in,” she said.",
                ParagraphRole.QUOTE to "To be or not to be.",
                ParagraphRole.LIST_ITEM to "First item",
                ParagraphRole.LIST_ITEM to "Second item",
                ParagraphRole.LIST_ITEM to "Nested item",
                ParagraphRole.TABLE_CELL to "Name",
                ParagraphRole.PARAGRAPH to "Run [[CODE_1]] with [[SPAN_2]] today.",
                ParagraphRole.FOOTNOTE to "A footnote here.",
                ParagraphRole.ALT_TEXT to "A red door",
                ParagraphRole.TITLE to "Chapter Two",
                ParagraphRole.HEADING to "Chapter Two",
                ParagraphRole.PARAGRAPH to "The end.",
                ParagraphRole.TITLE to "Contents",                // nav document, outside the spine
                ParagraphRole.TOC_LABEL to "Contents",
                ParagraphRole.TOC_LABEL to "{1}Chapter One{/1}",
                ParagraphRole.TOC_LABEL to "{1}Chapter Two{/1}",
            ),
            paragraphs,
        )
        assertEquals(1, doc.paragraphs[11].level) // the nested list item
    }

    @Test
    fun `code, pre, scripts and translate=no stay out of the text`() {
        val all = EpubParser.parse(book()).paragraphs.joinToString("\n") { it.text }
        for (hidden in listOf("ls -l", "Acme", "code block stays", "Keep this English", "Do not translate", "42")) {
            assertFalse(hidden, hidden in all)
        }
    }

    @Test
    fun `segments are sentences with their paragraph's xpath and their own tags`() {
        val doc = EpubParser.parse(book())
        val first = doc.segments.filter { it.paragraph == 6 }
        assertEquals(listOf("Mr. Smith walked in.", "He said {1}hello{/1} to {2}the {3}old{/3} man{/2}.{4}1{/4}"), first.map { it.text })
        assertEquals(SourceRef.Epub("OEBPS/chapter1.xhtml", "/html[1]/body[1]/section[1]/p[1]"), first[0].ref)
        assertEquals(listOf(1, 2, 3, 4), first[1].tags.map { it.id })
        assertEquals(emptyList<Int>(), first[0].tags.map { it.id })
        assertEquals(64, first[0].hash.length)
        val alt = doc.segments.single { doc.paragraphs[it.paragraph].role == ParagraphRole.ALT_TEXT }
        assertEquals("alt", (alt.ref as SourceRef.Epub).attribute)
        // Headings, labels and titles are one segment each.
        assertEquals(1, doc.segments.count { it.paragraph == 5 })
    }

    @Test
    fun `rebuilding without translations changes no byte`() {
        val source = book()
        val doc = EpubParser.parse(source)
        val out = temp.newFile("same.epub")
        out.outputStream().use { EpubRebuilder.rebuild(source, it, doc.paragraphs, doc.paragraphs.map { null }, language = null) }
        ZipFile(source).use { a ->
            ZipFile(out).use { b ->
                assertEquals(a.entries().toList().map { it.name }, b.entries().toList().map { it.name })
                for (entry in a.entries()) assertArrayEquals(entry.name, a.bytes(entry.name), b.bytes(entry.name))
            }
        }
    }

    @Test
    fun `a translated epub keeps its markup, assets and validity`() {
        val source = book()
        val doc = EpubParser.parse(source)
        val out = temp.newFile("hindi.epub")
        out.outputStream().use { EpubRebuilder.rebuild(source, it, doc.paragraphs, doc.paragraphs.map { shout(it.text) }, language = "hi") }

        // Read back: every paragraph is the "translation", in the same places.
        val again = EpubParser.parse(out)
        assertEquals(doc.paragraphs.map { shout(it.text) }, again.paragraphs.map { it.text })
        assertEquals(doc.paragraphs.map { it.ref }, again.paragraphs.map { it.ref })
        assertEquals("hi", again.language)

        ZipFile(out).use { zip ->
            val chapter = zip.text("OEBPS/chapter1.xhtml")
            assertTrue(chapter, chapter.contains("""HE SAID <b>HELLO</b> TO <a class="ref" href="chapter2.xhtml">THE <i>OLD</i> MAN</a>.<a epub:type="noteref" href="#n1">1</a>"""))
            assertTrue(chapter.contains("""<img src="images/pic.png" alt="A RED DOOR" />THE DOOR WAS OPEN."""))
            assertTrue(chapter.contains("Run <code>ls -l</code> with <span translate=\"no\">Acme™</span> today.".uppercase().replace("LS -L", "ls -l").replace("<CODE>", "<code>").replace("</CODE>", "</code>").replace("<SPAN TRANSLATE=\"NO\">ACME™</SPAN>", "<span translate=\"no\">Acme™</span>")))
            assertTrue(chapter.contains("<pre>code block stays</pre>"))
            assertTrue(chapter.contains("<div translate=\"no\"><p>Keep this English.</p></div>"))
            assertTrue(chapter.contains("""var s = "Do not translate";"""))
            assertTrue(chapter.contains("""xml:lang="hi" lang="hi""""))
            assertTrue(zip.text("OEBPS/toc.ncx").contains("<text>CHAPTER ONE</text>"))
            assertTrue(zip.text("OEBPS/content.opf").contains("<dc:title>THE TEST BOOK</dc:title>"))
            assertTrue(zip.text("OEBPS/content.opf").contains("<dc:language>hi</dc:language>"))
            assertArrayEquals(css, zip.bytes("OEBPS/style.css"))
            assertArrayEquals(png, zip.bytes("OEBPS/images/pic.png"))
        }
        val (problems, log) = epubcheck(out)
        assertEquals("EPUBCheck:\n$log", 0, problems)
    }

    @Test
    fun `a translation with broken placeholders is refused`() {
        val source = book()
        val doc = EpubParser.parse(source)
        val translations = doc.paragraphs.map { shout(it.text) }.toMutableList()
        translations[6] = "मिस्टर स्मिथ अंदर आए। {1}हैलो{/1} {2}बूढ़े आदमी से{/2}" // {3} and {4} lost
        try {
            temp.newFile("bad.epub").outputStream().use { EpubRebuilder.rebuild(source, it, doc.paragraphs, translations, "hi") }
            fail("accepted a translation without its placeholders")
        } catch (e: PlaceholderException) {
            assertTrue(e.message!!.contains("Missing"))
        }
    }
}
