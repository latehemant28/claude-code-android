package com.example.hinglishpdf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BookEntity::class, PageEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageDao(): PageDao

    companion object {
        fun create(context: Context): AppDatabase =
            // WAL (Room's default) makes each page's save durable on its own,
            // so a crash loses at most the page being translated.
            Room.databaseBuilder(context, AppDatabase::class.java, "translations.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** 5.0: books remember their source language (the target already had a column). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `sourceLanguage` TEXT NOT NULL DEFAULT 'auto'")
            }
        }
    }
}
