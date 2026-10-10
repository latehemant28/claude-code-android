package com.example.hinglishpdf.data.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Anthropic Claude through the Messages API (`/v1/messages`, `"stream": true`).
 * The prompt is the `system` field; text arrives in `content_block_delta`
 * events; overload and rate-limit errors can also arrive mid-stream.
 */
class AnthropicTranslator(
    private val apiKey: String,
    model: String,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) : HttpStreamingTranslator("Claude", model) {

    override fun request(systemPrompt: String, chunk: String, temperature: Boolean) = Request(
        url = endpoint,
        headers = mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION),
        body = jsonObject {
            addProperty("model", model)
            // Room for a whole chunk in Devanagari, which takes several tokens per word.
            addProperty("max_tokens", if (model.startsWith("claude-3-haiku")) CLAUDE_3_MAX_TOKENS else MAX_TOKENS)
            addProperty("system", systemPrompt)
            add(
                "messages",
                JsonArray().apply {
                    add(jsonObject { addProperty("role", "user"); addProperty("content", chunk) })
                },
            )
            addProperty("stream", true)
            if (temperature) addProperty("temperature", 0.2)
        },
    )

    override fun textOf(event: JsonObject): String? = when (event.string("type")) {
        "content_block_delta" -> event.obj("delta")?.takeIf { it.string("type") == "text_delta" }?.string("text")
        "message_delta" -> {
            when (event.obj("delta")?.string("stop_reason")) {
                "refusal" -> throw TranslatorException.Blocked("Claude declined this text")
                "max_tokens" -> throw TranslatorException.Truncated("Claude's answer reached its length limit")
            }
            null
        }
        "error" -> throw classify(
            when (event.obj("error")?.string("type")) {
                "rate_limit_error" -> 429
                "overloaded_error" -> 529
                else -> 500
            },
            event.toString(),
            null,
        )
        else -> null // message_start, content_block_start/stop, ping, message_stop
    }

    override fun classify(status: Int, body: String, retryAfterMillis: Long?): TranslatorException {
        val error = errorObject(body)
        val message = error?.string("message") ?: body
        return when {
            status == 401 -> TranslatorException.Fatal(
                "Anthropic rejected the API key (${detail(message)}). Check the key you saved.",
            )
            status == 403 -> TranslatorException.Fatal("Anthropic refused the request: ${detail(message)}")
            status == 404 -> TranslatorException.ModelUnavailable(model)
            status == 413 || TOO_LONG.containsMatchIn(message) ->
                TranslatorException.Blocked("Too long for one Claude request")
            NO_CREDIT.containsMatchIn(message) -> TranslatorException.Fatal(
                "Your Anthropic account has no credit left (${detail(message)}). Add credit, then tap Resume.",
            )
            status == 429 -> TranslatorException.Transient(
                "Claude rate limit ($model: ${detail(message)})", retryAfterMillis, rateLimited = true,
            )
            status >= 500 -> TranslatorException.Transient("Claude is busy (${detail(message)})", retryAfterMillis)
            else -> TranslatorException.Fatal("Anthropic error $status: ${detail(message)}")
        }
    }

    private companion object {
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 8_192
        const val CLAUDE_3_MAX_TOKENS = 4_096 // the most Claude 3 Haiku can write in one answer
        val TOO_LONG = Regex("prompt is too long|too many tokens", RegexOption.IGNORE_CASE)
        val NO_CREDIT = Regex("credit balance is too low", RegexOption.IGNORE_CASE)
    }
}
