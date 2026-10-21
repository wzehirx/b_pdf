package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Draws one PDF page as a bitmap; the engine's seam for tests that cannot run [PdfRenderer]. */
interface PageRasterizer {
    /** Page [pageIndex] (0-based) of [file] drawn at [dpi] pixels per inch. The caller recycles the bitmap. */
    fun render(file: File, pageIndex: Int, dpi: Int): Bitmap
}

/**
 * [PageRasterizer] backed by [PdfRenderer]. It keeps the document open between calls (rendering a
 * whole file opens it once) and must be [close]d; it is not thread-safe. Pages are drawn on a white
 * background. A page that would need more than [MAX_PIXELS] pixels is drawn smaller so a huge page
 * cannot exhaust memory. Password-protected PDFs are not supported by [PdfRenderer].
 */
class AndroidPageRasterizer : PageRasterizer, Closeable {
    private var openPath: String? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
        val renderer = rendererFor(file)
        return renderer.openPage(pageIndex).use { page ->
            val (w, h) = sizeInPixels(page.width, page.height, dpi)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE) // PdfRenderer draws onto the bitmap as is; pages have no background
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                bitmap
            } catch (t: Throwable) {
                bitmap.recycle()
                throw t
            }
        }
    }

    private fun rendererFor(file: File): PdfRenderer {
        val path = file.absolutePath
        renderer?.let { if (openPath == path) return it }
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = try {
            PdfRenderer(fd)
        } catch (t: Throwable) {
            runCatching { fd.close() }
            throw t
        }
        descriptor = fd
        renderer = r
        openPath = path
        return r
    }

    override fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        descriptor = null
        openPath = null
    }

    companion object {
        /** Largest bitmap drawn, in pixels (about 96 MB as ARGB_8888). */
        const val MAX_PIXELS = 24_000_000L

        /** Pixel size of a page that is [widthPt] by [heightPt] points (1/72 inch) at [dpi], capped at [MAX_PIXELS]. */
        fun sizeInPixels(widthPt: Int, heightPt: Int, dpi: Int): Pair<Int, Int> {
            var scale = dpi / 72f
            val pixels = widthPt.toDouble() * scale * heightPt * scale
            if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels).toFloat()
            return (widthPt * scale).roundToInt().coerceAtLeast(1) to (heightPt * scale).roundToInt().coerceAtLeast(1)
        }
    }
}
