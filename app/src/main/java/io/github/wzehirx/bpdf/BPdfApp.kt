package io.github.wzehirx.bpdf

import android.app.Application
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit

/** Application entry point; performs process-wide initialisation. */
class BPdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PdfBoxInit.ensure(this)
    }
}
