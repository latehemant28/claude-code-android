package com.example.hinglishpdf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(viewModel: TranslatorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Storage Access Framework pickers: no storage permission required.
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::translatePdf)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }

    // Translating a long PDF takes minutes; don't let the screen sleep meanwhile.
    val view = LocalView.current
    DisposableEffect(state.isBusy) {
        view.keepScreenOn = state.isBusy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("PDF → Hinglish") }) },
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
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    enabled = state.canSelectPdf,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Select PDF")
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

            OutputCard(
                chunks = state.translatedChunks,
                isStreaming = state.phase == Phase.Translating,
                modifier = Modifier.weight(1f),
            )

            FilledTonalButton(
                onClick = {
                    val message = try {
                        clipboard.setText(AnnotatedString(state.fullText))
                        "Copied to clipboard"
                    } catch (e: RuntimeException) {
                        // Binder transactions cap out around 1 MB.
                        "Text is too large for the clipboard"
                    }
                    scope.launch { snackbar.showSnackbar(message) }
                },
                enabled = state.fullText.isNotEmpty() && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy to Clipboard")
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
                    if (status.progress != null) {
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                    "No model found. Import a MediaPipe .task/.bin file (e.g. Gemma 3 1B " +
                        "or Llama 3.2 3B), or adb push one to " +
                        "Android/data/com.example.hinglishpdf/files/models/ and tap Rescan.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is ModelStatus.Failed -> Text(
                    status.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        state.pdfName?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun OutputCard(chunks: List<String>, isStreaming: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Follow the newest text unless the user has scrolled up to read.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.lastScrolledBackward }
            .collect { (canScrollForward, scrolledBack) ->
                if (!canScrollForward) follow = true else if (scrolledBack) follow = false
            }
    }
    LaunchedEffect(chunks.size, chunks.lastOrNull()) {
        if (isStreaming && follow && chunks.isNotEmpty()) {
            listState.scrollToItem(chunks.lastIndex)
            listState.scrollBy(100_000f) // clamps to the end of the last item
        }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(),
    ) {
        if (chunks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Hinglish translation will appear here, chunk by chunk.",
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(chunks) { index, text ->
                        val streamingThis = isStreaming && index == chunks.lastIndex
                        Text(
                            text = if (streamingThis) "$text▍" else text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
