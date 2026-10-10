package com.example.hinglishpdf.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.llm.Language

/**
 * "From [Auto-Detect ▾] ⇄ To [Hindi ▾]": two pill buttons, each opening the
 * list of languages. Applies to books added from now on; each book keeps the
 * pair it was added with.
 */
@Composable
fun LanguageCard(
    source: Language,
    target: Language,
    onSource: (Language) -> Unit,
    onTarget: (Language) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier) {
        SectionLabel("Languages")
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LanguagePill("From", source, Language.sources, onSource, Modifier.weight(1f))
            // The swap button turns half a circle each time it is used.
            var turns by rememberSaveable { mutableFloatStateOf(0f) }
            val rotation by animateFloatAsState(turns * 180f, label = "swap")
            IconButton(
                onClick = { turns += 1f; onSwap() },
                enabled = source != Language.AUTO_DETECT,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap languages", modifier = Modifier.rotate(rotation))
            }
            LanguagePill("To", target, Language.targets, onTarget, Modifier.weight(1f))
        }
        Spacer(Modifier.size(10.dp))
        if (source == target) {
            Text(
                "From and To are the same language. Pick a different one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "Natural, modern ${target.englishName}, the way people speak it today. " +
                    "Books keep the languages they were added with.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A pill showing "From" / "To" over the language; tapping it lists every language. */
@Composable
private fun LanguagePill(
    label: String,
    selected: Language,
    options: List<Language>,
    onSelect: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selected.shortName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Text(
                            language.label,
                            fontWeight = if (language == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(language)
                    },
                )
            }
        }
    }
}
