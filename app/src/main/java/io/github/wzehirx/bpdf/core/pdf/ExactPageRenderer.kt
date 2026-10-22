package io.github.wzehirx.bpdf.core.pdf

import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Draws pages as pictures for the "exact look" layout. A protected PDF is first saved as an unprotected
 * temporary copy in [workDir] (the system renderer cannot open protected files); it is deleted on [close].
 * One page is in memory at a time: the bitmap is recycled right after it is encoded.
 */
internal class ExactPageRenderer(
    private val reader: PdfContentReader,
    private val input: File,
    private val workDir: File,
    private val rasterizer: PageRasterizer,
) : Closeable {
    private var unprotected: File? = null

    companion object {
        const val DPI = 150
    }

    private fun source(): File {
        if (!reader.isEncrypted) return input
        unprotected?.let { return it }
        workDir.mkdirs()
        return File(workDir, "unprotected.pdf").also {
            reader.saveUnprotected(it)
            unprotected = it
        }
    }

    /** Page [index] (0-based) as a JPEG. */
    fun render(index: Int): EncodedImage {
        val file = source()
        val bitmap = try {
            rasterizer.render(file, index, DPI)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PdfError.Corrupt(e)
        }
        try {
            return ImageEncoder.encode(bitmap, jpegQuality = 85) ?: throw PdfError.Corrupt(null)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        unprotected?.delete()
    }
}
