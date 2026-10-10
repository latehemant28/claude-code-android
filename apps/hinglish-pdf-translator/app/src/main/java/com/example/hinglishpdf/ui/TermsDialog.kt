package com.example.hinglishpdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.settings.Terms
import java.text.DateFormat
import java.util.Date

/**
 * The Terms of Use and disclaimer. Before the first translation it must be
 * accepted (tick the box, then "I Agree"); "Not now" dismisses it and no
 * translation starts. From the menu it shows when it was accepted.
 */
@Composable
fun TermsDialog(
    acceptedAt: Long?,
    onAgree: () -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Gavel, contentDescription = null) },
        title = { Text(Terms.TITLE) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(Terms.DISCLAIMER, style = MaterialTheme.typography.bodyMedium)
                if (acceptedAt != null) {
                    Text(
                        "You accepted these terms on ${DateFormat.getDateTimeInstance().format(Date(acceptedAt))}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { checked = !checked },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(Terms.AGREEMENT, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (acceptedAt == null) {
                Button(onClick = onAgree, enabled = checked) { Text("I Agree") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (acceptedAt == null) {
            { TextButton(onClick = onDismiss) { Text("Not now") } }
        } else {
            null
        },
    )
}
