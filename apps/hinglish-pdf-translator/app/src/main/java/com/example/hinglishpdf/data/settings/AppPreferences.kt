package com.example.hinglishpdf.data.settings

import android.content.Context
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.llm.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The From / To languages, the output format toggle, the Terms of Use
 * acceptance, the reader and voice settings and the first-launch flags, saved on this
 * phone (app-private SharedPreferences).
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val _outputFormat = MutableStateFlow(
        runCatching { DocFormat.valueOf(prefs.getString(OUTPUT_FORMAT, null)!!) }.getOrDefault(DocFormat.EPUB),
    )

    /** The format "Save to Downloads" (and the automatic save at the end) writes. EPUB by default. */
    val outputFormat: StateFlow<DocFormat> = _outputFormat.asStateFlow()

    fun setOutputFormat(format: DocFormat) {
        prefs.edit().putString(OUTPUT_FORMAT, format.name).apply()
        _outputFormat.value = format
    }

    private val _sourceLanguage = MutableStateFlow(
        Language.fromCode(prefs.getString(SOURCE_LANGUAGE, null), fallback = Language.AUTO_DETECT),
    )

    /** "From" for books added next: Auto-Detect by default. */
    val sourceLanguage: StateFlow<Language> = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(
        Language.fromCode(prefs.getString(TARGET_LANGUAGE, null), fallback = Language.HINDI)
            .takeIf { it != Language.AUTO_DETECT } ?: Language.HINDI,
    )

    /** "To" for books added next: Hindi by default. */
    val targetLanguage: StateFlow<Language> = _targetLanguage.asStateFlow()

    fun setSourceLanguage(language: Language) {
        prefs.edit().putString(SOURCE_LANGUAGE, language.code).apply()
        _sourceLanguage.value = language
    }

    fun setTargetLanguage(language: Language) {
        if (language == Language.AUTO_DETECT) return
        prefs.edit().putString(TARGET_LANGUAGE, language.code).apply()
        _targetLanguage.value = language
    }

    /**
     * True only on the very first launch: the "Select your Target Language"
     * popup is shown then. Recorded at once, so it never comes back by itself.
     */
    fun takeLanguagePrompt(): Boolean = takeOnce(LANGUAGE_PROMPT_SHOWN)

    /** True only on the very first launch: the spotlight tour of the main screen runs then. */
    fun takeTour(): Boolean = takeOnce(TOUR_SHOWN)

    private fun takeOnce(key: String): Boolean {
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    private val _readerDark = MutableStateFlow(prefs.getBoolean(READER_DARK, false))

    /** In-app reader: dark pages. */
    val readerDark: StateFlow<Boolean> = _readerDark.asStateFlow()

    private val _readerScale = MutableStateFlow(prefs.getFloat(READER_SCALE, 1f).coerceIn(READER_SCALE_MIN, READER_SCALE_MAX))

    /** In-app reader: text size (EPUB) or zoom (PDF), 1 = normal. */
    val readerScale: StateFlow<Float> = _readerScale.asStateFlow()

    fun setReaderDark(dark: Boolean) {
        prefs.edit().putBoolean(READER_DARK, dark).apply()
        _readerDark.value = dark
    }

    fun setReaderScale(scale: Float) {
        val value = scale.coerceIn(READER_SCALE_MIN, READER_SCALE_MAX)
        prefs.edit().putFloat(READER_SCALE, value).apply()
        _readerScale.value = value
    }

    private val _termsAcceptedAt = MutableStateFlow(
        prefs.getLong(TERMS_ACCEPTED_AT, 0L).takeIf { prefs.getInt(TERMS_VERSION, 0) >= CURRENT_TERMS_VERSION && it > 0 },
    )

    /** When the user agreed to the current Terms of Use; null until they do. */
    val termsAcceptedAt: StateFlow<Long?> = _termsAcceptedAt.asStateFlow()

    val termsAccepted: Boolean get() = _termsAcceptedAt.value != null

    fun acceptTerms(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(TERMS_ACCEPTED_AT, now).putInt(TERMS_VERSION, CURRENT_TERMS_VERSION).apply()
        _termsAcceptedAt.value = now
    }

    private val _voiceHelp = MutableStateFlow(prefs.getBoolean(VOICE_HELP, true))

    /** Spoken help: every big button says what it does, in Hindi or English. On by default. */
    val voiceHelp: StateFlow<Boolean> = _voiceHelp.asStateFlow()

    fun setVoiceHelp(on: Boolean) {
        prefs.edit().putBoolean(VOICE_HELP, on).apply()
        _voiceHelp.value = on
    }

    private val _speechRate = MutableStateFlow(prefs.getFloat(SPEECH_RATE, 1f))

    /** How fast books are read aloud: 1 = normal, [SLOW_SPEECH] = slow. */
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    fun setSpeechRate(rate: Float) {
        prefs.edit().putFloat(SPEECH_RATE, rate).apply()
        _speechRate.value = rate
    }

    /** Where listening stopped in a book (its paragraph number), to carry on from there. */
    fun listenPosition(bookId: Long): Int = prefs.getInt("$LISTEN_POSITION$bookId", 0)

    fun setListenPosition(bookId: Long, paragraph: Int) {
        prefs.edit().putInt("$LISTEN_POSITION$bookId", paragraph).apply()
    }

    companion object {
        const val SLOW_SPEECH = 0.75f
        const val READER_SCALE_MIN = 0.8f
        const val READER_SCALE_MAX = 2f
        private const val OUTPUT_FORMAT = "output_format"
        private const val SOURCE_LANGUAGE = "source_language"
        private const val TARGET_LANGUAGE = "target_language"
        private const val LANGUAGE_PROMPT_SHOWN = "language_prompt_shown"
        private const val TOUR_SHOWN = "tour_shown"
        private const val READER_DARK = "reader_dark"
        private const val READER_SCALE = "reader_scale"
        private const val TERMS_ACCEPTED_AT = "terms_accepted_at"
        private const val TERMS_VERSION = "terms_version"
        private const val VOICE_HELP = "voice_help"
        private const val SPEECH_RATE = "speech_rate"
        private const val LISTEN_POSITION = "listen_position_"

        /** Raise when the Terms text changes, so everyone is asked again. */
        private const val CURRENT_TERMS_VERSION = 1
    }
}
