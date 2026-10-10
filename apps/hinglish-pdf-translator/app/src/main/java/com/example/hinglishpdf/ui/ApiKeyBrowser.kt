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
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieClipSpec
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.hinglishpdf.R
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.data.ai.ApiKeyDetector

/** The 15 s guide for each provider (tools/key_guide_lottie.py). */
@RawRes
fun guideAnimation(provider: AIProvider): Int = when (provider) {
    AIProvider.GEMINI -> R.raw.key_guide_gemini
    AIProvider.OPENAI -> R.raw.key_guide_openai
    AIProvider.ANTHROPIC -> R.raw.key_guide_anthropic
    AIProvider.GROQ -> R.raw.key_guide_groq
}

/** The part of the guide looped as the hint: tap "Create key", then Copy (3 s to 11.5 s of 15 s). */
private val HINT_CLIP = LottieClipSpec.Progress(3f / 15f, 11.5f / 15f)

/**
 * The provider's official API-key dashboard in a full-screen in-app browser,
 * with no reading required:
 *
 *  - a small looping animation shows which button to tap;
 *  - the floating ▶ plays a silent 15 s video guide in the top half while the
 *    website stays usable in the bottom half;
 *  - the moment a key is copied (the page's Copy button, or any copy), it is
 *    recognised by its shape ([ApiKeyDetector]) and handed to [onKeyCaptured]
 *    at once; the clipboard is then cleared so the key doesn't linger there.
 *
 * The page's Copy buttons are watched by a small script that runs only on the
 * providers' key pages (an origin-restricted WebMessageListener), plus the
 * system clipboard. Sign-in cookies are kept for next time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyBrowser(
    provider: AIProvider,
    onKeyCaptured: (AIProvider, String) -> Unit,
    onClose: (copiedText: String?) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(provider.keyPageUrl) }
    var menu by remember { mutableStateOf(false) }
    var hint by rememberSaveable { mutableStateOf(true) }
    var video by rememberSaveable { mutableStateOf(false) }
    // Copied text that looks like a key but has an unknown shape: offered, not saved.
    var unknownKey by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf(false) }
    val capture by rememberUpdatedState { text: String? ->
        if (!captured) {
            val found = ApiKeyDetector.detect(text, provider)
            if (found != null) {
                captured = true
                clearClipboard(context)
                CookieManager.getInstance().flush()
                onKeyCaptured(found.first, found.second)
            } else {
                looksLikeKey(text)?.let { unknownKey = it }
            }
        }
    }

    DisposableEffect(context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { capture(clipboardText(context)) }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }

    fun close() {
        CookieManager.getInstance().flush()
        onClose(unknownKey)
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

                if (video) {
                    // Watch and do: the guide on top, the website below.
                    VideoGuide(provider, Modifier.weight(1f).fillMaxWidth()) { video = false }
                } else if (hint) {
                    HintStrip(provider) { hint = false }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            createWebView(
                                ctx,
                                onProgress = { progress = it },
                                onUrl = { url = it },
                                onCopied = { capture(it) },
                            ).also {
                                it.loadUrl(provider.keyPageUrl)
                                webView = it
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                    if (!video) {
                        FloatingActionButton(
                            onClick = { video = true },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Watch video tutorial", modifier = Modifier.size(32.dp))
                        }
                    }
                }

                // A copied key of an unknown shape: one tap to use it anyway.
                AnimatedVisibility(visible = unknownKey != null) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🔑", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Button(onClick = ::close) { Text("Use this key") }
                        }
                    }
                }
            }
        }
    }
}

/** The small looping hint: a hand taps the provider's "create key" button, then Copy. */
@Composable
private fun HintStrip(provider: AIProvider, onHide: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(guideAnimation(provider)))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever, clipSpec = HINT_CLIP)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF4F1FA))
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier
                .height(150.dp)
                .aspectRatio(400f / 260f)
                .semantics { contentDescription = "Tap the create key button, then tap Copy" },
        )
        IconButton(onClick = onHide, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Filled.Close, contentDescription = "Hide hint", tint = Color(0xFF5F5A6E))
        }
    }
}

/** The silent 15-second video guide, played once (replayable), in the top half of the screen. */
@Composable
fun VideoGuide(provider: AIProvider, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(guideAnimation(provider)))
    val player = rememberLottieAnimatable()
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(composition, round) {
        composition?.let { player.animate(it, iterations = 1) }
    }
    Box(modifier.background(Color(0xFF1C0B4B))) {
        LottieAnimation(
            composition = composition,
            progress = { player.progress },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp, vertical = 28.dp)
                .fillMaxWidth()
                .aspectRatio(400f / 260f)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = "Video guide: sign in, create a key, tap Copy" },
        )
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Filled.Close, contentDescription = "Close video", tint = Color.White)
        }
        if (player.progress >= 1f) {
            // In the corner, so the final "saved" check stays visible.
            IconButton(onClick = { round++ }, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 20.dp).size(56.dp)) {
                Icon(Icons.Filled.Replay, contentDescription = "Replay", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        LinearProgressIndicator(
            progress = { player.progress },
            color = Color(0xFF7FD4FF),
            trackColor = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        )
    }
}

/**
 * Runs at document start on the providers' key pages only: reports the text
 * of every copy (the page's Copy button calling the Clipboard API, or a
 * manual copy) to the app through the origin-restricted `keyCatcher` channel.
 */
private const val COPY_WATCHER = """
(function () {
  if (window.__keyCatcher || typeof keyCatcher === 'undefined') return;
  window.__keyCatcher = true;
  function send(t) { try { if (t) keyCatcher.postMessage(String(t).slice(0, 4000)); } catch (e) {} }
  try {
    var c = navigator.clipboard;
    if (c && c.writeText) {
      var writeText = c.writeText.bind(c);
      c.writeText = function (t) { send(t); return writeText(t); };
    }
    if (c && c.write) {
      var write = c.write.bind(c);
      c.write = function (items) {
        try {
          for (var i = 0; i < items.length; i++) {
            if (items[i].types.indexOf('text/plain') >= 0) {
              items[i].getType('text/plain').then(function (b) { return b.text(); }).then(send);
            }
          }
        } catch (e) {}
        return write(items);
      };
    }
  } catch (e) {}
  window.addEventListener('copy', function (e) {
    try { send((e.clipboardData && e.clipboardData.getData('text/plain')) || String(document.getSelection())); } catch (err) {}
  });
})();
"""

@SuppressLint("SetJavaScriptEnabled") // the dashboards are JavaScript apps
private fun createWebView(
    context: Context,
    onProgress: (Int) -> Unit,
    onUrl: (String) -> Unit,
    onCopied: (String) -> Unit,
): WebView = WebView(context).apply {
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

    // Copy detection on the key pages only; the clipboard listener covers older WebViews.
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addWebMessageListener(this, "keyCatcher", ApiKeyDetector.KEY_PAGE_ORIGINS) { _, message, _, _, _ ->
                    message.data?.let(onCopied)
                }
                WebViewCompat.addDocumentStartJavaScript(this, COPY_WATCHER, ApiKeyDetector.KEY_PAGE_ORIGINS)
            }
        }
    }

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

/** The clipboard's text, if any. */
fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    return runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
}

/** Removes a captured key from the clipboard, so it doesn't linger there. */
fun clearClipboard(context: Context) {
    runCatching { context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
}

/** Text that looks like a key of a shape [ApiKeyDetector] doesn't know (one long token). */
private fun looksLikeKey(text: String?): String? =
    text?.trim()?.takeIf { it.length in 20..300 && it.none(Char::isWhitespace) && !it.startsWith("http") }
