package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Turns pages of a PDF; the rotation is added to whatever each page already has. */
object RotateOperation {
    /**
     * Rotates the 0-based [pages] (all pages when null) of [input] clockwise by [degrees] and
     * writes the result to [out]; returns the page count of the document. The new rotation is the
     * existing one plus [degrees], normalized modulo 360. The output is not encrypted.
     * Throws [IllegalArgumentException] when [degrees] is not 90, 180 or 270, or when [pages] is
     * empty or has an index outside the document. [isCancelled] is polled before loading, before
     * saving and after saving; any failure or cancellation leaves no [out].
     */
    fun run(
        input: File,
        pages: List<Int>?,
        degrees: Int,
        out: File,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        require(degrees == 90 || degrees == 180 || degrees == 270) { "Degrees must be 90, 180 or 270" }
        require(pages == null || pages.isNotEmpty()) { "No pages selected" }
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Rotate cancelled")
            PdfLoader.load(input, password).use { doc ->
                val total = doc.numberOfPages
                val targets = pages ?: (0 until total).toList()
                require(targets.all { it in 0 until total }) { "Page index outside 0..${total - 1}" }
                targets.distinct().forEach { index ->
                    val page = doc.getPage(index)
                    page.rotation = Math.floorMod(page.rotation + degrees, 360)
                }
                progress(0.5f)
                if (isCancelled()) throw CancellationException("Rotate cancelled")
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("Rotate cancelled")
                progress(1f)
                success = true
                return total
            }
        } finally {
            if (!success) out.delete()
        }
    }
}
