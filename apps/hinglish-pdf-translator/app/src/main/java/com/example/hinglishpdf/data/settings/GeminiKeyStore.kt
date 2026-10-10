package com.example.hinglishpdf.data.settings

import android.content.Context
import com.example.hinglishpdf.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the Gemini API key comes from:
 *  1. BuildConfig.GEMINI_API_KEY, compiled in from local.properties (preferred), or
 *  2. a key pasted into the app, kept in app-private storage on this phone
 *     (for APKs built without one). Never in source code or git.
 */
class GeminiKeyStore(context: Context) {

    enum class Source { BUILD_CONFIG, ENTERED_IN_APP, NONE }

    private val prefs = context.getSharedPreferences("gemini", Context.MODE_PRIVATE)
    private val _key = MutableStateFlow(current())
    val key: StateFlow<String> = _key.asStateFlow()

    val source: Source
        get() = when {
            BuildConfig.GEMINI_API_KEY.isNotBlank() -> Source.BUILD_CONFIG
            prefs.getString(KEY, "").orEmpty().isNotBlank() -> Source.ENTERED_IN_APP
            else -> Source.NONE
        }

    fun save(apiKey: String) {
        prefs.edit().putString(KEY, apiKey.trim()).apply()
        _key.value = current()
    }

    fun clear() = save("")

    private fun current(): String =
        BuildConfig.GEMINI_API_KEY.ifBlank { prefs.getString(KEY, "").orEmpty() }

    private companion object {
        const val KEY = "api_key"
    }
}
