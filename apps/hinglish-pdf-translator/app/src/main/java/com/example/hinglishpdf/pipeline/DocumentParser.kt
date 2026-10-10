package com.example.hinglishpdf.pipeline

import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.pipeline.epub.EpubParser
import com.example.hinglishpdf.pipeline.pdf.PdfParser
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import java.io.File

/** A PDF that is (mostly) pictures of text: it has to go through OCR first. */
class NeedsOcrException(scanned: Int, pages: Int) : IllegalStateException(
    "This PDF is scanned: $scanned of $pages pages are pictures of text, with no text to translate. " +
        "It needs text recognition (OCR), which is coming in a later update.",
)

/** The pipeline's Parser + Segmenter stage: the right parser for the format. */
class DocumentParser(private val tempDir: File?, private val config: () -> PipelineConfig = { PipelineConfig() }) {

    fun parse(file: File, format: DocFormat, onProgress: (label: String, done: Int, total: Int) -> Unit = { _, _, _ -> }): ParsedDocument =
        when (format) {
            DocFormat.EPUB -> EpubParser.parse(file, config())
            DocFormat.PDF -> PdfParser.parse(file, tempDir, config()) { page, count -> onProgress("Analysing page $page of $count", page, count) }
        }
}
