package com.example.hinglishpdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import com.example.hinglishpdf.ui.theme.brand
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import android.view.ViewTreeObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.example.hinglishpdf.data.ai.ApiKeyDetector
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
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

/** The key sheet's title. */
const val PROVIDER_SHEET_TITLE = "AI provider & key"

/**
 * "AI provider & key": the provider dropdown (✓ for providers with a key),
 * "Get API Key" (the provider's own key page in the in-app browser; a key
 * copied there is saved or pasted into the field when it closes), the key
 * with Paste and show / hide, and, folded away under "Advanced", the optional
 * model name.
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
    onAutoSaveKey: (AIProvider, String) -> Unit,
    onRemoveKey: (AIProvider) -> Unit,
    onSetModel: (AIProvider, String) -> Unit,
    browserOpen: Boolean,
    onBrowserOpenChange: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ProviderSettingsContent(
            settings, activeModel, busy, onSelect, onSaveKey, onRemoveKey, onSetModel,
            browserOpen, onBrowserOpenChange, onAutoSaveKey,
        )
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
    browserOpen: Boolean,
    onBrowserOpenChange: (Boolean) -> Unit,
    onAutoSaveKey: (AIProvider, String) -> Unit = onSaveKey,
) {
    val provider = settings.provider
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val browserShown by rememberUpdatedState(browserOpen)
    var key by rememberSaveable(provider) { mutableStateOf("") }
    var pasted by rememberSaveable(provider) { mutableStateOf(false) }

    if (browserOpen) {
        ApiKeyBrowser(
            provider,
            onKeyCaptured = { owner, captured ->
                onBrowserOpenChange(false)
                onAutoSaveKey(owner, captured)
            },
            onClose = { copied ->
                onBrowserOpenChange(false)
                if (copied != null && !settings.configured) {
                    key = copied
                    pasted = true
                }
            },
        )
    }

    // Back from another app (e.g. the browser fallback) with a key on the clipboard: save it.
    val view = LocalView.current
    DisposableEffect(view, settings) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && !browserShown) {
                ApiKeyDetector.detect(clipboardText(context), provider)?.let { (owner, found) ->
                    if (settings.key(owner) != found) {
                        clearClipboard(context)
                        onAutoSaveKey(owner, found)
                    }
                }
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(PROVIDER_SHEET_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 1. Provider picker; ✓ marks providers that already have a key.
        var expanded by rememberSaveable { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded && !busy, onExpandedChange = { if (!busy) expanded = it }) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = !busy,
                label = { Text("AI provider") },
                leadingIcon = if (settings.configured) {
                    { Icon(Icons.Filled.CheckCircle, contentDescription = "Key saved", tint = MaterialTheme.brand.success) }
                } else {
                    null
                },
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
                    val hasKey = settings.key(option).isNotBlank()
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        trailingIcon = {
                            if (hasKey) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Key saved", tint = MaterialTheme.brand.success, modifier = Modifier.size(18.dp))
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

        if (settings.configured) {
            // 2a. Ready: what answers, and the key's origin.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.brand.success)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ready · ${activeModel ?: "model picked automatically"}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Needs an internet connection. ${provider.dataNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (settings.keySource(provider)) {
                ProviderSettings.KeySource.ENTERED_IN_APP -> OutlinedButton(
                    onClick = { onRemoveKey(provider) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove saved API key") }
                ProviderSettings.KeySource.BUILD_CONFIG -> Text(
                    "Key from local.properties (built into this app).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderSettings.KeySource.NONE -> Unit
            }
        } else {
            // 2b. Get the key (the provider's own page, in the in-app browser), then paste it.
            OutlinedButton(onClick = { onBrowserOpenChange(true) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Get API Key")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            var visible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it.trim()
                    pasted = false
                },
                label = { Text("${provider.displayName} API key") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                supportingText = {
                    Text(if (pasted) "Pasted the key you copied. Check it, then tap Save key." else "Stored only on this phone.")
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "Hide key" else "Show key",
                            )
                        }
                        IconButton(onClick = { clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { key = it } }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 3. Advanced (folded away): the optional model name.
        var advanced by rememberSaveable { mutableStateOf(false) }
        val arrow by animateFloatAsState(if (advanced) 180f else 0f, label = "advanced")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { advanced = !advanced }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Advanced", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (advanced) "Hide advanced settings" else "Show advanced settings",
                modifier = Modifier.rotate(arrow),
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

        if (!settings.configured) {
            Button(
                onClick = { onSaveKey(provider, key) },
                enabled = key.length >= 20,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save key", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
