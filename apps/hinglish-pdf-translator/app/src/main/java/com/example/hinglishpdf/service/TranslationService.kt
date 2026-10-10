package com.example.hinglishpdf.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.hinglishpdf.HinglishApp
import com.example.hinglishpdf.data.db.BookEntity
import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.data.document.DocumentFormatter
import com.example.hinglishpdf.data.PageEvent
import com.example.hinglishpdf.data.pdf.PdfPasswordProtectedException
import com.example.hinglishpdf.data.db.PageEntity
import com.example.hinglishpdf.data.document.BookImporter
import com.example.hinglishpdf.data.translate.PageFiller
import com.example.hinglishpdf.data.translate.PipelinePages
import com.example.hinglishpdf.pipeline.NeedsOcrException
import com.example.hinglishpdf.pipeline.segment.ParsedDocument
import com.example.hinglishpdf.pipeline.translate.ParagraphChunker
import com.example.hinglishpdf.ui.status.Eta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translates books in the background, page by page, with a persistent
 * notification. Keeps going with the screen locked or the app closed.
 *
 * Crash safety comes from Room, not from this service's memory: every page is
 * saved the moment it is translated, and work always restarts at the first
 * page without a translation. The service is START_STICKY, so if Android
 * kills it the system restarts it and it picks up where it stopped; after a
 * reboot or a crash, opening the app restarts it the same way.
 *
 * Books are processed one at a time, oldest first (a simple queue).
 */
class TranslationService : Service() {

    private val app get() = application as HinglishApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Set when the user taps Pause, so the book is marked PAUSED rather than interrupted. */
    @Volatile
    private var pauseRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be in the foreground within seconds of startForegroundService().
        goForeground(Notifications.progress(this, "Book translation", "Starting…", 0, 0))

        if (intent?.action == ACTION_PAUSE) {
            pauseRequested = true
            job?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }

        if (job?.isActive != true) {
            pauseRequested = false
            // intent == null: the system restarted us after killing the process.
            job = scope.launch { runQueue() }
        }
        return START_STICKY
    }

    private suspend fun runQueue() {
        acquireWakeLock()
        try {
            while (true) {
                val book = app.db.bookDao().nextResumable() ?: break
                translateBook(book)
            }
        } finally {
            releaseWakeLock()
            app.monitor.reset()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun translateBook(initial: BookEntity) {
        val books = app.db.bookDao()
        val pages = app.db.pageDao()
        val id = initial.id
        app.monitor.update {
            LiveStatus(running = true, bookId = id, label = "Starting…", phase = LivePhase.STARTING, phaseStartedAt = System.currentTimeMillis())
        }
        try {
            showProgress(initial, "Starting…", 0, 0)
            check(app.preferences.termsAccepted) {
                "Accept the Terms of Use in the app first (menu ⋮ → Terms of Use), then tap Resume."
            }
            check(app.translatorConfigured) {
                "No API key for ${app.providers.state.value.provider.displayName}. Paste one in the app, then tap Resume."
            }

            // 0. The pipeline's parse, once per book (again after a parser upgrade):
            // assembled paragraphs, sentence segments, placeholders and the source
            // map. A scanned PDF stops here (it needs OCR). Any other problem with
            // the parser is logged and the book is translated page by page instead.
            val doc = analyse(initial)
            if (doc != null) app.writeProject(initial)

            // 1. The pages, once. Pages are stored in one transaction, so an
            // interrupted extraction leaves none and is simply redone; a resumed
            // book keeps its pages and their saved translations.
            if (pages.count(id) == 0) {
                books.setStatus(id, BookStatus.READING)
                val built = doc?.takeIf { d -> d.paragraphs.any { it.translatable } }
                    ?.let { PipelinePages.build(id, it, initial.format, BookImporter.EPUB_SECTION_WORDS) }
                val extracted = built ?: run {
                    var lastShown = 0L
                    app.importer.readPages(initial) { label, done, total ->
                        app.monitor.progress(LivePhase.READING, done, total, label)
                        val now = System.currentTimeMillis()
                        if (now - lastShown > 1_000) { // don't flood the notification
                            lastShown = now
                            showProgress(initial, label, done, total)
                        }
                    }
                }
                pages.replacePages(id, extracted)
                books.setPageCount(id, extracted.size)
            }
            books.setStatus(id, BookStatus.TRANSLATING)
            val book = books.get(id) ?: return

            // 2. Translate, saving each page the moment it is complete. Books whose
            // pages were built from the parse go paragraph by paragraph (whole
            // paragraphs, even across page breaks); older books page by page.
            if (doc != null && PipelinePages.isPipeline(pages.pages(id))) translateParagraphs(book, doc) else translatePages(book)

            // 3. Build the PDF or EPUB (the Output Format toggle) and save it to Downloads.
            val format = app.preferences.outputFormat.value
            val saving = "Saving ${format.name} to Downloads…"
            app.monitor.update { it.copy(liveText = "", waiting = false) }
            app.monitor.progress(LivePhase.SAVING, 0, 0, saving)
            showProgress(book, saving, book.pageCount, book.pageCount)
            app.writeProject(book)
            val saved = app.exporter.exportToDownloads(book, format)
            books.setStatus(id, BookStatus.COMPLETED)
            Notifications.finished(this, book.title, saved.displayName, saved.uri, format.mimeType)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Paused by the user: wait for a manual resume. Otherwise (the
                // service is being destroyed) leave it resumable.
                if (pauseRequested) books.setStatus(id, BookStatus.PAUSED)
                app.writeProject(initial)
            }
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Translation of book $id failed", e)
            val message = e.message ?: e.javaClass.simpleName
            books.setStatus(id, BookStatus.FAILED, message)
            Notifications.failed(this, initial.title, message)
        }
    }

    /** Books from before the pipeline: page by page, each page saved immediately. */
    private suspend fun translatePages(book: BookEntity) {
        val id = book.id
        val pages = app.db.pageDao()
        val unit = book.unitName
        while (true) {
            val page = pages.nextUntranslated(id) ?: break
            val label = "Translating $unit ${page.pageNumber} of ${book.pageCount}..."
            app.monitor.update {
                it.copy(
                    label = label, page = page.pageNumber, chunk = 0, chunkCount = 0,
                    pageBlocks = page.sourceBlocks, pageTranslations = emptyList(),
                    liveText = "", waiting = false,
                )
            }
            app.monitor.progress(LivePhase.TRANSLATING, page.pageNumber - 1, book.pageCount, label)
            showProgress(book, withTimeLeft(label), page.pageNumber - 1, book.pageCount)

            // The answer streams in; the page is saved only once complete.
            var translations: List<String?> = emptyList()
            app.translationRepository.translatePage(page.sourceBlocks).collect { event ->
                when (event) {
                    is PageEvent.ChunkStarted -> {
                        app.monitor.update {
                            it.copy(chunk = event.chunk, chunkCount = event.chunkCount, liveText = "")
                        }
                        if (event.chunkCount > 1) {
                            showProgress(book, "$label (part ${event.chunk}/${event.chunkCount})", page.pageNumber - 1, book.pageCount)
                        }
                    }
                    is PageEvent.Token -> app.monitor.update {
                        it.copy(label = label, waiting = false, liveText = it.liveText + event.text)
                    }
                    is PageEvent.Waiting -> {
                        val message = "${event.reason}: retrying in ${event.seconds} s"
                        app.monitor.update { it.copy(label = message, liveText = "", waiting = true) }
                        showProgress(book, message, page.pageNumber - 1, book.pageCount)
                    }
                    is PageEvent.ChunkFinished -> app.monitor.update {
                        it.copy(label = label, pageTranslations = event.translations, liveText = "", waiting = false)
                    }
                    is PageEvent.PageFinished -> translations = event.translations
                }
            }
            pages.saveTranslation(
                bookId = id,
                pageNumber = page.pageNumber,
                translations = translations,
                text = DocumentFormatter.toPlainText(page.sourceBlocks, translations),
                at = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Books translated through the pipeline: requests of 1-3 whole
     * paragraphs with their structure markers, in reading order across page
     * breaks. Each page is saved once every paragraph on it is translated,
     * and progress is shown in pages ("page 45 of 300", the page the
     * current paragraph starts on).
     */
    private suspend fun translateParagraphs(book: BookEntity, doc: ParsedDocument) {
        val id = book.id
        val pageDao = app.db.pageDao()
        val unit = book.unitName
        val all = pageDao.pages(id)
        val filler = PageFiller(all.filter { it.translations == null }, doc.paragraphs)
        var saved = all.count { it.translations != null }
        suspend fun save(done: List<PageEntity>) = done.forEach { page ->
            pageDao.saveTranslation(id, page.pageNumber, page.translations.orEmpty(), page.translatedText.orEmpty(), System.currentTimeMillis())
            saved++
        }
        save(filler.release())

        val config = app.pipelineConfig
        val chunks = ParagraphChunker.chunks(ParagraphChunker.units(doc.paragraphs, config, filler.paragraphsToTranslate), config)
        for (chunk in chunks) {
            val page = filler.pageOf(chunk.first().paragraph)
            val label = "Translating $unit $page of ${book.pageCount}..."
            val (blocks, sofar) = filler.progress(page)
            app.monitor.update {
                it.copy(
                    label = label, page = page, chunk = 0, chunkCount = 0,
                    pageBlocks = blocks, pageTranslations = sofar, liveText = "", waiting = false,
                )
            }
            app.monitor.progress(LivePhase.TRANSLATING, saved, book.pageCount, label)
            showProgress(book, withTimeLeft(label), saved, book.pageCount)

            var result: List<String?> = emptyList()
            app.paragraphTranslator.translate(chunk).collect { event ->
                when (event) {
                    is PageEvent.Token -> app.monitor.update { it.copy(label = label, waiting = false, liveText = it.liveText + event.text) }
                    is PageEvent.Waiting -> {
                        val message = "${event.reason}: retrying in ${event.seconds} s"
                        app.monitor.update { it.copy(label = message, liveText = "", waiting = true) }
                        showProgress(book, message, saved, book.pageCount)
                    }
                    is PageEvent.PageFinished -> result = event.translations
                    else -> Unit
                }
            }
            save(filler.fill(chunk, result))
            app.monitor.update { it.copy(pageTranslations = filler.progress(page).second, liveText = "") }
        }
        save(filler.finish())
    }

    /** The book's pipeline parse (made or remade as needed); null if the parser failed on it. */
    private suspend fun analyse(book: BookEntity): ParsedDocument? {
        val doc = try {
            app.pipelineDocument(
                book,
                onReanalyse = {
                    app.monitor.update { it.copy(label = REANALYSE_NOTICE) }
                    showProgress(book, REANALYSE_NOTICE, 0, 0)
                },
            ) { label, done, total ->
                app.monitor.progress(LivePhase.ANALYSING, done, total, label)
                if (done == total || done % 10 == 0) showProgress(book, label, done, total)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfPasswordProtectedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pipeline parse of book ${book.id} failed; translating it page by page", e)
            return null
        }
        if (doc.needsOcr) throw NeedsOcrException(doc.scannedPages.size, doc.pageCount)
        return doc
    }

    /** "Translating page 45 of 300... · About 25 min left" (under-promised; see Eta). */
    private fun withTimeLeft(label: String): String {
        val s = app.monitor.status.value
        val left = Eta.remainingMillis(s.doneAtStart, s.done, s.total, s.phaseStartedAt, System.currentTimeMillis())
        return if (left == null) label else "$label · ${Eta.format(left)}"
    }

    private fun showProgress(book: BookEntity, text: String, done: Int, total: Int) =
        Notifications.update(this, Notifications.progress(this, book.title, text, done, total))

    private fun goForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.PROGRESS_ID, notification, type)
    }

    /** Keeps the CPU running with the screen off; the time limit is a safety net. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HinglishTranslator:translation")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        app.monitor.reset()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslationService"
        const val REANALYSE_NOTICE = "Re-analysing this book with the improved parser; your translations are kept"
        const val ACTION_PAUSE = "com.example.hinglishpdf.PAUSE"
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        /** Starts (or wakes) the service; it picks up every queued or interrupted book. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TranslationService::class.java))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, TranslationService::class.java).setAction(ACTION_PAUSE))
        }
    }
}
