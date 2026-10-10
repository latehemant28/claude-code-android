package com.example.hinglishpdf.data.project

import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.units.TextUnit
import com.example.hinglishpdf.pipeline.units.TextUnitExtractor
import com.example.hinglishpdf.pipeline.units.XliffWriter
import java.io.File

/**
 * A book's translation project on disk, in the app's private folder:
 * `book.xlf` (XLIFF 2.1: the text units with their inline codes and, as
 * they are done, their translations) and `skeleton.json` (everything else).
 * Written after analysis, when the book pauses or finishes, and on export.
 * Saved translations keep going into the database after every request;
 * these files are rewritten from it.
 */
class ProjectFiles(private val root: File, private val config: () -> PipelineConfig) {

    class Written(val xliff: String, val skeleton: String)

    fun folder(bookId: Long) = File(root, bookId.toString())

    /** The project's XLIFF and skeleton as text; [skeletonHref] is how the XLIFF names the skeleton file. */
    fun render(book: BookEntity, doc: ParsedDocument, pages: List<PageEntity>, skeletonHref: String = SKELETON): Written {
        val extraction = TextUnitExtractor.extract(doc, book.format.extension, "${book.title}.${book.format.extension}", config())
        val xliff = XliffWriter.write(
            extraction.units,
            sourceName = "${book.title}.${book.format.extension}",
            skeletonHref = skeletonHref,
            srcLang = doc.language?.substringBefore('-')?.ifBlank { null } ?: "en",
            trgLang = TARGET_LANGUAGE,
            targets = targets(extraction.units, doc, pages),
        )
        return Written(xliff, extraction.skeleton.toJson())
    }

    /** Writes both files; each is replaced only once fully written. */
    fun write(book: BookEntity, doc: ParsedDocument, pages: List<PageEntity>): File {
        val dir = folder(book.id).apply { mkdirs() }
        val written = render(book, doc, pages)
        replace(File(dir, SKELETON), written.skeleton)
        replace(File(dir, XLIFF), written.xliff)
        return File(dir, XLIFF)
    }

    private fun replace(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val XLIFF = "book.xlf"
        const val SKELETON = "skeleton.json"
        const val TARGET_LANGUAGE = "hi"

        /**
         * Each unit's saved translation: the translations of its paragraph's
         * blocks on the pages (a paragraph across a page break has one per
         * page), joined in order; none until every part is translated.
         */
        fun targets(units: List<TextUnit>, doc: ParsedDocument, pages: List<PageEntity>): Map<String, String> {
            val parts = mutableMapOf<Int, MutableMap<Int, String>>()
            for (page in pages) {
                val translations = page.translations ?: continue
                page.sourceBlocks.forEachIndexed { i, block ->
                    val paragraph = block.paragraph ?: return@forEachIndexed
                    translations.getOrNull(i)?.let { parts.getOrPut(paragraph) { mutableMapOf() }[block.part] = it }
                }
            }
            return units.mapNotNull { u ->
                val index = u.paragraph ?: return@mapNotNull null
                val found = parts[index] ?: return@mapNotNull null
                val expected = doc.paragraphs[index].parts.size.coerceAtLeast(1)
                if (found.size < expected) null else u.id to found.toSortedMap().values.joinToString(" ").trim()
            }.toMap()
        }
    }
}
