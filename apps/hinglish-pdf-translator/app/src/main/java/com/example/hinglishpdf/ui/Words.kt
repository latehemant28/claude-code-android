package com.example.hinglishpdf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.data.voice.Voice
import java.util.Locale

/** One thing the app says or shows, in simple Hindi and in English. */
class Phrase(val hi: String, val en: String) {
    fun pick(hindi: Boolean) = if (hindi) hi else en
}

/** True when the easy screens are in Hindi (and speak Hindi). */
val LocalHindi = staticCompositionLocalOf { false }

/** The phrase in the screen's language. */
@Composable
@ReadOnlyComposable
fun Phrase.text(): String = pick(LocalHindi.current)

/**
 * Hindi when the book is translated into Hindi or the phone is set to Hindi
 * or to India; English otherwise.
 */
fun useHindi(target: Language, locale: Locale = Locale.getDefault()): Boolean =
    target == Language.HINDI || locale.language == "hi" || locale.country == "IN"

/**
 * Spoken help. [say] speaks only while voice help is on (Settings); [sayNow]
 * always speaks, for the 🔊 buttons the user taps on purpose.
 */
class VoiceGuide(private val voice: Voice?, private val hindi: Boolean, private val enabled: Boolean) {
    fun say(phrase: Phrase) {
        if (enabled) sayNow(phrase)
    }

    fun sayNow(phrase: Phrase) {
        voice?.speak(phrase.pick(hindi), if (hindi) Language.HINDI else Language.ENGLISH)
    }

    /** Says [text] in [language] itself (a language's own name, say), while voice help is on. */
    fun sayIn(text: String, language: Language) {
        if (enabled) voice?.speak(text, language)
    }

    fun stop() = voice?.stop()
}

val LocalGuide = staticCompositionLocalOf { VoiceGuide(null, hindi = false, enabled = false) }

/** Everything the easy screens say. Short words, everyday language. */
object Say {
    // Tabs
    val tabTranslate = Phrase("अनुवाद", "Translate")
    val tabBooks = Phrase("मेरी किताबें", "My books")
    val tabSettings = Phrase("सेटिंग", "Settings")

    // Settings
    val spokenHelp = Phrase("बोलकर मदद", "Spoken help")
    val spokenHelpDetail = Phrase("बटन दबाने पर ऐप बोलकर बताता है", "Buttons say out loud what they do")

    // Home
    val help = Phrase("सुनिए कैसे चलाएं", "Hear how it works")
    val welcome = Phrase(
        "नमस्ते! किताब का अनुवाद तीन आसान कदमों में होता है। पहला, चाबी। दूसरा, भाषा। " +
            "तीसरा, हरा बटन दबाकर अपनी किताब चुनें। अनुवाद पूरा होने पर, सुनें बटन दबाकर किताब सुनें।",
        "Hello! Translating a book takes three easy steps. First, the key. Second, the language. " +
            "Third, press the green button and choose your book. When it is done, press Listen to hear the book.",
    )
    val keyTitle = Phrase("चाबी लगवाएं", "Set up the key")
    val keySubtitle = Phrase("किसी जानकार से एक बार लगवा लें", "Ask a helper, just once")
    val keyHelp = Phrase(
        "अनुवाद के लिए एक मुफ़्त चाबी चाहिए। किसी पढ़े-लिखे जानकार से एक बार लगवा लें। यह आपके फ़ोन में ही रहती है।",
        "Translation needs a free key. Ask someone who can read to set it up once. It stays on your phone.",
    )
    val keyReady = Phrase("तैयार है", "Ready")
    val keyReadyHelp = Phrase("चाबी लगी हुई है। सब तैयार है।", "The key is set up. Everything is ready.")
    val change = Phrase("बदलें", "Change")
    val aiKey = Phrase("AI चाबी", "AI key")
    val tapToAddKey = Phrase("चाबी लगाने के लिए दबाएं", "Tap to add a key")
    val connected = Phrase("जुड़ा हुआ", "Connected")
    val translateTo = Phrase("किस भाषा में", "Translate to")
    val chooseBookFile = Phrase("किताब चुनें (PDF/EPUB)", "Choose a book (PDF/EPUB)")
    val languageTitle = Phrase("भाषा", "Language")
    fun languageHelp(target: Language) = Phrase(
        "किताब किस भाषा में चाहिए? भाषा बदलने के लिए यहाँ दबाएं। अभी चुनी है: ${target.nativeName ?: target.englishName}",
        "Which language do you want the book in? Press here to change it. Now: ${target.englishName}",
    )
    val chooseBook = Phrase("किताब चुनें", "Choose a book")
    val chooseBookHelp = Phrase(
        "हरा बटन दबाकर अपनी पीडीएफ़ या ई-पब किताब चुनें।",
        "Press the green button to choose your PDF or EPUB book.",
    )
    val chooseLanguage = Phrase("अपनी भाषा चुनें", "Choose your language")
    val chooseLanguageHelp = Phrase(
        "अपनी भाषा चुनें। जिस भाषा में किताब चाहिए, उसके नाम पर दबाएं।",
        "Choose your language. Press the name of the language you want the book in.",
    )
    val chooseLanguageNote = Phrase(
        "किताबें इसी भाषा में अनुवाद होंगी। आप इसे कभी भी बदल सकते हैं।",
        "Books will be translated into this language. You can change it any time.",
    )
    val continueButton = Phrase("आगे बढ़ें", "Continue")
    val notNow = Phrase("अभी नहीं", "Not now")

    // Tour
    val tourKeyTitle = Phrase("🔑 चाबी", "🔑 Your AI key")
    val tourKeyText = Phrase(
        "यहाँ दबाकर एक मुफ़्त AI चाबी लगवाएं। एक मिनट लगता है और यह आपके फ़ोन में ही रहती है।",
        "Tap here to connect a free AI engine. It takes a minute and stays on your phone.",
    )
    val tourLanguageTitle = Phrase("🌐 भाषा", "🌐 Your language")
    val tourLanguageText = Phrase("यहाँ वह भाषा चुनें जिसमें किताब चाहिए।", "Choose the language your book should be translated into.")
    val tourBookTitle = Phrase("📚 किताब", "📚 Your book")
    val tourBookText = Phrase("फिर यह हरा बटन दबाकर अपनी किताब चुनें। बस इतना ही!", "Then tap this green button to pick a PDF or EPUB. That's it!")
    val tapToContinue = Phrase("👆 आगे बढ़ने के लिए कहीं भी दबाएं", "👆 Tap to continue")
    val tapToStart = Phrase("👆 शुरू करने के लिए दबाएं", "👆 Tap to start")
    val skipTour = Phrase("छोड़ें", "Skip")
    val gotIt = Phrase("ठीक है", "Got it")

    // Consent
    val consentTitle = Phrase("क्या किताब AI को भेजें?", "Send the book to AI?")
    fun consentHelp(provider: String) = Phrase(
        "अनुवाद के लिए आपकी किताब के शब्द $provider नाम की AI कंपनी को भेजे जाएंगे। तस्वीरें आपके फ़ोन में ही रहेंगी। " +
            "कोई निजी या गुप्त कागज़ न भेजें। भेजना है तो हरा हाँ बटन दबाएं, नहीं तो लाल नहीं बटन।",
        "To translate it, the words of your book will be sent to the AI company $provider. Pictures stay on your phone. " +
            "Don't send private or secret papers. To send it, press the green Yes button; if not, the red No button.",
    )
    val yesSend = Phrase("हाँ, भेजें", "Yes, send")
    val no = Phrase("नहीं", "No")

    // Books
    val listen = Phrase("सुनें", "Listen")
    val read = Phrase("पढ़ें", "Read")
    val pause = Phrase("रोकें", "Pause")
    val resume = Phrase("आगे चलाएं", "Resume")
    val translating = Phrase("अनुवाद हो रहा है", "Translating")
    val done = Phrase("पूरा हुआ", "Done")
    val paused = Phrase("रुका हुआ", "Paused")
    val waiting = Phrase("इंतज़ार", "Waiting")
    val stopped = Phrase("रुक गया", "Stopped")
    val noBooks = Phrase("अभी कोई किताब नहीं", "No books yet")
    val noBooksHelp = Phrase(
        "अभी कोई किताब नहीं है। अनुवाद वाले पन्ने पर हरा बटन दबाकर किताब चुनें।",
        "There are no books yet. On the Translate page, press the green button to choose a book.",
    )
    val page = Phrase("पेज", "Page")
    fun pageOf(page: Int, total: String) = Phrase("पेज $page / $total", "page $page of $total")
    fun pagesDone(done: Int, total: String) = Phrase("$done / $total पेज", "$done / $total pages")
    val noBooksNote = Phrase(
        "जिन किताबों का अनुवाद होगा, वे यहाँ दिखेंगी: पढ़ने, सुनने या सेव करने के लिए।",
        "Books you translate appear here, ready to read, listen to or save.",
    )

    // Listening
    val previous = Phrase("पिछला", "Previous")
    val next = Phrase("अगला", "Next")
    val play = Phrase("चलाएं", "Play")
    val slow = Phrase("धीमा", "Slow")
    val normal = Phrase("सामान्य", "Normal")
    val noVoice = Phrase("इस भाषा की आवाज़ फ़ोन में नहीं है", "This phone has no voice for this language")
    val getVoice = Phrase("आवाज़ डाउनलोड करें", "Get the voice")
    val nothingToHear = Phrase("अभी सुनने के लिए कुछ नहीं है", "Nothing to listen to yet")
    val listenHelp = Phrase(
        "बड़ा बटन दबाकर किताब सुनें। पिछला और अगला बटन से एक हिस्सा पीछे या आगे जाएं।",
        "Press the big button to hear the book. Previous and Next go back or forward one part.",
    )
}
