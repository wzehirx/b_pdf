package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** PdfRenderer cannot draw under Robolectric, so the sizing and the cache are tested with a fake source. */
@RunWith(RobolectricTestRunner::class)
class ThumbnailLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(val pageWidth: Float = 612f, val pageHeight: Float = 792f) : PageBitmapSource {
        override val pageCount = 10
        val renders = AtomicInteger()
        val concurrent = AtomicInteger()
        var maxConcurrent = 0
        var closed = false

        override fun render(pageIndex: Int, widthPx: Int): Bitmap {
            val now = concurrent.incrementAndGet()
            synchronized(this) { maxConcurrent = maxOf(maxConcurrent, now) }
            try {
                Thread.sleep(2)
                renders.incrementAndGet()
                val (w, h) = targetSize(pageWidth, pageHeight, widthPx)
                return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } finally {
                concurrent.decrementAndGet()
            }
        }

        override fun close() {
            closed = true
        }
    }

    private fun loader(source: FakeSource, maxBytes: Int = 10_000_000, opened: AtomicInteger = AtomicInteger()) =
        ThumbnailLoader(tmp.root, maxBytes, Dispatchers.IO) { _, _, _ -> opened.incrementAndGet(); source }

    private val file get() = File(tmp.root, "a.pdf")

    @Test
    fun downsamples() {
        // Letter page at 200 px wide keeps its ratio and never exceeds the requested width.
        assertEquals(200 to 259, targetSize(612f, 792f, 200))
        // Landscape page.
        assertEquals(200 to 113, targetSize(792f, 446f, 200))
        // A huge page is scaled down to the width, not rendered at its own size.
        val (w, h) = targetSize(14400f, 14400f, 160)
        assertEquals(160, w)
        assertEquals(160, h)
    }

    @Test
    fun targetSize_limitsExtremeAspectRatios_andHandlesBadPages() {
        val (w, h) = targetSize(100f, 100_000f, 200)
        assertEquals(800, h)
        assertTrue(w in 1..2)
        assertEquals(200 to 200, targetSize(0f, 0f, 200))
        assertEquals(200 to 1, targetSize(10_000f, 1f, 200))
    }

    @Test(expected = IllegalArgumentException::class)
    fun targetSize_rejectsNonPositiveWidth() {
        targetSize(612f, 792f, 0)
    }

    @Test
    fun cachesRenderedPages_perPageAndWidth() = runBlocking {
        val source = FakeSource()
        val opened = AtomicInteger()
        loader(source, opened = opened).use { loader ->
            val first = loader.render(file, 0, 100)
            assertSame(first, loader.render(file, 0, 100))
            assertEquals(1, source.renders.get())
            assertNotSame(first, loader.render(file, 1, 100))
            assertNotSame(first, loader.render(file, 0, 120))
            assertEquals(3, source.renders.get())
            assertEquals("one open document", 1, opened.get())
        }
    }

    @Test
    fun cacheIsSizedByBitmapBytes_andEvictsLeastRecentlyUsed() = runBlocking {
        val source = FakeSource()
        // A 100 px wide letter thumbnail is 100x129x4 = 51,600 bytes; room for two.
        loader(source, maxBytes = 110_000).use { loader ->
            val a = loader.render(file, 0, 100)
            loader.render(file, 1, 100)
            loader.render(file, 0, 100) // touch a: b is now the oldest
            loader.render(file, 2, 100) // evicts b
            assertSame(a, loader.render(file, 0, 100))
            assertEquals(3, source.renders.get())
            loader.render(file, 1, 100)
            assertEquals(4, source.renders.get())
        }
    }

    @Test
    fun rendersOnePageAtATime() = runBlocking {
        val source = FakeSource()
        loader(source).use { loader ->
            (0 until 8).map { i -> async(Dispatchers.Default) { loader.render(file, i, 80) } }.awaitAll()
        }
        assertEquals(8, source.renders.get())
        assertEquals(1, source.maxConcurrent)
    }

    @Test
    fun close_closesDocuments_andRefusesFurtherRenders() = runBlocking {
        val source = FakeSource()
        val loader = loader(source)
        loader.render(file, 0, 100)
        loader.close()
        assertTrue(source.closed)
        try {
            loader.render(file, 1, 100)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun release_closesThatDocument_andReopensOnDemand() = runBlocking {
        val source = FakeSource()
        val opened = AtomicInteger()
        loader(source, opened = opened).use { loader ->
            loader.render(file, 0, 100)
            loader.release(file)
            assertTrue(source.closed)
            loader.render(file, 0, 100) // cache was dropped too
            assertEquals(2, source.renders.get())
            assertEquals(2, opened.get())
        }
    }

    @Test
    fun renderOrNull_returnsNullWhenThePageCannotBeDrawn_andRethrowsCancellation() = runBlocking {
        val failing = ThumbnailLoader(tmp.root, 1_000_000, Dispatchers.IO) { _, _, _ -> throw java.io.IOException("bad") }
        failing.use { assertNull(it.renderOrNull(file, 0, 100)) }
        val missingPassword = ThumbnailLoader(tmp.root, 1_000_000, Dispatchers.IO) { _, _, _ -> throw PdfError.PasswordRequired() }
        missingPassword.use { assertNull(it.renderOrNull(file, 0, 100)) }
        assertFalse(file.exists())
    }
}
