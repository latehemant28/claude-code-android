package com.example.hinglishpdf.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.hinglishpdf.data.llm.Language

// ------------------------------------------------------------------ badge

/** The exact wording of the main screen's top badge. */
const val BYOK_BADGE_TEXT = "BYOK Model - 100% Free & Private Translation"

/** The persistent badge at the top of the main screen. */
@Composable
fun ByokBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(Color(0xFF3A1873), Color(0xFF7C3AED), Color(0xFFDB2777))))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(BYOK_BADGE_TEXT, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------ language prompt

/**
 * First launch: "Select your Target Language". Tapping a language saves it
 * as the "To" language; "Not now" (or outside / back) keeps the default.
 */
@Composable
fun TargetLanguageDialog(current: Language, onSelect: (Language) -> Unit, onDismiss: () -> Unit) {
    val guide = LocalGuide.current
    LaunchedEffect(Unit) { guide.say(Say.chooseLanguageHelp) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                Text("🌐", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(
                    Say.chooseLanguage.text(),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(Language.targets) { language ->
                        val chosen = language == current
                        Card(
                            onClick = {
                                guide.sayIn(language.nativeName ?: language.englishName, language)
                                onSelect(language)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            border = if (chosen) CardDefaults.outlinedCardBorder() else null,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                                // The language's own script first and large: recognisable without reading English.
                                Text(language.nativeName ?: language.shortName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                if (language.nativeName != null) {
                                    Text(language.shortName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(Say.notNow.text()) }
            }
        }
    }
}

// --------------------------------------------------------- spotlight tour

/** The main screen's tour, one highlighted element at a time. */
enum class TourStep(val title: Phrase, val text: Phrase) {
    API_KEY(Say.tourKeyTitle, Say.tourKeyText),
    LANGUAGE(Say.tourLanguageTitle, Say.tourLanguageText),
    UPLOAD(Say.tourBookTitle, Say.tourBookText),
}

/** Where each tour target is on screen, filled in by [spotlightTarget]. */
class SpotlightTargets {
    val bounds = mutableStateMapOf<TourStep, Rect>()
}

/** Marks this element as the target of [step]. */
fun Modifier.spotlightTarget(targets: SpotlightTargets, step: TourStep): Modifier =
    onGloballyPositioned { targets.bounds[step] = it.boundsInRoot() }

/**
 * Dims the screen except one element, with a pulsing ring around it and a
 * short helper text; a tap anywhere moves to the next element, and the last
 * tap ends the tour. [bringIntoView] scrolls a target onto the screen first.
 * Must be drawn at the root, so its coordinates match [SpotlightTargets].
 */
@Composable
fun SpotlightTour(
    targets: SpotlightTargets,
    bringIntoView: suspend (TourStep) -> Unit,
    onDone: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = TourStep.entries[index]
    val last = index == TourStep.entries.lastIndex
    val guide = LocalGuide.current
    LaunchedEffect(step) {
        guide.say(step.text) // each step is also read aloud
        bringIntoView(step)
    }
    val pulse by rememberInfiniteTransition(label = "spotlight").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_300), RepeatMode.Restart), label = "pulse",
    )
    val density = LocalDensity.current
    val hole = targets.bounds[step]?.let { with(density) { it.inflate(8.dp.toPx()) } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(index) { detectTapGestures { if (last) onDone() else index++ } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The dim layer with a rounded window cut out of it (even-odd fill: no blending needed).
            val corner = CornerRadius(20.dp.toPx())
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (hole != null) addRoundRect(RoundRect(hole, corner))
            }
            drawPath(dim, Color.Black.copy(alpha = 0.74f))
            if (hole != null) {
                val grow = pulse * 14.dp.toPx()
                drawRoundRect(
                    Color.White.copy(alpha = (1f - pulse) * 0.9f),
                    topLeft = Offset(hole.left - grow, hole.top - grow),
                    size = Size(hole.width + 2 * grow, hole.height + 2 * grow),
                    cornerRadius = CornerRadius(corner.x + grow),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }

        val screenHeight = with(density) { maxHeight.toPx() }
        val below = hole == null || hole.bottom < screenHeight * 0.6f
        val bubble = @Composable {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(horizontal = 24.dp).border(2.dp, Color(0xFF7C3AED), RoundedCornerShape(20.dp)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${index + 1} / ${TourStep.entries.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(step.title.text(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(step.text.text(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (last) Say.tapToStart.text() else Say.tapToContinue.text(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (hole != null) {
            with(density) {
                if (below) {
                    Box(Modifier.fillMaxSize().padding(top = (hole.bottom + 20.dp.toPx()).toDp()), contentAlignment = Alignment.TopCenter) { bubble() }
                } else {
                    Box(
                        Modifier.fillMaxSize().padding(bottom = (screenHeight - hole.top + 20.dp.toPx()).toDp()),
                        contentAlignment = Alignment.BottomCenter,
                    ) { bubble() }
                }
            }
        }
        TextButton(
            onClick = onDone,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 104.dp) // above the bottom tabs
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50)),
        ) {
            Text(Say.skipTour.text(), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
