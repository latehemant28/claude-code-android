package com.example.hinglishpdf.data.ai

import com.google.gson.JsonNull

/*
 * Providers that speak OpenAI's Chat Completions API, each with its own
 * address (and, for Sarvam and OpenRouter, a few extra fields). Errors are
 * sorted by ChatCompletionsTranslator, which also reads the error shapes
 * these providers use instead of OpenAI's.
 */

/**
 * Sarvam AI (api.sarvam.ai), the Indian provider. Its key goes in its own
 * `api-subscription-key` header (sent as a Bearer token too). Reasoning is
 * turned off (a translation needs none, and it is billed), and the answer
 * may be longer than Sarvam's default of 2,048 tokens: an 800-word chunk in
 * Devanagari takes several thousand.
 */
class SarvamTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator(
        "Sarvam AI", endpoint, apiKey, model,
        extraHeaders = mapOf("api-subscription-key" to apiKey),
        extraBody = {
            add("reasoning_effort", JsonNull.INSTANCE)
            addProperty("max_tokens", MAX_TOKENS)
        },
    ) {
    internal companion object {
        const val ENDPOINT = "https://api.sarvam.ai/v1/chat/completions"
        const val MAX_TOKENS = 8_192
    }
}

/** DeepSeek (api.deepseek.com). */
class DeepSeekTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("DeepSeek", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }
}

/** Mistral AI (api.mistral.ai). */
class MistralTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("Mistral", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.mistral.ai/v1/chat/completions"
    }
}

/** xAI's Grok (api.x.ai). */
class XAITranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("xAI Grok", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.x.ai/v1/chat/completions"
    }
}

/** Cohere's Command models, through its OpenAI compatibility API. */
class CohereTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("Cohere", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.cohere.ai/compatibility/v1/chat/completions"
    }
}

/** Cerebras (api.cerebras.ai): very fast open models. */
class CerebrasTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("Cerebras", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.cerebras.ai/v1/chat/completions"
    }
}

/**
 * OpenRouter (openrouter.ai): one key for hundreds of models from every
 * company (Qwen, Kimi, GLM, Llama, Gemma...), some of them free. The app
 * names itself in the attribution header OpenRouter asks for.
 */
class OpenRouterTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator(
        "OpenRouter", endpoint, apiKey, model,
        extraHeaders = mapOf("X-OpenRouter-Title" to "Hindi Book Translator"),
    ) {
    private companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }
}

/** Any other service with an OpenAI-compatible API, at the address the user typed. */
class CustomTranslator(apiKey: String, model: String, baseUrl: String) :
    ChatCompletionsTranslator("Custom AI", requireChatCompletionsUrl(baseUrl), apiKey, model) {

    companion object {
        /**
         * "https://example.com/v1" or ".../v1/chat/completions" → the full
         * chat completions address. Null unless it is an https:// address
         * (the app sends text over encrypted connections only).
         */
        fun chatCompletionsUrl(input: String): String? {
            val url = input.trim().trimEnd('/')
            if (!url.startsWith("https://", ignoreCase = true) || url.length <= "https://".length || url.any(Char::isWhitespace)) {
                return null
            }
            return if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
        }

        private fun requireChatCompletionsUrl(baseUrl: String): String =
            chatCompletionsUrl(baseUrl) ?: throw TranslatorException.Fatal(
                "The Custom AI address must start with https:// (for example https://example.com/v1).",
            )
    }
}
