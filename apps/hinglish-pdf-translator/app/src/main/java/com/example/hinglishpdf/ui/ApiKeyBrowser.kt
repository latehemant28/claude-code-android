package com.example.hinglishpdf.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hinglishpdf.data.ai.AIProvider

/**
 * The selected provider's official API-key dashboard, in a full-screen
 * in-app browser: the user signs in, creates and copies a key, and closes
 * the browser; [onClose] then receives the copied key (read from the
 * clipboard) so it can be pasted into the app without leaving it.
 *
 * Sign-in cookies are kept, so the next visit is usually already signed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyBrowser(provider: AIProvider, onClose: (copiedKey: String?) -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(provider.keyPageUrl) }
    var menu by remember { mutableStateOf(false) }

    // The moment the site's Copy button puts a key on the clipboard, offer to use it.
    var copied by remember { mutableStateOf<String?>(null) }
    DisposableEffect(context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { copied = copiedKey(context) }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }

    fun close() {
        CookieManager.getInstance().flush()
        onClose(copied ?: copiedKey(context))
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // Back goes back inside the website first, then closes the browser.
        BackHandler { webView?.takeIf { it.canGoBack() }?.goBack() ?: close() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Column {
                            Text("${provider.displayName} API key", style = MaterialTheme.typography.titleMedium)
                            Text(
                                Uri.parse(url).host.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = ::close) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                // Last resort only: Google refuses some sign-ins inside apps.
                                DropdownMenuItem(
                                    text = { Text("Sign-in blocked? Open in browser") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    },
                                )
                            }
                        }
                    },
                )
                if (progress in 0..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "Sign in, create a key and tap Copy: the key is pasted into the app for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        createWebView(
                            ctx,
                            onProgress = { progress = it },
                            onUrl = { url = it },
                        ).also {
                            it.loadUrl(provider.keyPageUrl)
                            webView = it
                        }
                    },
                    onRelease = { it.destroy() },
                )
                // "API key copied ✓  [Use this key]": closes the browser with the key in the field.
                AnimatedVisibility(visible = copied != null) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("API key copied ✓", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Button(onClick = ::close) { Text("Use this key") }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled") // the dashboards are JavaScript apps
private fun createWebView(context: Context, onProgress: (Int) -> Unit, onUrl: (String) -> Unit): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportMultipleWindows(false) // "open in new tab" links stay in this view
        // Only web pages: no access to the phone's files or content providers.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        // Sign-in flows hop between domains (e.g. a provider and its login service).
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                if (target.scheme == "https" || target.scheme == "http") return false
                // mailto:, intent:, market:... belong to other apps.
                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onUrl(url)

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onUrl(url)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
        }
    }

/** The clipboard's text, if it looks like an API key (one long token, no spaces). */
private fun copiedKey(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }
        .getOrNull()?.trim() ?: return null
    return text.takeIf { it.length in 20..300 && it.none(Char::isWhitespace) && !it.startsWith("http") }
}
