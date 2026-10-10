package com.example.hinglishpdf.data.document

import com.example.hinglishpdf.data.llm.Language

/**
 * The few words the app itself writes into an exported book (table of
 * contents, chapters named after pages, empty-page notes): in Hindi for a
 * Hindi translation, in English for every other language.
 */
data class ExportLabels(
    val contents: String,
    val pagePrefix: String,
    val partPrefix: String,
    val emptyPage: String,
    val emptyBook: String,
) {
    fun pages(first: Int, last: Int): String = if (first == last) "$pagePrefix $first" else "$pagePrefix $first–$last"

    fun part(number: Int): String = "$partPrefix $number"

    companion object {
        val HINDI = ExportLabels(
            contents = "विषय-सूची",
            pagePrefix = "पृष्ठ",
            partPrefix = "भाग",
            emptyPage = "(इस पेज पर कोई टेक्स्ट नहीं है)",
            emptyBook = "(कोई टेक्स्ट नहीं)",
        )
        val ENGLISH = ExportLabels(
            contents = "Contents",
            pagePrefix = "Pages",
            partPrefix = "Part",
            emptyPage = "(No text on this page of the original)",
            emptyBook = "(No text)",
        )

        fun forLanguage(language: Language): ExportLabels = if (language == Language.HINDI) HINDI else ENGLISH
    }
}
