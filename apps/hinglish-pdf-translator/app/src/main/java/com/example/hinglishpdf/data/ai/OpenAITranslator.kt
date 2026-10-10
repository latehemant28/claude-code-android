package com.example.hinglishpdf.data.ai

import com.example.hinglishpdf.data.llm.HinglishPrompt
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Any provider with OpenAI's Chat Completions API (`/chat/completions`,
 * `"stream": true`): the prompt is the system message, the chunk the user
 * message, and the answer streams as `choices[0].delta.content`. A model's
 * reasoning ("thinking") arrives in other fields and is never shown.
 */
open class ChatCompletionsTranslator(
    providerName: String,
    private val endpoint: String,
    private val apiKey: String,
    model: String,
    /** Sent with every request, after the Bearer key (a provider's own key header, attribution). */
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Provider-specific fields added to the request body. */
    private val extraBody: JsonObject.() -> Unit = {},
) : HttpStreamingTranslator(providerName, model) {

    override fun request(chunk: String, temperature: Boolean) = Request(
        url = endpoint,
        headers = mapOf("Authorization" to "Bearer $apiKey") + extraHeaders,
        body = jsonObject {
            addProperty("model", model)
            add(
                "messages",
                JsonArray().apply {
                    add(message("system", HinglishPrompt.SYSTEM_PROMPT))
                    add(message("user", chunk))
                },
            )
            addProperty("stream", true)
            if (temperature) addProperty("temperature", 0.2)
            extraBody()
        },
    )

    override fun textOf(event: JsonObject): String? {
        event.obj("error")?.let { throw classify(500, event.toString(), null) }
        val choice = event.firstOf("choices") ?: return null
        if (choice.string("finish_reason") == "content_filter") {
            throw TranslatorException.Blocked("$providerName declined this text (content filter)")
        }
        return choice.obj("delta")?.string("content")
    }

    override fun classify(status: Int, body: String, retryAfterMillis: Long?): TranslatorException {
        // OpenAI's {"error":{"message","code"}}, or another provider's own shape:
        // {"message"} (Cohere, Cerebras), {"detail"} (Mistral), {"error":"..."} (xAI).
        val error = errorObject(body)
        val top = jsonBody(body)
        val message = error?.string("message") ?: top?.string("message") ?: top?.string("detail") ?: top?.string("error") ?: body
        val code = (error?.string("code") ?: top?.string("code")).orEmpty()
        return when {
            status == 401 || KEY_REJECTED.containsMatchIn(message) || code.contains("api_key") -> TranslatorException.Fatal(
                "$providerName rejected the API key (${detail(message)}). Check the key you saved.",
            )
            status == 402 || code == "insufficient_balance" -> TranslatorException.Fatal(
                "Your $providerName account has no credit left (${detail(message)}). Add credit, then tap Resume.",
            )
            status == 403 -> TranslatorException.Fatal("$providerName refused the request: ${detail(message)}")
            status == 404 || code == "model_not_found" || MODEL_GONE.containsMatchIn(message) ->
                TranslatorException.ModelUnavailable(model)
            status == 413 || code == "context_length_exceeded" || TOO_LONG.containsMatchIn(message) ->
                TranslatorException.Blocked("Too long for one $providerName request")
            status == 429 -> when {
                code.startsWith("insufficient_quota") || NO_CREDIT.containsMatchIn(message) -> TranslatorException.Fatal(
                    "Your $providerName account has no credit left (${detail(message)}). Add billing credit, " +
                        "then tap Resume.",
                )
                PER_DAY.containsMatchIn(message) -> TranslatorException.DailyQuota(
                    "The daily $providerName limit for this key is used up. Tap Resume later; " +
                        "the book continues from the same page.",
                )
                else -> TranslatorException.Transient(
                    "$providerName rate limit ($model: ${detail(message)})",
                    retryAfterMillis ?: tryAgainIn(message),
                    rateLimited = true,
                )
            }
            status >= 500 -> TranslatorException.Transient("$providerName is busy (${detail(message)})", retryAfterMillis)
            else -> TranslatorException.Fatal("$providerName error $status: ${detail(message)}")
        }
    }

    private fun message(role: String, content: String) = jsonObject {
        addProperty("role", role)
        addProperty("content", content)
    }

    internal companion object {
        private val MODEL_GONE = Regex(
            "model .*(does not exist|not found|decommissioned|deprecated|not supported|not available)|" +
                "model_decommissioned|do not have access to (the )?model|(invalid|unknown|no such) model",
            RegexOption.IGNORE_CASE,
        )
        private val KEY_REJECTED = Regex(
            "(invalid|incorrect|wrong|missing)\\b.{0,24}\\b(api[ _-]?key|authentication|credentials)|authentication fails",
            RegexOption.IGNORE_CASE,
        )
        private val TOO_LONG = Regex(
            "maximum context length|context_length|too large|reduce the length|too many tokens",
            RegexOption.IGNORE_CASE,
        )
        private val NO_CREDIT = Regex("exceeded your current quota|insufficient[_ ]quota|billing", RegexOption.IGNORE_CASE)
        private val PER_DAY = Regex("per[ -]day|\\(TPD\\)|\\(RPD\\)|daily", RegexOption.IGNORE_CASE)
        private val TRY_AGAIN = Regex("try again in (?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([0-9.]+)s)?(?:([0-9.]+)ms)?", RegexOption.IGNORE_CASE)

        /** "Please try again in 7m12.5s" / "in 20ms" (OpenAI and Groq), when there is no Retry-After header. */
        fun tryAgainIn(message: String): Long? {
            val m = TRY_AGAIN.find(message) ?: return null
            val (h, min, s, ms) = m.destructured
            if (h.isEmpty() && min.isEmpty() && s.isEmpty() && ms.isEmpty()) return null
            val millis = (h.toDoubleOrNull() ?: 0.0) * 3_600_000 + (min.toDoubleOrNull() ?: 0.0) * 60_000 +
                (s.toDoubleOrNull() ?: 0.0) * 1000 + (ms.toDoubleOrNull() ?: 0.0)
            return millis.toLong() + 500
        }
    }
}

/** OpenAI (api.openai.com). */
class OpenAITranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("OpenAI", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }
}

/** Groq (api.groq.com): OpenAI-compatible, hosts open models such as Llama. */
class GroqTranslator(apiKey: String, model: String, endpoint: String = ENDPOINT) :
    ChatCompletionsTranslator("Groq", endpoint, apiKey, model) {
    private companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    }
}
