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
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextAlign
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
 * The My Books tab: every book as a card — progress ring and status on the
 * left, title, pages and quick actions (Pause / Resume, Read, Listen) in the
 * middle, and Save to Downloads, Copy text and Delete in its ⋮ menu. The
 * selected book's translated pages follow, with the page being written
 * streaming in live.
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.books, key = { "book-${it.book.id}" }) { entry ->
            BookCard(
                entry = entry,
                selected = entry.book.id == state.selected?.book?.id,
                running = state.isRunning(entry.book),
                liveLabel = state.live.label.takeIf { state.isRunning(entry.book) && it.isNotBlank() },
                exportFormat = state.outputFormat,
                opening = state.openingBookId == entry.book.id,
                actions = actions,
                onDelete = { deleting = entry.book.id },
                modifier = Modifier.animateItem(),
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
    LaunchedEffect(Unit) { guide.say(Say.noBooksHelp) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(Say.noBooks.text(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            Say.noBooksNote.text(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        ExtendedFloatingActionButton(onClick = onTranslateBook, containerColor = GoColor, contentColor = Color.White) {
            Icon(Icons.Filled.UploadFile, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(Say.chooseBook.text(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * One book. Left: progress ring and status tag. Middle: title, pages and the
 * quick actions. Top right: ⋮ with Save to Downloads, Copy text and Delete.
 * Tapping the card shows its pages below the list.
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
    modifier: Modifier = Modifier,
) {
    val book = entry.book
    var menu by remember { mutableStateOf(false) }
    val statusColor = bookStatusColor(entry, running)
    Card(
        onClick = { actions.onSelect(book.id) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box {
            Row(
                Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgressRing(entry, color = statusColor)
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = statusColor.copy(alpha = 0.16f)) {
                        Text(
                            bookStatus(entry, running),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(end = 32.dp)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${Say.pagesDone(entry.translatedPages, pagesTotal(entry)).text()} · ${book.toLanguage.nativeName ?: book.toLanguage.englishName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (liveLabel != null || (book.status == BookStatus.FAILED && book.error != null)) {
                        Text(
                            liveLabel ?: "${book.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (liveLabel == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    BookIconButtons(entry, running, opening, actions)
                }
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (entry.translatedPages > 0) {
                        DropdownMenuItem(
                            text = { Text("Save ${exportFormat.name} to Downloads") },
                            leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                            onClick = { menu = false; actions.onExport(book) },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy text") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menu = false; actions.onCopy(book) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = if (running) Color.Unspecified else MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = if (running) LocalContentColor.current else MaterialTheme.colorScheme.error,
                            )
                        },
                        enabled = !running,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
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

