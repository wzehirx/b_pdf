package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
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
                pages.forEach { dropPageReferences(part.importPage(source.getPage(it))) }
                part.save(out)
            } catch (e: IOException) {
                throw PdfError.Io(e)
            }
        } finally {
            runCatching { part.close() }
        }
    }

    /**
     * Annotations keep references to other pages of the source (`/P`, internal link destinations),
     * and saving them would copy those pages into every part. Internal page links cannot work in a
     * part anyway, so they are removed; external links and other annotations stay.
     */
    private fun dropPageReferences(page: PDPage) {
        val kept = page.annotations.filterNot { isInternalPageLink(it.cosObject) }
        kept.forEach { it.cosObject.removeItem(COSName.P) }
        page.annotations = kept
    }

    private fun isInternalPageLink(annotation: COSDictionary): Boolean {
        fun pointsAtPage(dest: COSBase?) = dest is COSArray && dest.size() > 0 && dest.getObject(0) is COSDictionary
        if (pointsAtPage(annotation.getDictionaryObject(COSName.DEST))) return true
        val action = annotation.getDictionaryObject(COSName.A) as? COSDictionary ?: return false
        return pointsAtPage(action.getDictionaryObject(COSName.D))
    }
}
