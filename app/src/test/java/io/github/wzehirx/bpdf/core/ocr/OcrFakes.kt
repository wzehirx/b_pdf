package io.github.wzehirx.bpdf.core.ocr

import android.graphics.Bitmap
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Draws a tiny bitmap per page and remembers which pages were asked for and which bitmaps it gave out. */
class FakeRasterizer(private val failOnPage: Int? = null) : PageRasterizer, Closeable {
    val rendered: MutableList<Int> = CopyOnWriteArrayList()
    val files: MutableList<File> = CopyOnWriteArrayList()
    val encryptedWhenRendered: MutableList<Boolean> = CopyOnWriteArrayList()
    val dpis: MutableList<Int> = CopyOnWriteArrayList()
    val bitmaps: MutableList<Bitmap> = CopyOnWriteArrayList()
    @Volatile var closed = false

    override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
        if (pageIndex == failOnPage) throw IllegalStateException("cannot draw")
        rendered += pageIndex
        files += file
        encryptedWhenRendered += io.github.wzehirx.bpdf.core.pdf.PdfLoader.isEncrypted(file)
        dpis += dpi
        return Bitmap.createBitmap(20 + pageIndex, 10, Bitmap.Config.ARGB_8888).also { bitmaps += it }
    }

    override fun close() {
        closed = true
    }
}

/** Reads "text of page N" from the width of the bitmap (20 + page index), so the order can be checked. */
class FakeOcrEngine(
    private val onRecognize: (callNumber: Int) -> Unit = {},
    private val textFor: (pageIndex: Int) -> String = { "text of page ${it + 1}" },
) : OcrEngine {
    val languages: MutableList<String> = CopyOnWriteArrayList()
    val recycledWhenCalled: MutableList<Boolean> = CopyOnWriteArrayList()
    @Volatile var stopped = false
    @Volatile var closed = false

    override fun recognize(bitmap: Bitmap, languages: String): String {
        check(!closed) { "engine was closed" }
        this.languages += languages
        recycledWhenCalled += bitmap.isRecycled
        val call = this.languages.size
        onRecognize(call)
        return textFor(bitmap.width - 20)
    }

    override fun stop() {
        stopped = true
    }

    override fun close() {
        closed = true
    }
}
