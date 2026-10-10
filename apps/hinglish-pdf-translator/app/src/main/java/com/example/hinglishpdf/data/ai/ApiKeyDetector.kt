package com.example.hinglishpdf.data.ai

/**
 * Recognises an API key in copied text (the in-app key browser watches the
 * clipboard and the page's Copy buttons). Each provider's keys have a
 * distinctive shape:
 *
 * | Provider | Key |
 * |---|---|
 * | Gemini | `AIza` + 35 characters, or an `AQ.` key |
 * | OpenAI | `sk-...` / `sk-proj-...` (but not `sk-ant-`) |
 * | Anthropic | `sk-ant-...` |
 * | Groq | `gsk_...` |
 *
 * A key copied for a different provider than the one selected is still
 * recognised, and belongs to that provider.
 */
object ApiKeyDetector {

    private const val EDGE_BEFORE = "(?<![A-Za-z0-9_.-])"
    private const val EDGE_AFTER = "(?![A-Za-z0-9_-])"

    private val PATTERNS: Map<AIProvider, Regex> = mapOf(
        AIProvider.GEMINI to Regex("${EDGE_BEFORE}(AIza[0-9A-Za-z_-]{35}|AQ\\.[0-9A-Za-z_-]{30,})$EDGE_AFTER"),
        AIProvider.ANTHROPIC to Regex("${EDGE_BEFORE}(sk-ant-[0-9A-Za-z_-]{20,})$EDGE_AFTER"),
        AIProvider.OPENAI to Regex("${EDGE_BEFORE}(sk-(?!ant-)[0-9A-Za-z_-]{20,})$EDGE_AFTER"),
        AIProvider.GROQ to Regex("${EDGE_BEFORE}(gsk_[0-9A-Za-z]{20,})$EDGE_AFTER"),
    )

    /** Pages whose Copy buttons are watched (where keys are made). */
    val KEY_PAGE_ORIGINS: Set<String> = setOf(
        "https://aistudio.google.com",
        "https://platform.openai.com",
        "https://console.anthropic.com",
        "https://platform.claude.com",
        "https://console.groq.com",
    )

    /** The key in [text] and its provider, [preferred] first; null if there is none. */
    fun detect(text: String?, preferred: AIProvider): Pair<AIProvider, String>? {
        if (text.isNullOrBlank() || text.length > MAX_TEXT) return null
        val order = listOf(preferred) + AIProvider.entries.filter { it != preferred }
        for (provider in order) {
            val match = PATTERNS.getValue(provider).find(text) ?: continue
            return provider to match.groupValues[1]
        }
        return null
    }

    private const val MAX_TEXT = 4_000
}
