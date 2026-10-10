package com.example.hinglishpdf.pipeline.assemble

import com.example.hinglishpdf.pipeline.PipelineConfig
import com.example.hinglishpdf.pipeline.segment.LineBox
import com.example.hinglishpdf.pipeline.segment.ParagraphRole
import com.example.hinglishpdf.pipeline.segment.ParsedParagraph
import com.example.hinglishpdf.pipeline.segment.Placeholders
import com.example.hinglishpdf.pipeline.segment.PlaceholderToken
import com.example.hinglishpdf.pipeline.segment.SentenceSegmenter
import com.example.hinglishpdf.pipeline.segment.SourcePart
import com.example.hinglishpdf.pipeline.segment.SourceRef

/**
 * The stage between the parsers and the segmenter: one text stream for the
 * whole book. Books flow across pages (PDF), columns and spine files (EPUB),
 * so a paragraph cut by such a break is joined back into one before it is
 * cut into sentences.
 *
 * Join rule, at a break: the last running-text block before it does not end
 * in terminal punctuation ([PipelineConfig.terminalPunctuation]), and the
 * first one after it is running text in the body font tier (never a heading
 * or a new list item, whatever its case). Footnotes, figures, captions,
 * tables and page furniture in between are skipped over (on a PDF page they
 * also count as a break: a figure set into a paragraph interrupts it). A word hyphenated
 * across the break is rejoined by [Hyphenation] (PDF only).
 *
 * Every assembled paragraph records its [SourcePart]s: which source
 * paragraph each stretch of text came from (and so its page span), for the
 * rebuilder to split it back. Order is kept, except that blocks skipped over
 * now follow the paragraph they interrupted.
 */
class DocumentAssembler(private val config: PipelineConfig, private val hyphenation: Hyphenation? = null) {

    fun assemble(paragraphs: List<ParsedParagraph>): List<ParsedParagraph> {
        val out = mutableListOf<ParsedParagraph>()
        var open = -1 // index in out of the last running-text paragraph, which may continue
        var last: ParsedParagraph? = null // the source paragraph that paragraph ends with
        var skipped = false // blocks skipped over since then
        paragraphs.forEachIndexed { index, p ->
            val single = p.copy(parts = listOf(SourcePart(index, p.ref, 0, 0)))
            if (p.role.transparent) {
                out += single
                skipped = true
                return@forEachIndexed
            }
            val previous = out.getOrNull(open)
            // On a PDF page, a figure or table set into the text interrupts it like a break; an
            // EPUB's markup is already in reading order, so only file boundaries count there.
            val atBreak = last?.let { isBreak(it, p) || (skipped && it.ref is SourceRef.Pdf) } == true
            if (previous != null && atBreak && continues(previous, p)) {
                out[open] = join(previous, p, index)
            } else {
                out += single
                open = out.lastIndex
            }
            last = p
            skipped = false
        }
        return out
    }

    /** A page, column or file break between two neighbouring blocks. */
    private fun isBreak(a: ParsedParagraph, b: ParsedParagraph): Boolean {
        val ra = a.ref
        val rb = b.ref
        return when {
            ra is SourceRef.Pdf && rb is SourceRef.Pdf -> ra.page != rb.page || a.column != b.column
            ra is SourceRef.Epub && rb is SourceRef.Epub -> ra.file != rb.file
            else -> false
        }
    }

    /** True if [b] carries on the sentence [a] leaves unfinished. */
    internal fun continues(a: ParsedParagraph, b: ParsedParagraph): Boolean {
        if (!a.role.body || a.tier != 0 || config.endsTerminally(a.text)) return false
        if (b.tier != 0 || !(b.role == ParagraphRole.PARAGRAPH || b.role == ParagraphRole.QUOTE)) return false
        val lowercase = Placeholders.strip(b.text).trimStart().firstOrNull()?.isLowerCase() == true
        return when (a.role) {
            ParagraphRole.PARAGRAPH -> b.role == ParagraphRole.PARAGRAPH || lowercase
            else -> lowercase // a list item or quotation runs on only into a lower-case continuation
        }
    }

    private fun join(a: ParsedParagraph, b: ParsedParagraph, index: Int): ParsedParagraph {
        val offset = a.tags.maxOfOrNull { it.id } ?: 0
        val bText = Placeholders.shift(b.text.trimStart(), offset)
        val aText = a.text.trimEnd()
        val text = when {
            aText.endsWith('­') -> aText.dropLast(1) + bText
            hyphenation != null -> hyphenation.join(aText, bText) ?: "$aText $bText"
            else -> "$aText $bText"
        }
        val start = text.length - bText.length
        return a.copy(
            text = text,
            tags = a.tags + b.tags.map { it.copy(id = it.id + offset) },
            lineBoxes = a.lineBoxes + b.lineBoxes.map { LineBox((it.range.first + start)..(it.range.last + start), it.page, it.box) },
            parts = a.parts + SourcePart(index, b.ref, start, offset),
        )
    }

    companion object {

        /**
         * The text each source part of [paragraph] had, with its own
         * placeholder numbers (exact for EPUB; for PDF a hyphen dropped at
         * a break is not restored).
         */
        fun sourceTexts(paragraph: ParsedParagraph): List<String> {
            val parts = paragraph.parts
            if (parts.size <= 1) return listOf(paragraph.text)
            return parts.mapIndexed { k, part ->
                val end = parts.getOrNull(k + 1)?.start ?: paragraph.text.length
                Placeholders.shift(paragraph.text.substring(part.start, end).trim(), -part.idOffset)
            }
        }

        /**
         * Splits the translation of a joined paragraph back into its source
         * parts, at the sentence boundary nearest each part's share of the
         * source text (a space if no sentence boundary fits), never inside a
         * paired placeholder and never moving a placeholder to the wrong
         * part. Each piece is renumbered to its part's own placeholders. If no
         * cut fits, the first part gets everything and the others nothing.
         */
        fun splitBack(paragraph: ParsedParagraph, translated: String): List<String> {
            val parts = paragraph.parts
            if (parts.size <= 1) return listOf(translated)
            val tokens = Placeholders.tokenize(translated)
            val ids = tokens.mapNotNull { t ->
                when (t) {
                    is PlaceholderToken.Open -> t.start to t.id
                    is PlaceholderToken.Close -> t.start to t.id
                    is PlaceholderToken.Standalone -> t.start to t.id
                    is PlaceholderToken.Text -> null
                }
            }
            val sentenceCuts = SentenceSegmenter.split(translated).map { it.start }.filter { it > 0 }
            val spaceCuts = translated.indices.filter { it > 0 && translated[it - 1].isWhitespace() && !translated[it].isWhitespace() }
            val total = paragraph.text.length.coerceAtLeast(1)

            val cuts = mutableListOf<Int>()
            var from = 0
            for (k in 1 until parts.size) {
                val boundary = parts[k].idOffset
                fun fits(c: Int) = c > from && Placeholders.openDepthAfter(tokens, c) == 0 &&
                    ids.all { (at, id) -> if (at < c) id <= boundary else id > boundary }
                val target = translated.length.toFloat() * parts[k].start / total
                val cut = (sentenceCuts.filter(::fits).ifEmpty { spaceCuts.filter(::fits) }).minByOrNull { kotlin.math.abs(it - target) }
                if (cut == null) return listOf(translated) + List(parts.size - 1) { "" }
                cuts += cut
                from = cut
            }
            val bounds = listOf(0) + cuts + translated.length
            return parts.mapIndexed { k, part ->
                Placeholders.shift(translated.substring(bounds[k], bounds[k + 1]).trim(), -part.idOffset)
            }
        }
    }
}
