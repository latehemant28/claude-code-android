package com.example.hinglishpdf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query(
        """
        SELECT b.*, (SELECT COUNT(*) FROM pages p
                     WHERE p.bookId = b.id AND p.translations IS NOT NULL) AS translatedPages
        FROM books b ORDER BY b.createdAt DESC
        """,
    )
    fun observeAll(): Flow<List<BookWithProgress>>

    /** The oldest book that should be (or was being) translated: queued or interrupted. */
    @Query("SELECT * FROM books WHERE status IN ('QUEUED', 'READING', 'TRANSLATING') ORDER BY createdAt LIMIT 1")
    suspend fun nextResumable(): BookEntity?

    @Query("UPDATE books SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: BookStatus, error: String? = null)

    @Query("UPDATE books SET pageCount = :pageCount WHERE id = :id")
    suspend fun setPageCount(id: Long, pageCount: Int)

    @Query("UPDATE books SET outputUri = :uri, outputName = :name WHERE id = :id")
    suspend fun setOutput(id: Long, uri: String, name: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<PageEntity>)

    @Query("DELETE FROM pages WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** Replaces a book's extracted pages in one transaction (re-extraction after a crash). */
    @Transaction
    suspend fun replacePages(bookId: Long, pages: List<PageEntity>) {
        deleteForBook(bookId)
        insertAll(pages)
    }

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    suspend fun count(bookId: Long): Int

    /** Pages built from the book's pipeline parse: their blocks point at its paragraphs (see PipelinePages). */
    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId AND sourceBlocks LIKE '%\"paragraph\":%'")
    suspend fun pipelinePageCount(bookId: Long): Int

    /** Where to resume: the first page without a saved translation. */
    @Query("SELECT * FROM pages WHERE bookId = :bookId AND translations IS NULL ORDER BY pageNumber LIMIT 1")
    suspend fun nextUntranslated(bookId: Long): PageEntity?

    @Query(
        """
        UPDATE pages SET translations = :translations, translatedText = :text, translatedAt = :at
        WHERE bookId = :bookId AND pageNumber = :pageNumber
        """,
    )
    suspend fun saveTranslation(bookId: Long, pageNumber: Int, translations: List<String?>, text: String, at: Long)

    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageNumber")
    suspend fun pages(bookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND translations IS NOT NULL ORDER BY pageNumber")
    fun observeTranslated(bookId: Long): Flow<List<PageEntity>>
}
