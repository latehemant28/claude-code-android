package com.example.hinglishpdf.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/*
 * The pipeline's parse of a book (added in database version 2): the document,
 * its paragraphs and their sentence segments. JSON columns hold structured
 * values (placeholder tags, source references); see PipelineStore.
 */

@Entity(
    tableName = "parsed_documents",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ParsedDocumentEntity(
    @PrimaryKey val bookId: Long,
    val title: String?,
    val language: String?,
    /** JSON object of metadata (creator, publisher...; PDF: tables, multi-column pages). */
    val metadata: String,
    val hasToc: Boolean,
    val pageCount: Int,
    /** JSON array of page numbers without a usable text layer. */
    val scannedPages: String,
    val needsOcr: Boolean,
    val parserVersion: Int,
    val parsedAt: Long,
)

@Entity(
    tableName = "paragraphs",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "ordinal"], unique = true)],
)
data class ParagraphEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Position in the book (0-based). */
    val ordinal: Int,
    /** ParagraphRole name. */
    val role: String,
    val level: Int,
    /** Placeholder text. */
    val text: String,
    /** JSON array of placeholder tags. */
    val tags: String,
    /** JSON source reference: EPUB file + XPath, or PDF page + box. */
    val ref: String,
    /** JSON text style (PDF), or null. */
    val style: String?,
    val translatable: Boolean,
)

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ParagraphEntity::class, parentColumns = ["id"], childColumns = ["paragraphId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["bookId", "ordinal"], unique = true), Index("paragraphId"), Index("hash")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val paragraphId: Long,
    /** Position in the book (0-based); the order segments are translated and shown in. */
    val ordinal: Int,
    /** Position within its paragraph. */
    val indexInParagraph: Int,
    /** Placeholder text. */
    val text: String,
    /** Whitespace that followed it in the source. */
    val separator: String,
    val ref: String,
    val tags: String,
    /** SHA-256 of the text with placeholders renumbered: equal for repeated sentences. */
    val hash: String,
    val words: Int,
)

@Dao
interface PipelineDao {
    @Insert
    suspend fun insertDocument(document: ParsedDocumentEntity)

    @Insert
    suspend fun insertParagraphs(paragraphs: List<ParagraphEntity>): List<Long>

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM parsed_documents WHERE bookId = :bookId")
    suspend fun deleteDocument(bookId: Long)

    @Query("DELETE FROM paragraphs WHERE bookId = :bookId") // segments go with them (ON DELETE CASCADE)
    suspend fun deleteParagraphs(bookId: Long)

    @Query("SELECT * FROM parsed_documents WHERE bookId = :bookId")
    suspend fun document(bookId: Long): ParsedDocumentEntity?

    @Query("SELECT * FROM paragraphs WHERE bookId = :bookId ORDER BY ordinal")
    suspend fun paragraphs(bookId: Long): List<ParagraphEntity>

    @Query("SELECT * FROM segments WHERE bookId = :bookId ORDER BY ordinal")
    suspend fun segments(bookId: Long): List<SegmentEntity>

    /** Books whose PDF turned out to be scanned (they need OCR). */
    @Query("SELECT bookId FROM parsed_documents WHERE needsOcr = 1")
    fun observeNeedsOcr(): kotlinx.coroutines.flow.Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM segments WHERE bookId = :bookId")
    suspend fun segmentCount(bookId: Long): Int

    /** Replaces a book's parse in one transaction: all or nothing. */
    @Transaction
    suspend fun replace(document: ParsedDocumentEntity, paragraphs: List<ParagraphEntity>, segments: (paragraphIds: List<Long>) -> List<SegmentEntity>) {
        deleteParagraphs(document.bookId)
        deleteDocument(document.bookId)
        insertDocument(document)
        val ids = insertParagraphs(paragraphs)
        insertSegments(segments(ids))
    }
}
