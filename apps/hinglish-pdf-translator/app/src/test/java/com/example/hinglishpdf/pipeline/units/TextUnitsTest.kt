package com.example.hinglishpdf.pipeline.units

import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.project.ProjectFiles
import com.example.hinglishpdf.data.translate.PipelinePages
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.epub.EpubParser
import com.example.hinglishpdf.pipeline.pdf.OutlineEntry
import com.example.hinglishpdf.pipeline.pdf.PdfLayout
import com.example.hinglishpdf.pipeline.pdf.page
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.google.gson.Gson
import net.sf.okapi.lib.xliff2.reader.XLIFFReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/** Stage 3: text units, skeleton, contents links and XLIFF 2.1 that other tools can open. */
class TextUnitsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = PipelineConfig()

    // ------------------------------------------------------------------ fixtures

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en"><head><title>Book</title></head><body>$body</body></html>"""

    private fun epub(): File {
        val file = temp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype); zip.closeEntry()
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><dc:title>A Book of Rivers</dc:title><dc:language>en</dc:language></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="front" href="text/front.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine toc="ncx"><itemref idref="front"/><itemref idref="c1"/></spine></package>""")
            put("OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>A Book of Rivers</text></docTitle>
<navMap><navPoint id="n1" playOrder="1"><navLabel><text>The Flood</text></navLabel><content src="text/ch1.xhtml#flood"/></navPoint></navMap></ncx>""")
            put("OEBPS/nav.xhtml", xhtml("""<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml#flood">The Flood</a></li><li><a href="text/ch1.xhtml#after">After the Water</a></li></ol></nav>"""))
            put("OEBPS/text/front.xhtml", xhtml("<h1>For My Mother</h1><p>Praise for this book: “A <i>wonderful</i> read.”</p>"))
            put(
                "OEBPS/text/ch1.xhtml",
                xhtml(
                    """<h1 id="flood">The Flood</h1><p>The river rose in <b>one</b> night, see <a href="#n1">the note</a>.<img src="x.png" alt="A map of the river"/></p>""" +
                        """<ul><li>A coat</li></ul><table><tr><td>Year</td><td>1910</td></tr></table>""" +
                        """<h2 id="after">After the Water</h2><p>Then it went down.</p><aside epub:type="footnote" id="n1"><p>The note itself.</p></aside>""",
                ),
            )
        }
        return file
    }

    private fun pdfDoc(outline: List<OutlineEntry> = emptyList()): ParsedDocument {
        val pages = listOf(
            page(1) {
                text("Contents", 50f, 80f, size = 18f)
                text("Chapter One ........ 2", 50f, 120f)
                text("Chapter Two ........ 3", 50f, 140f)
            },
            page(2) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                text("Chapter One", 50f, 90f, size = 18f)
                lines(listOf("The water rose all through the night."), 50f, 130f)
                text("2", 295f, 785f, size = 9f)
            },
            page(3) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                text("Chapter Two", 50f, 90f, size = 18f)
                lines(listOf("In the morning the water went down."), 50f, 130f)
                text("3", 295f, 785f, size = 9f)
            },
            page(4) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                lines(listOf("Nothing more to tell."), 50f, 130f)
                text("1910", 50f, 200f)
                text("4", 295f, 785f, size = 9f)
            },
        )
        val layout = PdfLayout.analyze(pages, config)
        val paragraphs = DocumentAssembler(config, layout.hyphenation).assemble(layout.paragraphs)
        val metadata = if (outline.isEmpty()) emptyMap() else mapOf("outline" to Gson().toJson(outline))
        return ParsedDocument(null, "en", metadata, false, 4, emptyList(), false, paragraphs, Segmenter.segment(paragraphs))
    }

    private fun validateWithSchemas(xliff: String) {
        val dir = File(javaClass.classLoader!!.getResource("xliff/xliff_core_2.0.xsd")!!.toURI()).parentFile
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        val schema = factory.newSchema(arrayOf(StreamSource(File(dir, "xliff_core_2.0.xsd")), StreamSource(File(dir, "metadata.xsd"))))
        schema.newValidator().validate(StreamSource(StringReader(xliff)))
    }

    /** Okapi's XLIFF 2 reader (what the Okapi plugin for OmegaT uses), with maximal validation. */
    private fun readWithOkapi(xliff: String): Map<String, Pair<String, String?>> {
        // The JDK's own schema factory: one on the test classpath lacks a property Okapi sets.
        System.setProperty(
            "javax.xml.validation.SchemaFactory:" + XMLConstants.W3C_XML_SCHEMA_NS_URI,
            "com.sun.org.apache.xerces.internal.jaxp.validation.XMLSchemaFactory",
        )
        val out = mutableMapOf<String, Pair<String, String?>>()
        XLIFFReader(XLIFFReader.VALIDATION_MAXIMAL).use { reader ->
            reader.open(xliff)
            while (reader.hasNext()) {
                val event = reader.next()
                if (event.isUnit) {
                    val unit = event.unit
                    val source = unit.segments.joinToString("") { it.source.plainText }
                    val target = unit.segments.map { it.target?.plainText }.let { t -> if (t.all { it != null }) t.joinToString("") else null }
                    out[unit.id] = source to target
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `every text element is a unit, front matter included, with its inline codes`() {
        val doc = EpubParser.parse(epub())
        val units = TextUnitExtractor.extract(doc, "epub", "book.epub", config).units
        val texts = units.map { it.plain }
        for (expected in listOf("A Book of Rivers", "For My Mother", "Praise for this book: “A wonderful read.”", "The note itself.", "A map of the river", "A coat")) {
            assertTrue("$expected in $texts", expected in texts)
        }
        val body = units.single { it.plain.startsWith("The river rose") }
        assertEquals("The river rose in {1}one{/1} night, see {2}the note{/2}.[[IMG_3]]", body.text)
        assertEquals(listOf("b", "a", "IMG"), body.codes.map { it.label })
        // Lists and tables are groups.
        assertEquals(1, units.single { it.plain == "A coat" }.groups.size)
        assertEquals(2, units.single { it.plain == "Year" }.groups.size)
    }

    @Test
    fun `epub contents entries are linked to the headings they point to`() {
        val units = TextUnitExtractor.extract(EpubParser.parse(epub()), "epub", "book.epub", config).units
        val flood = units.single { it.kind == UnitKind.HEADING && it.plain == "The Flood" }.id
        val after = units.single { it.kind == UnitKind.HEADING && it.plain == "After the Water" }.id
        val entries = units.filter { it.kind == UnitKind.TOC_ENTRY }
        assertEquals(mapOf("The Flood" to flood, "After the Water" to after), entries.filter { it.plain != "The Flood" || it.linkedTo == flood }.associate { it.plain to it.linkedTo })
        assertTrue(entries.filter { it.plain == "The Flood" }.all { it.linkedTo == flood }) // nav and NCX alike
    }

    @Test
    fun `a printed contents page is linked to the headings it names, and bookmarks to their headings`() {
        val extraction = TextUnitExtractor.extract(
            pdfDoc(listOf(OutlineEntry("Chapter One", 2, 0), OutlineEntry("Chapter Two", 3, 0))),
            "pdf", "book.pdf", config,
        )
        val units = extraction.units
        val one = units.single { it.kind == UnitKind.HEADING && it.plain == "Chapter One" }.id
        val two = units.single { it.kind == UnitKind.HEADING && it.plain == "Chapter Two" }.id
        val entries = units.filter { it.kind == UnitKind.TOC_ENTRY }
        assertEquals(listOf("Chapter One ........ 2" to one, "Chapter Two ........ 3" to two), entries.map { it.plain to it.linkedTo })
        assertEquals(listOf(one, two), extraction.skeleton.outline.map { it.unit })
    }

    @Test
    fun `running heads are one unit per text, page numbers stay in the skeleton`() {
        val extraction = TextUnitExtractor.extract(pdfDoc(), "pdf", "book.pdf", config)
        val heads = extraction.units.filter { it.kind == UnitKind.RUNNING_HEAD }
        assertEquals(listOf("A BOOK OF RIVERS"), heads.map { it.plain })
        val furniture = extraction.skeleton.furniture
        assertEquals(3, furniture.count { it.unit == heads.single().id })
        assertTrue(furniture.filter { it.text.trim().all(Char::isDigit) }.all { it.unit == null })
        // Every other unit has a slot with its source spans.
        assertTrue(extraction.units.filter { it.kind != UnitKind.RUNNING_HEAD }.all { extraction.skeleton.slots.getValue(it.id).isNotEmpty() })
        assertEquals(extraction.skeleton, Skeleton.fromJson(extraction.skeleton.toJson()))
        // Numbers alone are units too, marked not to translate.
        assertFalse(extraction.units.single { it.plain == "1910" }.translate)
    }

    @Test
    fun `the xliff is valid against the OASIS schemas, loads in Okapi, and reads back unchanged`() {
        val doc = EpubParser.parse(epub())
        val units = TextUnitExtractor.extract(doc, "epub", "book.epub", config).units
        val body = units.single { it.plain.startsWith("The river rose") }
        val targets = mapOf(body.id to "Nadi {1}ek{/1} raat mein badhi, {2}note{/2} dekho.[[IMG_3]]")
        val xliff = XliffWriter.write(units, "book.epub", "skeleton.json", "en", "hi", targets)

        validateWithSchemas(xliff)
        val okapi = readWithOkapi(xliff)
        assertEquals(units.size, okapi.size)
        assertEquals("The river rose in one night, see the note." to "Nadi ek raat mein badhi, note dekho.", okapi.getValue(body.id))

        val back = XliffReader.read(xliff).associateBy { it.id }
        for (u in units) {
            val r = back.getValue(u.id)
            assertEquals(u.text, r.text)
            assertEquals(u.codes, r.codes)
            assertEquals(u.translate, r.translate)
            assertEquals(u.linkedTo, r.linkedTo)
            assertEquals(u.kind.name.lowercase(), r.kind)
        }
        assertEquals(targets[body.id], back.getValue(body.id).target)
        assertNull(back.values.first { it.id != body.id }.target)
    }

    @Test
    fun `inline codes - paired as pc with original data, standalone as ph, broken nesting as sc and ec`() {
        val codes = listOf(
            PlaceholderTag(1, PlaceholderTag.Kind.PAIRED, "a", "<a href=\"#n1\"></a>"),
            PlaceholderTag(2, PlaceholderTag.Kind.STANDALONE, "SUP", "1"),
        )
        assertEquals(
            """see <pc id="1" dataRefStart="d1" dataRefEnd="d1e" type="link">the note</pc><ph id="2" dataRef="d2" equiv="1"/>""",
            XliffWriter.inline("see {1}the note{/1}[[SUP_2]]", codes),
        )
        val unit = TextUnit("u1", "e1", UnitKind.PARAGRAPH, 0, "see {1}the note{/1}[[SUP_2]]", codes, true)
        val xliff = XliffWriter.write(listOf(unit), "b", "s.json", "en", "hi", mapOf("u1" to "{1}note dekho[[SUP_2]]")) // never closed
        assertTrue(xliff.contains("""<sc id="1" dataRef="d1" isolated="yes" type="link"/>"""))
        validateWithSchemas(xliff)
        assertTrue(xliff.contains("""<data id="d1">&lt;a href="#n1"&gt;</data><data id="d1e">&lt;/a&gt;</data>"""))
    }

    @Test
    fun `saved translations become targets, a paragraph across pages only when all its parts are done`() {
        val doc = pdfDoc()
        val units = TextUnitExtractor.extract(doc, "pdf", "book.pdf", config).units
        val pages = PipelinePages.build(1L, doc, DocFormat.PDF, 350)
        val translated: List<PageEntity> = pages.map { p -> p.copy(translations = p.sourceBlocks.map { "HI(" + it.text + ")" }) }
        val targets = ProjectFiles.targets(units, doc, translated)
        val heading = units.single { it.kind == UnitKind.HEADING && it.plain == "Chapter One" }
        assertEquals("HI(Chapter One)", targets[heading.id])
        assertEquals(units.count { it.kind != UnitKind.RUNNING_HEAD }, targets.size)
        // Page 3 not done: its units have no target.
        val partial = translated.map { if (it.pageNumber == 3) it.copy(translations = null) else it }
        assertNull(ProjectFiles.targets(units, doc, partial)[units.single { it.plain == "Chapter Two" && it.kind == UnitKind.HEADING }.id])
        validateWithSchemas(XliffWriter.write(units, "book.pdf", "skeleton.json", "en", "hi", targets))
    }
}
