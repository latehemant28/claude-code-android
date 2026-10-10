package com.example.hinglishpdf.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.bulletFor
import com.example.hinglishpdf.data.llm.ModelStatus

/**
 * Qwen 2.5 1.5B Instruct, int8, 1280-token KV cache (~1.6 GB, Apache-2.0):
 * the fastest variant on the GPU, and micro-chunks never need more context.
 * Opened in the browser, so the app itself never needs internet.
 */
private const val QWEN_DOWNLOAD_URL =
    "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    // Both collected on the main thread; the heavy work behind them runs on Dispatchers.Default.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val livePage by viewModel.livePage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Storage Access Framework pickers: no storage permission required.
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addBook)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }

    // Android 13+: ask once so "Translating page 45 of 300..." can be shown.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Book → Hinglish / Minglish") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "model") {
                ModelCard(
                    status = state.model,
                    canChange = state.model !is ModelStatus.Preparing && !state.live.running,
                    onImport = { modelPicker.launch(arrayOf("*/*")) },
                    onRescan = viewModel::rescanModel,
                    onDownload = {
                        try {
                            uriHandler.openUri(QWEN_DOWNLOAD_URL)
                        } catch (e: Exception) {
                            viewModel.showMessage("No browser found to download the model")
                        }
                    },
                )
            }

            item(key = "new") {
                NewBookCard(
                    enabled = state.canAddBook,
                    importing = state.importing,
                    onPick = {
                        // Some file managers label EPUBs as generic binaries.
                        bookPicker.launch(arrayOf(DocFormat.PDF.mimeType, DocFormat.EPUB.mimeType, "application/octet-stream"))
                    },
                )
            }

            if (state.books.isNotEmpty()) {
                item(key = "library-title") {
                    Text("Your books", style = MaterialTheme.typography.titleMedium)
                }
                items(state.books, key = { "book-${it.book.id}" }) { entry ->
                    BookCard(
                        entry = entry,
                        selected = entry.book.id == state.selected?.book?.id,
                        running = state.isRunning(entry.book),
                        liveLabel = state.live.label.takeIf { state.isRunning(entry.book) },
                        onSelect = { viewModel.select(entry.book.id) },
                        onPause = viewModel::pause,
                        onResume = { viewModel.resume(entry.book) },
                        onSave = { viewModel.saveToDownloads(entry.book) },
                        onOpen = {
                            val uri = entry.book.outputUri ?: return@BookCard
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(Uri.parse(uri), entry.book.format.mimeType)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            } catch (e: Exception) {
                                viewModel.showMessage("No app found to open ${entry.book.outputName}")
                            }
                        },
                        onCopy = {
                            val message = try {
                                clipboard.setText(AnnotatedString(viewModel.plainText()))
                                "Copied the translated pages"
                            } catch (e: RuntimeException) {
                                "Too large for the clipboard; use Save instead"
                            }
                            viewModel.showMessage(message)
                        },
                        onDelete = { viewModel.delete(entry.book) },
                    )
                }
            }

            val selected = state.selected
            if (selected != null) {
                val unit = selected.book.unitName.replaceFirstChar { it.uppercase() }
                val live = livePage
                if (state.isRunning(selected.book) && live != null) {
                    item(key = "live") { LiveCard(state.live.label, live, unit) }
                }
                if (pages.isNotEmpty()) {
                    item(key = "preview-title") {
                        Text("Preview: ${selected.book.title}", style = MaterialTheme.typography.titleMedium)
                    }
                }
                // Newest page first while translating, so progress is visible without scrolling.
                val ordered = if (state.isRunning(selected.book)) pages.asReversed() else pages
                items(ordered, key = { "page-${it.bookId}-${it.pageNumber}" }) { page ->
                    PageView(page, unit)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    status: ModelStatus,
    canChange: Boolean,
    onImport: () -> Unit,
    onRescan: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Qwen 2.5 1.5B · on-device", style = MaterialTheme.typography.titleSmall)
            }
            when (status) {
                is ModelStatus.Preparing -> {
                    Text(status.message, style = MaterialTheme.typography.bodyMedium)
                    val progress = status.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is ModelStatus.Ready -> Text(
                    "Ready on the ${status.backend} · temperature 0.2 · offline",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ModelStatus.Missing -> Text(
                    "No model yet. Tap Download Qwen (1.6 GB, opens your browser, one time only), " +
                        "then Import model and pick the downloaded .task file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ModelStatus.Failed -> Text(
                    status.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status !is ModelStatus.Ready) {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download Qwen")
                    }
                }
                TextButton(onClick = onImport, enabled = canChange) { Text("Import model") }
                if (status is ModelStatus.Missing || status is ModelStatus.Failed) {
                    TextButton(onClick = onRescan, enabled = canChange) { Text("Rescan") }
                }
            }
        }
    }
}

@Composable
private fun NewBookCard(
    enabled: Boolean,
    importing: Boolean,
    onPick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Translate a book into Hinglish", style = MaterialTheme.typography.titleSmall)
            Button(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (importing) "Opening…" else "Select PDF / EPUB")
            }
            Text(
                "Runs in the background, page by page. You can lock the phone; every page is saved " +
                    "as soon as it is done and translation resumes after a crash or restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookCard(
    entry: BookWithProgress,
    selected: Boolean,
    running: Boolean,
    liveLabel: String?,
    onSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val book = entry.book
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onSelect), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${book.title}.${book.format.extension}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val unit = book.unitName
            val status = liveLabel ?: when (book.status) {
                BookStatus.QUEUED -> "Waiting to start"
                BookStatus.READING -> "Reading ${unit}s…"
                BookStatus.TRANSLATING -> "Interrupted: will resume at the next untranslated $unit"
                BookStatus.PAUSED -> "Paused"
                BookStatus.COMPLETED -> "Done · saved to Downloads${book.outputName?.let { ": $it" } ?: ""}"
                BookStatus.FAILED -> "Stopped: ${book.error}"
            }
            Text(
                "${entry.translatedPages} of ${book.pageCount.takeIf { it > 0 } ?: "?"} ${unit}s · $status",
                style = MaterialTheme.typography.bodyMedium,
                color = if (book.status == BookStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (book.pageCount > 0) {
                LinearProgressIndicator(
                    progress = { entry.translatedPages.toFloat() / book.pageCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (selected) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        running -> OutlinedButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Pause")
                        }
                        book.status != BookStatus.COMPLETED -> FilledTonalButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Resume")
                        }
                    }
                    if (entry.translatedPages > 0) {
                        FilledTonalButton(onClick = onSave) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Save to Downloads")
                        }
                        IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy text") }
                    }
                    if (book.outputUri != null) {
                        IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open saved file") }
                    }
                    if (!running) {
                        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }
}

/** The page in progress: finished blocks with their structure, then the micro-chunk being written. */
@Composable
private fun LiveCard(label: String, live: LivePage, unit: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (live.chunkCount > 0) {
                Text(
                    "$unit ${live.page} · micro-chunk ${live.chunk} of ${live.chunkCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { (live.chunk - 1).coerceAtLeast(0).toFloat() / live.chunkCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            var previous: DocBlock? = null
            for ((block, text) in live.blocks) {
                BlockView(block, text, previous)
                previous = block
            }
            if (live.streaming.isNotEmpty()) {
                Text(
                    "${live.streaming}▍",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One translated page, rendered with its original structure. */
@Composable
private fun PageView(page: PageEntity, unit: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$unit ${page.pageNumber}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                var previous: DocBlock? = null
                page.sourceBlocks.forEachIndexed { i, block ->
                    val text = page.translations?.getOrNull(i) ?: block.text
                    if (text.isNotBlank()) {
                        BlockView(block, text, previous)
                        previous = block
                    }
                }
                if (page.sourceBlocks.isEmpty()) {
                    Text("(No text on this page of the original)", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}

@Composable
private fun BlockView(block: DocBlock, text: String, previous: DocBlock?) {
    val type = MaterialTheme.typography
    val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
    val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
    val top = when {
        previous == null -> 0.dp
        block.kind == BlockKind.HEADING -> 10.dp
        isList && prevIsList -> 0.dp
        else -> 4.dp
    }
    Box(Modifier.padding(top = top)) {
        when (block.kind) {
            BlockKind.HEADING -> Text(
                text,
                style = when (block.level) {
                    1 -> type.headlineSmall
                    2 -> type.titleLarge
                    3 -> type.titleMedium
                    else -> type.titleSmall
                },
                fontWeight = FontWeight.Bold,
            )
            BlockKind.BULLET, BlockKind.NUMBERED -> Row(Modifier.padding(start = (block.level * 20).dp)) {
                Text(
                    if (block.kind == BlockKind.BULLET) bulletFor(block.level) else block.marker,
                    style = type.bodyLarge,
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
                )
                Text(text, style = type.bodyLarge)
            }
            BlockKind.QUOTE -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(10.dp))
                Text(text, style = type.bodyLarge, fontStyle = FontStyle.Italic)
            }
            BlockKind.CODE -> Text(text, style = type.bodyMedium, fontFamily = FontFamily.Monospace)
            BlockKind.PARAGRAPH -> Text(text, style = type.bodyLarge)
        }
    }
}

