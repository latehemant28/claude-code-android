package com.example.hinglishpdf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.bulletFor

/**
 * Recommended model: Llama 3.2 3B Instruct in LiteRT-LM format (~2.1 GB, 4k
 * context). Opened in the browser, so the app itself never needs internet.
 */
private const val LLAMA_DOWNLOAD_URL =
    "https://huggingface.co/mlboydaisuke/Llama-3.2-3B-Instruct-LiteRT/resolve/main/model.litertlm?download=true"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    // Storage Access Framework pickers: no storage permission required.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::translate)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocFormat.PDF.mimeType)) { uri ->
        uri?.let(viewModel::export)
    }
    val saveEpub = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DocFormat.EPUB.mimeType)) { uri ->
        uri?.let(viewModel::export)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // Translating a long document takes a while; don't let the screen sleep meanwhile.
    val view = LocalView.current
    DisposableEffect(state.isBusy) {
        view.keepScreenOn = state.isBusy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("PDF / EPUB → Hinglish") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModelCard(
                status = state.model,
                canChangeModel = state.canChangeModel,
                onImport = { modelPicker.launch(arrayOf("*/*")) },
                onRetry = viewModel::retryModelSearch,
                onDownload = {
                    try {
                        uriHandler.openUri(LLAMA_DOWNLOAD_URL)
                    } catch (e: Exception) {
                        viewModel.showMessage("No browser found to download the model")
                    }
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // Some file managers label EPUBs as generic binaries.
                        documentPicker.launch(
                            arrayOf(DocFormat.PDF.mimeType, DocFormat.EPUB.mimeType, "application/octet-stream"),
                        )
                    },
                    enabled = state.canSelectDocument,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Select PDF / EPUB")
                }
                if (state.isBusy) {
                    OutlinedButton(onClick = viewModel::cancelTranslation) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
            }

            ProgressSection(state)

            OutputCard(state = state, modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canUseOutput = state.hasOutput && !state.isBusy
                FilledTonalButton(
                    onClick = {
                        val message = try {
                            clipboard.setText(AnnotatedString(viewModel.plainText()))
                            "Copied to clipboard"
                        } catch (e: RuntimeException) {
                            // Binder transactions cap out around 1 MB.
                            "Too large for the clipboard; use Save instead"
                        }
                        viewModel.showMessage(message)
                    },
                    enabled = canUseOutput,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy to Clipboard")
                }
                FilledTonalButton(
                    onClick = {
                        val doc = state.document ?: return@FilledTonalButton
                        val name = "${doc.title} (Hinglish).${doc.format.extension}"
                        if (doc.format == DocFormat.EPUB) saveEpub.launch(name) else savePdf.launch(name)
                    },
                    enabled = canUseOutput,
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.document?.format == DocFormat.EPUB) "Save EPUB" else "Save PDF")
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    status: ModelStatus,
    canChangeModel: Boolean,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("On-device model", style = MaterialTheme.typography.titleSmall)
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
                    "${status.fileName} · ${status.backend}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                ModelStatus.Missing -> Text(
                    "No model yet. Recommended: Llama 3.2 3B Instruct (2.1 GB, best Hindi). " +
                        "Tap Download Llama (opens your browser, one time only), then Import model " +
                        "and pick the downloaded model.litertlm.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is ModelStatus.Failed -> Text(
                    status.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status !is ModelStatus.Ready) {
                    TextButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download Llama")
                    }
                }
                TextButton(onClick = onImport, enabled = canChangeModel) { Text("Import model") }
                if (status is ModelStatus.Missing || status is ModelStatus.Failed) {
                    TextButton(onClick = onRetry, enabled = canChangeModel) { Text("Rescan") }
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(state: TranslatorUiState) {
    if (state.phase == Phase.Idle) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.document?.let {
            Text(
                "${it.title}.${it.format.extension}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(state.progressLabel, style = MaterialTheme.typography.bodyMedium)
        if (state.isBusy) {
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The translated document, rendered with its original structure. */
@Composable
private fun OutputCard(state: TranslatorUiState, modifier: Modifier = Modifier) {
    val blocks = state.document?.blocks.orEmpty()
    val shown = remember(state.translations) {
        state.translations.withIndex().filter { it.value != null }.map { it.index }
    }
    val live = remember(state.liveText) { state.liveText.lines().joinToString("\n") { it.replace(TAG, "") }.trim() }
    val listState = rememberLazyListState()

    // Follow the newest text unless the user has scrolled up to read.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.lastScrolledBackward }
            .collect { (canScrollForward, scrolledBack) ->
                if (!canScrollForward) follow = true else if (scrolledBack) follow = false
            }
    }
    LaunchedEffect(shown.size, live) {
        if (state.isBusy && follow && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
            listState.scrollBy(100_000f) // clamps to the end of the last item
        }
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        if (shown.isEmpty() && live.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your Hinglish translation appears here with the original headings, " +
                            "bullet points and numbering.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(shown, key = { _, index -> index }) { position, index ->
                        val previous = shown.getOrNull(position - 1)?.let(blocks::get)
                        BlockView(blocks[index], state.translations[index].orEmpty(), previous)
                    }
                    if (live.isNotEmpty()) {
                        item(key = "live") {
                            Text(
                                "$live▍",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
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
        block.kind == BlockKind.HEADING -> 14.dp
        isList && prevIsList -> 0.dp
        else -> 6.dp
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
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(Modifier.width(10.dp))
                Text(text, style = type.bodyLarge, fontStyle = FontStyle.Italic)
            }

            BlockKind.CODE -> Text(text, style = type.bodyMedium, fontFamily = FontFamily.Monospace)

            BlockKind.PARAGRAPH -> Text(text, style = type.bodyLarge)
        }
    }
}

private val TAG = Regex("^\\s*\\[\\d+]\\s?")
