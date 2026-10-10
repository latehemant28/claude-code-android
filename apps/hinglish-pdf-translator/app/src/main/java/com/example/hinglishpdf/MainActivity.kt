package com.example.hinglishpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.hinglishpdf.ui.TranslatorScreen
import com.example.hinglishpdf.ui.TranslatorViewModel
import com.example.hinglishpdf.ui.theme.HinglishPdfTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TranslatorViewModel by viewModels { TranslatorViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HinglishPdfTheme {
                TranslatorScreen(viewModel)
            }
        }
    }
}
