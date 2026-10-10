package com.example.hinglishpdf.data.settings

import android.content.Context
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.llm.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The From / To languages, the output format toggle, the Terms of Use
 * acceptance and the first-launch onboarding flag, saved on this phone.
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
     * True only the very first time the app opens: the onboarding is shown
     * then, and this records it at once, so it never appears on its own again
     * (even if the app is closed in the middle of it).
     */
    fun takeFirstLaunch(): Boolean {
        if (prefs.getBoolean(ONBOARDING_SHOWN, false)) return false
        prefs.edit().putBoolean(ONBOARDING_SHOWN, true).apply()
        return true
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

    private companion object {
        const val OUTPUT_FORMAT = "output_format"
        const val SOURCE_LANGUAGE = "source_language"
        const val TARGET_LANGUAGE = "target_language"
        const val ONBOARDING_SHOWN = "onboarding_shown"
        const val TERMS_ACCEPTED_AT = "terms_accepted_at"
        const val TERMS_VERSION = "terms_version"

        /** Raise when the Terms text changes, so everyone is asked again. */
        const val CURRENT_TERMS_VERSION = 1
    }
}
