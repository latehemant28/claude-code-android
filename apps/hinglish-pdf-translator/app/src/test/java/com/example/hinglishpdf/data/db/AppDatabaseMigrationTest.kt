package com.example.hinglishpdf.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.pipeline.PipelineStore
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TextStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrading a real version-1 database (exactly as the app created it before)
 * to version 2: existing books and translations survive, and Room accepts
 * the new tables (it checks every column and index against the entities).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The statements Room generated for version 1 (AppDatabase_Impl of version 4.1). */
    private val version1 = listOf(
        "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `format` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `language` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `status` TEXT NOT NULL, `error` TEXT, `outputUri` TEXT, `outputName` TEXT, `createdAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `pages` (`bookId` INTEGER NOT NULL, `pageNumber` INTEGER NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, `sourceBlocks` TEXT NOT NULL, `translations` TEXT, `translatedText` TEXT, `translatedAt` INTEGER, PRIMARY KEY(`bookId`, `pageNumber`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b40075681df81362d9d3de3a39c3e476')",
    )

    private fun createVersion1(name: String) {
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            version1.forEach(db::execSQL)
            db.execSQL("INSERT INTO books (id, title, format, sourcePath, language, pageCount, status, createdAt) VALUES (1, 'Old Book', 'PDF', '/x.pdf', 'Hinglish', 1, 'COMPLETED', 0)")
            db.execSQL("INSERT INTO pages VALUES (1, 1, 595.0, 842.0, '[]', '[\"हाँ\"]', 'हाँ', 5)")
            db.version = 1
        }
    }

    private fun sample(): ParsedDocument {
        val paragraphs = listOf(
            ParsedParagraph(ParagraphRole.HEADING, "Chapter One", emptyList(), SourceRef.Epub("OEBPS/c1.xhtml", "/html[1]/body[1]/h1[1]"), level = 1),
            ParsedParagraph(
                ParagraphRole.PARAGRAPH, "It was {1}cold{/1}. [[IMG_2]]Then it snowed.",
                listOf(PlaceholderTag(1, PlaceholderTag.Kind.PAIRED, "b", "<b></b>"), PlaceholderTag(2, PlaceholderTag.Kind.STANDALONE, "IMG", "<img src=\"a.png\" />")),
                SourceRef.Pdf(2, Box(50f, 90f, 300f, 130f)),
                style = TextStyle("Serif", 11f, bold = false, italic = false),
            ),
            ParsedParagraph(ParagraphRole.TABLE_CELL, "42", emptyList(), SourceRef.Epub("OEBPS/c1.xhtml", "/html[1]/body[1]/td[1]", attribute = null)),
        )
        return ParsedDocument("Book", "en", mapOf("creator" to "A. Author"), true, 2, listOf(5), false, paragraphs, Segmenter.segment(paragraphs))
    }

    @Test
    fun `a version 1 database is upgraded without losing anything`() = runBlocking {
        createVersion1("upgrade.db")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "upgrade.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening runs the migration and Room's schema check.
            val book = db.bookDao().get(1)!!
            assertEquals("Old Book", book.title)
            assertEquals(BookStatus.COMPLETED, book.status)
            assertEquals(listOf("हाँ"), db.pageDao().pages(1).single().translations)

            // The new tables work on the upgraded database.
            val store = PipelineStore(db.pipelineDao())
            val doc = sample()
            store.save(1, doc, now = 7)
            assertEquals(doc, store.load(1))
            assertEquals(3, db.pipelineDao().segmentCount(1)) // "It was {1}cold{/1}." "[[IMG_2]]Then it snowed." "Chapter One"

            // Saving again replaces the old parse; deleting the book removes it all.
            store.save(1, doc, now = 8)
            assertEquals(3, db.pipelineDao().segmentCount(1))
            db.bookDao().delete(1)
            assertNull(store.load(1))
            assertEquals(0, db.pipelineDao().segmentCount(1))
        } finally {
            db.close()
        }
    }
}
