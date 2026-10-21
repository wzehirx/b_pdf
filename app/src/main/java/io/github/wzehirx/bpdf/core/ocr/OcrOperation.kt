package io.github.wzehirx.bpdf.core.ocr

import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the text of scanned pages: each page is drawn as a picture and given to an [OcrEngine]. */
object OcrOperation {
    /** Resolution the pages are drawn at; 300 dpi is what Tesseract reads best. */
    const val DPI = 300

    /**
     * Recognizes the 0-based [pages] of [input] (all pages when null; duplicates are dropped, the given order is kept)
     * one at a time, with [rasterizer] and [engine], and returns one [PageText] each. Runs on [dispatcher], never on
     * the caller's thread. Every bitmap is recycled right after its page, so only one page is in memory at a time.
     *
     * [progress] is called before each page starts with `(finished pages, total pages, page number)` where the page
     * number is 1-based in the document, and once at the end with `(total, total, last page)`. The coroutine is checked
     * for cancellation before each page, and a cancel also tells the engine to [OcrEngine.stop] (from a second thread) so a long page ends
     * early; nothing is returned then. The caller owns [engine] and [rasterizer] and must close them. A page that cannot
     * be drawn maps to [PdfError.Corrupt]; an [OcrFailed] from the engine is passed on. [input] must not be encrypted
     * (the renderer cannot open such files).
     */
    suspend fun run(
        input: File,
        pages: List<Int>?,
        languages: String,
        rasterizer: PageRasterizer,
        engine: OcrEngine,
        progress: (finished: Int, total: Int, pageNumber: Int) -> Unit = { _, _, _ -> },
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): List<PageText> {
        require(pages == null || pages.isNotEmpty()) { "No pages selected" }
        require(languages.isNotBlank()) { "No language" }
        return withContext(dispatcher) {
            val count = PdfLoader.pageCount(input)
            val targets = pages?.distinct() ?: (0 until count).toList()
            require(targets.all { it in 0 until count }) { "Page index outside 0..${count - 1}" }
            // A cancel arrives while the engine is busy inside a page: tell it to give up (from the IO pool, because the
            // calling thread is blocked in the engine; ATOMIC so even a cancel right after the launch gets through).
            // Not on normal completion.
            val finished = AtomicBoolean(false)
            val watcher = launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    if (!finished.get()) engine.stop()
                    throw e
                }
            }
            try {
                val out = ArrayList<PageText>(targets.size)
                targets.forEachIndexed { i, index ->
                    ensureActive()
                    progress(i, targets.size, index + 1)
                    val bitmap = try {
                        rasterizer.render(input, index, DPI)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw PdfError.Corrupt(e)
                    }
                    val text = try {
                        engine.recognize(bitmap, languages)
                    } finally {
                        bitmap.recycle()
                    }
                    ensureActive() // a stopped engine returns early with partial text: do not keep it
                    out += PageText(index, text.trim())
                }
                ensureActive()
                progress(targets.size, targets.size, targets.last() + 1)
                out
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    }
}
