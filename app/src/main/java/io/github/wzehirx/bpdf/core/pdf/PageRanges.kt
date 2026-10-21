package io.github.wzehirx.bpdf.core.pdf

/** Parses and formats user-entered page ranges ("1-3,5"); pages are 1-based in text, 0-based in lists. */
object PageRanges {
    fun parse(spec: String, pageCount: Int): List<Int> = parseGroups(spec, pageCount).flatten().distinct()

    fun parseGroups(spec: String, pageCount: Int): List<List<Int>> {
        if (spec.isBlank()) throw PdfError.InvalidRange(RangeErrorReason.EMPTY, spec)
        return spec.split(',').map { parseToken(it.trim(), pageCount) }
    }

    private fun parseToken(token: String, pageCount: Int): List<Int> {
        if (token.isEmpty()) throw PdfError.InvalidRange(RangeErrorReason.EMPTY, token)
        val dash = token.indexOf('-')
        if (dash < 0) {
            val n = number(token, token, pageCount)
            return listOf(n - 1)
        }
        val a = token.substring(0, dash).trim()
        val b = token.substring(dash + 1).trim()
        if (a.isEmpty() || b.isEmpty()) throw PdfError.InvalidRange(RangeErrorReason.INCOMPLETE, token)
        val start = number(a, token, pageCount)
        val end = number(b, token, pageCount)
        if (start > end) throw PdfError.InvalidRange(RangeErrorReason.REVERSED, token)
        return (start - 1 until end).toList()
    }

    private fun number(text: String, token: String, pageCount: Int): Int {
        if (text.any { it !in '0'..'9' }) throw PdfError.InvalidRange(RangeErrorReason.NOT_A_NUMBER, token)
        val n = text.toIntOrNull() ?: throw PdfError.InvalidRange(RangeErrorReason.OUT_OF_BOUNDS, token)
        if (n < 1 || n > pageCount) throw PdfError.InvalidRange(RangeErrorReason.OUT_OF_BOUNDS, token)
        return n
    }

    fun format(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val out = mutableListOf<String>()
        var start = pages[0]
        var prev = start
        fun flush() { out += if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}" }
        for (p in pages.drop(1)) {
            if (p == prev + 1) prev = p else { flush(); start = p; prev = p }
        }
        flush()
        return out.joinToString(",")
    }
}
