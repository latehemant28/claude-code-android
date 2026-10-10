package com.example.hinglishpdf.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.llm.Language

// ------------------------------------------------------------------ badge

/** The exact wording of the main screen's badge, under its title. */
const val BYOK_BADGE_TEXT = "100% Free & Private Translation"

/** The badge under "Bring your own key (BYOK)" on the main screen. */
@Composable
fun ByokBadge(modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(BYOK_BADGE_TEXT, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ------------------------------------------------------ language prompt

/**
 * First launch (and the language tile): "Choose your language", a sheet with
 * each language in its own script first, so it can be found without reading
 * English. Tapping one says its name; Continue saves it as the "To" language.
 * Swiping the sheet away (or back) keeps the current one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetLanguageDialog(current: Language, onSelect: (Language) -> Unit, onDismiss: () -> Unit) {
    val guide = LocalGuide.current
    LaunchedEffect(Unit) { guide.say(Say.chooseLanguageHelp) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by rememberSaveable { mutableStateOf(current) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = Language.targets.indexOf(current).coerceAtLeast(0))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Say.chooseLanguage.text(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                Say.chooseLanguageNote.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                state = list,
                modifier = Modifier.heightIn(max = 420.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Language.targets, key = { it.code }) { language ->
                    val selected = language == chosen
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton) {
                                guide.sayIn(language.nativeName ?: language.englishName, language)
                                chosen = language
                            },
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                // The language's own script first and large: recognisable without reading English.
                                Text(language.nativeName ?: language.shortName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (language.nativeName != null) {
                                    Text(language.shortName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
            }
            Button(onClick = { onSelect(chosen) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(Say.continueButton.text(), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
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
 * tooltip card: what it is for, "1 / 3", Skip and Next (Got it on the last
 * one). A tap anywhere also moves on. [bringIntoView] scrolls a target onto
 * the screen first. Must be drawn at the root, so its coordinates match
 * [SpotlightTargets].
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
    val next: () -> Unit = { if (last) onDone() else index++ }
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
            .pointerInput(index) { detectTapGestures { next() } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The dim layer with a rounded window cut out of it (even-odd fill: no blending needed).
            val corner = CornerRadius(20.dp.toPx())
            val dim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (hole != null) addRoundRect(RoundRect(hole, corner))
            }
            drawPath(dim, Color.Black.copy(alpha = 0.75f))
            if (hole != null) {
                val grow = pulse * 12.dp.toPx()
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
        val tooltip = @Composable {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(step.title.text(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(step.text.text(), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${index + 1} / ${TourStep.entries.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDone) { Text(Say.skipTour.text()) }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = next) { Text(if (last) Say.gotIt.text() else Say.next.text()) }
                    }
                }
            }
        }
        with(density) {
            when {
                hole == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { tooltip() }
                below -> Box(Modifier.fillMaxSize().padding(top = (hole.bottom + 16.dp.toPx()).toDp()), contentAlignment = Alignment.TopCenter) { tooltip() }
                else -> Box(
                    Modifier.fillMaxSize().padding(bottom = (screenHeight - hole.top + 16.dp.toPx()).toDp()),
                    contentAlignment = Alignment.BottomCenter,
                ) { tooltip() }
            }
        }
    }
}
