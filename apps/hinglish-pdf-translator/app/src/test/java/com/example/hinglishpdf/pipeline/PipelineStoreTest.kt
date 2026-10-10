package com.example.hinglishpdf.pipeline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.db.AppDatabase
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TextStyle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tiers, columns, list markers, furniture roles and source parts survive the database (no schema change). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PipelineStoreTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a parse is read back as it was saved`() = runTest {
        val bookId = db.bookDao().insert(
            BookEntity(title = "Book", format = DocFormat.PDF, sourcePath = "/x", status = BookStatus.QUEUED, createdAt = 1L),
        )
        val style = TextStyle("Serif", 10f, bold = false, italic = false)
        fun pdf(page: Int) = SourceRef.Pdf(page, Box(1f, 2f, 3f, 4f))
        val raw = listOf(
            ParsedParagraph(ParagraphRole.HEADER, "A BOOK", emptyList(), pdf(1), style = style, tier = -1),
            ParsedParagraph(ParagraphRole.LIST_ITEM, "a coat for the", emptyList(), pdf(1), style = style, column = 2, marker = "•"),
            ParsedParagraph(ParagraphRole.PARAGRAPH, "rain.", emptyList(), pdf(2), style = style, column = 1),
        )
        val paragraphs = DocumentAssembler(PipelineConfig()).assemble(raw)
        val doc = ParsedDocument("T", "en", mapOf("k" to "v"), false, 2, emptyList(), false, paragraphs, Segmenter.segment(paragraphs))
        val store = PipelineStore(db.pipelineDao())
        store.save(bookId, doc)
        val back = store.load(bookId)!!
        assertEquals(doc.paragraphs, back.paragraphs)
        assertEquals(doc.segments, back.segments)
        assertEquals(PipelineStore.PARSER_VERSION, db.pipelineDao().document(bookId)!!.parserVersion)
        assertEquals(1..2, back.paragraphs[1].pages)
    }

    @Test
    fun `books parsed by an older parser are found, unless their pages were built from that parse`() = runTest {
        suspend fun book() = db.bookDao().insert(BookEntity(title = "B", format = DocFormat.EPUB, sourcePath = "/x", status = BookStatus.QUEUED, createdAt = 1L))
        val old = book()
        val oldWithPages = book()
        val current = book()
        val store = PipelineStore(db.pipelineDao())
        val paragraphs = DocumentAssembler(PipelineConfig()).assemble(
            listOf(ParsedParagraph(ParagraphRole.PARAGRAPH, "Text.", emptyList(), SourceRef.Epub("a.xhtml", "/p[1]"))),
        )
        val doc = ParsedDocument(null, null, emptyMap(), false, 1, emptyList(), false, paragraphs, Segmenter.segment(paragraphs))
        for (id in listOf(old, oldWithPages, current)) store.save(id, doc)
        // Make two of them look like version 1 parses.
        db.openHelper.writableDatabase.execSQL("UPDATE parsed_documents SET parserVersion = 1 WHERE bookId IN ($old, $oldWithPages)")
        db.pageDao().replacePages(oldWithPages, com.example.hinglishpdf.data.translate.PipelinePages.build(oldWithPages, doc, DocFormat.EPUB, 350))
        assertEquals(listOf(old, oldWithPages), db.pipelineDao().outdated(PipelineStore.PARSER_VERSION).sorted())
        assertEquals(1, db.pageDao().pipelinePageCount(oldWithPages))
        assertEquals(0, db.pageDao().pipelinePageCount(old))
        assertEquals(
            "Re-analysing 2 books with the improved parser; your translations are kept",
            com.example.hinglishpdf.ui.TranslatorViewModel.reanalyseNotice(2),
        )
    }
}
