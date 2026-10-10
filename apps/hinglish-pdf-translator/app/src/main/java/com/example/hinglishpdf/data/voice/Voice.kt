package com.example.hinglishpdf.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.hinglishpdf.data.llm.Language
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/**
 * The phone's text-to-speech voice: speaks the app's spoken help and reads
 * translated books aloud, for people who cannot read (or would rather listen).
 */
interface Voice {
    /** Ids of utterances that finished (or failed), so the next one can follow. */
    val finished: SharedFlow<String>

    /** Speaks [text] in [language], replacing whatever is being said. */
    fun speak(text: String, language: Language, id: String = GUIDE)

    fun stop()

    /** False when the phone has no voice for [language] (or the engine is not ready). */
    fun hasVoice(language: Language): Boolean

    /** 1 = normal speed; below 1 is slower. */
    fun setRate(rate: Float)

    companion object {
        /** The id of spoken help (not part of a book). */
        const val GUIDE = "guide"

        fun locale(language: Language): Locale = Locale.forLanguageTag(
            if (language == Language.AUTO_DETECT) "en" else language.code,
        )
    }
}

/**
 * [Voice] on Android's [TextToSpeech]. The engine starts in the background;
 * a sentence asked for before it is ready is spoken as soon as it is.
 */
class AndroidVoice(context: Context) : Voice, TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val finished: SharedFlow<String> = _finished.asSharedFlow()

    private var ready = false
    private var pending: Triple<String, Language, String>? = null
    private var rate = 1f
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = report(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = report(utteranceId)
                override fun onError(utteranceId: String, errorCode: Int) = report(utteranceId)
            },
        )
        pending?.let { (text, language, id) -> speak(text, language, id) }
        pending = null
    }

    private fun report(id: String) {
        main.post { _finished.tryEmit(id) }
    }

    override fun speak(text: String, language: Language, id: String) {
        if (!ready) {
            pending = Triple(text, language, id)
            return
        }
        tts.language = Voice.locale(language)
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    override fun hasVoice(language: Language): Boolean =
        ready && tts.isLanguageAvailable(Voice.locale(language)) >= TextToSpeech.LANG_AVAILABLE

    override fun setRate(rate: Float) {
        this.rate = rate
    }
}
