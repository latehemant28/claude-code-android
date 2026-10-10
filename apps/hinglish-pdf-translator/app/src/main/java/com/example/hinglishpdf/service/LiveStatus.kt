package com.example.hinglishpdf.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the service is doing right now; progress that must survive is in Room instead. */
data class LiveStatus(
    val running: Boolean = false,
    val bookId: Long? = null,
    /** "Translating page 45 of 300...", "Gemini rate limit reached: retrying in 30 s", ... */
    val label: String = "",
    val page: Int = 0,
    /** Micro-chunk in progress within the page, e.g. 2 of 4. */
    val chunk: Int = 0,
    val chunkCount: Int = 0,
    /** The page's blocks and the translations finished so far (null = pending). */
    val pageBlocks: List<com.example.hinglishpdf.data.document.DocBlock> = emptyList(),
    val pageTranslations: List<String?> = emptyList(),
    /** Raw model output for the micro-chunk being generated right now. */
    val liveText: String = "",
    /** Waiting out a Gemini rate limit or a dropped connection. */
    val waiting: Boolean = false,
)

/** Shared between the service (writer) and the screen (reader); same process. */
class TranslationMonitor {
    private val _status = MutableStateFlow(LiveStatus())
    val status: StateFlow<LiveStatus> = _status.asStateFlow()

    fun update(transform: (LiveStatus) -> LiveStatus) = _status.update(transform)

    fun reset() {
        _status.value = LiveStatus()
    }
}
