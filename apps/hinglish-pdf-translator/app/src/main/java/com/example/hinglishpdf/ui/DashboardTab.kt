package com.example.hinglishpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.ui.theme.brand

/** Index of the "Choose a book" step in the dashboard (badge, engine, language, book...). */
const val NEW_BOOK_ITEM = 3

/**
 * The Translate tab, as three numbered steps: ① the AI engine, ② the
 * language, ③ choose a book. Below them, the book being translated (or the
 * latest one). Everything else lives in Library and Settings.
 */
@Composable
fun DashboardTab(
    state: TranslatorUiState,
    livePage: LivePage?,
    listState: LazyListState,
    targets: SpotlightTargets,
    actions: BookActions,
    onConfigure: () -> Unit,
    onSourceLanguage: (Language) -> Unit,
    onTargetLanguage: (Language) -> Unit,
    onSwapLanguages: () -> Unit,
    onPick: () -> Unit,
    onShowTerms: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Keep the order: NEW_BOOK_ITEM is the index of the "Choose a book" step.
        item(key = "byok") { ByokBadge() }

        item(key = "status") {
            EngineStep(state, onConfigure, Modifier.spotlightTarget(targets, TourStep.API_KEY))
        }

        item(key = "languages") {
            LanguageCard(
                modifier = Modifier.spotlightTarget(targets, TourStep.LANGUAGE),
                source = state.sourceLanguage,
                target = state.targetLanguage,
                onSource = onSourceLanguage,
                onTarget = onTargetLanguage,
                onSwap = onSwapLanguages,
            )
        }

        item(key = "new") {
            SectionCard {
                StepHeader(3, "Choose a book")
                Spacer(Modifier.height(12.dp))
                GradientButton(
                    text = if (state.importing) "Opening…" else "Select PDF / EPUB",
                    icon = Icons.Filled.UploadFile,
                    onClick = onPick,
                    enabled = state.languagesValid && !state.importing,
                    modifier = Modifier.spotlightTarget(targets, TourStep.UPLOAD),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Translated into ${state.targetLanguage.englishName} in the background, page by page. " +
                        "You'll be asked before anything is sent to ${state.provider.displayName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Terms of Use",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onShowTerms),
                )
            }
        }

        val running = state.books.firstOrNull { state.isRunning(it.book) }
        val latest = running ?: state.books.firstOrNull()
        if (latest != null) {
            item(key = "current") {
                CurrentBookCard(
                    entry = latest,
                    running = running != null,
                    liveLabel = state.live.label.takeIf { running != null },
                    livePage = livePage.takeIf { running != null },
                    opening = state.openingBookId == latest.book.id,
                    actions = actions,
                    onOpenLibrary = onOpenLibrary,
                )
            }
        }
    }
}

/** "①  AI engine": a numbered circle and the step's name. */
@Composable
fun StepHeader(number: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Step 1: the AI engine in use with a green "Connected" light, or, with no
 * key yet, "⚠️ API Key Required - Tap to Configure". Tapping it opens the
 * provider and key sheet.
 */
@Composable
private fun EngineStep(state: TranslatorUiState, onConfigure: () -> Unit, modifier: Modifier) {
    val brand = MaterialTheme.brand
    val configured = state.configured
    SectionCard(modifier = modifier, onClick = onConfigure) {
        StepHeader(1, "AI engine")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (configured) {
                    Text(state.provider.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(brand.success)
                        Spacer(Modifier.width(6.dp))
                        Text("Connected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        "⚠️ API Key Required - Tap to Configure",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Free with Google Gemini. Takes a minute.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (configured) {
                TextButton(onClick = onConfigure) { Text("Change") }
            } else {
                Button(onClick = onConfigure) { Text("Set up") }
            }
        }
    }
}

/** The book being translated now (live), or the latest one with its next step. */
@Composable
private fun CurrentBookCard(
    entry: BookWithProgress,
    running: Boolean,
    liveLabel: String?,
    livePage: LivePage?,
    opening: Boolean,
    actions: BookActions,
    onOpenLibrary: () -> Unit,
) {
    val book = entry.book
    SectionCard {
        SectionLabel(if (running) "Now translating" else "Latest book")
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        if (book.pageCount > 0) {
            GradientProgress(entry.translatedPages.toFloat() / book.pageCount)
        } else if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        Text(
            liveLabel ?: "${entry.translatedPages} of ${book.pageCount.takeIf { it > 0 } ?: "?"} ${book.unitName}s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        livePage?.let { live ->
            Spacer(Modifier.height(8.dp))
            LiveCard(live, book.unitName.replaceFirstChar { it.uppercase() }, compact = true)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> OutlinedButton(onClick = actions.onPause) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Pause")
                }
                book.status == BookStatus.COMPLETED -> Button(onClick = { actions.onRead(book) }, enabled = !opening) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (opening) "Opening…" else "Read Now")
                }
                else -> FilledTonalButton(onClick = { actions.onResume(book) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Resume")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenLibrary) { Text("All books") }
        }
    }
}
