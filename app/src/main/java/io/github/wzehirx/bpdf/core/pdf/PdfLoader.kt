package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** The only way engine code opens PDFs: temp-file memory mode and error mapping. */
object PdfLoader {
    /** Caller must close the returned document. */
    fun load(file: File, password: String? = null): PDDocument {
        val stream = try {
            FileInputStream(file)
        } catch (e: IOException) {
            throw PdfError.Io(e)
        }
        try {
            return PDDocument.load(stream, password ?: "", MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw if (password == null) PdfError.PasswordRequired() else PdfError.WrongPassword()
        } catch (e: IOException) {
            throw PdfError.Corrupt(e)
        } catch (e: RuntimeException) {
            throw PdfError.Corrupt(e)
        } finally {
            // PDDocument.load(InputStream) fully buffers the data, so the stream is no longer needed.
            try { stream.close() } catch (_: IOException) { }
        }
    }

    fun pageCount(file: File, password: String? = null): Int = load(file, password).use { it.numberOfPages }

    fun isEncrypted(file: File): Boolean =
        try {
            load(file).use { it.isEncrypted }
        } catch (_: PdfError.PasswordRequired) {
            true
        }
}
