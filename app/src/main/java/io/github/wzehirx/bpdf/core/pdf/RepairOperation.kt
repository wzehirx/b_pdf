package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** What a repair got back. */
data class RepairResult(val pagesRecovered: Int)

/** Rebuilds a damaged PDF: parse it leniently (PDFBox rebuilds a missing or wrong cross-reference table) and save it afresh. */
object RepairOperation {
    /**
     * Reads [input] as far as it can be read and writes everything it found to [out] with a clean
     * cross-reference table; the output is not encrypted. Throws [PdfError.Corrupt] when nothing usable can be recovered, and
     * [PdfError.PasswordRequired] / [PdfError.WrongPassword] for a protected file. [isCancelled] is polled
     * before loading and after saving; any failure or cancellation leaves no [out].
     */
    fun run(input: File, out: File, password: String? = null, isCancelled: () -> Boolean = { false }): RepairResult {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Repair cancelled")
            val pages = PdfLoader.load(input, password).use { doc ->
                val count = doc.numberOfPages
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                count
            }
            if (isCancelled()) throw CancellationException("Repair cancelled")
            success = true
            return RepairResult(pages)
        } finally {
            if (!success) out.delete()
        }
    }
}
