package com.example.hinglishpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BlockKind
import com.example.hinglishpdf.data.document.DocBlock
import com.example.hinglishpdf.data.document.bulletFor
import com.example.hinglishpdf.ui.reader.LocalReaderStyle

/*
 * Translated text as the reader sees it: the page being written right now,
 * and finished pages with their original structure.
 */

/** The page in progress: finished blocks with their structure, then the chunk being written. */
@Composable
internal fun LiveCard(label: String, live: LivePage, unit: String) {
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
internal fun PageView(page: PageEntity, unit: String) {
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

