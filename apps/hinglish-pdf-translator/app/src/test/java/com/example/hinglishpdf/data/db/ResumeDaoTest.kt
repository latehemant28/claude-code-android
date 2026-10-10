package com.example.hinglishpdf.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The crash-recovery contract: work always restarts at the first unsaved page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResumeDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newBook(status: BookStatus = BookStatus.QUEUED, createdAt: Long = 1L): Long =
        db.bookDao().insert(
            BookEntity(
                title = "Book", format = DocFormat.PDF, sourcePath = "/x",
                status = status, createdAt = createdAt,
            ),
        )

    private fun page(bookId: Long, n: Int) = PageEntity(
        bookId, n, 595f, 842f,
        listOf(DocBlock(BlockKind.HEADING, "Title $n", level = 1), DocBlock(BlockKind.BULLET, "Item $n")),
    )

    @Test
    fun `resumes at the first untranslated page and counts progress`() = runTest {
        val id = newBook(BookStatus.TRANSLATING)
        db.pageDao().replacePages(id, (1..5).map { page(id, it) })
        db.bookDao().setPageCount(id, 5)

        db.pageDao().saveTranslation(id, 1, listOf("Sheershak 1", "Cheez 1"), "text", 10L)
        db.pageDao().saveTranslation(id, 2, listOf(null, "Cheez 2"), "text", 11L)

        // "Crash" here: a fresh query must point at page 3.
        assertEquals(3, db.pageDao().nextUntranslated(id)?.pageNumber)
        assertEquals(2, db.bookDao().observeAll().first().single().translatedPages)
        assertEquals(listOf(null, "Cheez 2"), db.pageDao().pages(id)[1].translations)
        assertEquals(listOf(1, 2), db.pageDao().observeTranslated(id).first().map { it.pageNumber })

        (3..5).forEach { db.pageDao().saveTranslation(id, it, listOf("a", "b"), "t", 12L) }
        assertNull(db.pageDao().nextUntranslated(id))
    }

    @Test
    fun `interrupted and queued books resume oldest first, paused ones wait`() = runTest {
        newBook(BookStatus.PAUSED, createdAt = 1)
        newBook(BookStatus.COMPLETED, createdAt = 2)
        val interrupted = newBook(BookStatus.TRANSLATING, createdAt = 3)
        newBook(BookStatus.QUEUED, createdAt = 4)
        assertEquals(interrupted, db.bookDao().nextResumable()?.id)

        db.bookDao().setStatus(interrupted, BookStatus.FAILED, "boom")
        assertEquals(4L, db.bookDao().nextResumable()?.createdAt)
    }

    @Test
    fun `deleting a book removes its pages`() = runTest {
        val id = newBook()
        db.pageDao().insertAll((1..3).map { page(id, it) })
        db.bookDao().delete(id)
        assertEquals(0, db.pageDao().count(id))
    }
}
