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
        // Drop leftovers of earlier sessions without blocking startup: first anything that can hold
        // unencrypted data (older than this process), then the 24 hour age purge for the rest.
        val startedAt = System.currentTimeMillis()
        val files = container.fileRepository
        Thread({
            runCatching {
                files.purgeLeftovers(startedAt)
                files.purgeStale(STALE_CACHE_MILLIS)
            }
        }, "bpdf-cache-purge").start()
    }

    private companion object {
        const val STALE_CACHE_MILLIS = 24L * 60 * 60 * 1000
    }
}
