package com.example.hinglishpdf.data.ai

/**
 * The AI services the user can pick in the app. Each one knows where its API
 * keys are made ([keyPageUrl], opened in the in-app browser), which models to
 * try, how hard it may be paced, and which [AITranslator] strategy talks to
 * it ([createTranslator]).
 */
enum class AIProvider(
    val displayName: String,
    /** Free or paid, in a few words, under the name in the provider menu. */
    val cost: String,
    /** The provider's official API-key dashboard; null for [CUSTOM], which has none. */
    val keyPageUrl: String?,
    /** Tried in this order; a model the user types in the app is tried first. */
    val defaultModels: List<String>,
    /** Fixed pause after every chunk. Free tiers allow only a few requests a minute. */
    val chunkPauseMillis: Long,
    /** Shortest wait after a rate-limit (429) answer before trying again. */
    val rateLimitWaitMillis: Long,
    /** Shown under the key: where the text goes and what it costs. */
    val dataNote: String,
) {
    GEMINI(
        displayName = "Google Gemini",
        cost = "Free tier",
        keyPageUrl = "https://aistudio.google.com/app/apikey",
        defaultModels = GEMINI_MODELS,
        chunkPauseMillis = 4_500, // ~13 requests a minute, under the free tier's 15
        rateLimitWaitMillis = 60_000,
        dataNote = "Each page's text is sent to Google to be translated. On the free tier, " +
            "Google may use it to improve its products.",
    ),
    SARVAM(
        displayName = "Sarvam AI",
        cost = "Indian AI · free credits on sign-up",
        keyPageUrl = "https://dashboard.sarvam.ai",
        defaultModels = listOf("sarvam-105b", "sarvam-105b-conversations"),
        chunkPauseMillis = 1_500, // 40 requests a minute for its large models
        rateLimitWaitMillis = 20_000,
        dataNote = "Each page's text is sent to Sarvam AI (India) to be translated. Paid per use from " +
            "your Sarvam credits; new accounts get free credits. Create the key under API Keys.",
    ),
    GROQ(
        displayName = "Groq",
        cost = "Free tier",
        keyPageUrl = "https://console.groq.com/keys",
        defaultModels = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"),
        chunkPauseMillis = 4_500, // the free tier allows only a few thousand tokens a minute
        rateLimitWaitMillis = 30_000,
        dataNote = "Each page's text is sent to Groq to be translated. The free tier has small " +
            "per-minute and per-day limits, so a long book takes a while.",
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        cost = "Free models · 400+ models with one key",
        keyPageUrl = "https://openrouter.ai/keys",
        defaultModels = listOf(
            "google/gemma-4-31b-it:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "google/gemma-4-26b-a4b-it:free",
            "openrouter/free",
        ),
        chunkPauseMillis = 3_500, // free models: 20 requests a minute
        rateLimitWaitMillis = 60_000,
        dataNote = "Each page's text is sent to OpenRouter, which passes it to the model's company. " +
            "Free models (\":free\") allow 50 requests a day, 1,000 once you have bought 10 credits; " +
            "type any other OpenRouter model (Qwen, Kimi, GLM, Llama...) in the Model field.",
    ),
    CEREBRAS(
        displayName = "Cerebras",
        cost = "Free tier",
        keyPageUrl = "https://cloud.cerebras.ai",
        defaultModels = listOf("gpt-oss-120b", "qwen-3.8-27b"),
        chunkPauseMillis = 12_000, // free tier: 5 requests a minute
        rateLimitWaitMillis = 60_000,
        dataNote = "Each page's text is sent to Cerebras to be translated. The free tier allows " +
            "5 requests a minute and 1 million tokens a day.",
    ),
    MISTRAL(
        displayName = "Mistral AI",
        cost = "Free tier",
        keyPageUrl = "https://console.mistral.ai/api-keys",
        defaultModels = listOf("mistral-medium-latest", "mistral-small-latest", "mistral-large-latest"),
        chunkPauseMillis = 2_000,
        rateLimitWaitMillis = 30_000,
        dataNote = "Each page's text is sent to Mistral AI (France) to be translated. On the free " +
            "Experiment plan, Mistral may use it to improve its models.",
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        cost = "Paid · low cost",
        keyPageUrl = "https://platform.deepseek.com/api_keys",
        defaultModels = listOf("deepseek-flash", "deepseek-v4-pro"),
        chunkPauseMillis = 1_000,
        rateLimitWaitMillis = 20_000,
        dataNote = "Each page's text is sent to DeepSeek (China) to be translated. Paid per use; " +
            "the account needs a topped-up balance.",
    ),
    OPENAI(
        displayName = "OpenAI",
        cost = "Paid",
        keyPageUrl = "https://platform.openai.com/api-keys",
        defaultModels = listOf("gpt-4.1-mini", "gpt-4o-mini", "gpt-5-mini"),
        chunkPauseMillis = 1_000,
        rateLimitWaitMillis = 20_000,
        dataNote = "Each page's text is sent to OpenAI to be translated. Paid per use; " +
            "the API key's account needs billing credit.",
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        cost = "Paid",
        keyPageUrl = "https://console.anthropic.com/settings/keys",
        defaultModels = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-haiku-4-5"),
        chunkPauseMillis = 1_000,
        rateLimitWaitMillis = 20_000,
        dataNote = "Each page's text is sent to Anthropic to be translated. Paid per use; " +
            "the API key's account needs credit.",
    ),
    XAI(
        displayName = "xAI Grok",
        cost = "Paid",
        keyPageUrl = "https://console.x.ai/team/default/api-keys",
        defaultModels = listOf("grok-4.20-0309-non-reasoning", "grok-4.3", "grok-4.7"),
        chunkPauseMillis = 1_000,
        rateLimitWaitMillis = 20_000,
        dataNote = "Each page's text is sent to xAI to be translated. Paid per use; " +
            "load credits on the account first.",
    ),
    COHERE(
        displayName = "Cohere",
        cost = "Free trial key",
        keyPageUrl = "https://dashboard.cohere.com/api-keys",
        defaultModels = listOf("command-a-plus-05-2026", "command-a-03-2025"),
        chunkPauseMillis = 3_500, // trial keys: 20 chat requests a minute
        rateLimitWaitMillis = 60_000,
        dataNote = "Each page's text is sent to Cohere to be translated. Trial keys are free " +
            "but allow 1,000 requests a month (about one per page).",
    ),
    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        cost = "Any other AI service",
        keyPageUrl = null,
        defaultModels = emptyList(),
        chunkPauseMillis = 1_000,
        rateLimitWaitMillis = 20_000,
        dataNote = "Each page's text is sent to the address you entered. Use only a service you trust.",
    ),
    ;

    /**
     * The strategy for this provider: its own endpoint, headers and JSON.
     * [customUrl] is the address typed for [CUSTOM], ignored by the others.
     */
    fun createTranslator(apiKey: String, model: String, customUrl: String = ""): AITranslator = when (this) {
        GEMINI -> GeminiTranslator(apiKey, model)
        SARVAM -> SarvamTranslator(apiKey, model)
        GROQ -> GroqTranslator(apiKey, model)
        OPENROUTER -> OpenRouterTranslator(apiKey, model)
        CEREBRAS -> CerebrasTranslator(apiKey, model)
        MISTRAL -> MistralTranslator(apiKey, model)
        DEEPSEEK -> DeepSeekTranslator(apiKey, model)
        OPENAI -> OpenAITranslator(apiKey, model)
        ANTHROPIC -> AnthropicTranslator(apiKey, model)
        XAI -> XAITranslator(apiKey, model)
        COHERE -> CohereTranslator(apiKey, model)
        CUSTOM -> CustomTranslator(apiKey, model, customUrl)
    }

    /** [customModel] (typed in the app) first, then the defaults. */
    fun models(customModel: String?): List<String> =
        (listOfNotNull(customModel?.trim()?.takeIf { it.isNotEmpty() }) + defaultModels).distinct()
}
