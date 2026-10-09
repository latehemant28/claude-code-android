package com.example.hinglishpdf

import android.app.Application
import com.example.hinglishpdf.data.db.AppDatabase
import com.example.hinglishpdf.data.document.BookImporter
import com.example.hinglishpdf.data.export.BookExporter
import com.example.hinglishpdf.data.llm.LlmTranslator
import com.example.hinglishpdf.data.llm.ModelController
import com.example.hinglishpdf.data.llm.ModelFileManager
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import com.example.hinglishpdf.data.translate.PageTranslator
import com.example.hinglishpdf.service.Notifications
import com.example.hinglishpdf.service.ThermalGovernor
import com.example.hinglishpdf.service.TranslationMonitor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-wide singletons. The screen and the background service live in the same
 * process and share one database, one loaded model and one live status.
 */
class HinglishApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db by lazy { AppDatabase.create(this) }
    val translator by lazy { LlmTranslator(this) }
    val modelController by lazy { ModelController(ModelFileManager(this), translator, appScope) }
    val pageTranslator by lazy { PageTranslator(translator) }
    val importer by lazy { BookImporter(this, PdfTextExtractor(this)) }
    val exporter by lazy { BookExporter(this, db) }
    val thermal by lazy { ThermalGovernor(this) }
    val monitor = TranslationMonitor()

    override fun onCreate() {
        super.onCreate()
        // PDFBox needs its font/encoding resources before the first PDF is parsed.
        PDFBoxResourceLoader.init(this)
        Notifications.createChannels(this)
    }
}
