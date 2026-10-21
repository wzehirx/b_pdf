package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** The text of one page; [pageIndex] is 0-based. [isBlank] is true for a page without any text (often a scan). */
data class PageText(val pageIndex: Int, val text: String) {
    val isBlank: Boolean get() = text.isBlank()
}

/**
 * Header line, blank line, the page's text, blank line... for every page in the list, so the
 * result reads as one document. [header] receives the 1-based page number.
 */
fun List<PageText>.toPlainText(header: (Int) -> String): String =
    joinToString(separator = "\n") { page ->
        val body = page.text.trim('\n', '\r')
        if (body.isEmpty()) "${header(page.pageIndex + 1)}\n" else "${header(page.pageIndex + 1)}\n$body\n"
    }

/**
 * An open document that gives out the text of single pages on demand, so a viewer can extract the
 * pages it shows instead of the whole file at once. Thread-safe (calls are serialised); [close] it
 * when done. Opening (parsing) happens in the constructor.
 */
class PdfTextReader(file: File, password: String? = null) : Closeable {
    private val doc: PDDocument = PdfLoader.load(file, password)
    private var closed = false

    val pageCount: Int = try {
        doc.numberOfPages
    } catch (t: Throwable) {
        runCatching { doc.close() }
        throw t
    }

    /** Text of page [pageIndex] (0-based). Unreadable pages map to [PdfError.Corrupt]. */
    @Synchronized
    fun text(pageIndex: Int): String {
        require(pageIndex in 0 until pageCount) { "Page index outside 0..${pageCount - 1}" }
        check(!closed) { "Reader is closed" }
        return try {
            PDFTextStripper().apply {
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }.getText(doc)
        } catch (e: IOException) {
            throw PdfError.Corrupt(e)
        } catch (e: RuntimeException) {
            throw PdfError.Corrupt(e)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { doc.close() }
    }
}

/** Reads the text layer of a PDF. Scanned pages have none (see the OCR tool). */
object TextExtractOperation {
    /**
     * Extracts the text of the 0-based [pages] of [input] (all pages when null; duplicates are
     * dropped, the given order is kept), one [PageText] each. [progress] receives 0..1.
     * [isCancelled] is polled before every page and after the last; when it returns true a
     * [CancellationException] is thrown. Throws [PdfError.PasswordRequired] / [PdfError.WrongPassword]
     * for an encrypted input and [IllegalArgumentException] for an empty list or a page outside the document.
     */
    fun run(
        input: File,
        pages: List<Int>? = null,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<PageText> {
        require(pages == null || pages.isNotEmpty()) { "No pages selected" }
        if (isCancelled()) throw CancellationException("PDF to text cancelled")
        PdfTextReader(input, password).use { reader ->
            val targets = pages?.distinct() ?: (0 until reader.pageCount).toList()
            require(targets.all { it in 0 until reader.pageCount }) { "Page index outside 0..${reader.pageCount - 1}" }
            val out = ArrayList<PageText>(targets.size)
            targets.forEachIndexed { i, index ->
                if (isCancelled()) throw CancellationException("PDF to text cancelled")
                out += PageText(index, reader.text(index).trim())
                progress((i + 1).toFloat() / targets.size)
            }
            if (isCancelled()) throw CancellationException("PDF to text cancelled")
            return out
        }
    }
}
