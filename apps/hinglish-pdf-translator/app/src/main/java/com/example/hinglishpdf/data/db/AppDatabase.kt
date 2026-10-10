package com.example.hinglishpdf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [BookEntity::class, PageEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageDao(): PageDao

    companion object {
        fun create(context: Context): AppDatabase =
            // WAL (Room's default) makes each page's save durable on its own,
            // so a crash loses at most the page being translated.
            Room.databaseBuilder(context, AppDatabase::class.java, "translations.db").build()
    }
}
