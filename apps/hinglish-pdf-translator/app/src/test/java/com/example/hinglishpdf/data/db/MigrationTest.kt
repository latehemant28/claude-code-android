package com.example.hinglishpdf.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.llm.Language
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A book in progress under app 4.x (database version 1) survives the update to 5.0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {

    @Test
    fun `version 1 database opens with its books, pages and languages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }

        // Exactly the schema Room created for version 1 (app 4.0).
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `format` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `language` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `status` TEXT NOT NULL, `error` TEXT, `outputUri` TEXT, `outputName` TEXT, `createdAt` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE IF NOT EXISTS `pages` (`bookId` INTEGER NOT NULL, `pageNumber` INTEGER NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, `sourceBlocks` TEXT NOT NULL, `translations` TEXT, `translatedText` TEXT, `translatedAt` INTEGER, PRIMARY KEY(`bookId`, `pageNumber`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_pages_bookId` ON `pages` (`bookId`)")
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b40075681df81362d9d3de3a39c3e476')")
            old.execSQL("INSERT INTO books VALUES (1, 'Old book', 'PDF', '/x.pdf', 'Hinglish', 2, 'TRANSLATING', NULL, NULL, NULL, 5)")
            old.execSQL("INSERT INTO pages VALUES (1, 1, 595.0, 842.0, '[]', '[]', 'done', 6)")
            old.execSQL("INSERT INTO pages VALUES (1, 2, 595.0, 842.0, '[]', NULL, NULL, NULL)")
            old.version = 1
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val book = db.bookDao().get(1)!!
            assertEquals("Old book", book.title)
            assertEquals(Language.HINDI, book.toLanguage) // "Hinglish" books were always Hindi
            assertEquals(Language.AUTO_DETECT, book.fromLanguage)
            assertEquals(2, db.pageDao().count(1))
            assertEquals(2, db.pageDao().nextUntranslated(1)!!.pageNumber) // resumes where it stopped

            val id = db.bookDao().insert(
                BookEntity(title = "New", format = DocFormat.EPUB, sourcePath = "/y.epub", sourceLanguage = "en", language = "es"),
            )
            val added = db.bookDao().get(id)!!
            assertEquals(Language.ENGLISH, added.fromLanguage)
            assertEquals(Language.SPANISH, added.toLanguage)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
