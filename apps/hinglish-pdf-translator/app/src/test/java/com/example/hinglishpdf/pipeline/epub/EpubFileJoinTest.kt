package com.example.hinglishpdf.pipeline.epub

import com.adobe.epubcheck.api.EpubCheck
import com.adobe.epubcheck.util.DefaultReportImpl
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A chapter split by its publisher into two files mid-paragraph: joined to translate, split back to rebuild. */
class EpubFileJoinTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head><title>Part</title></head>
<body>$body</body>
</html>"""

    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:12345678-1234-1234-1234-123456789abd</dc:identifier>
<dc:title>Split</dc:title>
<dc:language>en</dc:language>
<meta property="dcterms:modified">2026-10-10T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="a" href="part1.xhtml" media-type="application/xhtml+xml"/>
<item id="b" href="part2.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="a"/><itemref idref="b"/></spine>
</package>"""

    private fun book(): File {
        val file = temp.newFile("split.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype); zip.closeEntry()
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", opf)
            put("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en">
<head><title>Contents</title></head>
<body><nav epub:type="toc"><ol><li><a href="part1.xhtml">Start</a></li></ol></nav></body>
</html>""")
            put("OEBPS/part1.xhtml", xhtml("<h1>One</h1><p>A paragraph that is finished.</p><p>The story goes on in <i>the</i></p>"))
            put("OEBPS/part2.xhtml", xhtml("<p>next file, as some publishers do.</p><p>Then it ends.</p>"))
        }
        return file
    }

    @Test
    fun `the cut paragraph is one paragraph, rebuilt into its two elements`() {
        val source = book()
        val doc = EpubParser.parse(source)
        val joined = doc.paragraphs.single { it.parts.size == 2 }
        assertEquals("The story goes on in {1}the{/1} next file, as some publishers do.", joined.text)
        assertEquals(listOf("OEBPS/part1.xhtml", "OEBPS/part2.xhtml"), joined.parts.map { (it.ref as SourceRef.Epub).file })
        assertEquals(ParagraphRole.PARAGRAPH, joined.role)
        // Its sentence is one segment, not two fragments.
        assertTrue(doc.segments.any { it.text == joined.text })

        // Unchanged when nothing is translated...
        val same = temp.newFile("same.epub")
        same.outputStream().use { EpubRebuilder.rebuild(source, it, doc.paragraphs, doc.paragraphs.map { null }, null) }
        ZipFile(source).use { a -> ZipFile(same).use { b -> for (e in a.entries()) assertArrayEquals(e.name, a.getInputStream(e).readBytes(), b.getInputStream(b.getEntry(e.name)).readBytes()) } }

        // ...and each half of the translation goes back to its own file.
        val out = temp.newFile("out.epub")
        out.outputStream().use { EpubRebuilder.rebuild(source, it, doc.paragraphs, doc.paragraphs.map { p -> p.text.uppercase() }, "hi") }
        ZipFile(out).use { zip ->
            val part1 = zip.getInputStream(zip.getEntry("OEBPS/part1.xhtml")).readBytes().toString(Charsets.UTF_8)
            val part2 = zip.getInputStream(zip.getEntry("OEBPS/part2.xhtml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(part1, part1.contains("<p>THE STORY GOES ON IN <i>THE</i></p>"))
            assertTrue(part2, part2.contains("<p>NEXT FILE, AS SOME PUBLISHERS DO.</p>"))
        }
        val report = DefaultReportImpl(out.name)
        EpubCheck(out, report).doValidate()
        assertEquals(0, report.errorCount + report.fatalErrorCount)
    }
}
