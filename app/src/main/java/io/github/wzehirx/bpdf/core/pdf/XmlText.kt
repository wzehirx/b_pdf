package io.github.wzehirx.bpdf.core.pdf

/** Text helpers shared by the document writers (Word, Excel, HTML). */
internal object XmlText {
    /** True for the characters XML 1.0 allows in text. */
    private fun allowed(cp: Int): Boolean =
        cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

    /** Drops characters XML cannot carry (control characters, lone surrogates). */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (allowed(cp)) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    /** Escapes [text] for use as XML text or as an attribute value in double quotes. */
    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (ch in clean(text)) {
            when (ch) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

/**
 * Splits the text of one PDF page into paragraphs. PDFTextStripper gives one line per row of text, so a
 * paragraph break is a blank line, or a line that ends a sentence and is clearly shorter than the
 * page's longest line (the last line of a paragraph). Other line breaks are joined with a space.
 */
internal object Paragraphs {
    private val SENTENCE_END = Regex("""[.!?:;…]["')\]»”]?$""")

    fun split(pageText: String): List<String> {
        val lines = pageText.lines().map { it.trim() }
        val longest = lines.maxOfOrNull { it.length } ?: 0
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) out += current.toString()
            current.setLength(0)
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            if (SENTENCE_END.containsMatchIn(line) && line.length < longest * 0.7) flush()
        }
        flush()
        return out
    }
}
