package com.example.hinglishpdf.ui.listen

import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.voice.Voice
import com.example.hinglishpdf.ui.Phrase
import com.example.hinglishpdf.ui.VoiceGuide
import com.example.hinglishpdf.ui.useHindi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ListenPlayerTest {

    /** Remembers what it was asked to say. */
    private class FakeVoice : Voice {
        val spoken = mutableListOf<Triple<String, Language, String>>()
        var stops = 0
        override val finished: SharedFlow<String> = MutableSharedFlow()
        override fun speak(text: String, language: Language, id: String) {
            spoken += Triple(text, language, id)
        }
        override fun stop() {
            stops++
        }
        override fun hasVoice(language: Language) = true
        override fun setRate(rate: Float) = Unit
    }

    private val book = listOf(Paragraph(1, "अध्याय 1", heading = true), Paragraph(1, "पहला हिस्सा।"), Paragraph(2, "दूसरा हिस्सा।"))

    @Test
    fun `the book is read aloud part after part, and stops at the end`() {
        val voice = FakeVoice()
        val positions = mutableListOf<Int>()
        val player = ListenPlayer(voice, Language.HINDI, book, start = 0) { positions += it }

        player.play()
        assertEquals("अध्याय 1", voice.spoken.last().first)
        assertEquals(Language.HINDI, voice.spoken.last().second)

        player.onFinished("someone-else") // not ours: ignored
        assertEquals(1, voice.spoken.size)
        player.onFinished(voice.spoken.last().third)
        assertEquals("पहला हिस्सा।", voice.spoken.last().first)
        player.onFinished(voice.spoken.last().third)
        assertEquals("दूसरा हिस्सा।", voice.spoken.last().first)
        assertEquals(2, player.state.value.index)

        player.onFinished(voice.spoken.last().third)
        assertFalse(player.state.value.playing)
        assertEquals(listOf(1, 2, 0), positions) // heard to the end: from the start next time
    }

    @Test
    fun `pause stops the voice, next and previous move one part, and listening resumes where it stopped`() {
        val voice = FakeVoice()
        val player = ListenPlayer(voice, Language.HINDI, book, start = 1) {}
        assertEquals("पहला हिस्सा।", player.current?.text)

        player.play()
        player.pause()
        assertEquals(1, voice.stops)
        val stale = voice.spoken.last().third
        player.onFinished(stale) // finishing after a pause does not carry on
        assertEquals(1, voice.spoken.size)

        player.next()
        assertEquals(2, player.state.value.index)
        player.next() // already at the end
        assertEquals(2, player.state.value.index)
        player.previous()
        player.previous()
        player.previous()
        assertEquals(0, player.state.value.index)
        assertEquals(1, voice.spoken.size) // moving while paused says nothing
    }

    @Test
    fun `only translated text is read, without code or empty blocks`() {
        val blocks = listOf(
            DocBlock(BlockKind.HEADING, "Chapter", level = 1),
            DocBlock(BlockKind.CODE, "x = 1"),
            DocBlock(BlockKind.PARAGRAPH, "p"),
            DocBlock(BlockKind.PARAGRAPH, "—"),
        )
        val pages = listOf(
            PageEntity(7, 1, 595f, 842f, blocks, translations = listOf("अध्याय", null, "पाठ", "—"), translatedText = "x", translatedAt = 1),
            PageEntity(7, 2, 595f, 842f, blocks, translations = null), // not translated yet
        )
        assertEquals(listOf(Paragraph(1, "अध्याय", heading = true), Paragraph(1, "पाठ")), paragraphsOf(pages))
    }

    @Test
    fun `spoken help follows the switch, and the speaker buttons always speak`() {
        val voice = FakeVoice()
        val hello = Phrase("नमस्ते", "Hello")
        VoiceGuide(voice, hindi = true, enabled = false).say(hello)
        assertTrue(voice.spoken.isEmpty())
        VoiceGuide(voice, hindi = true, enabled = false).sayNow(hello)
        assertEquals(Triple("नमस्ते", Language.HINDI, Voice.GUIDE), voice.spoken.single())
        VoiceGuide(voice, hindi = false, enabled = true).say(hello)
        assertEquals("Hello", voice.spoken.last().first)
        assertEquals(Language.ENGLISH, voice.spoken.last().second)
    }

    @Test
    fun `hindi for hindi books and for phones in hindi or in india`() {
        assertTrue(useHindi(Language.HINDI, Locale.US))
        assertTrue(useHindi(Language.SPANISH, Locale("hi")))
        assertTrue(useHindi(Language.TAMIL, Locale("en", "IN")))
        assertFalse(useHindi(Language.SPANISH, Locale.US))
    }
}
