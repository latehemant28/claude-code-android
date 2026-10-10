package com.example.hinglishpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.settings.ProviderSettings

/**
 * The compact banner on the main screen: "🤖 Using: Groq", or, with no key
 * saved, a highlighted "⚠️ API Key Required - Tap to Configure". Tapping it
 * opens [ProviderSettingsSheet].
 */
@Composable
fun ApiStatusBanner(settings: ProviderSettings.State, activeModel: String?, onClick: () -> Unit) {
    val configured = settings.configured
    val container = if (configured) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    val content = if (configured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (configured) "🤖 Using: ${settings.provider.displayName}" else "⚠️ API Key Required - Tap to Configure",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (configured && activeModel != null) {
                    Text(activeModel.substringAfter(" · "), style = MaterialTheme.typography.bodySmall)
                } else if (!configured) {
                    Text("Choose an AI provider and add its key", style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

/**
 * "AI provider & API key": the provider dropdown, "Get API Key" (the
 * provider's own key page in the in-app browser; a key copied there is
 * pasted into the field when it closes), the key itself, and, folded away
 * under "Advanced Settings", the optional model name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsSheet(
    settings: ProviderSettings.State,
    activeModel: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (AIProvider) -> Unit,
    onSaveKey: (AIProvider, String) -> Unit,
    onRemoveKey: (AIProvider) -> Unit,
    onSetModel: (AIProvider, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ProviderSettingsContent(settings, activeModel, busy, onSelect, onSaveKey, onRemoveKey, onSetModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsContent(
    settings: ProviderSettings.State,
    activeModel: String?,
    busy: Boolean,
    onSelect: (AIProvider) -> Unit,
    onSaveKey: (AIProvider, String) -> Unit,
    onRemoveKey: (AIProvider) -> Unit,
    onSetModel: (AIProvider, String) -> Unit,
) {
    val provider = settings.provider
    val clipboard = LocalClipboardManager.current
    var browserOpen by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable(provider) { mutableStateOf("") }
    var pasted by rememberSaveable(provider) { mutableStateOf(false) }

    if (browserOpen) {
        ApiKeyBrowser(provider) { copied ->
            browserOpen = false
            if (copied != null && !settings.configured) {
                key = copied
                pasted = true
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI provider & API key", style = MaterialTheme.typography.titleLarge)

        // 1. Provider dropdown, above the key field.
        var expanded by rememberSaveable { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded && !busy, onExpandedChange = { if (!busy) expanded = it }) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = !busy,
                label = { Text("AI provider") },
                supportingText = if (busy) {
                    { Text("Pause the translation to change the provider") }
                } else {
                    null
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !busy).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded && !busy, onDismissRequest = { expanded = false }) {
                AIProvider.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.displayName)
                                Text(
                                    if (settings.key(option).isNotBlank()) "Key saved" else "No key yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }

        // 2. The key.
        if (settings.configured) {
            Text("Ready · ${activeModel ?: "model picked automatically"}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Needs an internet connection. ${provider.dataNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (settings.keySource(provider)) {
                ProviderSettings.KeySource.ENTERED_IN_APP ->
                    TextButton(onClick = { onRemoveKey(provider) }, enabled = !busy) { Text("Remove saved API key") }
                ProviderSettings.KeySource.BUILD_CONFIG -> Text(
                    "Key from local.properties (built into this app).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderSettings.KeySource.NONE -> Unit
            }
        } else {
            Text(
                "Paste your ${provider.displayName} API key. It is kept only on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = { browserOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Key, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Get API Key")
            }
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it.trim()
                    pasted = false
                },
                label = { Text("${provider.displayName} API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (pasted) {
                    { Text("Pasted the key you copied. Check it, then tap Save key.") }
                } else {
                    null
                },
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSaveKey(provider, key) }, enabled = key.length >= 20) { Text("Save key") }
        }

        // 3. Advanced Settings (folded away): the optional model name.
        var advanced by rememberSaveable { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { advanced = !advanced }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced Settings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (advanced) "Hide advanced settings" else "Show advanced settings",
            )
        }
        AnimatedVisibility(visible = advanced) {
            var model by rememberSaveable(provider, settings.customModel(provider)) {
                mutableStateOf(settings.customModel(provider))
            }
            val changed = model.trim() != settings.customModel(provider)
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model (optional)") },
                placeholder = { Text("Automatic: ${provider.defaultModels.first()}") },
                supportingText = { Text("Leave empty to try ${provider.defaultModels.joinToString()} in turn.") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    if (changed) {
                        IconButton(onClick = { onSetModel(provider, model) }) {
                            Icon(Icons.Filled.Check, contentDescription = "Use this model")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
