package com.example.hinglishpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hinglishpdf.ui.LocalGuide
import com.example.hinglishpdf.ui.LocalHindi
import com.example.hinglishpdf.ui.TranslatorScreen
import com.example.hinglishpdf.ui.VoiceGuide
import com.example.hinglishpdf.ui.listen.ListenDocument
import com.example.hinglishpdf.ui.listen.ListenScreen
import com.example.hinglishpdf.ui.useHindi
import com.example.hinglishpdf.ui.TranslatorViewModel
import com.example.hinglishpdf.ui.reader.BookReaderScreen
import com.example.hinglishpdf.ui.reader.ReaderDocument
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme

/** The app's screens: the tabs, the reader, and Listen. */
private sealed interface Screen {
    data object Main : Screen
    data class Reader(val document: ReaderDocument) : Screen
    data class Listen(val document: ListenDocument) : Screen
}

class MainActivity : ComponentActivity() {

    private val viewModel: TranslatorViewModel by viewModels { TranslatorViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = (application as HinglishApp).preferences
        setContent {
            HinglishPdfTheme {
                // Very first launch only (each recorded at once; kept across rotation): the
                // target-language popup, then the spotlight tour (replayable from the menu).
                var languagePrompt by rememberSaveable { mutableStateOf(preferences.takeLanguagePrompt()) }
                var tour by rememberSaveable { mutableStateOf(preferences.takeTour()) }
                val reading by viewModel.reader.collectAsStateWithLifecycle()
                val listening by viewModel.listening.collectAsStateWithLifecycle()
                val rate by viewModel.speechRate.collectAsStateWithLifecycle()
                val voiceHelp by viewModel.voiceHelp.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val dark by viewModel.readerDark.collectAsStateWithLifecycle()
                val scale by viewModel.readerScale.collectAsStateWithLifecycle()
                val screen = when {
                    reading != null -> Screen.Reader(reading!!)
                    listening != null -> Screen.Listen(listening!!)
                    else -> Screen.Main
                }
                // The easy screens speak, and are written, in Hindi or English.
                val hindi = useHindi(state.targetLanguage)
                CompositionLocalProvider(
                    LocalHindi provides hindi,
                    LocalGuide provides VoiceGuide(viewModel.voice, hindi, voiceHelp),
                ) {
                AnimatedContent(
                    targetState = screen,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { target ->
                    when (target) {
                        Screen.Main -> TranslatorScreen(
                            viewModel,
                            languagePrompt = languagePrompt,
                            onLanguagePromptDone = { languagePrompt = false },
                            tour = tour,
                            onTourDone = { tour = false },
                            onShowTutorial = { tour = true },
                        )
                        is Screen.Reader -> BookReaderScreen(
                            document = target.document,
                            dark = dark,
                            scale = scale,
                            onDark = viewModel::setReaderDark,
                            onScale = viewModel::setReaderScale,
                            onClose = viewModel::closeReader,
                        )
                        is Screen.Listen -> ListenScreen(
                            document = target.document,
                            voice = viewModel.voice,
                            start = viewModel.listenPosition(target.document.bookId),
                            rate = rate,
                            onRate = viewModel::setSpeechRate,
                            onPosition = { viewModel.setListenPosition(target.document.bookId, it) },
                            onClose = viewModel::closeListen,
                        )
                    }
                }
                }
            }
        }
    }
}
