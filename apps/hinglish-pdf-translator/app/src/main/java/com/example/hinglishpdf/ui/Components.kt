package com.example.hinglishpdf.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.ui.theme.brand

/** The app's card: rounded, softly raised, with a hairline edge that keeps it crisp in dark mode. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    val body: @Composable () -> Unit = { Column(Modifier.padding(18.dp), content = content) }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, shadowElevation = 2.dp, border = border) {
            body()
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, shadowElevation = 2.dp, border = border) {
            body()
        }
    }
}

/** A small caps label above a group of settings or cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** The main action: a full-width button filled with the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(MaterialTheme.brand.accent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A pill-shaped segmented toggle ("PDF | EPUB"): the selected option sits on
 * a gradient pill that slides across when the choice changes.
 */
@Composable
fun <T> PillToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.height(44.dp)) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val segment = maxWidth / options.size
            val offset by animateDpAsState(segment * index, spring(dampingRatio = 0.8f, stiffness = 500f), label = "pill")
            Box(
                Modifier
                    .offset { IntOffset(offset.roundToPx(), 0) } // moves in the layout phase only
                    .width(segment)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.brand.accent),
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                options.forEachIndexed { i, option ->
                    val chosen = i == index
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(option) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(option),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (chosen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A status light: green when ready, amber when something is needed. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

/** A rounded progress bar filled with the brand gradient; the fill glides to each new value. */
@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (shown > 0f) {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(MaterialTheme.brand.accent))
        }
    }
}

/** A round icon tile with the brand gradient: the provider "logo", empty-state art... */
@Composable
fun GradientIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(MaterialTheme.brand.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

/** Vertical rhythm between stacked items inside a card. */
val CardSpacing = Arrangement.spacedBy(10.dp)

/** The green of "go": the main action, as the spoken help calls it ("the green button"). */
val GoGreen = Brush.linearGradient(listOf(Color(0xFF047857), Color(0xFF10B981)))

/**
 * A big picture button for people who may not read: a large icon, one or
 * two words, and a 🔊 button that says what it does ([onSpeak]).
 */
@Composable
fun BigTile(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    background: Brush,
    onClick: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    minHeight: Dp = 112.dp,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        onClick = onClick,
        shape = shape,
        shadowElevation = 3.dp,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.background(background, shape).heightIn(min = minHeight).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = contentColor, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Text(subtitle, color = contentColor.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            SpeakButton(onSpeak, tint = contentColor)
        }
    }
}

/** 🔊: says what the thing next to it is for. */
@Composable
fun SpeakButton(onSpeak: () -> Unit, tint: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    IconButton(onClick = onSpeak, modifier = modifier.size(52.dp)) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Listen to this", tint = tint, modifier = Modifier.size(30.dp))
    }
}
