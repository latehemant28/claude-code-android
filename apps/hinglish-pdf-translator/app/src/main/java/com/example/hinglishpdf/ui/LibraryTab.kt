package com.example.hinglishpdf.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.db.BookWithProgress
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.document.bulletFor
import com.example.hinglishpdf.ui.reader.LocalReaderStyle

/**
 * The Library tab: every book as a card with its progress and quick actions
 * (Read, Pause / Resume; save, copy and delete in its menu); the selected book's translated
 * pages follow, with the page being written streaming in live.
 */
@Composable
fun LibraryTab(
    state: TranslatorUiState,
    pages: List<PageEntity>,
    livePage: LivePage?,
    actions: BookActions,
    onTranslateBook: () -> Unit,
) {
    if (state.books.isEmpty()) {
        EmptyLibrary(onTranslateBook)
        return
    }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    state.books.firstOrNull { it.book.id == deleting }?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${entry.book.title}”?") },
            text = { Text("Its translation and the app's copy of the book are removed. Files you exported to Downloads stay.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; actions.onDelete(entry.book) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            val done = state.books.count { it.book.status == BookStatus.COMPLETED }
            Text(
                "📚 ${state.books.size}   ✅ $done",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.books, key = { "book-${it.book.id}" }) { entry ->
            BookCard(
                entry = entry,
                selected = entry.book.id == state.selected?.book?.id,
                running = state.isRunning(entry.book),
                liveLabel = state.live.label.takeIf { state.isRunning(entry.book) },
                exportFormat = state.outputFormat,
                opening = state.openingBookId == entry.book.id,
                actions = actions,
                onDelete = { deleting = entry.book.id },
            )
        }

        val selected = state.selected
        if (selected != null) {
            val unit = selected.book.unitName.replaceFirstChar { it.uppercase() }
            val live = livePage
            if (state.isRunning(selected.book) && live != null) {
                item(key = "live") { LiveCard(live, unit) }
            }
            if (pages.isNotEmpty()) {
                item(key = "preview-title") {
                    Text(
                        "Preview: ${selected.book.title}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // Newest page first while translating, so progress is visible without scrolling.
            val ordered = if (state.isRunning(selected.book)) pages.asReversed() else pages
            items(ordered, key = { "page-${it.bookId}-${it.pageNumber}" }) { page -> PageView(page, unit) }
        }
    }
}

@Composable
private fun EmptyLibrary(onTranslateBook: () -> Unit) {
    val guide = LocalGuide.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GradientIcon(Icons.Filled.AutoStories, size = 96.dp)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Say.noBooks.text(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SpeakButton({ guide.sayNow(Say.noBooksHelp) })
        }
        Spacer(Modifier.height(20.dp))
        BigTile(
            icon = Icons.Filled.UploadFile,
            title = Say.chooseBook.text(),
            subtitle = "PDF / EPUB",
            background = GoGreen,
            onClick = onTranslateBook,
            onSpeak = { guide.sayNow(Say.chooseBookHelp) },
        )
    }
}

/**
 * One book: a ring that fills as it is translated, the title, and big
 * buttons — Listen, Read, Pause / Resume. Save to Downloads, copy and delete
 * are in the ⋮ menu.
 */
@Composable
private fun BookCard(
    entry: BookWithProgress,
    selected: Boolean,
    running: Boolean,
    liveLabel: String?,
    exportFormat: DocFormat,
    opening: Boolean,
    actions: BookActions,
    onDelete: () -> Unit,
) {
    val book = entry.book
    var menu by remember { mutableStateOf(false) }
    SectionCard(
        onClick = { actions.onSelect(book.id) },
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(entry)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${bookStatus(entry, running)} · ${entry.translatedPages}/${book.pageCount.takeIf { it > 0 } ?: "?"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (book.status == BookStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${book.format.name} · ${book.fromLanguage.englishName} → ${book.toLanguage.nativeName ?: book.toLanguage.englishName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (entry.translatedPages > 0) {
                        DropdownMenuItem(
                            text = { Text("Save ${exportFormat.name} to Downloads") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                            onClick = { menu = false; actions.onExport(book) },
                        )
                    }
                    // Copies the pages shown below, so only for the selected book.
                    if (selected && entry.translatedPages > 0) {
                        DropdownMenuItem(
                            text = { Text("Copy text") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menu = false; actions.onCopy() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = if (running) Color.Unspecified else MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        enabled = !running,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        if (book.status == BookStatus.FAILED || liveLabel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                liveLabel ?: "${book.error}",
                style = MaterialTheme.typography.bodySmall,
                color = if (liveLabel == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        BookButtons(entry, running, opening, actions)
    }
}

/** The page in progress: finished blocks with their structure, then the chunk being written. */
@Composable
fun LiveCard(live: LivePage, unit: String, compact: Boolean = false) {
    SectionCard(color = MaterialTheme.colorScheme.surfaceContainer) {
        Text(
            if (live.chunkCount > 1) "$unit ${live.page} · part ${live.chunk} of ${live.chunkCount}" else "$unit ${live.page}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (live.chunkCount > 1) {
            Spacer(Modifier.height(6.dp))
            GradientProgress((live.chunk - 1).coerceAtLeast(0).toFloat() / live.chunkCount)
        }
        Spacer(Modifier.height(6.dp))
        val blocks = if (compact) live.blocks.takeLast(2) else live.blocks
        var previous: DocBlock? = null
        for ((block, text) in blocks) {
            BlockView(block, text, previous)
            previous = block
        }
        if (live.streaming.isNotEmpty()) {
            val reader = LocalReaderStyle.current
            Text(
                "${if (compact) live.streaming.takeLast(240) else live.streaming}▍",
                fontFamily = reader.font.family,
                fontSize = reader.textSizeSp.sp,
                lineHeight = (reader.textSizeSp * 1.6f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One translated page, rendered with its original structure. */
@Composable
private fun PageView(page: PageEntity, unit: String) {
    SectionCard(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$unit ${page.pageNumber}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                var previous: DocBlock? = null
                page.sourceBlocks.forEachIndexed { i, block ->
                    val text = page.translations?.getOrNull(i) ?: block.text
                    if (text.isNotBlank()) {
                        BlockView(block, text, previous)
                        previous = block
                    }
                }
                if (page.sourceBlocks.isEmpty()) {
                    Text(
                        "(No text on this page of the original)",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockView(block: DocBlock, text: String, previous: DocBlock?) {
    // Translated text uses the reader's chosen font and size; headings scale with it.
    val reader = LocalReaderStyle.current
    val body = TextStyle(
        fontFamily = reader.font.family,
        fontSize = reader.textSizeSp.sp,
        lineHeight = (reader.textSizeSp * 1.6f).sp, // Devanagari needs room for matras
        color = MaterialTheme.colorScheme.onSurface,
    )
    val isList = block.kind == BlockKind.BULLET || block.kind == BlockKind.NUMBERED
    val prevIsList = previous?.kind == BlockKind.BULLET || previous?.kind == BlockKind.NUMBERED
    val top = when {
        previous == null -> 0.dp
        block.lineBreak -> 0.dp
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
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
                Spacer(Modifier.width(10.dp))
                Text(text, style = body, fontStyle = FontStyle.Italic)
            }
            BlockKind.CODE -> Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            BlockKind.PARAGRAPH -> Text(text, style = body)
        }
    }
}

