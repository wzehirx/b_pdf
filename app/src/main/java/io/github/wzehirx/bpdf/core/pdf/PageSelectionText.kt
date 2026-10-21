package io.github.wzehirx.bpdf.core.pdf

/**
 * Keeps a page-range text field and a page grid in sync: the text is the source of truth, the
 * grid edits it. Pages keep the order they have in the text (extraction saves them in that order).
 */
object PageSelectionText {
    /** 0-based pages named by [text]; empty when the text is blank or not a valid range. */
    fun selected(text: String, pageCount: Int): List<Int> =
        try {
            PageRanges.parse(text, pageCount)
        } catch (_: PdfError.InvalidRange) {
            emptyList()
        }

    /** [text] with page [index] added at the end, or taken out when it is already selected. */
    fun toggle(text: String, pageCount: Int, index: Int): String {
        val current = selected(text, pageCount)
        return PageRanges.format(if (index in current) current - index else current + index)
    }

    fun all(pageCount: Int): String = PageRanges.format((0 until pageCount).toList())
}
