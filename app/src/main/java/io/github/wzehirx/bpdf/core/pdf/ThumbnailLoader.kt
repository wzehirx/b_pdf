package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * Size of a page thumbnail that is [widthPx] wide and keeps the page's aspect ratio. A very tall
 * page is limited to four times the width (the width shrinks to keep the ratio) so a thumbnail
 * can never grow huge.
 */
fun targetSize(pageWidthPt: Float, pageHeightPt: Float, widthPx: Int): Pair<Int, Int> {
    require(widthPx > 0) { "widthPx must be positive" }
    if (pageWidthPt <= 0f || pageHeightPt <= 0f) return widthPx to widthPx
    val maxHeight = widthPx * 4
    val height = (widthPx * pageHeightPt / pageWidthPt).roundToInt().coerceAtLeast(1)
    if (height <= maxHeight) return widthPx to height
    return (maxHeight * pageWidthPt / pageHeightPt).roundToInt().coerceAtLeast(1) to maxHeight
}

/** One open document that can draw its pages as bitmaps; [render] is never called concurrently. */
interface PageBitmapSource : Closeable {
    val pageCount: Int

    /** Draws page [pageIndex] (0-based) as a bitmap [widthPx] wide. */
    fun render(pageIndex: Int, widthPx: Int): Bitmap
}

/**
 * Renders page thumbnails through [PdfRenderer] and keeps recent ones in an in-memory cache sized
 * by bitmap bytes. [PdfRenderer] is not thread-safe and allows one open page at a time, so all
 * rendering runs on [dispatcher] under one [Mutex]. A document is opened on first use and stays
 * open until [release] or [close]. Encrypted documents are decrypted to a copy under [cacheDir]
 * (using the password given to [render]) because [PdfRenderer] cannot open them.
 */
class ThumbnailLoader(
    private val cacheDir: File,
    maxCacheBytes: Int = defaultCacheBytes(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openSource: (file: File, password: String?, cacheDir: File) -> PageBitmapSource = ::openPdfRendererSource,
) : Closeable {
    private data class Key(val path: String, val pageIndex: Int, val widthPx: Int)

    private val cache = object : LruCache<Key, Bitmap>(maxCacheBytes.coerceAtLeast(1)) {
        // Evicted bitmaps are not recycled: a cell may still be drawing one.
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }
    private val mutex = Mutex()
    private val sources = HashMap<String, PageBitmapSource>() // guarded by mutex
    private val pendingRelease = ConcurrentLinkedQueue<String>()

    @Volatile
    private var closed = false

    /**
     * Thumbnail of page [pageIndex] (0-based) of [file], [widthPx] wide. Throws [PdfError] for a
     * missing or wrong [password] and an I/O or runtime exception for pages that cannot be drawn.
     */
    suspend fun render(file: File, pageIndex: Int, widthPx: Int, password: String? = null): Bitmap {
        val key = Key(file.absolutePath, pageIndex, widthPx)
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            mutex.withLock {
                try {
                    check(!closed) { "ThumbnailLoader is closed" }
                    cache.get(key) ?: run {
                        val source = sources.getOrPut(key.path) { openSource(file, password, cacheDir) }
                        source.render(pageIndex, widthPx).also { cache.put(key, it) }
                    }
                } finally {
                    drainLocked()
                }
            }
        }
    }

    /** Like [render], but null when the page cannot be drawn (the caller shows a placeholder). */
    suspend fun renderOrNull(file: File, pageIndex: Int, widthPx: Int, password: String? = null): Bitmap? =
        try {
            render(file, pageIndex, widthPx, password)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    /** Closes [file]'s document and forgets its thumbnails (call when the file is removed or replaced). */
    fun release(file: File) {
        val path = file.absolutePath
        pendingRelease.add(path)
        cache.snapshot().keys.filter { it.path == path }.forEach { cache.remove(it) }
        drainIfIdle()
    }

    /** Closes every document; a render in progress finishes first. The loader cannot be used afterwards. */
    override fun close() {
        closed = true
        cache.evictAll()
        drainIfIdle()
    }

    private fun drainIfIdle() {
        // A render in progress drains when it is done.
        if (mutex.tryLock()) {
            try {
                drainLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun drainLocked() {
        if (closed) {
            sources.values.forEach { runCatching { it.close() } }
            sources.clear()
            pendingRelease.clear()
            return
        }
        while (true) {
            val path = pendingRelease.poll() ?: break
            sources.remove(path)?.let { runCatching { it.close() } }
        }
    }

    companion object {
        /** About one eighth of the memory the app may use. */
        fun defaultCacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

private class PdfRendererSource(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val ownedCopy: File?,
) : PageBitmapSource {
    override val pageCount: Int get() = renderer.pageCount

    override fun render(pageIndex: Int, widthPx: Int): Bitmap =
        renderer.openPage(pageIndex).use { page ->
            val (w, h) = targetSize(page.width.toFloat(), page.height.toFloat(), widthPx)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE) // PdfRenderer draws onto the bitmap as is; pages have no background
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (t: Throwable) {
                bitmap.recycle()
                throw t
            }
        }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        ownedCopy?.delete()
    }

    companion object {
        fun open(file: File, ownedCopy: File? = null): PageBitmapSource {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (t: Throwable) {
                runCatching { descriptor.close() }
                throw t
            }
            return PdfRendererSource(descriptor, renderer, ownedCopy)
        }
    }
}

/** Opens [file] with [PdfRenderer]; a password-protected one is first decrypted to a copy in [cacheDir]. */
internal fun openPdfRendererSource(file: File, password: String?, cacheDir: File): PageBitmapSource {
    try {
        return PdfRendererSource.open(file)
    } catch (_: SecurityException) {
        // PdfRenderer refuses password-protected documents; decrypt below.
    }
    if (password == null) throw PdfError.PasswordRequired()
    cacheDir.mkdirs()
    val copy = File(cacheDir, "thumb-${UUID.randomUUID()}.pdf")
    try {
        PdfLoader.load(file, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(copy)
        }
        return PdfRendererSource.open(copy, ownedCopy = copy)
    } catch (t: Throwable) {
        copy.delete()
        throw t
    }
}
