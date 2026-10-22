package io.github.wzehirx.bpdf.core.pdf

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Guards for reading untrusted .docx and .xlsx files: a tiny zip can inflate to gigabytes, and an XML
 * DOCTYPE can declare nested entities that expand into gigabytes of text.
 */
internal object OoxmlLimits {
    /** Uncompressed bytes read from one XML part before the file is treated as unreadable. */
    const val MAX_PART_BYTES = 64L * 1024 * 1024

    /** Largest embedded picture that is read into memory. */
    const val MAX_PICTURE_BYTES = 30L * 1024 * 1024

    /** Characters kept of one paragraph, and in total of an Excel shared string table. */
    const val MAX_TEXT_CHARS = 20_000_000

    /** How far into a part a DOCTYPE is looked for; real OOXML has none. */
    private const val DOCTYPE_SCAN_BYTES = 256 * 1024

    /** Opens [entry] with the size limit; an XML part ([xml]) with a DOCTYPE is rejected. */
    fun open(zip: ZipFile, entry: ZipEntry, displayName: String, limit: Long, xml: Boolean): InputStream {
        val raw = zip.getInputStream(entry)
        val base: InputStream = if (xml) BufferedInputStream(raw, 8192).also { rejectDoctype(it, displayName) } else raw
        return Limited(base, limit, displayName)
    }

    private fun rejectDoctype(input: BufferedInputStream, displayName: String) {
        input.mark(DOCTYPE_SCAN_BYTES + 16)
        val head = ByteArray(DOCTYPE_SCAN_BYTES)
        var n = 0
        while (n < head.size) {
            val r = try { input.read(head, n, head.size - n) } catch (e: IOException) { throw DocumentUnreadable(displayName, e) }
            if (r < 0) break
            n += r
        }
        input.reset()
        val text = String(head, 0, n, Charsets.ISO_8859_1)
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) throw DocumentUnreadable(displayName)
    }

    private class Limited(input: InputStream, private val limit: Long, private val displayName: String) : FilterInputStream(input) {
        private var count = 0L

        private fun add(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > limit) throw DocumentUnreadable(displayName)
            }
            return n
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
        override fun skip(n: Long): Long = super.skip(n).also { count += it; if (count > limit) throw DocumentUnreadable(displayName) }
    }
}
