package com.example.hinglishpdf.data.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Models to try, in order of preference. If one is retired (404), out of
 * quota or rate-limited, the next one is used automatically.
 *
 * The first two are the ones originally requested. Google shut both down on
 * 29 Sept 2025, so the current free Flash models follow them; without those
 * the list could never translate anything.
 */
val GEMINI_MODELS = listOf(
    "gemini-1.5-flash",
    "gemini-1.5-pro",
    "gemini-3.5-flash-lite",
    "gemini-3.8-flash",
)

/**
 * Google Gemini through its REST API (`streamGenerateContent`, server-sent
 * events). The prompt is Gemini's system instruction; temperature 0.2; the
 * safety filters are off so ordinary literature isn't refused mid-book.
 * Parts the app doesn't need (a model's "thoughts", signatures) are skipped
 * instead of breaking the answer.
 */
class GeminiTranslator(
    private val apiKey: String,
    model: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : HttpStreamingTranslator("Gemini", model) {

    override fun request(systemPrompt: String, chunk: String, temperature: Boolean) = Request(
        url = "$baseUrl/models/$model:streamGenerateContent?alt=sse",
        headers = mapOf("x-goog-api-key" to apiKey),
        body = jsonObject {
            add("systemInstruction", jsonObject { add("parts", textParts(systemPrompt)) })
            add(
                "contents",
                JsonArray().apply {
                    add(jsonObject { addProperty("role", "user"); add("parts", textParts(chunk)) })
                },
            )
            if (temperature) add("generationConfig", jsonObject { addProperty("temperature", 0.2) })
            add(
                "safetySettings",
                JsonArray().apply {
                    SAFETY_CATEGORIES.forEach { category ->
                        add(jsonObject { addProperty("category", category); addProperty("threshold", "BLOCK_NONE") })
                    }
                },
            )
        },
    )

    override fun textOf(event: JsonObject): String? {
        event.obj("error")?.let { error ->
            val code = runCatching { error.get("code").asInt }.getOrDefault(500)
            throw classify(code, event.toString(), null)
        }
        event.obj("promptFeedback")?.string("blockReason")?.let {
            throw TranslatorException.Blocked("Gemini declined this text ($it)")
        }
        val candidate = event.firstOf("candidates") ?: return null
        val text = candidate.obj("content")?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { p -> p.isJsonObject }?.asJsonObject }
            ?.filter { it.get("thought")?.asBoolean != true }
            ?.mapNotNull { it.string("text") }
            ?.joinToString("")
        candidate.string("finishReason")?.let { reason ->
            if (reason in BLOCKED) throw TranslatorException.Blocked("Gemini stopped its answer ($reason)")
        }
        return text
    }

    override fun classify(status: Int, body: String, retryAfterMillis: Long?): TranslatorException {
        val message = errorObject(body)?.string("message") ?: body
        val retry = retryAfter(body) ?: retryAfterMillis
        return when {
            LOCATION.containsMatchIn(body) ->
                TranslatorException.Fatal("The Gemini API is not available in your country or region.")
            status == 401 || status == 403 || API_KEY.containsMatchIn(body) -> TranslatorException.Fatal(
                "Gemini rejected the API key (${detail(message)}). Check the key in the app or in local.properties.",
            )
            status == 404 || NOT_FOUND.containsMatchIn(message) -> TranslatorException.ModelUnavailable(model)
            status == 429 || RATE_LIMIT.containsMatchIn(body) -> when {
                NO_QUOTA.containsMatchIn(body) -> TranslatorException.ModelUnavailable(model)
                PER_DAY.containsMatchIn(body) -> TranslatorException.DailyQuota(
                    "The free Gemini daily quota is used up. Tap Resume tomorrow; the book continues from the same page.",
                )
                else -> TranslatorException.Transient(
                    "Gemini rate limit ($model: ${detail(message)})", retry, rateLimited = true,
                )
            }
            status >= 500 -> TranslatorException.Transient("Gemini is busy (${detail(message)})", retry)
            TOO_LONG.containsMatchIn(message) -> TranslatorException.Blocked("Too long for one Gemini request")
            else -> TranslatorException.Fatal("Gemini error $status: ${detail(message)}")
        }
    }

    internal companion object {
        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
        private val BLOCKED = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")

        private val LOCATION = Regex("location is not supported", RegexOption.IGNORE_CASE)
        private val API_KEY = Regex("API_KEY_INVALID|API key not valid|API key expired", RegexOption.IGNORE_CASE)
        private val PER_DAY = Regex("per[_ ]?day|PerDay|daily", RegexOption.IGNORE_CASE)
        private val NOT_FOUND = Regex(
            "is not found|not supported for generateContent|no longer available",
            RegexOption.IGNORE_CASE,
        )
        private val RATE_LIMIT = Regex("RESOURCE_EXHAUSTED", RegexOption.IGNORE_CASE)
        private val TOO_LONG = Regex("exceeds the maximum number of tokens|too long", RegexOption.IGNORE_CASE)

        /** "limit: 0": this model has no free quota at all for this key, so waiting cannot help. */
        private val NO_QUOTA = Regex("\\blimit:\\s*0\\b", RegexOption.IGNORE_CASE)
        private val RETRY_IN = Regex("retry in ([0-9.]+)s|\"retryDelay\":\\s*\"([0-9.]+)s\"", RegexOption.IGNORE_CASE)

        private fun textParts(text: String) = JsonArray().apply { add(jsonObject { addProperty("text", text) }) }

        /** Gemini says how long to wait, e.g. "Please retry in 23.4s" or "retryDelay": "23s". */
        fun retryAfter(message: String?): Long? {
            val m = RETRY_IN.find(message.orEmpty()) ?: return null
            val seconds = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDoubleOrNull() ?: return null
            return (seconds * 1000).toLong() + 1_000 // a little margin
        }
    }
}
