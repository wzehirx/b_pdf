package io.github.wzehirx.bpdf.core.ocr

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PageOverlay
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.drawable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/**
 * Makes a scanned PDF searchable and readable by screen readers: the original pages stay exactly as they are and each
 * recognized page gets its text as one invisible block (text render mode "neither": not painted, but found by search,
 * selection and text extraction).
 */
object SearchablePdfOperation {
    private const val MARGIN = 18f
    private const val START_SIZE = 10f
    private const val MIN_SIZE = 1f
    private const val LEADING = 1.2f

    /**
     * Writes a copy of [input] to [out] with the text of [pages] (0-based [PageText.pageIndex]) laid under the
     * page's picture, and returns how many pages got a text layer (blank ones get none). Throws
     * [IllegalArgumentException] for a page outside the document. Characters the font lacks are skipped. The output is
     * not encrypted. [fontLoader] opens the font TTF; [isCancelled] is polled per page and around saving. A failure or
     * cancellation leaves no [out].
     */
    fun run(
        input: File,
        pages: List<PageText>,
        out: File,
        fontLoader: () -> InputStream,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Searchable PDF cancelled")
            PdfLoader.load(input, password).use { doc ->
                require(pages.all { it.pageIndex in 0 until doc.numberOfPages }) { "Page index outside 0..${doc.numberOfPages - 1}" }
                val font = Fonts.unicode(doc, fontLoader)
                var layered = 0
                val targets = pages.filter { !it.isBlank }.distinctBy { it.pageIndex }
                targets.forEachIndexed { n, page ->
                    if (isCancelled()) throw CancellationException("Searchable PDF cancelled")
                    val lines = page.text.lines().map { font.drawable(it.trim()) }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) {
                        PageOverlay.draw(doc, doc.getPage(page.pageIndex)) { cs, visual ->
                            writeInvisibleBlock(cs, font, lines, visual)
                        }
                        layered++
                    }
                    progress(0.9f * (n + 1) / targets.size)
                }
                if (isCancelled()) throw CancellationException("Searchable PDF cancelled")
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("Searchable PDF cancelled")
                progress(1f)
                success = true
                return layered
            }
        } finally {
            if (!success) out.delete()
        }
    }

    /** [lines] top to bottom as one text block, sized so the widest line and all lines fit inside the page margins. */
    private fun writeInvisibleBlock(cs: PDPageContentStream, font: PDType0Font, lines: List<String>, page: PageOverlay.Visual) {
        val availableWidth = (page.width - 2 * MARGIN).coerceAtLeast(1f)
        val availableHeight = (page.height - 2 * MARGIN).coerceAtLeast(1f)
        val widest = lines.maxOf { font.getStringWidth(it) } / 1000f // width of the widest line at size 1
        val size = minOf(START_SIZE, availableWidth / widest, availableHeight / (lines.size * LEADING)).coerceAtLeast(MIN_SIZE)
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        cs.setFont(font, size)
        cs.setLeading(size * LEADING)
        cs.newLineAtOffset(MARGIN, page.height - MARGIN - size)
        lines.forEach {
            cs.showText(it)
            cs.newLine()
        }
        cs.endText()
    }
}
