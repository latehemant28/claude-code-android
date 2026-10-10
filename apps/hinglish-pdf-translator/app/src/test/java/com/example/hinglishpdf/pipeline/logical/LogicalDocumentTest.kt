package com.example.hinglishpdf.pipeline.logical

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.assemble.Hyphenation
import com.example.hinglishpdf.pipeline.epub.EpubParser
import com.example.hinglishpdf.pipeline.pdf.PdfLayout
import com.example.hinglishpdf.pipeline.pdf.page
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.Placeholders
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TableCellRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stage 2: one document-wide stream of typed elements with source spans. */
class LogicalDocumentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val config = PipelineConfig()

    private fun pdf(pages: List<com.example.hinglishpdf.pipeline.pdf.PdfPageGlyphs>): LogicalDocument {
        val layout = PdfLayout.analyze(pages, config)
        val paragraphs = DocumentAssembler(config, layout.hyphenation).assemble(layout.paragraphs)
        return LogicalStructure.build(paragraphs, 4)
    }

    private fun LogicalDocument.texts() = leaves().map { paragraphOf(it)!!.text }

    @Test
    fun `a paragraph across a page break has one span per page, with characters, page and box`() {
        val doc = pdf(
            listOf(
                page(1) { lines(listOf("The water rose all through the night until the", "lower streets of the old town were deep under"), 50f, 680f) },
                page(2) { lines(listOf("water, and the people carried what they could", "up the hill to the church. Nobody slept."), 50f, 100f) },
            ),
        )
        val p = doc.paragraphOf(doc.leaves().single())!!
        val spans = p.spans
        assertEquals(listOf(1, 2), spans.map { (it.ref as SourceRef.Pdf).page })
        assertEquals("The water rose all through the night until the lower streets of the old town were deep under", p.text.substring(spans[0].start, spans[0].end))
        assertEquals("water, and the people carried what they could up the hill to the church. Nobody slept.", p.text.substring(spans[1].start, spans[1].end))
        val box1 = (spans[0].ref as SourceRef.Pdf).box
        assertTrue("page 1 box $box1", box1.y0 > 660f && box1.y1 < 700f)
        assertEquals(p.text.length, spans.last().end)
    }

    @Test
    fun `a word hyphenated across the break - its spans meet where the hyphen was`() {
        val paragraphs = DocumentAssembler(config, Hyphenation.NONE).assemble(
            listOf(pdfParagraph("The strange contrap-", 1), pdfParagraph("tions stood still.", 2)),
        )
        val p = paragraphs.single()
        assertEquals("The strange contraptions stood still.", p.text)
        assertEquals(listOf("The strange contrap", "tions stood still."), p.spans.map { p.text.substring(it.start, it.end) })
    }

    @Test
    fun `a soft hyphen at a page break always joins`() {
        val p = DocumentAssembler(config, Hyphenation.NONE).assemble(listOf(pdfParagraph("It was a beauti­", 1), pdfParagraph("ful day.", 2))).single()
        assertEquals("It was a beautiful day.", p.text)
    }

    @Test
    fun `no join across a font-tier change, even into lower case`() {
        val a = pdfParagraph("and so the story went on", 1)
        assertEquals(2, DocumentAssembler(config).assemble(listOf(a, pdfParagraph("in smaller print", 2).copy(tier = -1))).size)
        assertEquals(1, DocumentAssembler(config).assemble(listOf(a, pdfParagraph("in the same print", 2))).size)
    }

    @Test
    fun `lists are typed elements with nested lists inside their items, across a page break`() {
        val doc = pdf(
            listOf(
                page(1) {
                    text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                    text("You will need these things for the walk:", 50f, 100f)
                    text("• a coat", 50f, 112f)
                    text("◦ with a hood", 70f, 124f)
                    text("1", 295f, 785f, size = 9f)
                },
                page(2) {
                    text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                    text("• a lamp", 50f, 100f)
                    text("2", 295f, 785f, size = 9f)
                },
            ),
        )
        assertEquals(listOf(ElementType.PARAGRAPH, ElementType.LIST), doc.elements.map { it.type })
        val list = doc.elements[1]
        assertEquals(listOf(ElementType.LIST_ITEM, ElementType.LIST_ITEM), list.children.map { it.type })
        val nested = list.children[0].children.single()
        assertEquals(ElementType.LIST, nested.type)
        assertEquals("with a hood", doc.paragraphOf(nested.children.single())!!.text)
        assertEquals("a lamp", doc.paragraphOf(list.children[1])!!.text)
        assertEquals(4, doc.furniture.size)
    }

    @Test
    fun `a table is rows of cells, never flattened`() {
        val doc = pdf(
            listOf(
                page {
                    text("Some text before the table of people in the town.", 50f, 80f)
                    val rows = listOf(listOf("Name", "Age", "City"), listOf("Ravi", "34", "Pune"), listOf("Asha", "29", "Delhi"))
                    rows.forEachIndexed { r, row -> row.forEachIndexed { c, cell -> text(cell, 50f + c * 120f, 110f + r * 14f) } }
                },
            ),
        )
        val table = doc.elements.single { it.type == ElementType.TABLE }
        assertEquals(3, table.children.size)
        assertEquals(
            listOf(listOf("Name", "Age", "City"), listOf("Ravi", "34", "Pune"), listOf("Asha", "29", "Delhi")),
            table.children.map { row -> row.children.map { doc.paragraphOf(it)!!.text } },
        )
        assertEquals(TableCellRef(0, 2, 1), doc.paragraphOf(table.children[2].children[1])!!.cell)
    }

    @Test
    fun `epub - lists, tables from the markup, and a paragraph across spine files with xpath spans`() {
        val doc = EpubParser.parse(epub())
        val logical = LogicalDocument.of(doc)
        val joined = doc.paragraphs.single { it.parts.size == 2 }
        assertEquals(
            listOf("OEBPS/a.xhtml" to "The story goes on in the", "OEBPS/b.xhtml" to "next file without a break."),
            joined.spans.map { (it.ref as SourceRef.Epub).file to joined.text.substring(it.start, it.end) },
        )
        val table = logical.elements.single { it.type == ElementType.TABLE }
        assertEquals(listOf(listOf("Name", "City"), listOf("Ravi", "Pune")), table.children.map { r -> r.children.map { logical.paragraphOf(it)!!.text } })
        val list = logical.elements.single { it.type == ElementType.LIST }
        assertEquals(listOf("One", "Two"), list.children.map { logical.paragraphOf(it)!!.text })
        assertEquals(1, list.children[1].children.single().children.size) // "Two" holds a nested list
    }

    @Test
    fun `the element texts are the book's body text, without furniture`() {
        val pages = (1..3).map { n ->
            page(n) {
                text("A BOOK OF RIVERS", 250f, 30f, size = 9f)
                when (n) {
                    1 -> {
                        text("Chapter One", 50f, 90f, size = 18f)
                        lines(listOf("The water rose all through the night until the", "lower streets of the old town were deep under"), 50f, 680f)
                    }
                    2 -> {
                        lines(listOf("water, and the people carried what they could", "up the hill to the church. Nobody slept."), 50f, 100f)
                        text("• a coat", 50f, 140f)
                    }
                    else -> lines(listOf("In the morning the water went down again."), 50f, 100f)
                }
                text("$n", 295f, 785f, size = 9f)
            }
        }
        val layout = PdfLayout.analyze(pages, config)
        val doc = pdf(pages)
        fun words(texts: List<String>) = texts.flatMap { Placeholders.strip(it).split(Regex("\\s+")) }.filter { it.isNotEmpty() }.sorted()
        val body = layout.layout!!.pages.flatMap { p -> p.blocks.map { it.text.removePrefix("• ") } }
        assertEquals(words(body), words(doc.texts()))
        assertFalse(doc.texts().any { "RIVERS" in it || it.trim().matches(Regex("\\d+")) })
    }

    private fun pdfParagraph(text: String, page: Int) =
        ParsedParagraph(ParagraphRole.PARAGRAPH, text, emptyList(), SourceRef.Pdf(page, Box(50f, 100f, 500f, 200f)))

    private fun epub(): File {
        fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en"><head><title>T</title></head><body>$body</body></html>"""
        val file = temp.newFile("b.epub")
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
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">x</dc:identifier><dc:title>B</dc:title><dc:language>en</dc:language></metadata>
<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            put("OEBPS/a.xhtml", xhtml("<ul><li>One</li><li>Two<ul><li>Two point one</li></ul></li></ul><table><tr><th>Name</th><th>City</th></tr><tr><td>Ravi</td><td>Pune</td></tr></table><p>The story goes on in the</p>"))
            put("OEBPS/b.xhtml", xhtml("<p>next file without a break.</p>"))
        }
        return file
    }
}
