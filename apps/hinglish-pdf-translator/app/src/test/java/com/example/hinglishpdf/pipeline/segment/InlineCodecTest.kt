package com.example.hinglishpdf.pipeline.segment

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Placeholder round trip: XHTML inline content → placeholder text → the same XHTML. */
class InlineCodecTest {

    private fun paragraph(xml: String): Element {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        doc.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        return doc.selectFirst("p")!!
    }

    private fun encode(p: Element): Pair<String, List<PlaceholderTag>> {
        val builder = PlaceholderTextBuilder()
        InlineCodec.encode(p.childNodes(), builder) { it.normalName() == "code" || it.attr("translate") == "no" }
        return builder.result()
    }

    private val sample = """<p>He said <b>hello</b> to <a href="ch2.xhtml#x" class="ref">the <i>old</i> man</a><img src="i.png" alt="A man"/> and ran<br/>away. Run <code>ls -l</code> and <span translate="no">Acme™</span>.</p>"""

    @Test
    fun `inline tags become numbered placeholders`() {
        val (text, tags) = encode(paragraph(sample))
        assertEquals(
            "He said {1}hello{/1} to {2}the {3}old{/3} man{/2}[[IMG_4]] and ran[[BR_5]]away. Run [[CODE_6]] and [[SPAN_7]].",
            text,
        )
        assertEquals(PlaceholderTag.Kind.PAIRED, tags[1].kind)
        assertEquals("""<a href="ch2.xhtml#x" class="ref"></a>""", tags[1].markup)
        assertEquals("""<img src="i.png" alt="A man" />""", tags[3].markup)
        assertEquals("<code>ls -l</code>", tags[5].markup)
    }

    @Test
    fun `decoding the source gives back exactly the same markup`() {
        val p = paragraph(sample)
        val original = p.html()
        val (text, tags) = encode(p)
        val nodes = InlineCodec.decode(text, tags)
        p.empty()
        nodes.forEach { p.appendChild(it) }
        assertEquals(original, p.html())
    }

    @Test
    fun `a translation is rebuilt around the same tags`() {
        val p = paragraph("""<p>A <b>red</b> car<img src="c.png"/>.</p>""")
        val (_, tags) = encode(p)
        val nodes = InlineCodec.decode("एक {1}लाल{/1} कार[[IMG_2]]।", tags)
        p.empty()
        nodes.forEach { p.appendChild(it) }
        assertEquals("""एक <b>लाल</b> कार<img src="c.png" />।""", p.html())
    }

    @Test
    fun `literal braces in the source survive the round trip`() {
        val p = paragraph("<p>Let {1} be x and [[A_2]] stay.</p>")
        val (text, tags) = encode(p)
        assertTrue(text.contains("[[TXT_1]]"))
        val nodes = InlineCodec.decode(text, tags)
        assertEquals("Let {1} be x and [[A_2]] stay.", nodes.joinToString("") { (it as org.jsoup.nodes.TextNode).text() })
    }

    @Test
    fun `broken translations are refused, not half rebuilt`() {
        val (_, tags) = encode(paragraph("<p>A <b>red</b> car<br/>.</p>"))
        for (bad in listOf("एक लाल कार[[BR_2]]", "एक {1}लाल कार[[BR_2]]", "एक {1}लाल{/1}{/1} कार[[BR_2]]", "{1}लाल{/1} [[BR_2]] [[BR_2]]", "{9}x{/9} [[BR_2]] {1}{/1}")) {
            try {
                InlineCodec.decode(bad, tags)
                fail("accepted: $bad")
            } catch (e: PlaceholderException) {
                // expected
            }
        }
    }
}
