package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

sealed interface SplitMode {
    /** Comma separated page ranges ("1-2,4"); each range becomes its own file. */
    data class Ranges(val spec: String) : SplitMode

    /** Consecutive chunks of [n] pages; the last file may be shorter. */
    data class EveryN(val n: Int) : SplitMode

    data object EachPage : SplitMode
}

/** Splits one PDF into several files named `<baseName>_1.pdf`, `<baseName>_2.pdf`, ... */
object SplitOperation {
    /**
     * Writes the parts of [input] into [outDir] (created if missing) and returns them in order.
     * [progress] receives 0..1. [isCancelled] is polled before each file and after the last one;
     * when it returns true a [CancellationException] is thrown. Any failure or cancellation
     * deletes every file written so far. Throws [PdfError.InvalidRange] before writing anything
     * when a range is invalid, and [IllegalArgumentException] for `EveryN(n < 1)`.
     */
    fun run(
        input: File,
        mode: SplitMode,
        outDir: File,
        baseName: String,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<File> {
        if (mode is SplitMode.EveryN) require(mode.n >= 1) { "Pages per file must be at least 1" }
        val written = ArrayList<File>()
        var success = false
        try {
            PdfLoader.load(input, password).use { source ->
                val total = source.numberOfPages
                val groups = groupsFor(mode, total)
                if (!outDir.isDirectory && !outDir.mkdirs()) throw PdfError.Io(IOException("Cannot create $outDir"))
                groups.forEachIndexed { i, pages ->
                    if (isCancelled()) throw CancellationException("Split cancelled")
                    val out = File(outDir, "${baseName}_${i + 1}.pdf")
                    written += out
                    writePart(source, pages, out)
                    progress((i + 1).toFloat() / groups.size)
                }
            }
            if (isCancelled()) throw CancellationException("Split cancelled")
            success = true
            return written
        } finally {
            if (!success) written.forEach { it.delete() }
        }
    }

    private fun groupsFor(mode: SplitMode, total: Int): List<List<Int>> = when (mode) {
        is SplitMode.Ranges -> PageRanges.parseGroups(mode.spec, total)
        is SplitMode.EveryN -> (0 until total).chunked(mode.n)
        SplitMode.EachPage -> (0 until total).map { listOf(it) }
    }

    private fun writePart(source: PDDocument, pages: List<Int>, out: File) {
        // Imported pages share resources with [source], which therefore stays open until saved.
        val part = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            try {
                pages.forEach { part.importPage(source.getPage(it)) }
                part.save(out)
            } catch (e: IOException) {
                throw PdfError.Io(e)
            }
        } finally {
            runCatching { part.close() }
        }
    }
}
