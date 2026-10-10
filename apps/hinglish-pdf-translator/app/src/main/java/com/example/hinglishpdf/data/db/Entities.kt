package com.example.hinglishpdf.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat

enum class BookStatus {
    /** Waiting for the service (or interrupted before it started). */
    QUEUED,

    /** Extracting pages from the file. */
    READING,
    TRANSLATING,

    /** Stopped by the user; resumes only when asked. */
    PAUSED,
    COMPLETED,
    FAILED,
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val format: DocFormat,
    /** Private copy of the picked file (the original URI may not stay readable). */
    val sourcePath: String,
    /**
     * Output language label. Always "Hinglish" now; kept as a plain string so
     * databases written by earlier versions (which also had "Minglish") still
     * open without a migration.
     */
    val language: String = "Hinglish",
    val pageCount: Int = 0,
    val status: BookStatus = BookStatus.QUEUED,
    val error: String? = null,
    /** MediaStore URI of the last export in Downloads. */
    val outputUri: String? = null,
    val outputName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** PDFs have real pages; EPUBs are cut into page-sized sections. */
    val unitName: String get() = if (format == DocFormat.PDF) "page" else "section"
}

/**
 * One page of a book. (bookId, pageNumber) is the primary key, so a page is
 * stored exactly once and re-saving it simply overwrites it.
 *
 * [translations] stays NULL until the page is done; the next page to work on
 * after a crash is simply the first one where it is NULL.
 */
@Entity(
    tableName = "pages",
    primaryKeys = ["bookId", "pageNumber"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class PageEntity(
    val bookId: Long,
    /** 1-based, identical to the page number in the source PDF. */
    val pageNumber: Int,
    /** Original page size in PDF points (0 for EPUB sections). */
    val width: Float,
    val height: Float,
    /** The page's structure: headings, paragraphs, bullet and numbered items. */
    val sourceBlocks: List<DocBlock>,
    /** Translation of each block (null entries = kept as-is, e.g. code). */
    val translations: List<String?>? = null,
    /** The translated page as plain text with bullets and numbering. */
    val translatedText: String? = null,
    val translatedAt: Long? = null,
)

data class BookWithProgress(
    @Embedded val book: BookEntity,
    val translatedPages: Int,
)
