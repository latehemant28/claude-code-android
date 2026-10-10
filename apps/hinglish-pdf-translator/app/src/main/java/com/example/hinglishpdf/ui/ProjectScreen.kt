package com.example.hinglishpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.ui.status.ErrorGuide
import com.example.hinglishpdf.ui.status.GuideAction
import com.example.hinglishpdf.ui.status.GuidanceCard
import com.example.hinglishpdf.ui.status.ProgressPanel
import com.example.hinglishpdf.ui.status.StageMap

/** What can be done with a book from its project screen (and its card). */
class ProjectActions(
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onSave: () -> Unit,
    val onOpenFile: () -> Unit,
    val onCopy: () -> Unit,
    val onDelete: () -> Unit,
    val onGuide: (GuideAction) -> Unit,
)

/**
 * One book as a project: where it is (the stage map: the user's model of the
 * work), what needs doing if it is stuck, progress with time left, every
 * action as a visible button, and the translated pages.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectScreen(
    entry: BookWithProgress,
    state: TranslatorUiState,
    pages: List<PageEntity>,
    livePage: LivePage?,
    snackbar: SnackbarHostState,
    actions: ProjectActions,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val book = entry.book
    val status = state.status(entry)
    val running = state.isRunning(book)
    val unit = book.unitName

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to your books") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "headline") {
                Text(status.headline, style = MaterialTheme.typography.titleMedium)
            }
            item(key = "map") { Card { StageMap(status, Modifier.padding(8.dp)) } }

            if (status.needsAttention) {
                item(key = "guide") {
                    val message = if (book.id in state.needsOcr) "This PDF is scanned" else book.error
                    GuidanceCard(ErrorGuide.of(message), entry.translatedPages, unit, actions.onGuide)
                }
            }

            if (running) {
                item(key = "progress") {
                    val live = state.live
                    ProgressPanel(
                        label = live.label,
                        done = if (live.total > 0) live.done else 0,
                        total = live.total,
                        timeLeft = state.timeLeft(book),
                    )
                }
            }

            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.pausingBookId == book.id -> OutlinedButton(onClick = {}, enabled = false) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Pausing…")
                        }
                        running -> OutlinedButton(onClick = actions.onPause) { Label(Icons.Filled.Pause, "Pause") }
                        book.status != BookStatus.COMPLETED && !status.needsAttention ->
                            Button(onClick = actions.onResume) { Label(Icons.Filled.PlayArrow, "Resume") }
                    }
                    if (entry.translatedPages > 0) {
                        if (state.savingBookId == book.id) {
                            FilledTonalButton(onClick = {}, enabled = false) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Saving…")
                            }
                        } else {
                            FilledTonalButton(onClick = actions.onSave) { Label(Icons.Filled.Save, "Save ${state.outputFormat.name} to Downloads") }
                        }
                        OutlinedButton(onClick = actions.onCopy) { Label(Icons.Filled.ContentCopy, "Copy text") }
                    }
                    if (book.outputUri != null) {
                        OutlinedButton(onClick = actions.onOpenFile) { Label(Icons.AutoMirrored.Filled.OpenInNew, "Open file") }
                    }
                    OutlinedButton(onClick = actions.onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (running && livePage != null) {
                item(key = "live") { LiveCard(state.live.label, livePage, unit.replaceFirstChar { it.uppercase() }) }
            }
            if (pages.isNotEmpty()) {
                item(key = "pages-title") {
                    Text("Translated ${unit}s", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                val ordered = if (running) pages.asReversed() else pages // newest first while translating
                items(ordered, key = { "page-${it.bookId}-${it.pageNumber}" }) { PageView(it, unit.replaceFirstChar { c -> c.uppercase() }) }
            } else if (!running) {
                item(key = "no-pages") {
                    Text(
                        "Translated ${unit}s appear here as soon as each one is done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun Label(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text)
}
