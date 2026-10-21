package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException

/** Concatenates PDFs, in the given order, into one file. */
object MergeOperation {
    /**
     * Merges [inputs] (at least two) into [out] and returns the page count of the result.
     * [passwords] supplies the password for encrypted inputs; the merged file is not encrypted.
     * [progress] receives 0..1.
     */
    fun run(
        inputs: List<File>,
        out: File,
        passwords: Map<File, String> = emptyMap(),
        progress: (Float) -> Unit = {},
    ): Int {
        require(inputs.size >= 2) { "Merging needs at least two files" }
        val sources = ArrayList<PDDocument>(inputs.size)
        // Appended pages share content with their source documents, so sources stay open until
        // the merged document has been saved.
        val dest = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            val merger = PDFMergerUtility()
            inputs.forEachIndexed { i, file ->
                val source = PdfLoader.load(file, passwords[file])
                sources += source
                try {
                    merger.appendDocument(dest, source)
                } catch (e: IOException) {
                    throw PdfError.Corrupt(e)
                }
                progress((i + 1).toFloat() / (inputs.size + 1))
            }
            try {
                dest.save(out)
            } catch (e: IOException) {
                throw PdfError.Io(e)
            }
            progress(1f)
            return dest.numberOfPages
        } finally {
            sources.forEach { runCatching { it.close() } }
            runCatching { dest.close() }
        }
    }
}
