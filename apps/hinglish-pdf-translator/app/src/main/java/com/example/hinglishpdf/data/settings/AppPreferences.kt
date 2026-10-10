package com.example.hinglishpdf.data.settings

import android.content.Context
import com.example.hinglishpdf.data.document.DocFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The output format toggle and the Terms of Use acceptance, saved on this phone. */
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
        const val TERMS_ACCEPTED_AT = "terms_accepted_at"
        const val TERMS_VERSION = "terms_version"

        /** Raise when the Terms text changes, so everyone is asked again. */
        const val CURRENT_TERMS_VERSION = 1
    }
}
