package com.example.hinglishpdf.data.translate

import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Translation runs on paragraphs; pages are what is saved, shown and resumed. */
class PipelinePagesTest {

    private fun p(text: String, page: Int, role: ParagraphRole = ParagraphRole.PARAGRAPH, tags: List<PlaceholderTag> = emptyList(), marker: String? = null) =
        ParsedParagraph(role, text, tags, SourceRef.Pdf(page, Box(0f, 0f, 1f, 1f)), marker = marker)

    private val bold = PlaceholderTag(1, PlaceholderTag.Kind.PAIRED, "b", "<b></b>")

    /** Page 1: a heading and a paragraph that runs on to page 2; page 2: a list item; page 3: only a page number. */
    private fun doc(): ParsedDocument {
        val raw = listOf(
            p("A BOOK", 1, ParagraphRole.HEADER),
            p("Chapter One", 1, ParagraphRole.HEADING),
            p("The river rose. It rose all {1}night{/1} and", 1, tags = listOf(bold)),
            p("12", 1, ParagraphRole.PAGE_NUMBER),
            p("the town woke up to water. Nobody slept.", 2),
            p("Bring a coat.", 2, ParagraphRole.LIST_ITEM, marker = "•"),
            p("13", 3, ParagraphRole.PAGE_NUMBER),
        )
        val paragraphs = DocumentAssembler(PipelineConfig()).assemble(raw)
        return ParsedDocument(null, null, emptyMap(), false, 3, emptyList(), false, paragraphs, Segmenter.segment(paragraphs))
    }

    @Test
    fun `pdf pages keep their own blocks, a joined paragraph has a block on each page, furniture is not shown`() {
        val doc = doc()
        val pages = PipelinePages.build(7L, doc, DocFormat.PDF, 350)
        assertEquals(listOf(1, 2, 3), pages.map { it.pageNumber })
        assertEquals(listOf("Chapter One", "The river rose. It rose all {1}night{/1} and"), pages[0].sourceBlocks.map { it.text })
        assertEquals(listOf("the town woke up to water. Nobody slept.", "Bring a coat."), pages[1].sourceBlocks.map { it.text })
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH), pages[0].sourceBlocks.map { it.kind })
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.BULLET), pages[1].sourceBlocks.map { it.kind })
        assertEquals("The river rose. It rose all night and", pages[0].sourceBlocks[1].plain())
        // Page 3 has nothing to translate: done from the start.
        assertTrue(pages[2].sourceBlocks.isEmpty() && pages[2].translations != null)
        assertTrue(PipelinePages.isPipeline(pages))
    }

    @Test
    fun `a page is saved once every paragraph on it is translated, the joined one split back at a sentence`() {
        val doc = doc()
        val pages = PipelinePages.build(7L, doc, DocFormat.PDF, 350).filter { it.translations == null }
        val filler = PageFiller(pages, doc.paragraphs)
        val joined = doc.paragraphs.indexOfFirst { it.parts.size == 2 }
        val heading = doc.paragraphs.indexOfFirst { it.role == ParagraphRole.HEADING }
        val item = doc.paragraphs.indexOfFirst { it.role == ParagraphRole.LIST_ITEM }
        assertEquals(listOf(heading, joined, item), filler.paragraphsToTranslate.toList())
        assertEquals(1, filler.pageOf(joined))
        assertEquals(2, filler.pageOf(item))

        assertEquals(emptyList<Int>(), filler.fill(heading, "Adhyay Ek").map { it.pageNumber })
        // "Nadi badhi." | "Raat bhar {1}nadi{/1} badhi aur shahar paani mein jaaga. Koi nahi soya."
        val saved = filler.fill(joined, "Nadi badhi. Raat bhar {1}raat{/1} aur shahar paani mein jaaga. Koi nahi soya.")
        assertEquals(listOf(1), saved.map { it.pageNumber })
        assertEquals(listOf("Adhyay Ek", "Nadi badhi. Raat bhar {1}raat{/1} aur shahar paani mein jaaga."), saved[0].translations)
        assertEquals("Adhyay Ek\n\nNadi badhi. Raat bhar raat aur shahar paani mein jaaga.", saved[0].translatedText)
        val page2 = filler.fill(item, "Coat le aao.")
        assertEquals(listOf(2), page2.map { it.pageNumber })
        assertEquals(listOf("Koi nahi soya.", "Coat le aao."), page2[0].translations)
        assertEquals(emptyList<Int>(), filler.finish().map { it.pageNumber })
    }

    @Test
    fun `resuming after page 1 was saved translates only what touches the pages left`() {
        val doc = doc()
        val pages = PipelinePages.build(7L, doc, DocFormat.PDF, 350).filter { it.pageNumber >= 2 }
        val filler = PageFiller(pages, doc.paragraphs)
        val joined = doc.paragraphs.indexOfFirst { it.parts.size == 2 }
        val item = doc.paragraphs.indexOfFirst { it.role == ParagraphRole.LIST_ITEM }
        assertEquals(listOf(joined, item), filler.paragraphsToTranslate.toList())
        assertEquals(2, filler.pageOf(joined)) // shown as the page it continues on
        assertEquals(emptyList<Int>(), filler.fill(joined, "Nadi badhi. Shahar jaaga. Koi nahi soya.").map { it.pageNumber })
        val saved = filler.fill(item, null) // refused: the original stays
        assertEquals(listOf(null), saved[0].translations.orEmpty().drop(1))
    }

    @Test
    fun `epub books are page-sized sections of whole paragraphs`() {
        val paragraphs = (1..10).map { ParsedParagraph(ParagraphRole.PARAGRAPH, "Word ".repeat(100).trim() + ".", emptyList(), SourceRef.Epub("c.xhtml", "/p[$it]")) }
        val assembled = DocumentAssembler(PipelineConfig()).assemble(paragraphs)
        val doc = ParsedDocument(null, null, emptyMap(), false, 1, emptyList(), false, assembled, emptyList())
        val pages = PipelinePages.build(1L, doc, DocFormat.EPUB, 350)
        assertEquals(listOf(3, 3, 3, 1), pages.map { it.sourceBlocks.size })
        assertEquals((0..9).toList(), pages.flatMap { it.sourceBlocks }.map { it.paragraph })
        assertNull(pages[0].translations)
    }
}
