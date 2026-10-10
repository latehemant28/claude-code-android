package com.example.hinglishpdf.ui.reader

import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.data.epub.EpubBook
import com.example.hinglishpdf.data.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/** A translated book opened in the in-app reader: a private copy of the PDF or EPUB. */
data class ReaderDocument(val bookId: Long, val title: String, val file: File, val format: DocFormat)

private val PaperLight = Color(0xFFFBF8F3)
private val InkLight = Color(0xFF1D1B20)
private val PaperDark = Color(0xFF121212)
private val InkDark = Color(0xFFE6E1E5)

/**
 * The in-app reader ("Read Now"): PDFs page by page with PdfRenderer, EPUBs
 * chapter by chapter in a local WebView, edge to edge with nothing but the
 * book on screen. The top and bottom bars step aside as soon as the reader
 * scrolls; a tap on the page brings them back (and they hide again after a
 * few seconds). "Aa" opens the text size (EPUB) or zoom (PDF) and dark mode;
 * size changes glide instead of jumping. Both settings are remembered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(
    document: ReaderDocument,
    dark: Boolean,
    scale: Float,
    onDark: (Boolean) -> Unit,
    onScale: (Float) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var chrome by rememberSaveable { mutableStateOf(true) }
    var controls by rememberSaveable { mutableStateOf(false) }
    /** Bumped when a tap brings the bars back: they hide again after a while. */
    var shownByTap by remember { mutableIntStateOf(0) }
    LaunchedEffect(chrome, controls, shownByTap) {
        if (chrome && !controls && shownByTap > 0) {
            delay(AUTO_HIDE_MILLIS)
            chrome = false
        }
    }
    val toggleChrome: () -> Unit = {
        chrome = !chrome
        if (chrome) shownByTap++ else controls = false
    }
    val hideChrome: () -> Unit = {
        if (!controls) chrome = false
    }
    // Text size and zoom glide to each new value.
    val shownScale by animateFloatAsState(scale, tween(220), label = "scale")
    val paper = if (dark) PaperDark else PaperLight
    val ink = if (dark) InkDark else InkLight

    // Status bar icons that stay readable on the reader's own paper colour.
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, dark) {
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { activity?.enableEdgeToEdge() }
    }

    val epub = if (document.format == DocFormat.EPUB) rememberEpub(document.file) else null
    var chapter by rememberSaveable(document.file.path) { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(paper)) {
        val content = Modifier.fillMaxSize().statusBarsPadding()
        when {
            document.format == DocFormat.PDF ->
                PdfReader(document.file, dark, scale, shownScale, toggleChrome, hideChrome, content)
            epub != null -> EpubReader(epub, chapter, { chapter = it }, dark, shownScale, paper, toggleChrome, hideChrome, content)
        }

        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { onDark(!dark) }) {
                        Icon(
                            if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (dark) "Light mode" else "Dark mode",
                        )
                    }
                    IconButton(onClick = { controls = !controls }) {
                        Text("Aa", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = paper.copy(alpha = 0.96f),
                    titleContentColor = ink,
                    navigationIconContentColor = ink,
                    actionIconContentColor = ink,
                ),
            )
        }

        AnimatedVisibility(
            visible = chrome,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = paper.copy(alpha = 0.96f), contentColor = ink, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    AnimatedVisibility(visible = controls) {
                        ReaderControls(document.format, dark, scale, onDark, onScale, ink)
                    }
                    if (epub != null && epub.chapters.isNotEmpty()) {
                        ChapterBar(chapter, epub.chapters.size, { chapter = it }, ink)
                    }
                }
            }
        }
    }
}

/** How long bars brought back by a tap stay on screen. */
private const val AUTO_HIDE_MILLIS = 3_500L

@Composable
private fun ReaderControls(
    format: DocFormat,
    dark: Boolean,
    scale: Float,
    onDark: (Boolean) -> Unit,
    onScale: (Float) -> Unit,
    ink: Color,
) {
    val min = AppPreferences.READER_SCALE_MIN
    val max = AppPreferences.READER_SCALE_MAX
    val step = 0.1f
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dark mode", style = MaterialTheme.typography.titleSmall, color = ink, modifier = Modifier.weight(1f))
            Switch(checked = dark, onCheckedChange = onDark)
        }
        Text(
            if (format == DocFormat.PDF) "Zoom" else "Text size",
            style = MaterialTheme.typography.titleSmall,
            color = ink,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onScale((scale - step).coerceIn(min, max)) }, enabled = scale > min) {
                Text("A−", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
            }
            Slider(
                value = scale,
                onValueChange = onScale,
                valueRange = min..max,
                colors = SliderDefaults.colors(),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = { onScale((scale + step).coerceIn(min, max)) }, enabled = scale < max) {
                Text("A+", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ink)
            }
        }
        Text(
            "${(scale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        HorizontalDivider(color = ink.copy(alpha = 0.15f), modifier = Modifier.padding(top = 6.dp))
    }
}

/** "‹ Previous · Chapter 2 of 9 · Next ›" for EPUBs. */
@Composable
private fun ChapterBar(chapter: Int, count: Int, onChapter: (Int) -> Unit, ink: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onChapter(chapter - 1) }, enabled = chapter > 0) {
            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
            Text("Previous")
        }
        Text(
            "Chapter ${chapter + 1} of $count",
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick = { onChapter(chapter + 1) }, enabled = chapter < count - 1) {
            Text("Next")
            Spacer(Modifier.width(2.dp))
            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
        }
    }
}

// ------------------------------------------------------------------- PDF

/** Black text on white, inverted to light text on black for dark mode. */
private val Invert = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** PdfRenderer may open one page at a time; [closed] guards renders that outlive the screen. */
private class PdfSource(file: File) {
    val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    val lock = Mutex()
    var closed = false

    suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }.asImageBitmap()
            }
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                closed = true
                renderer.close() // also closes the file descriptor
            }
        }
    }
}

@Composable
private fun PdfReader(
    file: File,
    dark: Boolean,
    scale: Float,
    shownScale: Float,
    onTap: () -> Unit,
    onScroll: () -> Unit,
    modifier: Modifier,
) {
    val source = remember(file) { PdfSource(file) }
    DisposableEffect(source) { onDispose { source.close() } }
    val pages = source.renderer.pageCount
    val density = LocalDensity.current
    val list = rememberLazyListState()
    // Reading on: the bars step aside.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { if (it) onScroll() }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The page glides to the new zoom; it is re-rendered sharp at the final size only.
        val pageWidth = maxWidth * shownScale - 16.dp
        // Rendering above ~1800 px wide costs memory without visible gain.
        val widthPx = with(density) { (maxWidth * scale - 16.dp).roundToPx() }.coerceIn(1, 1800)
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onTap() } }
                .then(if (scale > 1f) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(pages) { index -> PdfPage(source, index, widthPx, pageWidth, dark, pages) }
        }
    }
}

@Composable
private fun PdfPage(source: PdfSource, index: Int, widthPx: Int, width: Dp, dark: Boolean, pages: Int) {
    var bitmap by remember(index, widthPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, widthPx) { bitmap = source.render(index, widthPx) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(width)
                .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: (595f / 842f))
                .background(if (dark) Color.Black else Color.White),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Page ${index + 1}",
                    colorFilter = if (dark) Invert else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            "${index + 1} / $pages",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ------------------------------------------------------------------ EPUB

private const val BOOK_HOST = "book.local"

/** Shared between the screen and the WebView's loading thread. */
private class EpubState {
    @Volatile var dark = false
    @Volatile var currentPath: String? = null
    @Volatile var restoreScrollY = 0
}

/** An open EPUB: its zip and its chapters in reading order. */
private class OpenEpub(val zip: ZipFile, val chapters: List<String>)

@Composable
private fun rememberEpub(file: File): OpenEpub {
    val epub = remember(file) {
        val zip = ZipFile(file)
        OpenEpub(zip, runCatching { EpubBook.chapterPaths(zip) }.getOrDefault(emptyList()))
    }
    DisposableEffect(epub) { onDispose { runCatching { epub.zip.close() } } }
    return epub
}

@SuppressLint("ClickableViewAccessibility") // taps still reach the WebView; this only listens
@Composable
private fun EpubReader(
    epub: OpenEpub,
    chapter: Int,
    onChapter: (Int) -> Unit,
    dark: Boolean,
    scale: Float,
    paper: Color,
    onTap: () -> Unit,
    onScroll: () -> Unit,
    modifier: Modifier,
) {
    val chapters = epub.chapters
    val state = remember { EpubState() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val tap by rememberUpdatedState(onTap)
    val scroll by rememberUpdatedState(onScroll)
    val chapterChanged by rememberUpdatedState(onChapter)

    if (chapters.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("This book has no readable chapters.")
        }
        return
    }

    // Dark mode is part of each page's injected stylesheet: reload, keeping the place.
    LaunchedEffect(dark) {
        val view = webView
        if (view != null && state.dark != dark) {
            state.restoreScrollY = view.scrollY
            state.dark = dark
            view.reload()
        } else {
            state.dark = dark
        }
    }
    LaunchedEffect(chapter, webView) {
        val view = webView ?: return@LaunchedEffect
        val path = chapters[chapter.coerceIn(0, chapters.lastIndex)]
        if (state.currentPath != path) view.loadUrl(bookUrl(path))
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Only the book itself: no scripts, no files, no network.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = BookClient(epub.zip, state) { path ->
                    chapters.indexOf(path).takeIf { it >= 0 }?.let { chapterChanged(it) }
                }
                // A single tap shows or hides the bars; scrolling hides them.
                val taps = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            tap()
                            return false
                        }
                    },
                )
                setOnTouchListener { _, event -> taps.onTouchEvent(event); false }
                setOnScrollChangeListener { _, _, y, _, oldY -> if (abs(y - oldY) > 2) scroll() }
                webView = this
            }
        },
        update = { view ->
            view.settings.textZoom = (scale * 100).roundToInt()
            view.setBackgroundColor(paper.toArgb())
        },
        onRelease = { it.destroy() },
    )
}

private fun bookUrl(path: String) = "https://$BOOK_HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

/**
 * Serves the EPUB's files straight from the zip at https://book.local/...,
 * adds the reader's stylesheet to every chapter, and blocks everything else,
 * so a book never reaches the network and links never leave the app.
 */
private class BookClient(
    private val zip: ZipFile,
    private val state: EpubState,
    private val onChapter: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host != BOOK_HOST

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val path = Uri.parse(url).path?.removePrefix("/") ?: return
        state.currentPath = path
        onChapter(path)
    }

    override fun onPageFinished(view: WebView, url: String) {
        val y = state.restoreScrollY
        if (y > 0) {
            state.restoreScrollY = 0
            view.postDelayed({ view.scrollTo(0, y) }, 120)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        if (url.host != BOOK_HOST) return notFound()
        val path = url.path?.removePrefix("/") ?: return notFound()
        return try {
            val entry = zip.getEntry(path) ?: return notFound()
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val extension = path.substringAfterLast('.', "").lowercase()
            val body = if (extension in DOCUMENTS) withReaderStyle(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes
            WebResourceResponse(MIME[extension] ?: "application/octet-stream", "utf-8", ByteArrayInputStream(body))
        } catch (e: Exception) {
            notFound() // e.g. the reader was closed while a page was loading
        }
    }

    private fun withReaderStyle(html: String): String {
        val css = if (state.dark) DARK_CSS else LIGHT_CSS
        val tag = """<style type="text/css" id="reader-style">$css</style>"""
        val head = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html) ?: return html
        return html.substring(0, head.range.first) + tag + html.substring(head.range.first)
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val DOCUMENTS = setOf("xhtml", "html", "htm")
        val MIME = mapOf(
            "xhtml" to "application/xhtml+xml", "html" to "text/html", "htm" to "text/html",
            "css" to "text/css", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "svg" to "image/svg+xml", "webp" to "image/webp",
            "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff", "woff2" to "font/woff2",
        )
        const val BASE_CSS = "body{margin:0 auto !important;padding:20px 22px 120px !important;max-width:42em;" +
            "line-height:1.65 !important;word-wrap:break-word}img,svg{max-width:100% !important;height:auto !important}"
        const val LIGHT_CSS = "$BASE_CSS html,body{background:#FBF8F3 !important;color:#1D1B20 !important}"
        const val DARK_CSS = "$BASE_CSS html,body{background:#121212 !important;color:#E6E1E5 !important}" +
            "body *{color:inherit !important;background-color:transparent !important;border-color:#444 !important}" +
            "a{color:#9ECBFF !important}"
    }
}
