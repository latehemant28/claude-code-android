package com.example.hinglishpdf.ui.listen

import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.voice.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One part of a book read aloud: a heading or a paragraph of a translated page. */
data class Paragraph(val page: Int, val text: String, val heading: Boolean = false)

/** A translated book ready to be heard. */
data class ListenDocument(
    val bookId: Long,
    val title: String,
    val language: Language,
    val paragraphs: List<Paragraph>,
)

/** The translated pages as parts to read aloud, in order; code and empty blocks are skipped. */
fun paragraphsOf(pages: List<PageEntity>): List<Paragraph> = pages.flatMap { page ->
    val translations = page.translations ?: return@flatMap emptyList()
    page.sourceBlocks.mapIndexedNotNull { i, block ->
        if (block.kind == BlockKind.CODE) return@mapIndexedNotNull null
        val text = (translations.getOrNull(i) ?: block.text).trim()
        if (text.none(Char::isLetterOrDigit)) null else Paragraph(page.pageNumber, text, block.kind == BlockKind.HEADING)
    }
}

/**
 * Reads [paragraphs] aloud one after another with [voice]. [onPosition] is
 * told every move, so listening carries on from the same place next time.
 */
class ListenPlayer(
    private val voice: Voice,
    private val language: Language,
    val paragraphs: List<Paragraph>,
    start: Int,
    private val onPosition: (Int) -> Unit,
) {
    data class State(val index: Int, val playing: Boolean)

    private val _state = MutableStateFlow(State(start.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)), playing = false))
    val state: StateFlow<State> = _state.asStateFlow()

    private var utterance: String? = null
    private var count = 0

    val current: Paragraph? get() = paragraphs.getOrNull(state.value.index)

    fun play() {
        if (paragraphs.isEmpty()) return
        _state.update { it.copy(playing = true) }
        speakCurrent()
    }

    fun pause() {
        _state.update { it.copy(playing = false) }
        utterance = null
        voice.stop()
    }

    fun toggle() = if (state.value.playing) pause() else play()

    fun next() = move(+1)

    fun previous() = move(-1)

    private fun move(step: Int) {
        if (paragraphs.isEmpty()) return
        val index = (state.value.index + step).coerceIn(0, paragraphs.lastIndex)
        _state.update { it.copy(index = index) }
        onPosition(index)
        if (state.value.playing) speakCurrent()
    }

    /** The voice finished [id]: go on to the next part, or stop at the end of the book. */
    fun onFinished(id: String) {
        if (id != utterance || !state.value.playing) return
        val next = state.value.index + 1
        if (next > paragraphs.lastIndex) {
            utterance = null
            _state.update { it.copy(playing = false) }
            onPosition(0) // heard to the end: start from the beginning next time
            return
        }
        _state.update { it.copy(index = next) }
        onPosition(next)
        speakCurrent()
    }

    private fun speakCurrent() {
        val id = "book-${count++}"
        utterance = id
        voice.speak(paragraphs[state.value.index].text, language, id)
    }
}
