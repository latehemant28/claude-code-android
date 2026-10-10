package com.example.hinglishpdf.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.hinglishpdf.R
import com.example.hinglishpdf.data.ai.AIProvider
import kotlinx.coroutines.delay

/** What the success screen says, exactly as specified. */
const val KEY_SAVED_TITLE = "✅ API Key Saved Successfully!"

/**
 * Shown the moment a key is saved: a large animated check, a strong
 * double-pulse vibration, and the title; it closes itself after a moment
 * (or on a tap) and leaves the user on the main screen, ready to translate.
 */
@Composable
fun KeySavedCelebration(provider: AIProvider, onDone: () -> Unit) {
    val context = LocalContext.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.key_saved))
    val progress by animateLottieCompositionAsState(composition, iterations = 1)
    LaunchedEffect(Unit) {
        successVibration(context)
        delay(CELEBRATION_MS)
        onDone()
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, modifier = Modifier.padding(32.dp)) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress = { progress },
                        renderMode = RenderMode.SOFTWARE, // the same on every GPU: no black boxes
                        modifier = Modifier.size(200.dp),
                    )
                    Text(
                        KEY_SAVED_TITLE,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "🤖 ${provider.displayName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private const val CELEBRATION_MS = 2_600L

/** A strong, unmistakable "done": two firm pulses (falls back to a plain buzz without amplitude control). */
fun successVibration(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    val timings = longArrayOf(0, 70, 70, 140)
    val effect = if (vibrator.hasAmplitudeControl()) {
        VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255), -1)
    } else {
        VibrationEffect.createWaveform(timings, -1)
    }
    runCatching { vibrator.vibrate(effect) }
}
