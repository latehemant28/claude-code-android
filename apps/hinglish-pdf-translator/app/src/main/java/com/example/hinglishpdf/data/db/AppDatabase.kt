package com.example.hinglishpdf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, PageEntity::class, ParsedDocumentEntity::class, ParagraphEntity::class, SegmentEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageDao(): PageDao
    abstract fun pipelineDao(): PipelineDao

    companion object {
        fun create(context: Context): AppDatabase =
            // WAL (Room's default) makes each page's save durable on its own,
            // so a crash loses at most the page being translated.
            Room.databaseBuilder(context, AppDatabase::class.java, "translations.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Version 2 adds the pipeline's tables (parsed_documents, paragraphs,
         * segments). Only additions: books and pages are left exactly as they
         * are, so every existing book and translation is kept.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Exactly the statements Room generates for these entities (checked by AppDatabaseMigrationTest).
                db.execSQL("CREATE TABLE IF NOT EXISTS `parsed_documents` (`bookId` INTEGER NOT NULL, `title` TEXT, `language` TEXT, `metadata` TEXT NOT NULL, `hasToc` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `scannedPages` TEXT NOT NULL, `needsOcr` INTEGER NOT NULL, `parserVersion` INTEGER NOT NULL, `parsedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `paragraphs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `role` TEXT NOT NULL, `level` INTEGER NOT NULL, `text` TEXT NOT NULL, `tags` TEXT NOT NULL, `ref` TEXT NOT NULL, `style` TEXT, `translatable` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_paragraphs_bookId_ordinal` ON `paragraphs` (`bookId`, `ordinal`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `paragraphId` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `indexInParagraph` INTEGER NOT NULL, `text` TEXT NOT NULL, `separator` TEXT NOT NULL, `ref` TEXT NOT NULL, `tags` TEXT NOT NULL, `hash` TEXT NOT NULL, `words` INTEGER NOT NULL, FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`paragraphId`) REFERENCES `paragraphs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_segments_bookId_ordinal` ON `segments` (`bookId`, `ordinal`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_paragraphId` ON `segments` (`paragraphId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_hash` ON `segments` (`hash`)")
            }
        }
    }
}
