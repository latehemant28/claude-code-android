package com.example.hinglishpdf.data.ai

/**
 * The AI services the user can pick in the app. Each one knows where its API
 * keys are made ([keyPageUrl], opened in the in-app browser), which models to
 * try, how hard it may be paced, and which [AITranslator] strategy talks to
 * it ([createTranslator]).
 */
enum class AIProvider(
    val displayName: String,
    /** The provider's official API-key dashboard. */
    val keyPageUrl: String,
    /** Tried in this order; a model the user types in the app is tried first. */
    val defaultModels: List<String>,
    /**
     * Smart throttling: the pause between pages (and the spacing between
     * requests), sized for the free tier's requests-per-minute limit.
     */
    val chunkPauseMillis: Long,
    /** Wait after a rate-limit (429) answer before trying again: a full minute lets the limit refresh. */
    val rateLimitWaitMillis: Long = 60_000,
    /** Shown under the key: where the text goes and what it costs. */
    val dataNote: String,
) {
    GEMINI(
        displayName = "Google Gemini",
        keyPageUrl = "https://aistudio.google.com/app/apikey",
        defaultModels = GEMINI_MODELS,
        chunkPauseMillis = 4_000, // free tier: 15 requests a minute
        dataNote = "Each page's text is sent to Google to be translated. On the free tier, " +
            "Google may use it to improve its products.",
    ),
    OPENAI(
        displayName = "OpenAI",
        keyPageUrl = "https://platform.openai.com/api-keys",
        defaultModels = listOf("gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini"),
        chunkPauseMillis = 20_000, // free / first tier: 3 requests a minute
        dataNote = "Each page's text is sent to OpenAI to be translated. Paid per use; " +
            "the API key's account needs billing credit.",
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        keyPageUrl = "https://console.anthropic.com/settings/keys",
        defaultModels = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-haiku-4-5"),
        chunkPauseMillis = 12_000, // first tier: 5 requests a minute
        dataNote = "Each page's text is sent to Anthropic to be translated. Paid per use; " +
            "the API key's account needs credit.",
    ),
    GROQ(
        displayName = "Groq",
        keyPageUrl = "https://console.groq.com/keys",
        defaultModels = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"),
        chunkPauseMillis = 2_000, // free tier: 30 requests a minute
        dataNote = "Each page's text is sent to Groq to be translated. The free tier has small " +
            "per-minute and per-day limits, so a long book takes a while.",
    ),
    ;

    /** The strategy for this provider: its own endpoint, headers and JSON. */
    fun createTranslator(apiKey: String, model: String): AITranslator = when (this) {
        GEMINI -> GeminiTranslator(apiKey, model)
        OPENAI -> OpenAITranslator(apiKey, model)
        ANTHROPIC -> AnthropicTranslator(apiKey, model)
        GROQ -> GroqTranslator(apiKey, model)
    }

    /**
     * Zero-config models: with the Model field empty, the provider's best
     * default ([defaultModels], tried in order) is used automatically;
     * [customModel] (typed in the app) is tried first.
     */
    fun models(customModel: String?): List<String> =
        (listOfNotNull(customModel?.trim()?.takeIf { it.isNotEmpty() }) + defaultModels).distinct()
}
