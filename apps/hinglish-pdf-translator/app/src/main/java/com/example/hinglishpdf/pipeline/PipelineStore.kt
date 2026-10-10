package com.example.hinglishpdf.pipeline

import com.example.hinglishpdf.data.db.ParagraphEntity
import com.example.hinglishpdf.data.db.ParsedDocumentEntity
import com.example.hinglishpdf.data.db.PipelineDao
import com.example.hinglishpdf.data.db.SegmentEntity
import com.example.hinglishpdf.pipeline.segment.Box
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.ParsedSegment
import com.example.hinglishpdf.pipeline.segment.PlaceholderTag
import com.example.hinglishpdf.pipeline.segment.SourcePart
import com.example.hinglishpdf.pipeline.segment.SourceRef
import com.example.hinglishpdf.pipeline.segment.TextStyle
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * Saves a [ParsedDocument] in the database and reads it back. Structured
 * values (placeholder tags, source references, styles) are JSON columns.
 */
class PipelineStore(private val dao: PipelineDao) {

    suspend fun save(bookId: Long, doc: ParsedDocument, now: Long = System.currentTimeMillis()) {
        val document = ParsedDocumentEntity(
            bookId = bookId,
            title = doc.title,
            language = doc.language,
            metadata = gson.toJson(doc.metadata),
            hasToc = doc.hasToc,
            pageCount = doc.pageCount,
            scannedPages = gson.toJson(doc.scannedPages),
            needsOcr = doc.needsOcr,
            parserVersion = PARSER_VERSION,
            parsedAt = now,
        )
        val paragraphs = doc.paragraphs.mapIndexed { i, p ->
            ParagraphEntity(
                bookId = bookId,
                ordinal = i,
                role = p.role.name,
                level = p.level,
                text = p.text,
                tags = gson.toJson(p.tags),
                ref = refToJson(p.ref),
                style = layoutToJson(p),
                translatable = p.translatable,
            )
        }
        dao.replace(document, paragraphs) { ids ->
            doc.segments.mapIndexed { i, s ->
                SegmentEntity(
                    bookId = bookId,
                    paragraphId = ids[s.paragraph],
                    ordinal = i,
                    indexInParagraph = s.index,
                    text = s.text,
                    separator = s.separator,
                    ref = refToJson(s.ref),
                    tags = gson.toJson(s.tags),
                    hash = s.hash,
                    words = s.words,
                )
            }
        }
    }

    /** The saved parse of a book, or null if it has none yet. */
    suspend fun load(bookId: Long): ParsedDocument? {
        val document = dao.document(bookId) ?: return null
        val paragraphRows = dao.paragraphs(bookId)
        val ordinalById = paragraphRows.associate { it.id to it.ordinal }
        val paragraphs = paragraphRows.map { row ->
            val ref = refFromJson(row.ref)
            val layout = row.style?.let { JsonParser.parseString(it).asJsonObject }
            ParsedParagraph(
                role = ParagraphRole.entries.firstOrNull { it.name == row.role } ?: ParagraphRole.UNKNOWN,
                text = row.text,
                tags = gson.fromJson(row.tags, tagsType),
                ref = ref,
                level = row.level,
                style = layout?.takeIf { it.has("font") }?.let { gson.fromJson(it, TextStyle::class.java) },
                tier = layout?.get("tier")?.asInt ?: 0,
                column = layout?.get("column")?.asInt ?: 0,
                marker = layout?.get("marker")?.asString,
                parts = layout?.get("parts")?.asJsonArray?.map { e ->
                    val o = e.asJsonObject
                    SourcePart(o["index"].asInt, refFromJson(o["ref"].toString()), o["start"].asInt, o["idOffset"].asInt)
                } ?: listOfNotNull(layout?.get("source")?.asInt?.let { SourcePart(it, ref, 0, 0) }),
            )
        }
        val segments = dao.segments(bookId).map { row ->
            ParsedSegment(
                paragraph = ordinalById.getValue(row.paragraphId),
                index = row.indexInParagraph,
                text = row.text,
                separator = row.separator,
                ref = refFromJson(row.ref),
                tags = gson.fromJson(row.tags, tagsType),
                hash = row.hash,
                words = row.words,
            )
        }
        return ParsedDocument(
            title = document.title,
            language = document.language,
            metadata = gson.fromJson(document.metadata, metadataType),
            hasToc = document.hasToc,
            pageCount = document.pageCount,
            scannedPages = gson.fromJson(document.scannedPages, pagesType),
            needsOcr = document.needsOcr,
            paragraphs = paragraphs,
            segments = segments,
        )
    }

    companion object {
        /**
         * Raised when parsing changes in a way that makes stored parses
         * outdated. 2: page furniture, columns by region, font tiers,
         * paragraphs joined across pages, columns and files. 3: stage 1
         * layout (X-Y cut reading order, weighted block classification,
         * tables and figures read where they stand, footnotes last).
         */
        const val PARSER_VERSION = 3

        private val gson = Gson()
        private val tagsType = object : TypeToken<List<PlaceholderTag>>() {}.type
        private val metadataType = object : TypeToken<Map<String, String>>() {}.type
        private val pagesType = object : TypeToken<List<Int>>() {}.type

        /**
         * The paragraph's layout for the `style` column: its text style
         * (PDF) with font tier, column, list marker and source parts beside
         * it. Null when there is nothing to keep.
         */
        fun layoutToJson(p: ParsedParagraph): String? {
            val o = p.style?.let { gson.toJsonTree(it).asJsonObject } ?: JsonObject()
            if (p.tier != 0) o.addProperty("tier", p.tier)
            if (p.column != 0) o.addProperty("column", p.column)
            p.marker?.let { o.addProperty("marker", it) }
            when {
                p.parts.size > 1 -> o.add(
                    "parts",
                    JsonArray().apply {
                        p.parts.forEach { part ->
                            add(
                                JsonObject().apply {
                                    addProperty("index", part.index)
                                    add("ref", JsonParser.parseString(refToJson(part.ref)))
                                    addProperty("start", part.start)
                                    addProperty("idOffset", part.idOffset)
                                },
                            )
                        }
                    },
                )
                p.parts.size == 1 -> o.addProperty("source", p.parts.single().index)
            }
            return if (o.size() == 0) null else o.toString()
        }

        fun refToJson(ref: SourceRef): String = when (ref) {
            is SourceRef.Epub -> JsonObject().apply {
                addProperty("type", "epub")
                addProperty("file", ref.file)
                addProperty("xpath", ref.xpath)
                addProperty("run", ref.run)
                ref.attribute?.let { addProperty("attribute", it) }
            }
            is SourceRef.Pdf -> JsonObject().apply {
                addProperty("type", "pdf")
                addProperty("page", ref.page)
                add("box", JsonArray().apply { listOf(ref.box.x0, ref.box.y0, ref.box.x1, ref.box.y1).forEach { add(it) } })
            }
        }.toString()

        fun refFromJson(json: String): SourceRef {
            val o = JsonParser.parseString(json).asJsonObject
            return when (o["type"].asString) {
                "epub" -> SourceRef.Epub(
                    file = o["file"].asString,
                    xpath = o["xpath"].asString,
                    run = o["run"]?.asInt ?: 0,
                    attribute = o["attribute"]?.asString,
                )
                else -> {
                    val b = o["box"].asJsonArray.map { it.asFloat }
                    SourceRef.Pdf(o["page"].asInt, Box(b[0], b[1], b[2], b[3]))
                }
            }
        }
    }
}
