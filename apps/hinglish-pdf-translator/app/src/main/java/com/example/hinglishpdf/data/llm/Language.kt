package com.example.hinglishpdf.data.llm

/**
 * Languages offered in the From / To dropdowns. [englishName] goes into the
 * prompt; [code] (BCP 47) is stored with each book and labels the exported
 * EPUB, so e-readers pick the right fonts and text direction.
 */
enum class Language(
    val englishName: String,
    /** The language's own name, shown next to the English one ("Hindi · हिन्दी"). */
    val nativeName: String?,
    val code: String,
    val rightToLeft: Boolean = false,
    /** Written in Devanagari: exports embed Noto Sans Devanagari. */
    val devanagari: Boolean = false,
) {
    AUTO_DETECT("Auto-Detect", null, "auto"),
    ENGLISH("English", null, "en"),
    HINDI("Hindi", "हिन्दी", "hi", devanagari = true),
    SPANISH("Spanish", "Español", "es"),
    FRENCH("French", "Français", "fr"),
    GERMAN("German", "Deutsch", "de"),
    PORTUGUESE("Portuguese", "Português", "pt"),
    ITALIAN("Italian", "Italiano", "it"),
    DUTCH("Dutch", "Nederlands", "nl"),
    RUSSIAN("Russian", "Русский", "ru"),
    TURKISH("Turkish", "Türkçe", "tr"),
    ARABIC("Arabic", "العربية", "ar", rightToLeft = true),
    URDU("Urdu", "اردو", "ur", rightToLeft = true),
    BENGALI("Bengali", "বাংলা", "bn"),
    MARATHI("Marathi", "मराठी", "mr", devanagari = true),
    NEPALI("Nepali", "नेपाली", "ne", devanagari = true),
    GUJARATI("Gujarati", "ગુજરાતી", "gu"),
    PUNJABI("Punjabi", "ਪੰਜਾਬੀ", "pa"),
    TAMIL("Tamil", "தமிழ்", "ta"),
    TELUGU("Telugu", "తెలుగు", "te"),
    KANNADA("Kannada", "ಕನ್ನಡ", "kn"),
    MALAYALAM("Malayalam", "മലയാളം", "ml"),
    JAPANESE("Japanese", "日本語", "ja"),
    KOREAN("Korean", "한국어", "ko"),
    CHINESE_SIMPLIFIED("Chinese (Simplified)", "简体中文", "zh-Hans"),
    INDONESIAN("Indonesian", "Bahasa Indonesia", "id"),
    VIETNAMESE("Vietnamese", "Tiếng Việt", "vi"),
    THAI("Thai", "ไทย", "th"),
    ;

    /** Short enough for the narrow From / To fields ("Chinese" rather than "Chinese (Simplified)"). */
    val shortName: String get() = englishName.substringBefore(" (")

    /** "Hindi · हिन्दी" for the dropdowns. */
    val label: String get() = if (nativeName == null) englishName else "$englishName · $nativeName"

    /** Written in the Latin alphabet, like English (used to spot text left untranslated). */
    val latinScript: Boolean
        get() = this in LATIN_SCRIPT

    /** Chinese, Japanese and Korean need far fewer characters than English for the same text. */
    val compactScript: Boolean
        get() = this == JAPANESE || this == KOREAN || this == CHINESE_SIMPLIFIED

    /** What the prompt says for {sourceLanguage} / {targetLanguage}. */
    val promptName: String
        get() = if (this == AUTO_DETECT) "its original language (detect it automatically)" else englishName

    companion object {
        private val LATIN_SCRIPT = setOf(
            ENGLISH, SPANISH, FRENCH, GERMAN, PORTUGUESE, ITALIAN, DUTCH, TURKISH, INDONESIAN, VIETNAMESE,
        )

        /** "From": Auto-Detect first, then every language. */
        val sources: List<Language> = entries.toList()

        /** "To": every language (Auto-Detect is not a target). */
        val targets: List<Language> = entries.filter { it != AUTO_DETECT }

        /**
         * The language stored with a book. Books from before 5.0 kept
         * "Hinglish" there and were always translated into Hindi.
         */
        fun fromCode(code: String?, fallback: Language): Language =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: fallback
    }
}
