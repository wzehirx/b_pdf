package io.github.wzehirx.bpdf

import android.app.Application
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit

/** Application entry point; performs process-wide initialisation. */
class BPdfApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PdfBoxInit.ensure(this)
        container = AppContainer(this)
        // Drop leftovers of earlier sessions (imports, unshared results) without blocking startup.
        Thread { container.fileRepository.purgeStale(STALE_CACHE_MILLIS) }.start()
    }

    private companion object {
        const val STALE_CACHE_MILLIS = 24L * 60 * 60 * 1000
    }
}
