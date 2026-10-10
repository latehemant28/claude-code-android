package com.example.hinglishpdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.ai.AIProvider

/** The tick box the user must check before a file is chosen. */
fun uploadConsentText(provider: AIProvider) = "I agree to send this file's text to ${provider.displayName}"

/**
 * Asked every time a book is chosen: the file's text will be sent to the AI
 * provider to be translated. Nothing is picked until the box is ticked and
 * "Agree & choose file" is tapped.
 */
@Composable
fun UploadConsentDialog(provider: AIProvider, onAgree: () -> Unit, onDismiss: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
        title = { Text("Your file's text will be sent to AI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "To translate it, the text of each page is sent to ${provider.displayName}, " +
                        "the AI service you chose, with your own API key.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Point("Only the text is sent, page by page. Pictures stay on your phone.")
                Point(
                    "${provider.displayName}'s own privacy terms apply to it." +
                        if (provider == AIProvider.GEMINI) " On Google's free tier, it may be used to improve Google's products." else "",
                )
                Point("This app has no server of its own: we never see or keep your file.")
                Point("Don't choose private or confidential documents you would not share with an AI company.")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable(role = Role.Checkbox) { agreed = !agreed },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = agreed, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Text(uploadConsentText(provider), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = { Button(onClick = onAgree, enabled = agreed) { Text("Agree & choose file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Point(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
