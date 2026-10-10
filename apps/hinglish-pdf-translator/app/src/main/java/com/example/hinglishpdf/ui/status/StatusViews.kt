package com.example.hinglishpdf.ui.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The icon that stands for a stage's state: the same everywhere, so it is learned once. */
@Composable
fun StageIcon(state: StageState, size: Dp = 22.dp) {
    when (state) {
        StageState.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size))
        StageState.CURRENT -> CircularProgressIndicator(Modifier.size(size).padding(2.dp), strokeWidth = 2.5.dp)
        StageState.ATTENTION -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(size))
        StageState.WAITING -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(size))
        StageState.UNAVAILABLE -> Icon(Icons.Filled.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(size))
    }
}

private fun StageState.spoken(): String = when (this) {
    StageState.DONE -> "done"
    StageState.CURRENT -> "in progress"
    StageState.ATTENTION -> "needs attention"
    StageState.WAITING -> "not started"
    StageState.UNAVAILABLE -> "not available yet"
}

/**
 * The full map of a book's stages (Uploaded > Parsed > Translated > Checked
 * > Rebuilt > Delivered), the highlighted one on a tinted background.
 */
@Composable
fun StageMap(view: ProjectStatusView, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        view.stages.forEachIndexed { i, stage ->
            val highlighted = stage == view.current
            val background = when {
                highlighted && stage.state == StageState.ATTENTION -> MaterialTheme.colorScheme.errorContainer
                highlighted -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Step ${i + 1}, ${stage.stage.title}: ${stage.state.spoken()}. ${stage.detail}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageIcon(stage.state)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stage.stage.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                        color = if (stage.state == StageState.UNAVAILABLE) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The same map in one line of small icons, for a book card. */
@Composable
fun MiniStageMap(view: ProjectStatusView, modifier: Modifier = Modifier) {
    val description = view.stages.joinToString("; ") { "${it.stage.title} ${it.state.spoken()}" }
    Row(modifier.semantics { contentDescription = "Stages: $description" }, verticalAlignment = Alignment.CenterVertically) {
        view.stages.forEachIndexed { i, stage ->
            if (i > 0) {
                Box(
                    Modifier.width(10.dp).height(2.dp).background(
                        if (stage.state == StageState.DONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
            StageIcon(stage.state, size = 16.dp)
        }
    }
}

/**
 * An error as guidance: what happened, what can be done, buttons to do it,
 * and the original message folded away under "Details".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuidanceCard(guide: Guidance, keptPages: Int, unitName: String, onAction: (GuideAction) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(guide.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(guide.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (guide.keepsProgress && keptPages > 0) {
                Text(
                    "Your $keptPages translated ${unitName}s are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(guide.primary) }) { Text(guide.primary.label) }
                guide.secondary?.let { secondary -> OutlinedButton(onClick = { onAction(secondary) }) { Text(secondary.label) } }
            }
            if (guide.detail.isNotBlank()) {
                var open by rememberSaveable { mutableStateOf(false) }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                AnimatedVisibility(open) {
                    Text(guide.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/**
 * Progress of the step in hand: a bar (exact when the total is known), what
 * is happening, and an honest time estimate.
 */
@Composable
fun ProgressPanel(label: String, done: Int, total: Int, timeLeft: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (total > 0) {
            LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (timeLeft != null) {
                Spacer(Modifier.width(8.dp))
                Text(timeLeft, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
