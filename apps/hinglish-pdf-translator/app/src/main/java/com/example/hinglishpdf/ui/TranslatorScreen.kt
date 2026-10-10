package com.example.hinglishpdf.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.document.DocFormat
import com.example.hinglishpdf.ui.reader.LocalReaderStyle
import com.example.hinglishpdf.ui.reader.ReaderSettingsSheet

/** The exact title of the main dashboard's top bar. */
const val DASHBOARD_TITLE = "Bring your own key"

private class TabSpec(val tab: AppTab, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val TABS = listOf(
    TabSpec(AppTab.TRANSLATE, "Translate", Icons.Filled.Translate, Icons.Outlined.Translate),
    TabSpec(AppTab.LIBRARY, "Library", Icons.AutoMirrored.Filled.LibraryBooks, Icons.AutoMirrored.Outlined.LibraryBooks),
    TabSpec(AppTab.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/** What can be done with a book, shared by the dashboard and the library. */
class BookActions(
    val onSelect: (Long) -> Unit,
    val onPause: () -> Unit,
    val onResume: (BookEntity) -> Unit,
    val onExport: (BookEntity) -> Unit,
    val onRead: (BookEntity) -> Unit,
    val onCopy: () -> Unit,
    val onDelete: (BookEntity) -> Unit,
)

/**
 * The main screen: three bottom tabs — Translate (the dashboard), Library
 * (books, progress and their actions) and Settings — plus the sheets and
 * first-launch helpers that sit on top of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorScreen(
    viewModel: TranslatorViewModel,
    /** First launch: ask for the target language. */
    languagePrompt: Boolean = false,
    onLanguagePromptDone: () -> Unit = {},
    /** First launch (after the language), or from Settings: the spotlight tour. */
    tour: Boolean = false,
    onTourDone: () -> Unit = {},
    onShowTutorial: () -> Unit = {},
) {
    // Both collected on the main thread; the heavy work behind them runs on Dispatchers.Default.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val livePage by viewModel.livePage.collectAsStateWithLifecycle()
    val readerStyle by viewModel.readerStyle.collectAsStateWithLifecycle()
    val tabIndex by viewModel.tab.collectAsStateWithLifecycle()
    val tab = AppTab.entries[tabIndex.coerceIn(0, AppTab.entries.lastIndex)]
    var showTextSettings by rememberSaveable { mutableStateOf(false) }
    var showTerms by rememberSaveable { mutableStateOf(false) }
    /** Runs once the Terms are accepted (the translation the user was starting). */
    var afterTerms by remember { mutableStateOf<(() -> Unit)?>(null) }
    val providerSheet by viewModel.providerSheet.collectAsStateWithLifecycle()
    val keyBrowser by viewModel.keyBrowser.collectAsStateWithLifecycle()
    val dashboardList = rememberLazyListState()
    val targets = remember { SpotlightTargets() }
    val keySaved by viewModel.keySaved.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // Storage Access Framework pickers: no storage permission required.
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::addBook)
    }

    // Android 13+: ask once so "Translating page 45 of 300..." can be shown.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    // The tour points at the dashboard's cards.
    LaunchedEffect(tour) { if (tour) viewModel.selectTab(AppTab.TRANSLATE) }

    /** No translation starts before the Terms of Use are accepted. */
    fun withTerms(action: () -> Unit) {
        if (state.termsAccepted) {
            action()
        } else {
            afterTerms = action
            showTerms = true
        }
    }

    val actions = BookActions(
        onSelect = viewModel::select,
        onPause = viewModel::pause,
        onResume = { book -> withTerms { viewModel.resume(book) } },
        onExport = viewModel::saveToDownloads,
        onRead = viewModel::readNow,
        onCopy = {
            val message = try {
                clipboard.setText(AnnotatedString(viewModel.plainText()))
                "Copied the translated pages"
            } catch (e: RuntimeException) {
                "Too large for the clipboard; use Export instead"
            }
            viewModel.showMessage(message)
        },
        onDelete = viewModel::delete,
    )

    if (showTerms) {
        TermsDialog(
            acceptedAt = state.termsAcceptedAt,
            onAgree = {
                viewModel.acceptTerms()
                showTerms = false
                afterTerms?.invoke()
                afterTerms = null
            },
            onDismiss = {
                showTerms = false
                afterTerms = null
            },
        )
    }

    if (providerSheet) {
        ProviderSettingsSheet(
            settings = state.providers,
            activeModel = state.activeModel?.takeIf { it.startsWith(state.provider.displayName) },
            busy = state.live.running,
            onDismiss = { viewModel.showProviderSheet(false) },
            onSelect = viewModel::selectProvider,
            onSaveKey = viewModel::saveApiKey,
            onAutoSaveKey = viewModel::autoSaveKey,
            onRemoveKey = viewModel::removeApiKey,
            onSetModel = viewModel::setModel,
            browserOpen = keyBrowser,
            onBrowserOpenChange = viewModel::showKeyBrowser,
        )
    }

    keySaved?.let { provider -> KeySavedCelebration(provider, onDone = viewModel::keySavedShown) }

    if (languagePrompt) {
        TargetLanguageDialog(
            current = state.targetLanguage,
            onSelect = {
                viewModel.setTargetLanguage(it)
                onLanguagePromptDone()
            },
            onDismiss = onLanguagePromptDone,
        )
    }

    if (showTextSettings) {
        ReaderSettingsSheet(
            style = readerStyle,
            onFont = viewModel::setReaderFont,
            onTextSize = viewModel::setReaderTextSize,
            onDismiss = { showTextSettings = false },
        )
    }

    CompositionLocalProvider(LocalReaderStyle provides readerStyle) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                when (tab) {
                                    AppTab.TRANSLATE -> DASHBOARD_TITLE
                                    AppTab.LIBRARY -> "Library"
                                    AppTab.SETTINGS -> "Settings"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        actions = {
                            when (tab) {
                                AppTab.TRANSLATE -> IconButton(onClick = onShowTutorial) {
                                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Show the tutorial")
                                }
                                // "Aa": font and text size of the translated text.
                                AppTab.LIBRARY -> IconButton(onClick = { showTextSettings = true }) {
                                    Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                AppTab.SETTINGS -> Unit
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                },
                bottomBar = {
                    BottomTabs(selected = tab, translating = state.live.running, onSelect = viewModel::selectTab)
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { w -> if (forward) w / 8 else -w / 8 }) togetherWith
                            fadeOut(tween(160))
                    },
                    label = "tab",
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) { shown ->
                    when (shown) {
                        AppTab.TRANSLATE -> DashboardTab(
                            state = state,
                            livePage = livePage,
                            listState = dashboardList,
                            targets = targets,
                            actions = actions,
                            onConfigure = { viewModel.showProviderSheet(true) },
                            onSourceLanguage = viewModel::setSourceLanguage,
                            onTargetLanguage = viewModel::setTargetLanguage,
                            onSwapLanguages = viewModel::swapLanguages,
                            onOutputFormat = viewModel::setOutputFormat,
                            onPick = {
                                if (!state.configured) {
                                    viewModel.showProviderSheet(true)
                                    viewModel.showMessage("Add an API key first")
                                } else {
                                    withTerms {
                                        // Some file managers label EPUBs as generic binaries.
                                        bookPicker.launch(arrayOf(DocFormat.PDF.mimeType, DocFormat.EPUB.mimeType, "application/octet-stream"))
                                    }
                                }
                            },
                            onShowTerms = { afterTerms = null; showTerms = true },
                            onOpenLibrary = { viewModel.selectTab(AppTab.LIBRARY) },
                        )
                        AppTab.LIBRARY -> LibraryTab(
                            state = state,
                            pages = pages,
                            livePage = livePage,
                            actions = actions,
                            onTranslateBook = { viewModel.selectTab(AppTab.TRANSLATE) },
                        )
                        AppTab.SETTINGS -> SettingsTab(
                            state = state,
                            onEngine = { provider ->
                                viewModel.selectProvider(provider)
                                viewModel.showProviderSheet(true)
                            },
                            onOutputFormat = viewModel::setOutputFormat,
                            onTextStyle = { showTextSettings = true },
                            onShowTutorial = onShowTutorial,
                            onShowTerms = { afterTerms = null; showTerms = true },
                        )
                    }
                }
            }
            if (tour && !languagePrompt && tab == AppTab.TRANSLATE) {
                SpotlightTour(
                    targets = targets,
                    bringIntoView = { step ->
                        if (step == TourStep.UPLOAD) {
                            val item = dashboardList.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "new" }
                            if (item == null || item.offset + item.size > dashboardList.layoutInfo.viewportEndOffset) {
                                dashboardList.animateScrollToItem(NEW_BOOK_ITEM)
                            }
                        } else {
                            dashboardList.animateScrollToItem(0)
                        }
                    },
                    onDone = onTourDone,
                )
            }
        }
    }
}

/** Translate · Library · Settings, with a springy icon and a dot on Library while a book is translating. */
@Composable
private fun BottomTabs(selected: AppTab, translating: Boolean, onSelect: (AppTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
        for (spec in TABS) {
            val chosen = spec.tab == selected
            val scale by animateFloatAsState(
                if (chosen) 1.12f else 1f,
                spring(dampingRatio = 0.45f, stiffness = 380f),
                label = "tab-icon",
            )
            NavigationBarItem(
                selected = chosen,
                onClick = { onSelect(spec.tab) },
                icon = {
                    BadgedBox(badge = { if (spec.tab == AppTab.LIBRARY && translating) Badge() }) {
                        Icon(
                            if (chosen) spec.selected else spec.unselected,
                            contentDescription = null,
                            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                        )
                    }
                },
                label = { Text(spec.label, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
