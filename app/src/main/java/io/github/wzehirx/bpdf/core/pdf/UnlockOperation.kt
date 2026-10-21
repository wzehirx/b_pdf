package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Removes the password and every restriction from a PDF. */
object UnlockOperation {
    /**
     * Writes a copy of [input], opened with [password], to [out] without any encryption.
     * Throws [PdfError.WrongPassword] for a wrong [password]. [isCancelled] is polled before loading
     * and after saving; any failure or cancellation leaves no [out].
     */
    fun run(input: File, password: String, out: File, isCancelled: () -> Boolean = { false }) {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Unlock cancelled")
            PdfLoader.load(input, password).use { doc ->
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
            }
            if (isCancelled()) throw CancellationException("Unlock cancelled")
            success = true
        } finally {
            if (!success) out.delete()
        }
    }
}
