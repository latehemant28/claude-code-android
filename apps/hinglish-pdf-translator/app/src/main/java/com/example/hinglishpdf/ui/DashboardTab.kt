package com.example.hinglishpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material3.Surface
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
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.llm.Language
import com.example.hinglishpdf.ui.theme.brand

/** Index of the Translate card in the dashboard (badge, engine, languages, format, translate...). */
const val NEW_BOOK_ITEM = 4

/**
 * The Translate tab: the BYOK badge, the active AI engine, From / To, the
 * output format and the big "Select PDF / EPUB" action; below them, the book
 * being translated right now (or the latest one).
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
    onOutputFormat: (DocFormat) -> Unit,
    onPick: () -> Unit,
    onShowTerms: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Keep the order: NEW_BOOK_ITEM is the index of the Translate card.
        item(key = "byok") { ByokBadge() }

        item(key = "status") {
            ApiStatusBanner(
                settings = state.providers,
                activeModel = state.activeModel?.takeIf { it.startsWith(state.provider.displayName) },
                onClick = onConfigure,
                modifier = Modifier.spotlightTarget(targets, TourStep.API_KEY),
            )
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

        item(key = "format") { OutputFormatCard(state.outputFormat, onOutputFormat) }

        // The main action, right below the output format.
        item(key = "new") {
            NewBookCard(
                target = state.targetLanguage.englishName,
                buttonModifier = Modifier.spotlightTarget(targets, TourStep.UPLOAD),
                enabled = state.languagesValid && !state.importing,
                importing = state.importing,
                onPick = onPick,
                onShowTerms = onShowTerms,
            )
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

/** "Output Format: ( PDF | EPUB )": what Export and Read write. */
@Composable
private fun OutputFormatCard(format: DocFormat, onSelect: (DocFormat) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Output Format:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "For Export and the reader",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillToggle(
                options = listOf(DocFormat.PDF, DocFormat.EPUB),
                selected = format,
                label = { it.name },
                onSelect = onSelect,
                modifier = Modifier.width(168.dp),
            )
        }
    }
}

@Composable
private fun NewBookCard(
    target: String,
    buttonModifier: Modifier = Modifier,
    enabled: Boolean,
    importing: Boolean,
    onPick: () -> Unit,
    onShowTerms: () -> Unit,
) {
    // The focal point of the screen: a hero card washed in the brand colours.
    Surface(
        shape = MaterialTheme.shapes.large,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.background(MaterialTheme.brand.heroWash).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientIcon(Icons.AutoMirrored.Filled.MenuBook, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Translate a book into $target",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Pick a PDF or EPUB. Every heading, paragraph and list is translated in place, " +
                    "page by page, in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GradientButton(
                text = if (importing) "Opening…" else "Select PDF / EPUB",
                icon = Icons.Filled.UploadFile,
                onClick = onPick,
                enabled = enabled,
                modifier = buttonModifier,
            )
            Text(
                "For personal use, with documents you have the rights to. Terms of Use",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onShowTerms),
            )
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
        Spacer(Modifier.size(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${book.fromLanguage.englishName} → ${book.toLanguage.englishName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(10.dp))
        if (book.pageCount > 0) {
            GradientProgress(entry.translatedPages.toFloat() / book.pageCount)
        } else if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.size(6.dp))
        Text(
            liveLabel ?: "${entry.translatedPages} of ${book.pageCount.takeIf { it > 0 } ?: "?"} ${book.unitName}s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        livePage?.let { live ->
            Spacer(Modifier.size(8.dp))
            LiveCard(live, book.unitName.replaceFirstChar { it.uppercase() }, compact = true)
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            TextButton(onClick = onOpenLibrary) {
                Text("Library")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
