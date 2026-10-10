package com.example.hinglishpdf.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.hinglishpdf.data.ai.CustomTranslator
import com.example.hinglishpdf.data.settings.ProviderSettings
import com.example.hinglishpdf.ui.status.InputCleaner

/**
 * Picks the AI provider, and holds its API key: "Get API key" opens the
 * provider's own key page in the in-app browser; a key copied there is
 * pasted into the field when the browser closes. The Custom provider takes
 * an address, a key and a model name instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderCard(
    settings: ProviderSettings.State,
    activeModel: String?,
    busy: Boolean,
    /** A key found on the clipboard after the in-app browser closed; consumed once shown. */
    pastedKey: String?,
    onPastedKeyShown: () -> Unit,
    onSelect: (AIProvider) -> Unit,
    onGetKey: (AIProvider) -> Unit,
    onSaveKey: (AIProvider, String) -> Unit,
    /** Custom provider: address, key and model, saved together. */
    onSaveCustom: (url: String, key: String, model: String) -> Unit,
    onRemoveKey: (AIProvider) -> Unit,
    onSetModel: (AIProvider, String) -> Unit,
) {
    val provider = settings.provider
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                        "${option.cost} · ${if (settings.key(option).isNotBlank()) "Key saved" else "No key yet"}",
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
                Text(
                    "Ready · ${activeModel ?: "model picked automatically"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (provider == AIProvider.CUSTOM) {
                    Text("Address: ${settings.customUrl}", style = MaterialTheme.typography.bodySmall)
                }
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
            } else if (provider == AIProvider.CUSTOM) {
                CustomProviderForm(settings, onSaveCustom)
            } else {
                var key by rememberSaveable(provider) { mutableStateOf("") }
                LaunchedEffect(pastedKey) {
                    if (pastedKey != null) {
                        key = pastedKey
                        onPastedKeyShown()
                    }
                }
                Text(
                    "Paste your ${provider.displayName} API key. It is kept only on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (provider.keyPageUrl != null) {
                    FilledTonalButton(onClick = { onGetKey(provider) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Get API Key")
                    }
                }
                // Whatever is pasted is cleaned (quotes, "Bearer ", "API_KEY=", spaces);
                // a key that still can't be right says why instead of just greying out Save.
                val cleaned = InputCleaner.apiKey(key)
                val problem = InputCleaner.apiKeyProblem(cleaned)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("${provider.displayName} API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = problem != null,
                    supportingText = {
                        Text(problem ?: "Paste the whole key; spaces, quotes and labels are removed for you.")
                    },
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { key = it } }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSaveKey(provider, cleaned) }, enabled = cleaned.isNotEmpty() && problem == null) { Text("Save key") }
            }

            // 3. Optional model name, tried before the defaults (Custom: part of its form until saved).
            if (provider != AIProvider.CUSTOM || settings.configured) {
                var model by rememberSaveable(provider, settings.customModel(provider)) {
                    mutableStateOf(settings.customModel(provider))
                }
                val changed = InputCleaner.modelName(model) != settings.customModel(provider)
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(if (provider.defaultModels.isEmpty()) "Model" else "Model (optional)") },
                    placeholder = provider.defaultModels.firstOrNull()?.let { first -> { Text("Automatic: $first") } },
                    supportingText = {
                        Text(
                            if (provider.defaultModels.isEmpty()) {
                                "The model name your service uses."
                            } else {
                                "Leave empty to try ${provider.defaultModels.joinToString()} in turn."
                            },
                        )
                    },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        if (changed) {
                            IconButton(onClick = { onSetModel(provider, InputCleaner.modelName(model)) }) {
                                Icon(Icons.Filled.Check, contentDescription = "Use this model")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Any other AI service with an OpenAI-compatible API (Together, Fireworks,
 * Qwen's Model Studio, Moonshot, a company's own server...): its address,
 * key and model name, saved together.
 */
@Composable
private fun CustomProviderForm(settings: ProviderSettings.State, onSave: (url: String, key: String, model: String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf(settings.customUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(settings.customModel(AIProvider.CUSTOM)) }
    // "api.example.com/v1" or "http://..." is fixed up to https:// rather than refused.
    val cleanedUrl = InputCleaner.serviceUrl(url)
    val urlValid = CustomTranslator.chatCompletionsUrl(cleanedUrl) != null
    val cleanedKey = InputCleaner.apiKey(key)
    Text(
        "Any AI service with an OpenAI-compatible API. Enter its address, your key and the model name.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("API address") },
        placeholder = { Text("https://example.com/v1") },
        supportingText = {
            Text(
                when {
                    url.isBlank() -> "The address before /chat/completions."
                    !urlValid -> "This doesn't look like a web address. Example: https://example.com/v1"
                    cleanedUrl != url.trim() -> "Will use $cleanedUrl"
                    else -> "The address before /chat/completions."
                },
            )
        },
        isError = url.isNotBlank() && !urlValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) {
                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSave(cleanedUrl, cleanedKey, InputCleaner.modelName(model)) },
        enabled = urlValid && cleanedKey.isNotBlank() && InputCleaner.modelName(model).isNotBlank(),
    ) { Text("Save") }
}
