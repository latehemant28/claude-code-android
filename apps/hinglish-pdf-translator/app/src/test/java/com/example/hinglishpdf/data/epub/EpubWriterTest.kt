package com.example.hinglishpdf.data.epub

import com.adobe.epubcheck.api.EPUBLocation
import com.adobe.epubcheck.api.EpubCheck
import com.adobe.epubcheck.messages.Message
import com.adobe.epubcheck.util.DefaultReportImpl
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class EpubWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun p(text: String) = DocBlock(BlockKind.PARAGRAPH, text) to text
    private fun h(level: Int, text: String) = DocBlock(BlockKind.HEADING, text, level = level) to text
    private fun bullet(level: Int, text: String) = DocBlock(BlockKind.BULLET, text, level = level) to text
    private fun numbered(level: Int, marker: String, text: String) = DocBlock(BlockKind.NUMBERED, text, level = level, marker = marker) to text

    /** A small translated "PDF": a title page, then two chapters over five pages. */
    private val sections = listOf(
        EpubWriter.Section(1, listOf(h(1, "पुस्तक का नाम"), p("लेखक: A. Writer"))),
        EpubWriter.Section(2, listOf(h(2, "अध्याय 1: शुरुआत"), p("एक ठंडी सुबह थी।"), p("सब देर से आए।"))),
        EpubWriter.Section(
            3,
            listOf(
                p("आगे की बात <और> \"उद्धरण\" & चिह्न।\u0007"), // markup characters and a control character
                bullet(0, "कोट लाओ"),
                bullet(1, "गरम वाला"),
                bullet(1, "ऊनी"),
                numbered(0, "2.", "जल्दी निकलो"),
                numbered(0, "3.", "टिकट रखो"),
                bullet(2, "ज़्यादा गहरा"), // jumps two levels: clamped to one
                DocBlock(BlockKind.QUOTE, "एक उद्धरण") to "एक उद्धरण",
                DocBlock(BlockKind.CODE, "val x = 1") to "val x = 1",
            ),
        ),
        EpubWriter.Section(4, emptyList()), // a page with no text
        EpubWriter.Section(5, listOf(h(2, "अध्याय 2: अंत"), p("समाप्त।"))),
    )

    private fun write(pageMarkers: Boolean = true): File {
        val file = tmp.newFile("book.epub")
        file.outputStream().use {
            EpubWriter.write(
                it, "My Book", sections, pageMarkers,
                fonts = mapOf("NotoSansDevanagari-Regular.ttf" to fontBytes()),
                identifier = "urn:uuid:12345678-1234-1234-1234-123456789abc",
                modified = Instant.parse("2026-10-10T08:30:15.123Z"),
            )
        }
        return file
    }

    /** The real font, so EPUBCheck can verify the embedded font too. */
    private fun fontBytes(): ByteArray =
        File("src/main/assets/fonts/NotoSansDevanagari-Regular.ttf").takeIf { it.isFile }?.readBytes()
            ?: error("run from the app module")

    /** Runs the W3C validator; returns its error + warning count and its messages (with their IDs). */
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

    private fun ZipFile.text(name: String) = getInputStream(getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `is a valid epub 3 according to w3c epubcheck`() {
        val file = write()
        val (status, log) = epubcheck(file)
        assertEquals("EPUBCheck failed:\n$log", 0, status)

    }

    @Test
    fun `zip layout and package document`() {
        ZipFile(write()).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertEquals("application/epub+zip", zip.text("mimetype"))

            val opf = zip.text("OEBPS/content.opf")
            assertTrue(opf.contains("""version="3.0""""))
            assertTrue(opf.contains("<dc:language>hi</dc:language>"))
            assertTrue(opf.contains("<dc:title>My Book</dc:title>"))
            assertTrue(opf.contains("""<meta property="dcterms:modified">2026-10-10T08:30:15Z</meta>"""))
            assertTrue(opf.contains("""properties="nav""""))
            assertTrue(opf.contains("""media-type="font/ttf""""))
        }
    }

    @Test
    fun `chapters start at the top-level headings, with a toc and the original page numbers`() {
        ZipFile(write()).use { zip ->
            // The level-1 heading is used once (the title), so chapters split at level 2.
            val chapters = zip.entries().toList().map { it.name }.filter { it.startsWith("OEBPS/chapter-") }.sorted()
            assertEquals(listOf("OEBPS/chapter-001.xhtml", "OEBPS/chapter-002.xhtml", "OEBPS/chapter-003.xhtml"), chapters)

            val nav = zip.text("OEBPS/nav.xhtml")
            assertTrue(nav.contains("""<a href="chapter-002.xhtml">अध्याय 1: शुरुआत</a>"""))
            assertTrue(nav.contains("""<a href="chapter-003.xhtml">अध्याय 2: अंत</a>"""))
            assertTrue(nav.contains("""<a href="chapter-001.xhtml">पृष्ठ 1</a>"""))
            for (page in 1..5) assertTrue("page $page", nav.contains("#page-$page\">$page</a>"))

            val chapter2 = zip.text("OEBPS/chapter-002.xhtml")
            assertTrue(chapter2.contains("""xml:lang="hi""""))
            assertTrue(chapter2.contains("""id="page-2""""))
            assertTrue(chapter2.contains("""id="page-3""""))
            assertTrue(chapter2.contains("<h2>अध्याय 1: शुरुआत</h2>"))
        }
    }

    @Test
    fun `text is escaped and lists are properly nested`() {
        ZipFile(write()).use { zip ->
            val chapter = zip.text("OEBPS/chapter-002.xhtml")
            assertTrue(chapter.contains("आगे की बात &lt;और&gt; &quot;उद्धरण&quot; &amp; चिह्न।</p>"))
            assertFalse(chapter.contains('\u0007'))
            val compact = chapter.replace("\n", "")
            assertTrue(compact, compact.contains("<ul><li>कोट लाओ<ul><li>गरम वाला</li><li>ऊनी</li></ul></li></ul>"))
            assertTrue(compact, compact.contains("""<ol class="marked"><li><span class="marker">2.</span> जल्दी निकलो</li><li><span class="marker">3.</span> टिकट रखो<ul><li>ज़्यादा गहरा</li></ul></li></ol>"""))
            assertTrue(compact.contains("<blockquote><p>एक उद्धरण</p></blockquote>"))
            assertTrue(compact.contains("<pre>val x = 1</pre>"))

            // Every XHTML file is well-formed XML.
            val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            zip.entries().toList().filter { it.name.endsWith(".xhtml") || it.name.endsWith(".opf") || it.name.endsWith(".ncx") }
                .forEach { parser.parse(zip.getInputStream(it)) }
        }
    }

    @Test
    fun `a right-to-left translation is marked as such, with english labels`() {
        val file = tmp.newFile("arabic.epub")
        file.outputStream().use {
            EpubWriter.write(
                it, "كتاب", listOf(EpubWriter.Section(1, listOf(p("مرحبا بالعالم"))), EpubWriter.Section(2, emptyList())),
                pageMarkers = true, language = "ar", rightToLeft = true,
                labels = com.example.hinglishpdf.data.document.ExportLabels.ENGLISH,
            )
        }
        ZipFile(file).use { zip ->
            val chapter = zip.text("OEBPS/chapter-001.xhtml")
            assertTrue(chapter.contains("""xml:lang="ar" lang="ar" dir="rtl""""))
            assertTrue(zip.text("OEBPS/content.opf").contains("""page-progression-direction="rtl""""))
            assertTrue(zip.text("OEBPS/content.opf").contains("<dc:language>ar</dc:language>"))
            val nav = zip.text("OEBPS/nav.xhtml")
            assertTrue(nav.contains("<h1>Contents</h1>"))
            assertTrue(nav.contains(">Pages 1–2</a>"))
        }
        val (problems, log) = epubcheck(file)
        assertEquals(log, 0, problems)
    }

    @Test
    fun `a book without headings is split every few pages`() {
        val many = (1..45).map { EpubWriter.Section(it, listOf(p("पेज $it"))) }
        val file = tmp.newFile("plain.epub")
        file.outputStream().use { EpubWriter.write(it, "Plain", many, pageMarkers = true) }
        ZipFile(file).use { zip ->
            val nav = zip.text("OEBPS/nav.xhtml")
            assertTrue(nav.contains(">पृष्ठ 1–20</a>"))
            assertTrue(nav.contains(">पृष्ठ 21–40</a>"))
            assertTrue(nav.contains(">पृष्ठ 41–45</a>"))
        }
        val (status, log) = epubcheck(file)
        assertEquals(log, 0, status)
    }
}
