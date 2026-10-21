package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

/** Saves PDF pages as picture files named `<baseName>_p<page>.jpg` or `.png` (page numbers are 1-based). */
object PdfToImagesOperation {
    private const val JPEG_QUALITY = 90

    /**
     * Renders the 0-based [pages] of [input] at [dpi] with [rasterizer] and writes one picture each into
     * [outDir] (created if missing), returning the files in the order of [pages]. Every bitmap is
     * recycled right after it is written, so only one page is in memory at a time. [progress] receives
     * 0..1. [isCancelled] is polled before each page and after the last one; when it returns true a
     * [CancellationException] is thrown. A page that cannot be drawn maps to [PdfError.Corrupt], a
     * failed write to [PdfError.Io]. Any failure or cancellation deletes every file written so far.
     */
    fun run(
        input: File,
        pages: List<Int>,
        format: ImageFormat,
        dpi: Int,
        outDir: File,
        baseName: String,
        rasterizer: PageRasterizer,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<File> {
        require(pages.isNotEmpty()) { "At least one page is required" }
        require(dpi > 0) { "dpi must be positive" }
        val written = ArrayList<File>()
        var success = false
        try {
            if (!outDir.isDirectory && !outDir.mkdirs()) throw PdfError.Io(IOException("Cannot create $outDir"))
            pages.forEachIndexed { i, page ->
                if (isCancelled()) throw CancellationException("PDF to images cancelled")
                val bitmap = try {
                    rasterizer.render(input, page, dpi)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw PdfError.Corrupt(e)
                }
                val out = File(outDir, "${baseName}_p${page + 1}.${format.extension}")
                written += out
                try {
                    out.outputStream().buffered().use { stream ->
                        val ok = when (format) {
                            ImageFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                            ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        if (!ok) throw IOException("Could not encode page ${page + 1}")
                    }
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                } finally {
                    bitmap.recycle()
                }
                progress((i + 1).toFloat() / pages.size)
            }
            if (isCancelled()) throw CancellationException("PDF to images cancelled")
            success = true
            return written
        } finally {
            if (!success) written.forEach { it.delete() }
        }
    }
}
