package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

enum class NumberPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

/**
 * [format] may contain `{n}` (this page's number) and `{total}` (the last number printed). Numbering starts at
 * [startAt] on the first page of [pages] (0-based indexes, all pages when null) and goes up by one per page.
 */
data class PageNumberOptions(
    val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    val format: String = "{n}",
    val startAt: Int = 1,
    val fontSize: Float = 12f,
    val marginPt: Float = 24f,
    val pages: List<Int>? = null,
)

/** Prints a page number (embedded Unicode font) at a corner or edge of pages. */
object PageNumberOperation {
    private const val CAP_HEIGHT = 0.72f

    /** The text printed for page number [n] of [total]. */
    fun format(format: String, n: Int, total: Int): String =
        format.replace("{n}", n.toString()).replace("{total}", total.toString())

    /**
     * Writes a copy of [input] with numbers to [out] and returns how many pages were numbered. Throws
     * [IllegalArgumentException] for a non-positive size, a negative margin or start, or an empty / out-of-range
     * [PageNumberOptions.pages]. Characters the font lacks are skipped. The output is not encrypted.
     */
    fun run(
        input: File,
        options: PageNumberOptions,
        out: File,
        fontLoader: () -> InputStream,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        require(options.fontSize > 0f) { "Font size must be positive" }
        require(options.marginPt >= 0f) { "Margin must not be negative" }
        require(options.startAt >= 0) { "Start number must not be negative" }
        require(options.pages == null || options.pages.isNotEmpty()) { "No pages selected" }
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Page numbers cancelled")
            PdfLoader.load(input, password).use { doc ->
                val total = doc.numberOfPages
                val targets = (options.pages ?: (0 until total).toList()).distinct()
                require(targets.all { it in 0 until total }) { "Page index outside 0..${total - 1}" }
                val font = Fonts.unicode(doc, fontLoader)
                val last = options.startAt + targets.size - 1
                targets.forEachIndexed { rank, index ->
                    if (isCancelled()) throw CancellationException("Page numbers cancelled")
                    val label = font.drawable(format(options.format, options.startAt + rank, last))
                    if (label.isNotEmpty()) {
                        PageOverlay.draw(doc, doc.getPage(index)) { cs, visual ->
                            val width = font.getStringWidth(label) / 1000f * options.fontSize
                            val x = when (options.position) {
                                NumberPosition.TOP_LEFT, NumberPosition.BOTTOM_LEFT -> options.marginPt
                                NumberPosition.TOP_CENTER, NumberPosition.BOTTOM_CENTER -> (visual.width - width) / 2
                                NumberPosition.TOP_RIGHT, NumberPosition.BOTTOM_RIGHT -> visual.width - options.marginPt - width
                            }
                            val y = when (options.position) {
                                NumberPosition.TOP_LEFT, NumberPosition.TOP_CENTER, NumberPosition.TOP_RIGHT ->
                                    visual.height - options.marginPt - options.fontSize * CAP_HEIGHT
                                else -> options.marginPt
                            }
                            cs.setNonStrokingColor(0, 0, 0)
                            cs.beginText()
                            cs.setFont(font, options.fontSize)
                            cs.newLineAtOffset(x, y)
                            cs.showText(label)
                            cs.endText()
                        }
                    }
                    progress(0.9f * (rank + 1) / targets.size)
                }
                if (isCancelled()) throw CancellationException("Page numbers cancelled")
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("Page numbers cancelled")
                progress(1f)
                success = true
                return targets.size
            }
        } finally {
            if (!success) out.delete()
        }
    }
}
