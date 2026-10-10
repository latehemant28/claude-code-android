package com.example.hinglishpdf.pipeline.epub

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.assemble.DocumentAssembler
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.Segmenter
import com.example.hinglishpdf.pipeline.segment.SourcePart
import com.example.hinglishpdf.pipeline.segment.SourceRef
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
 * its file and XPath. A paragraph cut by a file boundary is joined back
 * (see DocumentAssembler). Pure JVM, so it is unit-tested without Android.
 */
object EpubParser {

    fun parse(file: File, config: PipelineConfig = PipelineConfig()): ParsedDocument = ZipFile(file).use { zip ->
        val epub = EpubPackage(zip)
        val units = epub.units().map { unit ->
            val encoded = unit.encode()
            ParsedParagraph(role = unit.role, text = encoded.text, tags = encoded.tags, ref = unit.ref, level = unit.level)
        }
        // Some publishers split a chapter into several files mid-paragraph: join those back.
        val paragraphs = DocumentAssembler(config).assemble(units)
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
     * @param paragraphs the paragraphs [EpubParser] returned for [source]
     *   (joined ones are split back to their elements).
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
        // One expected text and translation per element, joined paragraphs split back.
        val expected = mutableMapOf<Int, Pair<SourceRef, String>>()
        val perUnit = mutableMapOf<Int, String>()
        paragraphs.forEachIndexed { i, p ->
            val parts = p.parts.ifEmpty { listOf(SourcePart(i, p.ref, 0, 0)) }
            val texts = DocumentAssembler.sourceTexts(p)
            val split = translations[i]?.let { DocumentAssembler.splitBack(p, it) }
            parts.forEachIndexed { k, part ->
                expected[part.index] = part.ref to texts[k]
                split?.let { perUnit[part.index] = it[k] }
            }
        }
        ZipFile(source).use { zip ->
            val epub = EpubPackage(zip)
            val units = epub.units()
            if (units.size != expected.size || expected.keys.any { it !in units.indices }) {
                throw IOException("The EPUB changed since it was read (${units.size} paragraphs, expected ${expected.size}).")
            }
            // Attributes first: an image's new alt must be in place before the run around it is rebuilt.
            val order = units.indices.sortedBy { if (units[it] is EpubUnit.Attribute) 0 else 1 }
            for (i in order) {
                val unit = units[i]
                val translation = perUnit[i] ?: continue
                val encoded = unit.encode()
                val (ref, text) = expected.getValue(i)
                if (unit.ref != ref || encoded.text != text) {
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
