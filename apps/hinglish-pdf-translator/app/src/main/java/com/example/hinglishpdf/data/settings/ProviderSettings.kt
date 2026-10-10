package com.example.hinglishpdf.data.settings

import android.content.Context
import com.example.hinglishpdf.BuildConfig
import com.example.hinglishpdf.data.ai.AIProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AI provider picked in the app, and an API key (plus an optional model
 * name) for each provider. Keys pasted into the app stay in app-private
 * storage on this phone, never in code or git. For Gemini,
 * BuildConfig.GEMINI_API_KEY (from local.properties) takes priority.
 */
class ProviderSettings(context: Context) {

    enum class KeySource { BUILD_CONFIG, ENTERED_IN_APP, NONE }

    data class State(
        val provider: AIProvider,
        private val keys: Map<AIProvider, String>,
        private val models: Map<AIProvider, String>,
    ) {
        fun key(p: AIProvider): String =
            if (p == AIProvider.GEMINI) BuildConfig.GEMINI_API_KEY.ifBlank { keys[p].orEmpty() } else keys[p].orEmpty()

        fun keySource(p: AIProvider): KeySource = when {
            p == AIProvider.GEMINI && BuildConfig.GEMINI_API_KEY.isNotBlank() -> KeySource.BUILD_CONFIG
            keys[p].orEmpty().isNotBlank() -> KeySource.ENTERED_IN_APP
            else -> KeySource.NONE
        }

        fun customModel(p: AIProvider): String = models[p].orEmpty()

        val key: String get() = key(provider)
        val configured: Boolean get() = key.isNotBlank()
    }

    private val prefs = context.getSharedPreferences("providers", Context.MODE_PRIVATE)

    init {
        // Versions before 4.0 kept one Gemini key in their own file.
        val legacy = context.getSharedPreferences("gemini", Context.MODE_PRIVATE)
        legacy.getString("api_key", null)?.takeIf { it.isNotBlank() }?.let { old ->
            if (prefs.getString(keyName(AIProvider.GEMINI), null).isNullOrBlank()) {
                prefs.edit().putString(keyName(AIProvider.GEMINI), old).apply()
            }
            legacy.edit().clear().apply()
        }
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    fun select(provider: AIProvider) {
        prefs.edit().putString(SELECTED, provider.name).apply()
        _state.value = read()
    }

    fun saveKey(provider: AIProvider, apiKey: String) {
        prefs.edit().putString(keyName(provider), apiKey.trim()).apply()
        _state.value = read()
    }

    fun clearKey(provider: AIProvider) = saveKey(provider, "")

    fun setModel(provider: AIProvider, model: String) {
        prefs.edit().putString(modelName(provider), model.trim()).apply()
        _state.value = read()
    }

    private fun read() = State(
        provider = runCatching { AIProvider.valueOf(prefs.getString(SELECTED, null)!!) }.getOrDefault(AIProvider.GEMINI),
        keys = AIProvider.entries.associateWith { prefs.getString(keyName(it), "").orEmpty() },
        models = AIProvider.entries.associateWith { prefs.getString(modelName(it), "").orEmpty() },
    )

    private fun keyName(p: AIProvider) = "key_${p.name}"
    private fun modelName(p: AIProvider) = "model_${p.name}"

    private companion object {
        const val SELECTED = "selected_provider"
    }
}
