package com.example.hinglishpdf

import android.app.Application
import com.example.hinglishpdf.data.document.DocumentLoader
import com.example.hinglishpdf.data.llm.ModelFileManager
import com.example.hinglishpdf.data.pdf.PdfTextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HinglishApp : Application() {

    /** Outlives any screen; used to release the model after the ViewModel is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelFileManager by lazy { ModelFileManager(this) }
    val documentLoader by lazy { DocumentLoader(this, PdfTextExtractor(this)) }

    override fun onCreate() {
        super.onCreate()
        // PDFBox needs its font/encoding resources before the first PDF is parsed.
        PDFBoxResourceLoader.init(this)
    }
}
