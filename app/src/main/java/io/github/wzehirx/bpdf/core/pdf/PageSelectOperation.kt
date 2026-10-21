package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Removes pages from a PDF, or extracts selected pages into a new one. */
object PageSelectOperation {
    /**
     * Writes [input] without the 0-based [pages] to [out] and returns the page count of the result.
     * Throws [IllegalArgumentException] when [pages] is empty, contains an index outside the
     * document, or covers every page (a PDF needs at least one page).
     */
    fun remove(
        input: File,
        pages: List<Int>,
        out: File,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = write(input, out, password, progress, isCancelled) { total ->
        requireValid(pages, total)
        val removed = pages.toSet()
        val kept = (0 until total).filterNot { it in removed }
        require(kept.isNotEmpty()) { "Cannot remove every page" }
        kept
    }

    /**
     * Writes only the 0-based [pages] of [input], in the given order, to [out] and returns the
     * page count of the result. Throws [IllegalArgumentException] when [pages] is empty or
     * contains an index outside the document.
     */
    fun extract(
        input: File,
        pages: List<Int>,
        out: File,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = write(input, out, password, progress, isCancelled) { total ->
        requireValid(pages, total)
        pages
    }

    private fun requireValid(pages: List<Int>, total: Int) {
        require(pages.isNotEmpty()) { "No pages selected" }
        require(pages.all { it in 0 until total }) { "Page index outside 0..${total - 1}" }
    }

    /**
     * Copies the pages chosen by [select] (given the page count) into a new document at [out].
     * [isCancelled] is polled before copying and after saving; any failure or cancellation leaves no [out].
     */
    private fun write(
        input: File,
        out: File,
        password: String?,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        select: (total: Int) -> List<Int>,
    ): Int {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Cancelled")
            PdfLoader.load(input, password).use { source ->
                val pages = select(source.numberOfPages)
                progress(0.1f)
                // Imported pages share resources with [source], which therefore stays open until saved.
                val result = PDDocument(MemoryUsageSetting.setupTempFileOnly())
                try {
                    try {
                        PageCopier.copyPages(source, pages, result)
                        progress(0.6f)
                        if (isCancelled()) throw CancellationException("Cancelled")
                        result.save(out)
                    } catch (e: IOException) {
                        throw PdfError.Io(e)
                    }
                } finally {
                    runCatching { result.close() }
                }
                if (isCancelled()) throw CancellationException("Cancelled")
                progress(1f)
                success = true
                return pages.size
            }
        } finally {
            if (!success) out.delete()
        }
    }
}
