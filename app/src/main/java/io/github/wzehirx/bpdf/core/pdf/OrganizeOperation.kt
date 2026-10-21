package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * One page of an organized document: the 0-based [sourceIndex] of the page in the input and the
 * clockwise [rotation] (a multiple of 90) added to that page's existing rotation.
 */
data class PageEdit(val sourceIndex: Int, val rotation: Int = 0)

/** Builds a new PDF from the pages of another in any order, with turns, duplicates and deletions. */
object OrganizeOperation {
    /**
     * Writes the pages named by [edits] to [out], in list order, and returns the page count of the
     * result. A page missing from [edits] is deleted; a page named twice appears twice and each
     * copy can be turned on its own. Pages are copied through [PageCopier], so links between kept
     * pages still work (they follow their target) and links to deleted pages are removed. Document
     * info, XMP metadata and language are carried over; bookmarks, form fields and structure
     * (tags) are not. The output is not encrypted.
     * Throws [IllegalArgumentException] when [edits] is empty, names a page outside the document or
     * has a rotation that is not a multiple of 90. [isCancelled] is polled before loading, before
     * saving and after saving; any failure or cancellation leaves no [out].
     */
    fun run(
        input: File,
        edits: List<PageEdit>,
        out: File,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        require(edits.isNotEmpty()) { "No pages to keep" }
        require(edits.all { it.rotation % 90 == 0 }) { "Rotation must be a multiple of 90" }
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Organize cancelled")
            PdfLoader.load(input, password).use { source ->
                val total = source.numberOfPages
                require(edits.all { it.sourceIndex in 0 until total }) { "Page index outside 0..${total - 1}" }
                progress(0.1f)
                // Imported pages share resources with [source], which therefore stays open until saved.
                val result = PDDocument(MemoryUsageSetting.setupTempFileOnly())
                try {
                    PageCopier.copyPages(source, edits.map { it.sourceIndex }, result)
                    edits.forEachIndexed { i, edit ->
                        if (edit.rotation != 0) {
                            val page = result.getPage(i)
                            page.rotation = Math.floorMod(page.rotation + edit.rotation, 360)
                        }
                    }
                    copyDocumentProperties(source, result)
                    progress(0.6f)
                    if (isCancelled()) throw CancellationException("Organize cancelled")
                    result.isAllSecurityToBeRemoved = true
                    try {
                        result.save(out)
                    } catch (e: IOException) {
                        throw PdfError.Io(e)
                    }
                    val count = result.numberOfPages
                    if (isCancelled()) throw CancellationException("Organize cancelled")
                    progress(1f)
                    success = true
                    return count
                } finally {
                    runCatching { result.close() }
                }
            }
        } finally {
            if (!success) out.delete()
        }
    }
}
