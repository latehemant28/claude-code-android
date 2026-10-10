package com.example.hinglishpdf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.ui.theme.brand

/** Index of the "Choose a book" tile in the dashboard (badge, help, key, language, book...). */
const val NEW_BOOK_ITEM = 4

/**
 * The Translate tab, made to be used without reading: big picture tiles in
 * the order of the steps — the key (only until it is set up), the language,
 * and the green "Choose a book" — each saying what it does with 🔊. Below
 * them, the book being translated, with Listen.
 */
@Composable
fun DashboardTab(
    state: TranslatorUiState,
    livePage: LivePage?,
    listState: LazyListState,
    targets: SpotlightTargets,
    actions: BookActions,
    onConfigure: () -> Unit,
    onPickLanguage: () -> Unit,
    onPick: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val guide = LocalGuide.current
    val brand = MaterialTheme.brand
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Keep the order: NEW_BOOK_ITEM is the index of the "Choose a book" tile.
        item(key = "byok") { ByokBadge() }

        item(key = "help") {
            FilledTonalButton(
                onClick = { guide.sayNow(Say.welcome) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(Say.help.text(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        item(key = "status") {
            val target = Modifier.spotlightTarget(targets, TourStep.API_KEY)
            if (!state.configured) {
                BigTile(
                    icon = Icons.Filled.Key,
                    title = Say.keyTitle.text(),
                    subtitle = Say.keySubtitle.text(),
                    background = Brush.linearGradient(listOf(Color(0xFFB45309), brand.warning)),
                    onClick = { guide.say(Say.keyHelp); onConfigure() },
                    onSpeak = { guide.sayNow(Say.keyHelp) },
                    modifier = target,
                )
            } else {
                SectionCard(modifier = target, onClick = { guide.say(Say.keyReadyHelp) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = brand.success, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.provider.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(Say.keyReady.text(), style = MaterialTheme.typography.bodyMedium, color = brand.success)
                        }
                        TextButton(onClick = onConfigure) { Text(Say.change.text()) }
                        SpeakButton({ guide.sayNow(Say.keyReadyHelp) })
                    }
                }
            }
        }

        item(key = "languages") {
            val target = state.targetLanguage
            BigTile(
                icon = Icons.Filled.Language,
                title = target.nativeName ?: target.englishName,
                subtitle = "${Say.languageTitle.text()} · ${target.englishName}",
                background = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, brand.accentEnd)),
                onClick = { guide.say(Say.languageHelp(target)); onPickLanguage() },
                onSpeak = { guide.sayNow(Say.languageHelp(target)) },
                modifier = Modifier.spotlightTarget(targets, TourStep.LANGUAGE),
            )
        }

        item(key = "new") {
            BigTile(
                icon = Icons.Filled.UploadFile,
                title = if (state.importing) "…" else Say.chooseBook.text(),
                subtitle = "PDF / EPUB",
                background = GoGreen,
                onClick = { if (!state.importing) onPick() },
                onSpeak = { guide.sayNow(Say.chooseBookHelp) },
                minHeight = 132.dp,
                modifier = Modifier.spotlightTarget(targets, TourStep.UPLOAD),
            )
        }

        val running = state.books.firstOrNull { state.isRunning(it.book) }
        val latest = running ?: state.books.firstOrNull()
        if (latest != null) {
            item(key = "current") {
                CurrentBookCard(
                    entry = latest,
                    running = running != null,
                    livePage = livePage.takeIf { running != null },
                    opening = state.openingBookId == latest.book.id,
                    actions = actions,
                    onOpenLibrary = onOpenLibrary,
                )
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

/** A ring that fills as the book is translated, with the percentage inside. */
@Composable
fun ProgressRing(entry: BookWithProgress, size: Int = 72) {
    val total = entry.book.pageCount
    val progress = if (total > 0) entry.translatedPages.toFloat() / total else 0f
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 7.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** The book being translated now (live), or the latest one: Listen, Read, Pause or Resume. */
@Composable
private fun CurrentBookCard(
    entry: BookWithProgress,
    running: Boolean,
    livePage: LivePage?,
    opening: Boolean,
    actions: BookActions,
    onOpenLibrary: () -> Unit,
) {
    val book = entry.book
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(entry)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${bookStatus(entry, running)} · ${entry.translatedPages}/${book.pageCount.takeIf { it > 0 } ?: "?"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        livePage?.let { live ->
            Spacer(Modifier.height(10.dp))
            LiveCard(live, Say.page.text(), compact = true)
        }
        Spacer(Modifier.height(12.dp))
        BookButtons(entry, running, opening, actions)
        TextButton(onClick = onOpenLibrary, modifier = Modifier.align(Alignment.End)) { Text(Say.tabBooks.text()) }
    }
}

/** Listen (as soon as a page is ready), Read (when done), and Pause / Resume: big, with pictures. */
@Composable
fun BookButtons(entry: BookWithProgress, running: Boolean, opening: Boolean, actions: BookActions) {
    val book = entry.book
    val big = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry.translatedPages > 0) {
            Button(onClick = { actions.onListen(book) }, modifier = big) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(Say.listen.text(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (book.status == BookStatus.COMPLETED) {
                FilledTonalButton(onClick = { actions.onRead(book) }, enabled = !opening, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (opening) "…" else Say.read.text(), fontSize = 18.sp)
                }
            }
            when {
                running -> OutlinedButton(onClick = actions.onPause, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(Say.pause.text(), fontSize = 18.sp)
                }
                book.status != BookStatus.COMPLETED -> FilledTonalButton(
                    onClick = { actions.onResume(book) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(Say.resume.text(), fontSize = 18.sp)
                }
            }
        }
    }
}
