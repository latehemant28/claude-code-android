package com.example.hinglishpdf.ui.status

/**
 * Accepts what people actually paste, instead of rejecting it for format
 * reasons the app can fix itself (Norman: no blaming the user).
 */
object InputCleaner {

    private val QUOTES = charArrayOf('"', '\'', '`', '“', '”', '‘', '’')
    private val KEY_PREFIX = Regex(
        "^(?:authorization\\s*:\\s*)?(?:bearer\\s+)?(?:[A-Za-z_]*(?:api[_-]?key|key)\\s*[:=]\\s*)?",
        RegexOption.IGNORE_CASE,
    )

    /** An API key as pasted: quotes, labels ("GEMINI_API_KEY=", "Bearer "), spaces and line breaks removed. */
    fun apiKey(raw: String): String {
        var key = raw.trim().trim(*QUOTES).trim()
        key = KEY_PREFIX.replace(key, "")
        return key.trim().trim(*QUOTES).filterNot { it.isWhitespace() }
    }

    /** Why a cleaned key can't be right, in plain words; null if it looks fine. */
    fun apiKeyProblem(key: String): String? = when {
        key.isEmpty() -> null
        key.startsWith("http", ignoreCase = true) ->
            "That's a web address, not a key. On the key page, tap Copy next to the key itself."
        key.length < 20 -> "That looks too short for an API key (they are usually 30–60 characters). Copy the whole key."
        else -> null
    }

    /** A model name: quotes and stray spaces removed. */
    fun modelName(raw: String): String = raw.trim().trim(*QUOTES).filterNot { it.isWhitespace() }

    /**
     * A service address: quotes and spaces removed, and https:// added when
     * there is no scheme. An http:// address becomes https:// (the app only
     * sends text over encrypted connections).
     */
    fun serviceUrl(raw: String): String {
        val url = raw.trim().trim(*QUOTES).filterNot { it.isWhitespace() }
        return when {
            url.isEmpty() -> url
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring("http://".length)
            "://" in url -> url
            else -> "https://$url"
        }
    }
}
