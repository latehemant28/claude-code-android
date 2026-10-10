package com.example.hinglishpdf.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.BuildConfig
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.ui.theme.brand

/** The engine card's wording. */
const val ENGINE_TITLE = "Choose Your AI Engine"
const val ENGINE_TEXT = "Our app is like a luxury car, but YOU choose the engine! Translation quality depends on the " +
    "AI provider you select. Connect your own API key to pay only the actual AI cost, with zero app markup."

/**
 * The Settings tab: "Choose Your AI Engine" (each provider with its key
 * status), the output format and reading style, help and legal.
 */
@Composable
fun SettingsTab(
    state: TranslatorUiState,
    onEngine: (AIProvider) -> Unit,
    onOutputFormat: (DocFormat) -> Unit,
    onTextStyle: () -> Unit,
    onShowTutorial: () -> Unit,
    onShowTerms: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "engine") { EngineCard(state, onEngine) }

        item(key = "translation") {
            SectionCard {
                SectionLabel("Translation")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Output format", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    PillToggle(
                        options = listOf(DocFormat.PDF, DocFormat.EPUB),
                        selected = state.outputFormat,
                        label = { it.name },
                        onSelect = onOutputFormat,
                        modifier = Modifier.width(168.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                SettingsRow(Icons.Filled.TextFields, "Reading text style", "Font and size of the translated text", onTextStyle)
            }
        }

        item(key = "help") {
            SectionCard {
                SectionLabel("Help & legal")
                Spacer(Modifier.height(4.dp))
                SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, "Show the tutorial", "A quick tour of the main screen", onShowTutorial)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(Icons.Filled.Gavel, "Terms of Use & Disclaimer", "For personal use with documents you have the rights to", onShowTerms)
            }
        }

        item(key = "about") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "BYOK Translator ${BuildConfig.VERSION_NAME} · Your keys stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineCard(state: TranslatorUiState, onEngine: (AIProvider) -> Unit) {
    SectionCard {
        EngineChipArt(Modifier.fillMaxWidth().height(132.dp))
        Spacer(Modifier.height(14.dp))
        Text(ENGINE_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(ENGINE_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        // Gemini first: the most generous free tier.
        val providers = listOf(AIProvider.GEMINI, AIProvider.GROQ, AIProvider.OPENAI, AIProvider.ANTHROPIC)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            providers.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { provider ->
                        EngineTile(
                            provider = provider,
                            inUse = provider == state.provider,
                            hasKey = state.providers.key(provider).isNotBlank(),
                            onClick = { onEngine(provider) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineTile(provider: AIProvider, inUse: Boolean, hasKey: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val brand = MaterialTheme.brand
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (inUse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (inUse) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.heightIn(min = 84.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                provider.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (inUse) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (hasKey) brand.success else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        inUse && hasKey -> "In use"
                        hasKey -> "Key saved"
                        else -> "Add key"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (inUse) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The engine illustration: a glowing AI chip with its pins, drawn in Compose
 * (no bitmaps, no animation files) so it renders the same on every phone.
 */
@Composable
fun EngineChipArt(modifier: Modifier = Modifier) {
    val glow by rememberInfiniteTransition(label = "chip").animateFloat(
        0.35f, 0.75f, infiniteRepeatable(tween(1_600), RepeatMode.Reverse), label = "glow",
    )
    val brand = MaterialTheme.brand
    val pin = Color.White.copy(alpha = 0.85f)
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(brand.heroWash)
            .semantics { contentDescription = "AI engine chip" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(120.dp)) {
            val chip = size.minDimension * 0.56f
            val origin = Offset((size.width - chip) / 2, (size.height - chip) / 2)
            // The glow behind the chip.
            drawCircle(
                Brush.radialGradient(listOf(brand.accentStart.copy(alpha = glow), Color.Transparent)),
                radius = size.minDimension / 2,
            )
            // Pins on all four sides.
            val pins = 4
            val step = chip / (pins + 1)
            val length = size.minDimension * 0.11f
            val stroke = 3.dp.toPx()
            for (i in 1..pins) {
                val d = step * i
                drawLine(pin, Offset(origin.x + d, origin.y), Offset(origin.x + d, origin.y - length), stroke, StrokeCap.Round)
                drawLine(pin, Offset(origin.x + d, origin.y + chip), Offset(origin.x + d, origin.y + chip + length), stroke, StrokeCap.Round)
                drawLine(pin, Offset(origin.x, origin.y + d), Offset(origin.x - length, origin.y + d), stroke, StrokeCap.Round)
                drawLine(pin, Offset(origin.x + chip, origin.y + d), Offset(origin.x + chip + length, origin.y + d), stroke, StrokeCap.Round)
            }
            // The chip body and its inner core.
            drawRoundRect(brand.accent, topLeft = origin, size = Size(chip, chip), cornerRadius = CornerRadius(chip * 0.18f))
            val inset = chip * 0.2f
            drawRoundRect(
                Color.White.copy(alpha = 0.18f),
                topLeft = Offset(origin.x + inset, origin.y + inset),
                size = Size(chip - 2 * inset, chip - 2 * inset),
                cornerRadius = CornerRadius(chip * 0.1f),
            )
        }
        Text("AI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
