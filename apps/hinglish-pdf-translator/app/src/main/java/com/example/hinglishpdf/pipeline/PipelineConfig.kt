package com.example.hinglishpdf.pipeline

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * Every threshold and pattern the parser, the assembler and the chunker use.
 * The defaults below are also written out in `assets/pipeline-config.json`
 * (a test keeps the two equal); the app reads that file, so the values can
 * be tuned without touching code. Sizes ending in `Em` are multiples of the
 * line's font size; ratios are fractions of the page or column.
 *
 * Patterns are Java regular expressions. All of them are matched against
 * one trimmed line (or, for [boilerplatePagePatterns], a whole page's text).
 */
data class PipelineConfig(
    // ---------------------------------------------------------------- page furniture
    /** Top margin zone, as a share of the page height: running headers live here. */
    val marginTop: Float = 0.08f,
    /** Bottom margin zone: running footers and page numbers. */
    val marginBottom: Float = 0.08f,
    /** A margin line repeated (digits ignored) on at least this many pages is a running header or footer... */
    val repetitionMinPages: Int = 2,
    /** ...and on at least this share of the pages of the same side (odd or even pages counted apart). */
    val repetitionShare: Float = 0.3f,
    /** ...or on this many pages of the same side in a row (chapter titles in running heads). */
    val repetitionMinRun: Int = 3,
    /** Margin lines like these are running headers or footers even when they do not repeat. */
    val headerFooterPatterns: List<String> = listOf("^\\d+\\s+\\S.{0,40}$", "^.{0,40}\\s+\\d+$"),
    /** Margin lines that are page numbers. Case is ignored. */
    val pageNumberPatterns: List<String> = listOf(
        "^(?:page\\s+)?(?:\\d{1,4}|[ivxlcdm]{1,7})(?:\\s*(?:/|of)\\s*\\d{1,4})?$",
        "^[-–—]\\s*(?:\\d{1,4}|[ivxlcdm]{1,7})\\s*[-–—]$",
    ),
    /** A page whose whole text matches one of these is skipped (case ignored). */
    val boilerplatePagePatterns: List<String> = listOf(
        "^(?:this\\s+page\\s+(?:has\\s+been\\s+|is\\s+)?(?:intentionally|deliberately)\\s+left\\s+blank\\.?)$",
        "^(?:\\[?\\s*blank\\s+page\\s*]?)$",
    ),
    /** Lines like these are printer's marks (slugs, time stamps), wherever they are (case ignored). */
    val boilerplateLinePatterns: List<String> = listOf(
        "\\.(?:indd|qxd|qxp)\\b",
        "^\\d{1,2}/\\d{1,2}/\\d{2,4},?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]\\.?m\\.?)?$",
    ),

    // ---------------------------------------------------------------- blocks
    /** A text line that starts like one of these is a caption (case ignored). */
    val captionPatterns: List<String> = listOf(
        "^(?:figure|fig\\.|table|plate|chart|illustration|map|exhibit|diagram)\\s*[0-9ivxlc]+[a-z]?(?:[.:\\-–—]|\\s)",
    ),
    /** A short block this close to an image (in font sizes), above or below it, is its caption. */
    val captionGapEm: Float = 1.5f,
    /** Lines of a caption beside an image, at most. */
    val captionMaxLines: Int = 3,
    /** A new paragraph starts where the space between baselines exceeds this many times the usual line spacing. */
    val paragraphGapRatio: Float = 1.3f,
    /** Usual line spacing, in font sizes, until the book's own is measured. */
    val defaultLeadingEm: Float = 1.2f,
    /** A line ending this far (font sizes) short of its column's right edge is a paragraph's last line. */
    val shortLineEm: Float = 2.0f,
    /** Images covering more than this share of the page are backgrounds, not figures. */
    val figureMaxPageShare: Float = 0.6f,
    /** A first line indented by more than this many font sizes starts a paragraph. */
    val indentEm: Float = 0.8f,
    /** A list item's following lines are indented at least this much past its first line; less ends the item. */
    val listContinuationEm: Float = 0.5f,
    /** Each step of this many font sizes of indent is one list nesting level. */
    val listLevelEm: Float = 1.5f,
    /** A gap this wide (font sizes) inside a baseline splits it into separate pieces (columns, cells). */
    val columnGapEm: Float = 1.0f,
    /** A gap this wide (font sizes) between glyphs is a space between words. */
    val wordGapEm: Float = 0.15f,
    /** Narrowest gutter between two columns, in points. */
    val minGutter: Float = 8f,
    /** X-Y cut: horizontal whitespace at least this high (body font sizes) cuts a region into bands. */
    val xyBandGapEm: Float = 0.5f,
    /** X-Y cut: vertical whitespace at least this wide (body font sizes, and [minGutter]) cuts a band into columns. */
    val xyColumnGapEm: Float = 1.0f,
    /** X-Y cut: lines (or tables, figures) each side of a column cut needs at least. */
    val xyMinColumnItems: Int = 2,
    /**
     * X-Y cut: bands one above the other whose gutters line up are one
     * column region, so a paragraph gap that happens to line up across the
     * columns does not make the page read row by row.
     */
    val xyMergeAlignedBands: Boolean = true,
    /** Footnotes are read after the rest of their page. */
    val footnotesLast: Boolean = true,
    /** How much each signal counts towards each block role (see [LayoutWeights]). */
    val weights: LayoutWeights = LayoutWeights(),
    /**
     * In a column region, a column whose lines run on into a lower-case
     * next line less often than this is read line by line, each line its own
     * block (addresses, contents columns); prose columns run on far more.
     */
    val columnMinFlow: Float = 0.3f,
    /** Table rows: cells narrower than this share of the text width... */
    val tableCellMaxRatio: Float = 0.45f,
    /** ...narrower than this on average... */
    val tableCellMeanRatio: Float = 0.3f,
    /** ...and text that flows down a column less often than this (more is columns of prose). */
    val tableMaxFlow: Float = 0.3f,
    /** A line at least this many times the body size is a heading. */
    val headingSizeRatio: Float = 1.15f,
    /** A bold line of at most this many words, without closing punctuation, is a heading. */
    val boldHeadingMaxWords: Int = 12,
    /** Lines at most this many times the body size, low on the page, are footnotes. */
    val footnoteSizeRatio: Float = 0.9f,
    /** Footnotes sit below this share of the page height. */
    val footnoteZone: Float = 0.6f,
    /** Sizes within this share of each other are one font tier. */
    val tierTolerance: Float = 0.06f,
    /** Bullets that start a list item. */
    val bulletPattern: String = "^[•●○◦▪▫■□‣⁃∙·➢➤►▶✓✔→\\uE000-\\uF8FF]\\s*",
    /** Numbers or letters that start a list item ("1.", "(a)", "iv)"). */
    val numberedPattern: String = "^\\(?(?:\\d{1,3}|[a-z]|[ivx]{1,5})[.)]\\s+\\S",
    /** How a footnote starts: its number or mark. */
    val footnoteStartPattern: String = "^(?:\\d{1,3}|[*†‡§¹²³⁴⁵⁶⁷⁸⁹⁰]+)[.)]?\\s*\\S",

    // ---------------------------------------------------------------- assembly
    /** A block ending in one of these characters is finished; anything else may continue after a break. */
    val terminalPunctuation: String = ".!?:;\"”’…।॥。！？",
    /**
     * Words that also stand alone, so "well-" + "known" keeps its hyphen. The
     * book's own text adds to this list (see [standaloneMinCount]); it is
     * empty by default so the app stays language-neutral.
     */
    val commonStandaloneWords: List<String> = emptyList(),
    /** A word seen on its own at least this many times in the book counts as a standalone word. */
    val standaloneMinCount: Int = 2,

    // ---------------------------------------------------------------- chunking
    /** Whole paragraphs per request, at most. */
    val chunkMaxParagraphs: Int = 3,
    /** Words per request, at most (a longer paragraph goes alone, cut at sentence ends). */
    val chunkMaxWords: Int = 800,
) {
    // Compiled once per config.
    @delegate:Transient val headerFooter by lazy { headerFooterPatterns.map(::Regex) }
    @delegate:Transient val pageNumber by lazy { pageNumberPatterns.map { Regex(it, RegexOption.IGNORE_CASE) } }
    @delegate:Transient val boilerplatePage by lazy { boilerplatePagePatterns.map { Regex(it, RegexOption.IGNORE_CASE) } }
    @delegate:Transient val boilerplateLine by lazy { boilerplateLinePatterns.map { Regex(it, RegexOption.IGNORE_CASE) } }
    @delegate:Transient val caption by lazy { captionPatterns.map { Regex(it, RegexOption.IGNORE_CASE) } }
    @delegate:Transient val bullet by lazy { Regex(bulletPattern) }
    @delegate:Transient val numbered by lazy { Regex(numberedPattern) }
    @delegate:Transient val footnoteStart by lazy { Regex(footnoteStartPattern) }
    @delegate:Transient val standaloneWords by lazy { commonStandaloneWords.map { it.lowercase() }.toSet() }

    /** True if [text] ends finished: its last visible character is terminal punctuation. */
    fun endsTerminally(text: String): Boolean {
        val visible = com.example.hinglishpdf.pipeline.segment.Placeholders.strip(text).trimEnd()
        return visible.isNotEmpty() && visible.last() in terminalPunctuation
    }

    companion object {
        const val ASSET = "pipeline-config.json"
        private const val TAG = "PipelineConfig"

        fun fromJson(json: String): PipelineConfig = Gson().fromJson(json, PipelineConfig::class.java)

        /** The app's config from its assets; the built-in defaults if the file is missing or broken. */
        fun load(context: Context): PipelineConfig = try {
            context.assets.open(ASSET).bufferedReader().use { fromJson(it.readText()) }
        } catch (e: Exception) {
            Log.w(TAG, "Using the built-in pipeline config", e)
            PipelineConfig()
        }
    }
}

/**
 * Stage 1 block classification: each signal adds its weight to a role's
 * score and the highest score wins (ties go to body text). A signal only
 * counts where it applies: the margin-zone signals only in the top or
 * bottom zone, the caption signals only for short blocks, and so on.
 */
data class LayoutWeights(
    /** Every block's score as running text. */
    val body: Float = 1.0f,
    /** Every block's score as "unknown". */
    val unknown: Float = 0.2f,
    /** In the top / bottom margin zone: towards header / footer and page number. */
    val marginZone: Float = 0.6f,
    /** Repeated on enough pages of its side, or a run of them: towards header / footer. */
    val repetition: Float = 1.0f,
    /** Shaped like a page number (in a margin zone). */
    val pageNumberPattern: Float = 1.1f,
    /** Shaped like a running head whose number follows the page (in a margin zone, set apart). */
    val runningHeadPattern: Float = 0.6f,
    /** Taken off a running-head shape that could be a one-line footnote. */
    val footnoteLikePenalty: Float = 0.8f,
    /** A printer's mark or a "left blank" page. */
    val boilerplatePattern: Float = 3.0f,
    /** Small, set apart, in a margin zone, and nothing else: a stray note. */
    val strayMargin: Float = 1.3f,
    /** Set in a larger font tier than the body. */
    val headingTier: Float = 1.5f,
    /** A short bold line without closing punctuation. */
    val boldHeading: Float = 1.2f,
    /** Starts with a bullet. */
    val bullet: Float = 2.0f,
    /** Starts with a number or letter and a point, where a list can start. */
    val numbered: Float = 1.5f,
    /** Starts like "Figure 3." / "Table 2:". */
    val captionPattern: Float = 1.2f,
    /** Short and right above or below an image. */
    val nearImage: Float = 1.2f,
    /** Drawn inside an image. */
    val insideFigure: Float = 3.0f,
    /** Small and low on the page. */
    val footnoteZone: Float = 0.9f,
    /** Starts with a footnote number or mark. */
    val footnoteMark: Float = 0.5f,
    /** Right after a footnote, in the same small type. */
    val footnoteContinues: Float = 0.5f,
    /** In a table. */
    val tableCell: Float = 4.0f,
)

