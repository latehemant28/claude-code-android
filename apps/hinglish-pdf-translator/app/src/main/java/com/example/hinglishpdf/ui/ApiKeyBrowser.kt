package com.example.hinglishpdf.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.view.ViewTreeObserver
import android.os.Message
import android.view.WindowManager
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApiKeyBrowser(
    provider: AIProvider,
    onKeyCaptured: (AIProvider, String) -> Unit,
    onClose: (copiedText: String?) -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var url by rememberSaveable { mutableStateOf(provider.keyPageUrl) }
    // The page itself (history, current address) survives rotation and process death:
    // back from the email app, the user is on the same OTP page, not the home page.
    val browserState = rememberSaveable(saver = BrowserState.Saver) { BrowserState() }
    var hint by rememberSaveable { mutableStateOf(true) }
    var video by rememberSaveable { mutableStateOf(false) }
    // Copied text that looks like a key but has an unknown shape: offered, not saved.
    var unknownKey by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf(false) }
    // A sign-in popup ("Continue with Google" and the like), shown over the page.
    var popup by remember { mutableStateOf<WebView?>(null) }
    val capture by rememberUpdatedState { text: String?, offerUnknown: Boolean ->
        if (!captured) {
            val found = ApiKeyDetector.detect(text, provider)
            if (found != null) {
                captured = true
                clearClipboard(context)
                CookieManager.getInstance().flush()
                onKeyCaptured(found.first, found.second)
            } else if (offerUnknown) {
                looksLikeKey(text)?.let { unknownKey = it }
            }
        }
    }

    // Leaving for the email app or the external browser: write cookies (the sign-in) to disk now.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) CookieManager.getInstance().flush()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { capture(clipboardText(context), true) }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose { clipboard?.removePrimaryClipChangedListener(listener) }
    }

    fun close() {
        CookieManager.getInstance().flush()
        onClose(unknownKey)
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false, // the content handles the keyboard and system bars itself
        ),
    ) {
        // The window shrinks above the keyboard instead of hiding the page's text fields under it.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        val keyboardOpen = WindowInsets.isImeVisible

        // Back from Chrome (or anywhere) with a key copied there: Android doesn't tell a
        // background app about copies, so look at the clipboard when the browser regains focus.
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (focused) capture(clipboardText(context), false)
            }
            dialogView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { dialogView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }

        // Back closes a popup, then goes back inside the website, then closes the browser.
        BackHandler {
            val open = popup
            when {
                open != null -> if (open.canGoBack()) open.goBack() else popup = null
                webView?.canGoBack() == true -> webView?.goBack()
                else -> close()
            }
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                TopAppBar(
                    windowInsets = WindowInsets(0),
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
                        // The video guide, right at the top: visible at once, never over the website.
                        FilledTonalIconButton(onClick = { video = !video }) {
                            Icon(
                                if (video) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (video) "Stop video tutorial" else "Watch video tutorial",
                            )
                        }
                        // For password managers, email codes or a refused sign-in: finish in the
                        // phone's own browser, copy the key there, come back; it is picked up.
                        IconButton(onClick = {
                            CookieManager.getInstance().flush()
                            openInExternalBrowser(context, url)
                        }) {
                            Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open in Chrome / External Browser")
                        }
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    },
                )
                if (progress in 0..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }

                // While typing (Groq's sign-in form...), the header steps aside for the keyboard.
                if (video && !keyboardOpen) {
                    // Watch and do: the guide on top, the website below.
                    VideoGuide(provider, Modifier.weight(1f).fillMaxWidth()) { video = false }
                } else if (hint && !keyboardOpen) {
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
                                onCopied = { capture(it, true) },
                                onPopup = { popup = it },
                                onPopupClosed = { popup = null },
                            ).also {
                                // Where the user left off (after rotation or process death), else the key page.
                                browserState.restoreInto(it, home = provider.keyPageUrl)
                                webView = it
                            }
                        },
                        onRelease = {
                            browserState.detach(it)
                            it.destroy()
                        },
                    )
                    popup?.let { window ->
                        // The sign-in popup, over the page until it closes itself (or ✕).
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { popup = null }) { Icon(Icons.Filled.Close, contentDescription = "Close sign-in window") }
                                Text("🔐", style = MaterialTheme.typography.titleMedium)
                            }
                            AndroidView(
                                factory = { window },
                                modifier = Modifier.fillMaxSize(),
                                onRelease = { it.destroy() },
                            )
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

/**
 * The WebView's user agent without the marks that say "embedded WebView"
 * ("; wv" and "Version/4.0"). Some sign-in pages (Google's among them) show a
 * blank or refused page to embedded WebViews; this makes the page treat the
 * in-app browser like Chrome on the same phone.
 */
fun browserUserAgent(webViewUserAgent: String): String = webViewUserAgent
    .replace("; wv)", ")")
    .replace(Regex("\\s?Version/\\d+(\\.\\d+)*"), "")
    .replace(Regex("\\s{2,}"), " ")
    .trim()

@SuppressLint("SetJavaScriptEnabled") // the dashboards are JavaScript apps
private fun createWebView(
    context: Context,
    onProgress: (Int) -> Unit,
    onUrl: (String) -> Unit,
    onCopied: (String) -> Unit,
    onPopup: (WebView) -> Unit,
    onPopupClosed: () -> Unit,
): WebView = WebView(context).apply {
    configure(this)
    // Sign-in popups (window.open: "Continue with Google"...) open as a second WebView over the page.
    settings.setSupportMultipleWindows(true)
    settings.javaScriptCanOpenWindowsAutomatically = true

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

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val window = WebView(view.context).also(::configure)
            window.webViewClient = WebViewClient()
            window.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(closing: WebView) = onPopupClosed()
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = window
            resultMsg.sendToTarget()
            onPopup(window)
            return true
        }

        override fun onCloseWindow(window: WebView) = onPopupClosed()
    }
}

/** Settings shared by the page and its sign-in popups. */
@SuppressLint("SetJavaScriptEnabled")
private fun configure(view: WebView) = with(view.settings) {
    javaScriptEnabled = true
    domStorageEnabled = true
    @Suppress("DEPRECATION")
    databaseEnabled = true
    loadWithOverviewMode = true
    useWideViewPort = true
    userAgentString = browserUserAgent(userAgentString)
    // Only web pages: no access to the phone's files or content providers.
    allowFileAccess = false
    allowContentAccess = false
    CookieManager.getInstance().setAcceptCookie(true)
    // Sign-in flows hop between domains (e.g. a provider and its login service).
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
}

/**
 * The in-app browser's page, saved with the screen's state (rotation, and
 * process death while the user is away reading an email code): the WebView's
 * own history and page via [WebView.saveState], plus the current address on
 * its own in case the history is too large to keep or cannot be restored.
 * Cookies, and so the sign-in, are on disk already (flushed when the app
 * goes to the background). A sign-in popup is not kept: its page belonged to
 * the window that opened it.
 */
internal class BrowserState(private var restored: Bundle? = null) {
    private var webView: WebView? = null

    fun save(): Bundle {
        val view = webView ?: return restored ?: Bundle()
        CookieManager.getInstance().flush()
        return Bundle().apply {
            view.url?.let { putString(KEY_URL, it) }
            val history = Bundle()
            if (view.saveState(history) != null && sizeOf(history) <= MAX_HISTORY_BYTES) putBundle(KEY_HISTORY, history)
        }
    }

    /** Shows the saved page in [view]: its history if possible, else the saved address, else [home]. */
    fun restoreInto(view: WebView, home: String) {
        webView = view
        val saved = restored
        restored = null
        val history = saved?.getBundle(KEY_HISTORY)
        if (history != null && view.restoreState(history) != null) return
        view.loadUrl(saved?.getString(KEY_URL) ?: home)
    }

    /** The view is going away: keep what it showed in case the screen is saved after this. */
    fun detach(view: WebView) {
        if (webView === view) {
            restored = save()
            webView = null
        }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_HISTORY = "history"

        /** Saved screen state has to stay small (the whole app shares about 1 MB). */
        private const val MAX_HISTORY_BYTES = 200_000

        val Saver: Saver<BrowserState, Bundle> = Saver(save = { it.save() }, restore = { BrowserState(it) })

        private fun sizeOf(bundle: Bundle): Int {
            val parcel = Parcel.obtain()
            return try {
                parcel.writeBundle(bundle)
                parcel.dataSize()
            } finally {
                parcel.recycle()
            }
        }
    }
}

/** Opens [url] in the phone's default browser (Chrome, usually). */
fun openInExternalBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
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
