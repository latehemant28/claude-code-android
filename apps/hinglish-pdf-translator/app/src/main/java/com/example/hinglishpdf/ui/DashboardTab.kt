package com.example.hinglishpdf.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.ui.theme.brand

/** Index of the "Choose a book" button in the dashboard (hero, tiles, book...). */
const val NEW_BOOK_ITEM = 2

/**
 * The Translate tab: the "Bring your own key (BYOK)" hero, two tiles side by
 * side — the AI key and the language — the green "Choose a book", and the
 * book being translated with Listen / Read / Pause. Each tile says out loud
 * what it does when tapped (while spoken help is on).
 */
@Composable
fun DashboardTab(
    state: TranslatorUiState,
    listState: LazyListState,
    targets: SpotlightTargets,
    actions: BookActions,
    onConfigure: () -> Unit,
    onPickLanguage: () -> Unit,
    onPick: () -> Unit,
) {
    val guide = LocalGuide.current
    val brand = MaterialTheme.brand
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Keep the order: NEW_BOOK_ITEM is the index of the "Choose a book" button.
        item(key = "hero") { HeroBanner() }

        item(key = "tiles") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfigTile(
                    icon = Icons.Filled.Key,
                    label = Say.aiKey.text(),
                    value = state.provider.displayName,
                    status = if (state.configured) Say.connected.text() else Say.tapToAddKey.text(),
                    statusIcon = if (state.configured) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    statusColor = if (state.configured) brand.success else brand.warning,
                    onClick = {
                        guide.say(if (state.configured) Say.keyReadyHelp else Say.keyHelp)
                        onConfigure()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight().spotlightTarget(targets, TourStep.API_KEY),
                )
                val target = state.targetLanguage
                ConfigTile(
                    icon = Icons.Filled.Language,
                    label = Say.translateTo.text(),
                    value = target.englishName,
                    status = target.nativeName ?: target.shortName,
                    statusIcon = null,
                    statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        guide.say(Say.languageHelp(target))
                        onPickLanguage()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight().spotlightTarget(targets, TourStep.LANGUAGE),
                )
            }
        }

        item(key = "new") {
            // The content form of the FAB (not icon / text slots), so its label merges for TalkBack.
            ExtendedFloatingActionButton(
                onClick = { if (!state.importing) onPick() },
                containerColor = GoColor,
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth().height(64.dp).spotlightTarget(targets, TourStep.UPLOAD),
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.importing) "…" else Say.chooseBookFile.text(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        val running = state.books.firstOrNull { state.isRunning(it.book) }
        val latest = running ?: state.books.firstOrNull()
        if (latest != null) {
            item(key = "current") {
                ActiveBookCard(
                    entry = latest,
                    running = running != null,
                    liveLabel = state.live.label.takeIf { running != null && it.isNotBlank() },
                    opening = state.openingBookId == latest.book.id,
                    actions = actions,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** "Bring your own key (BYOK)" with the "100% Free & Private Translation" badge. */
@Composable
private fun HeroBanner() {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            DASHBOARD_TITLE,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        ByokBadge()
    }
}

/** One of the two settings tiles: an icon, what it is, its value and its status. */
@Composable
private fun ConfigTile(
    icon: ImageVector,
    label: String,
    value: String,
    status: String,
    statusIcon: ImageVector?,
    statusColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (statusIcon != null) {
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The status of a book, in a word or two. */
@Composable
fun bookStatus(entry: BookWithProgress, running: Boolean): String = when {
    running -> Say.translating.text()
    entry.book.status == BookStatus.COMPLETED -> Say.done.text()
    entry.book.status == BookStatus.PAUSED -> Say.paused.text()
    entry.book.status == BookStatus.FAILED -> Say.stopped.text()
    else -> Say.waiting.text()
}

/** The colour of that status: primary while translating, green when done, amber when paused. */
@Composable
fun bookStatusColor(entry: BookWithProgress, running: Boolean): Color = when {
    running -> MaterialTheme.colorScheme.primary
    entry.book.status == BookStatus.COMPLETED -> MaterialTheme.brand.success
    entry.book.status == BookStatus.PAUSED -> MaterialTheme.brand.warning
    entry.book.status == BookStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "45 / 120" (or "45 / ?" before the page count is known). */
fun pagesTotal(entry: BookWithProgress): String = entry.book.pageCount.takeIf { it > 0 }?.toString() ?: "?"

/** A ring that fills as the book is translated, with the percentage inside; it glides to each new value. */
@Composable
fun ProgressRing(entry: BookWithProgress, color: Color = MaterialTheme.colorScheme.primary, size: Int = 56) {
    val total = entry.book.pageCount
    val progress = if (total > 0) (entry.translatedPages.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(progress, tween(600), label = "progress")
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { shown },
            modifier = Modifier.fillMaxSize(),
            color = color,
            strokeWidth = 5.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** The book being translated now, or the latest one: ring, title, page, and Listen / Read / Pause. */
@Composable
private fun ActiveBookCard(
    entry: BookWithProgress,
    running: Boolean,
    liveLabel: String?,
    opening: Boolean,
    actions: BookActions,
    modifier: Modifier = Modifier,
) {
    val book = entry.book
    ElevatedCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(entry, color = bookStatusColor(entry, running))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${bookStatus(entry, running)} · ${Say.pageOf(entry.translatedPages, pagesTotal(entry)).text()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (liveLabel != null) {
                    Text(liveLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else if (book.status == BookStatus.FAILED && book.error != null) {
                    Text("${book.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                BookIconButtons(entry, running, opening, actions)
            }
        }
    }
}

/** Listen and Read (once a page is ready), and Pause / Resume while the book is not finished. */
@Composable
fun BookIconButtons(entry: BookWithProgress, running: Boolean, opening: Boolean, actions: BookActions) {
    val book = entry.book
    val ready = entry.translatedPages > 0
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            running -> FilledTonalIconButton(onClick = actions.onPause) {
                Icon(Icons.Filled.Pause, contentDescription = Say.pause.text())
            }
            book.status != BookStatus.COMPLETED -> FilledTonalIconButton(onClick = { actions.onResume(book) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = Say.resume.text())
            }
        }
        if (ready) {
            FilledTonalIconButton(onClick = { actions.onRead(book) }, enabled = !opening) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = Say.read.text())
            }
            FilledTonalIconButton(onClick = { actions.onListen(book) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = Say.listen.text())
            }
        }
    }
}
