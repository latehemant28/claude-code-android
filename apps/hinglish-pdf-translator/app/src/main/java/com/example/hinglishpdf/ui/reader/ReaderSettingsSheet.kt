package com.example.hinglishpdf.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val PREVIEW = "मेरा यकीन मानिए, उसके पास कोई और ऑप्शन नहीं था।"

/** The "Aa" sheet: pick one of three Hindi font styles and the text size, with a live preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    style: ReaderStyle,
    onFont: (ReaderFont) -> Unit,
    onTextSize: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Text settings", style = MaterialTheme.typography.titleMedium)

            Text("Font", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { font ->
                    FilterChip(
                        selected = font == style.font,
                        onClick = { onFont(font) },
                        label = { Text("अ  ${font.label}", fontFamily = font.family) },
                    )
                }
            }

            Text("Text size · ${style.textSizeSp.roundToInt()} sp", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = style.textSizeSp,
                onValueChange = { onTextSize(it.roundToInt().toFloat()) },
                valueRange = ReaderStyle.MIN_SIZE_SP..ReaderStyle.MAX_SIZE_SP,
                steps = (ReaderStyle.MAX_SIZE_SP - ReaderStyle.MIN_SIZE_SP).toInt() - 1,
            )

            Text(
                PREVIEW,
                fontFamily = style.font.family,
                fontSize = style.textSizeSp.sp,
                lineHeight = (style.textSizeSp * 1.6f).sp,
            )
        }
    }
}
