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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.ui.reader.LocalReaderStyle
import com.example.hinglishpdf.ui.reader.ReaderSettingsSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.example.hinglishpdf.data.ai.AIProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    // Both collected on the main thread; the heavy work behind them runs on Dispatchers.Default.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val livePage by viewModel.livePage.collectAsStateWithLifecycle()
    val readerStyle by viewModel.readerStyle.collectAsStateWithLifecycle()
    var showTextSettings by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTerms by rememberSaveable { mutableStateOf(false) }
    /** Runs once the Terms are accepted (the translation the user was starting). */
    var afterTerms by remember { mutableStateOf<(() -> Unit)?>(null) }
    var browserFor by rememberSaveable { mutableStateOf<AIProvider?>(null) }
    var pastedKey by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Storage Access Framework pickers: no storage permission required.
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addBook)
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

    /** No translation starts before the Terms of Use are accepted. */
    fun withTerms(action: () -> Unit) {
        if (state.termsAccepted) {
            action()
        } else {
            afterTerms = action
            showTerms = true
        }
    }

    if (showTerms) {
        TermsDialog(
            acceptedAt = state.termsAcceptedAt,
            onAgree = {
                viewModel.acceptTerms()
                showTerms = false
                afterTerms?.invoke()
                afterTerms = null
            },
            onDismiss = {
                showTerms = false
                afterTerms = null
            },
        )
    }

    browserFor?.let { provider ->
        ApiKeyBrowser(provider) { copied ->
            browserFor = null
            if (copied != null && !state.providers.configured) {
                pastedKey = copied
                viewModel.showMessage("Pasted the key you copied. Check it, then tap Save key.")
            }
        }
    }

    if (showTextSettings) {
        ReaderSettingsSheet(
            style = readerStyle,
            onFont = viewModel::setReaderFont,
            onTextSize = viewModel::setReaderTextSize,
            onDismiss = { showTextSettings = false },
        )
    }

    CompositionLocalProvider(LocalReaderStyle provides readerStyle) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Book → Hindi") },
                    actions = {
                        // "Aa": font style and text size for the translated text.
                        IconButton(onClick = { showTextSettings = true }) {
                            Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        // Settings menu.
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Settings")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Reading text style") },
                                    onClick = { showMenu = false; showTextSettings = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Terms of Use & Disclaimer") },
                                    onClick = { showMenu = false; afterTerms = null; showTerms = true },
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "format") {
                    OutputFormatSelector(state.outputFormat, onSelect = viewModel::setOutputFormat)
                }

                item(key = "provider") {
                    ProviderCard(
                        settings = state.providers,
                        activeModel = state.activeModel?.takeIf { it.startsWith(state.provider.displayName) },
                        busy = state.live.running,
                        pastedKey = pastedKey,
                        onPastedKeyShown = { pastedKey = null },
                        onSelect = viewModel::selectProvider,
                        onGetKey = { browserFor = it },
                        onSaveKey = viewModel::saveApiKey,
                        onSaveCustom = viewModel::saveCustomProvider,
                        onRemoveKey = viewModel::removeApiKey,
                        onSetModel = viewModel::setModel,
                    )
                }

                item(key = "new") {
                    NewBookCard(
                        enabled = state.canAddBook,
                        importing = state.importing,
                        onPick = {
                            withTerms {
                                // Some file managers label EPUBs as generic binaries.
                                bookPicker.launch(arrayOf(DocFormat.PDF.mimeType, DocFormat.EPUB.mimeType, "application/octet-stream"))
                            }
                        },
                        onShowTerms = { afterTerms = null; showTerms = true },
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
                            onResume = { withTerms { viewModel.resume(entry.book) } },
                            saveFormat = state.outputFormat,
                            onSave = { viewModel.saveToDownloads(entry.book) },
                            onOpen = {
                                val uri = entry.book.outputUri ?: return@BookCard
                                val format = DocFormat.ofFileName(entry.book.outputName) ?: entry.book.format
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(Uri.parse(uri), format.mimeType)
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
}

@Composable
private fun NewBookCard(
    enabled: Boolean,
    importing: Boolean,
    onPick: () -> Unit,
    onShowTerms: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Translate a book into Hindi", style = MaterialTheme.typography.titleSmall)
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
            Text(
                "For personal use, with documents you have the rights to. Terms of Use",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onShowTerms),
            )
        }
    }
}

/** "Output Format: [ PDF | EPUB ]": what Save to Downloads writes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputFormatSelector(format: DocFormat, onSelect: (DocFormat) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Output Format:", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            val options = listOf(DocFormat.PDF, DocFormat.EPUB)
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == format,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(option.name)
                    }
                }
            }
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
    saveFormat: DocFormat,
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
                            Text("Save ${saveFormat.name} to Downloads")
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

/** The page in progress: finished blocks with their structure, then the chunk being written. */
@Composable
private fun LiveCard(label: String, live: LivePage, unit: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (live.chunkCount > 1) {
                Text(
                    "$unit ${live.page} · part ${live.chunk} of ${live.chunkCount}",
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
                val reader = LocalReaderStyle.current
                Text(
                    "${live.streaming}▍",
                    fontFamily = reader.font.family,
                    fontSize = reader.textSizeSp.sp,
                    lineHeight = (reader.textSizeSp * 1.6f).sp,
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
    // Translated text uses the reader's chosen Hindi font and size; headings scale with it.
    val reader = LocalReaderStyle.current
    val body = TextStyle(
        fontFamily = reader.font.family,
        fontSize = reader.textSizeSp.sp,
        lineHeight = (reader.textSizeSp * 1.6f).sp, // Devanagari needs room for matras
    )
    val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
    val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
    val top = when {
        previous == null -> 0.dp
        block.kind == BlockKind.HEADING -> 12.dp
        isList && prevIsList -> 0.dp
        else -> 6.dp
    }
    Box(Modifier.padding(top = top)) {
        when (block.kind) {
            BlockKind.HEADING -> {
                val scale = when (block.level) { 1 -> 1.45f; 2 -> 1.3f; 3 -> 1.15f; else -> 1.05f }
                Text(
                    text,
                    style = body.copy(
                        fontSize = (reader.textSizeSp * scale).sp,
                        lineHeight = (reader.textSizeSp * scale * 1.4f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            BlockKind.BULLET, BlockKind.NUMBERED -> Row(Modifier.padding(start = (block.level * 20).dp)) {
                Text(
                    if (block.kind == BlockKind.BULLET) bulletFor(block.level) else block.marker,
                    style = body,
                    modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
                )
                Text(text, style = body)
            }
            BlockKind.QUOTE -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(10.dp))
                Text(text, style = body, fontStyle = FontStyle.Italic)
            }
            BlockKind.CODE -> Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            BlockKind.PARAGRAPH -> Text(text, style = body)
        }
    }
}

