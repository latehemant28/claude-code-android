package com.example.hinglishpdf.data.epub

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubBookTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chapter1 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title>
        <link rel="stylesheet" href="../styles/book.css" type="text/css"/></head>
        <body>
          <h1 class="title">Getting Started</h1>
          <p>Welcome to the <em>quick</em> guide&nbsp;for new users.</p>
          <ul>
            <li>Open the app</li>
            <li>Go to settings
              <ul><li>Choose a language</li></ul>
            </li>
          </ul>
          <ol start="3"><li>Save your work.</li><li>Restart.</li></ol>
          <p><strong>Important note</strong></p>
          <img src="../images/pic.png" alt="diagram"/>
          <pre>val x = 1</pre>
        </body></html>
    """.trimIndent()

    private val chapter2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><body>
          <h2>Part Two</h2><blockquote><p>Stay curious.</p></blockquote>
        </body></html>
    """.trimIndent()

    private val image = byteArrayOf(1, 2, 3, 4, 5)

    private fun makeEpub(): File {
        val file = tmp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                   <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                   <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test Book</dc:title></metadata>
                   <manifest>
                     <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                     <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                     <item id="css" href="styles/book.css" media-type="text/css"/>
                     <item id="img" href="images/pic.png" media-type="image/png"/>
                   </manifest>
                   <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""".toByteArray(),
            )
            put("OEBPS/text/ch 1.xhtml", chapter1.toByteArray())
            put("OEBPS/text/ch2.xhtml", chapter2.toByteArray())
            put("OEBPS/styles/book.css", "h1 { color: red; }".toByteArray())
            put("OEBPS/images/pic.png", image)
        }
        return file
    }

    @Test
    fun `reads the spine in order with structure`() {
        val content = EpubBook.read(makeEpub())
        assertEquals("Test Book", content.title)
        assertEquals(
            listOf(
                DocBlock(BlockKind.HEADING, "Getting Started", level = 1),
                DocBlock(BlockKind.PARAGRAPH, "Welcome to the quick guide for new users."),
                DocBlock(BlockKind.BULLET, "Open the app", level = 0),
                DocBlock(BlockKind.BULLET, "Go to settings", level = 0),
                DocBlock(BlockKind.BULLET, "Choose a language", level = 1),
                DocBlock(BlockKind.NUMBERED, "Save your work.", level = 0, marker = "3."),
                DocBlock(BlockKind.NUMBERED, "Restart.", level = 0, marker = "4."),
                DocBlock(BlockKind.PARAGRAPH, "Important note"),
                DocBlock(BlockKind.CODE, "val x = 1"),
                DocBlock(BlockKind.HEADING, "Part Two", level = 2),
                DocBlock(BlockKind.QUOTE, "Stay curious."),
            ),
            content.blocks,
        )
    }

    @Test
    fun `translated copy keeps markup, assets and order`() {
        val source = makeEpub()
        val blocks = EpubBook.read(source).blocks
        val translations = blocks.mapIndexed { i, b -> if (b.kind == BlockKind.CODE) null else "T$i ${b.text}" }

        val out = tmp.newFile("out.epub")
        out.outputStream().use { EpubBook.writeTranslated(source, it, translations) }

        // Same structure, translated text.
        val reread = EpubBook.read(out).blocks
        assertEquals(blocks.map { it.copy(text = "") }, reread.map { it.copy(text = "") })
        reread.forEachIndexed { i, b ->
            if (b.kind == BlockKind.CODE) assertEquals("val x = 1", b.text)
            else assertTrue(b.text, b.text.startsWith("T$i "))
        }

        ZipFile(out).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertArrayEquals(image, zip.getInputStream(zip.getEntry("OEBPS/images/pic.png")).readBytes())
            val html = zip.getInputStream(zip.getEntry("OEBPS/text/ch 1.xhtml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(html.contains("""<h1 class="title">T0 Getting Started</h1>"""))
            assertTrue(html.contains("""<strong>T7 Important note</strong>""")) // wrapper kept
            assertTrue(html.contains("""<img src="../images/pic.png" alt="diagram" />"""))
            assertTrue(html.contains("""<ol start="3">"""))
            assertTrue(html.contains("book.css"))
        }
    }
}
