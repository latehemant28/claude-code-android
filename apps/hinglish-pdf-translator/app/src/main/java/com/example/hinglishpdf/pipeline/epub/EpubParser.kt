package com.example.hinglishpdf.pipeline.epub

import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.Segmenter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reads an EPUB into paragraphs and sentence segments: chapter text (from
 * the DOM's text nodes, inline tags as placeholders), table-of-contents
 * labels (NCX and nav), the title metadata and image alt text. Code, scripts,
 * styles, maths, drawings and anything marked `translate="no"` are left out;
 * stylesheets, images and fonts are never opened. Every paragraph records
 * its file and XPath. Pure JVM, so it is unit-tested without Android.
 */
object EpubParser {

    fun parse(file: File): ParsedDocument = ZipFile(file).use { zip ->
        val epub = EpubPackage(zip)
        val paragraphs = epub.units().map { unit ->
            val encoded = unit.encode()
            ParsedParagraph(role = unit.role, text = encoded.text, tags = encoded.tags, ref = unit.ref, level = unit.level)
        }
        val metadata = epub.metadata
        ParsedDocument(
            title = metadata["title"],
            language = metadata["language"],
            metadata = metadata,
            hasToc = epub.ncxPath != null || epub.navPath != null,
            pageCount = epub.contentPaths.size,
            scannedPages = emptyList(),
            needsOcr = false,
            paragraphs = paragraphs,
            segments = Segmenter.segment(paragraphs),
        )
    }
}

/**
 * Writes a translated EPUB: each paragraph's translation (placeholder text)
 * goes back into the exact element it came from, with its inline tags
 * restored; untranslated paragraphs keep their text. Titles, TOC labels and
 * alt text are replaced too, and `dc:language` / `xml:lang` are set to the
 * target language. Every other file is copied byte for byte.
 */
object EpubRebuilder {

    /**
     * @param paragraphs the paragraphs [EpubParser] returned for [source].
     * @param translations aligned with [paragraphs]; null keeps the original.
     * @param language the target language code for dc:language and xml:lang; null leaves them.
     */
    fun rebuild(
        source: File,
        target: OutputStream,
        paragraphs: List<ParsedParagraph>,
        translations: List<String?>,
        language: String?,
    ) {
        require(translations.size == paragraphs.size) { "One translation (or null) per paragraph" }
        ZipFile(source).use { zip ->
            val epub = EpubPackage(zip)
            val units = epub.units()
            if (units.size != paragraphs.size) {
                throw IOException("The EPUB changed since it was read (${units.size} paragraphs, expected ${paragraphs.size}).")
            }
            // Attributes first: an image's new alt must be in place before the run around it is rebuilt.
            val order = units.indices.sortedBy { if (units[it] is EpubUnit.Attribute) 0 else 1 }
            for (i in order) {
                val unit = units[i]
                val translation = translations[i] ?: continue
                val encoded = unit.encode()
                if (unit.ref != paragraphs[i].ref || encoded.text != paragraphs[i].text) {
                    throw IOException("The EPUB changed since it was read (paragraph ${i + 1}, ${unit.ref.file}).")
                }
                when (unit) {
                    is EpubUnit.Run -> unit.replaceWith(translation, encoded.tags)
                    is EpubUnit.Attribute -> unit.replaceWith(translation, encoded.tags)
                }
                epub.markChanged(unit.ref.file)
            }
            if (language != null) setLanguage(epub, language)

            val rewritten = epub.changedDocuments.mapValues { (_, doc) -> epub.serialize(doc).toByteArray(Charsets.UTF_8) }
            writeZip(zip, target, rewritten)
        }
    }

    private fun setLanguage(epub: EpubPackage, language: String) {
        epub.opf.allElements.filter { it.tagName() == "dc:language" }.forEach { it.text(language) }
        epub.markChanged(epub.opfPath)
        for (path in epub.contentPaths + listOfNotNull(epub.ncxPath)) {
            val doc = epub.document(path) ?: continue
            epub.markChanged(path)
            val root = doc.children().firstOrNull() ?: continue
            root.attr("xml:lang", language)
            if (root.hasAttr("lang")) root.attr("lang", language) // XHTML 1.1 (EPUB 2) has no "lang"
        }
    }

    /** The same archive with [rewritten] entries replaced; "mimetype" first and uncompressed, as EPUB requires. */
    private fun writeZip(zip: ZipFile, target: OutputStream, rewritten: Map<String, ByteArray>) {
        ZipOutputStream(target).use { out ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            out.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            out.write(mimetype)
            out.closeEntry()
            for (entry in zip.entries()) {
                if (entry.name == "mimetype" || entry.isDirectory) continue
                out.putNextEntry(ZipEntry(entry.name))
                val replacement = rewritten[entry.name]
                if (replacement != null) out.write(replacement) else zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
}
