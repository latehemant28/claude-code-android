package com.example.hinglishpdf.ui.listen

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.data.settings.AppPreferences
import com.example.hinglishpdf.data.voice.Voice
import com.example.hinglishpdf.ui.GradientProgress
import com.example.hinglishpdf.ui.LocalGuide
import com.example.hinglishpdf.ui.PillToggle
import com.example.hinglishpdf.ui.Say
import com.example.hinglishpdf.ui.SectionCard
import com.example.hinglishpdf.ui.text
import com.example.hinglishpdf.ui.theme.brand
import kotlinx.coroutines.delay

/**
 * "Listen": the translated book read aloud by the phone's voice, part by
 * part, with three big buttons (back, play / pause, next) and a slow / normal
 * speed. Listening carries on where it stopped last time.
 */
@Composable
fun ListenScreen(
    document: ListenDocument,
    voice: Voice,
    start: Int,
    rate: Float,
    onRate: (Float) -> Unit,
    onPosition: (Int) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val player = remember(document) { ListenPlayer(voice, document.language, document.paragraphs, start, onPosition) }
    val state by player.state.collectAsState()
    LaunchedEffect(player) { voice.finished.collect(player::onFinished) }
    DisposableEffect(player) { onDispose { player.pause() } }
    LaunchedEffect(rate) { voice.setRate(rate) }

    // The engine starts in the background: give it a moment before saying the voice is missing.
    var voiceMissing by remember { mutableStateOf(false) }
    LaunchedEffect(document.language) {
        repeat(10) {
            if (voice.hasVoice(document.language)) {
                voiceMissing = false
                return@LaunchedEffect
            }
            delay(300)
        }
        voiceMissing = true
    }

    val guide = LocalGuide.current
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(32.dp))
            }
            Text(
                document.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (!state.playing) guide.sayNow(Say.listenHelp) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = Say.help.text(), modifier = Modifier.size(30.dp))
            }
        }

        if (voiceMissing) {
            SectionCard(color = MaterialTheme.brand.warning.copy(alpha = 0.18f)) {
                Text(Say.noVoice.text(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                ) { Text(Say.getVoice.text()) }
            }
        }

        // The part being read, large, so it can also be followed with the eyes.
        val paragraph = player.current
        SectionCard(modifier = Modifier.weight(1f)) {
            if (paragraph == null) {
                Text(Say.nothingToHear.text(), style = MaterialTheme.typography.titleLarge)
            } else {
                Text(
                    "${Say.page.text()} ${paragraph.page}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    paragraph.text,
                    fontSize = if (paragraph.heading) 28.sp else 24.sp,
                    lineHeight = 38.sp,
                    fontWeight = if (paragraph.heading) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        if (player.paragraphs.isNotEmpty()) {
            GradientProgress((state.index + 1).toFloat() / player.paragraphs.size)
            Text(
                "${state.index + 1} / ${player.paragraphs.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundControl(Icons.Filled.SkipPrevious, Say.previous.text(), 72.dp, onClick = player::previous)
            Box(
                Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.brand.accent)
                    .semantics { contentDescription = if (state.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = player::toggle, modifier = Modifier.fillMaxSize(), enabled = player.paragraphs.isNotEmpty()) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            RoundControl(Icons.Filled.SkipNext, Say.next.text(), 72.dp, onClick = player::next)
        }

        val slow = "🐢  ${Say.slow.text()}"
        val normal = "🐇  ${Say.normal.text()}"
        PillToggle(
            options = listOf(AppPreferences.SLOW_SPEECH, 1f),
            selected = if (rate < 1f) AppPreferences.SLOW_SPEECH else 1f,
            label = { if (it < 1f) slow else normal },
            onSelect = onRate,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(size)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.5f))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

