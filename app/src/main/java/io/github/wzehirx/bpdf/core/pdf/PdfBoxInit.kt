package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** One-time PdfBox-Android resource initialisation. */
object PdfBoxInit {
    @Volatile private var done = false

    fun ensure(context: Context) {
        if (done) return
        synchronized(this) {
            if (!done) {
                PDFBoxResourceLoader.init(context.applicationContext)
                done = true
            }
        }
    }
}
