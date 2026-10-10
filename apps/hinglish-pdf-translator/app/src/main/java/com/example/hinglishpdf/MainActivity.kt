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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.hinglishpdf.ui.TranslatorScreen
import com.example.hinglishpdf.ui.TranslatorViewModel
import com.example.hinglishpdf.ui.onboarding.OnboardingScreen
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TranslatorViewModel by viewModels { TranslatorViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = (application as HinglishApp).preferences
        setContent {
            HinglishPdfTheme {
                // The tutorial appears by itself only on the very first launch (recorded at
                // once); kept across rotation, and replayable from the menu.
                var onboarding by rememberSaveable { mutableStateOf(preferences.takeFirstLaunch()) }
                AnimatedContent(
                    targetState = onboarding,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding",
                ) { showing ->
                    if (showing) {
                        OnboardingScreen(onFinish = { onboarding = false })
                    } else {
                        TranslatorScreen(viewModel, onShowTutorial = { onboarding = true })
                    }
                }
            }
        }
    }
}
