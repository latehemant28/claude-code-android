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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.ui.reader.LocalReaderStyle
import com.example.hinglishpdf.ui.reader.ReaderSettingsSheet
import com.example.hinglishpdf.ui.status.ErrorGuide
import com.example.hinglishpdf.ui.status.GuideAction
import com.example.hinglishpdf.ui.status.MiniStageMap
import com.example.hinglishpdf.ui.status.ProgressPanel
import kotlinx.coroutines.launch

/**
 * The main screen, laid out as the job's steps: 1 connect an AI, 2 choose a
 * book, 3 choose the file you get; then your books, each with its stage map
 * and its next action in view. A book opens as a project screen.
 */
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
    var openBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Any file can be picked: the app recognises PDFs and EPUBs by their content,
    // whatever their name or the type a file manager gives them.
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addBook)
    }
    fun pickBook() = bookPicker.launch(arrayOf("*/*"))

    // Android 13+: ask once so "Translating page 45 of 300..." can be shown.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Messages, some with an action (Undo). When one goes away unanswered, its timeout runs.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        var acted = false
        try {
            val result = snackbar.showSnackbar(
                message.text,
                actionLabel = message.action,
                withDismissAction = message.action != null,
                duration = if (message.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            acted = result == SnackbarResult.ActionPerformed
        } finally {
            if (acted) message.onAction?.invoke() else message.onTimeout?.invoke()
            viewModel.messageShown(message)
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

    /** Back to step 1 (the AI provider card), with a word on what to do there. */
    fun goToProvider(hint: String) {
        openBookId = null
        scope.launch { listState.animateScrollToItem(0) }
        viewModel.showMessage(hint)
    }

    fun startNewBook() {
        if (!state.configured) {
            goToProvider("First connect an AI in step 1: choose a provider and paste its key.")
        } else {
            withTerms { pickBook() }
        }
    }

    fun openFile(entry: BookWithProgress) {
        val book = entry.book
        val uri = book.outputUri ?: return
        val format = DocFormat.ofFileName(book.outputName) ?: book.format
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), format.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: Exception) {
            viewModel.showMessage("No app on this phone opens ${format.name} files. It is in Downloads: ${book.outputName}")
        }
    }

    fun onGuide(action: GuideAction, entry: BookWithProgress?) {
        when (action) {
            GuideAction.RESUME -> entry?.let { withTerms { viewModel.resume(it.book) } }
            GuideAction.FIX_KEY -> goToProvider("Paste the key again in step 1 (or remove it and get a new one), then tap Resume on the book.")
            GuideAction.CHANGE_PROVIDER -> goToProvider("Choose another AI in step 1 and add its key, then tap Resume on the book.")
            GuideAction.SET_MODEL -> goToProvider("Type a current model name in the Model field of step 1, then tap Resume on the book.")
            GuideAction.ADD_CREDIT -> {
                val page = state.provider.keyPageUrl
                if (page != null) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }
                        .onFailure { viewModel.showMessage("Open $page in a browser to add credit.") }
                } else {
                    viewModel.showMessage("Add credit on your AI service's website, then tap Resume.")
                }
            }
            GuideAction.ACCEPT_TERMS -> {
                afterTerms = entry?.let { e -> { viewModel.resume(e.book) } }
                showTerms = true
            }
            GuideAction.CHOOSE_OTHER_FILE -> {
                openBookId = null
                startNewBook()
            }
            GuideAction.SAVE_AGAIN -> entry?.let { viewModel.saveToDownloads(it.book) }
        }
    }

    fun actionsFor(entry: BookWithProgress) = ProjectActions(
        onPause = viewModel::pause,
        onResume = { withTerms { viewModel.resume(entry.book) } },
        onSave = { viewModel.saveToDownloads(entry.book) },
        onOpenFile = { openFile(entry) },
        onCopy = {
            val message = try {
                clipboard.setText(AnnotatedString(viewModel.plainText()))
                "Copied the translated ${entry.book.unitName}s"
            } catch (e: RuntimeException) {
                "Too long to copy at once. Use Save to Downloads instead."
            }
            viewModel.showMessage(message)
        },
        onDelete = {
            openBookId = null
            viewModel.requestDelete(entry.book)
        },
        onGuide = { onGuide(it, entry) },
    )

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

    state.importProblem?.let { guide ->
        AlertDialog(
            onDismissRequest = viewModel::importProblemShown,
            title = { Text(guide.title) },
            text = { Text(guide.explanation) },
            confirmButton = {
                Button(onClick = { viewModel.importProblemShown(); onGuide(guide.primary, null) }) { Text(guide.primary.label) }
            },
            dismissButton = { TextButton(onClick = viewModel::importProblemShown) { Text("Close") } },
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

    val openEntry = state.books.firstOrNull { it.book.id == openBookId }
    LaunchedEffect(openEntry?.book?.id) { openEntry?.let { viewModel.select(it.book.id) } }

    CompositionLocalProvider(LocalReaderStyle provides readerStyle) {
        if (openEntry != null) {
            ProjectScreen(
                entry = openEntry,
                state = state,
                pages = pages,
                livePage = livePage,
                snackbar = snackbar,
                actions = actionsFor(openEntry),
                onClose = { openBookId = null },
            )
            return@CompositionLocalProvider
        }
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Book → Hindi") },
                    actions = {
                        // "Aa": font style and text size for the translated text.
                        IconButton(onClick = { showTextSettings = true }) {
                            Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
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
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "step-ai") {
                    Step(1, "Connect an AI", done = state.configured) {
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
                }
                item(key = "step-book") {
                    Step(2, "Choose a book", done = state.books.isNotEmpty()) {
                        NewBookCard(
                            configured = state.configured,
                            importing = state.importing,
                            onPick = ::startNewBook,
                            onShowTerms = { afterTerms = null; showTerms = true },
                        )
                    }
                }
                item(key = "step-format") {
                    Step(3, "Choose the file you get", done = false, showDone = false) {
                        OutputFormatSelector(state.outputFormat, onSelect = viewModel::setOutputFormat)
                    }
                }

                if (state.books.isNotEmpty()) {
                    item(key = "library-title") {
                        Text("Your books", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(state.books, key = { "book-${it.book.id}" }) { entry ->
                        BookCard(entry, state, actionsFor(entry), onOpen = { openBookId = entry.book.id })
                    }
                }
            }
        }
    }
}

/** A numbered step of the job, with a tick once it is done: the order to do things in, visible. */
@Composable
private fun Step(number: Int, title: String, done: Boolean, showDone: Boolean = true, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).background(
                    if (done && showDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (done && showDone) {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                } else {
                    Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        content()
    }
}

@Composable
private fun NewBookCard(configured: Boolean, importing: Boolean, onPick: () -> Unit, onShowTerms: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Never greyed out without a reason: without an AI it explains what to do first.
            Button(onClick = onPick, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                if (importing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Opening the book…")
                } else {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Select PDF / EPUB")
                }
            }
            Text(
                if (configured) {
                    "Runs in the background, page by page. You can lock the phone; every page is saved " +
                        "as soon as it is done, and translation picks up where it stopped."
                } else {
                    "Connect an AI in step 1 first."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
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

/** "PDF | EPUB": what Save to Downloads writes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputFormatSelector(format: DocFormat, onSelect: (DocFormat) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = listOf(DocFormat.PDF, DocFormat.EPUB)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
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
            Text(
                if (format == DocFormat.EPUB) "An e-book that reflows on any screen." else "Fixed A4 pages, ready to print or share.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One book: where it is (headline and a compact stage map), its progress
 * and time left while running, and its next action as a visible button.
 * Tapping the card opens the project screen with everything else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookCard(entry: BookWithProgress, state: TranslatorUiState, actions: ProjectActions, onOpen: () -> Unit) {
    val book = entry.book
    val status = state.status(entry)
    val running = state.isRunning(book)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = if (status.needsAttention) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MiniStageMap(status)
            Text(
                status.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (running) {
                val live = state.live
                ProgressPanel(live.label, if (live.total > 0) live.done else 0, live.total, state.timeLeft(book))
            } else if (book.pageCount > 0 && entry.translatedPages in 1 until book.pageCount) {
                ProgressPanel("${entry.translatedPages} of ${book.pageCount} ${book.unitName}s translated", entry.translatedPages, book.pageCount, null)
            }
            if (status.needsAttention) {
                val guide = ErrorGuide.of(if (book.id in state.needsOcr) "This PDF is scanned" else book.error)
                Text(guide.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    status.needsAttention -> {
                        val guide = ErrorGuide.of(if (book.id in state.needsOcr) "This PDF is scanned" else book.error)
                        Button(onClick = { actions.onGuide(guide.primary) }) { Text(guide.primary.label) }
                    }
                    state.pausingBookId == book.id -> OutlinedButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Pausing…")
                    }
                    running -> OutlinedButton(onClick = actions.onPause) { Label(Icons.Filled.Pause, "Pause") }
                    book.status == BookStatus.COMPLETED && book.outputUri != null ->
                        Button(onClick = actions.onOpenFile) { Label(Icons.AutoMirrored.Filled.OpenInNew, "Open file") }
                    book.status != BookStatus.COMPLETED -> Button(onClick = actions.onResume) { Label(Icons.Filled.PlayArrow, "Resume") }
                }
                TextButton(onClick = onOpen) { Text("Details") }
            }
        }
    }
}
