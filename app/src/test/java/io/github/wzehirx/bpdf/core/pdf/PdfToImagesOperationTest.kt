package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CancellationException

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PdfToImagesOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRasterizer(private val failOnPage: Int? = null) : PageRasterizer {
        val calls = mutableListOf<Pair<Int, Int>>()
        val bitmaps = mutableListOf<Bitmap>()

        override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
            calls += pageIndex to dpi
            if (pageIndex == failOnPage) throw IllegalStateException("cannot draw")
            return Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE); bitmaps += this }
        }
    }

    private val input get() = File(tmp.root, "in.pdf")
    private fun outDir() = File(tmp.root, "out")

    @Test
    fun threePages_makeThreeNamedJpegFiles() {
        val files = PdfToImagesOperation.run(input, listOf(0, 1, 2), ImageFormat.JPEG, 150, outDir(), "doc", FakeRasterizer())
        assertEquals(listOf("doc_p1.jpg", "doc_p2.jpg", "doc_p3.jpg"), files.map { it.name })
        files.forEach {
            val head = it.readBytes().take(2)
            assertEquals(listOf(0xFF.toByte(), 0xD8.toByte()), head)
        }
    }

    @Test
    fun png_writesPngSignature() {
        val files = PdfToImagesOperation.run(input, listOf(0), ImageFormat.PNG, 150, outDir(), "doc", FakeRasterizer())
        assertEquals("doc_p1.png", files.single().name)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(signature, files.single().readBytes().copyOfRange(0, 8))
    }

    @Test
    fun selectedPages_keepTheirOwnNumbers_andDpiIsPassedThrough() {
        val rasterizer = FakeRasterizer()
        val files = PdfToImagesOperation.run(input, listOf(4, 1), ImageFormat.PNG, 300, outDir(), "doc", rasterizer)
        assertEquals(listOf("doc_p5.png", "doc_p2.png"), files.map { it.name })
        assertEquals(listOf(4 to 300, 1 to 300), rasterizer.calls)
    }

    @Test
    fun everyBitmapIsRecycled() {
        val rasterizer = FakeRasterizer()
        PdfToImagesOperation.run(input, listOf(0, 1), ImageFormat.JPEG, 72, outDir(), "d", rasterizer)
        assertTrue(rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun progress_reachesOne() {
        val seen = mutableListOf<Float>()
        PdfToImagesOperation.run(input, listOf(0, 1), ImageFormat.JPEG, 72, outDir(), "d", FakeRasterizer(), progress = { seen += it })
        assertEquals(listOf(0.5f, 1f), seen)
    }

    @Test
    fun cancelled_throwsAndDeletesWrittenFiles() {
        var polls = 0
        try {
            PdfToImagesOperation.run(
                input, listOf(0, 1, 2), ImageFormat.JPEG, 72, outDir(), "d", FakeRasterizer(), isCancelled = { ++polls > 1 },
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertTrue(outDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pageThatCannotBeDrawn_failsAsCorrupt_andDeletesWrittenFiles() {
        val rasterizer = FakeRasterizer(failOnPage = 1)
        try {
            PdfToImagesOperation.run(input, listOf(0, 1, 2), ImageFormat.JPEG, 72, outDir(), "d", rasterizer)
            fail("expected PdfError.Corrupt")
        } catch (e: PdfError.Corrupt) {
            assertTrue(e.cause is IllegalStateException)
        }
        assertTrue(outDir().listFiles().orEmpty().isEmpty())
        assertTrue(rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun noPages_isRejected() {
        try {
            PdfToImagesOperation.run(input, emptyList(), ImageFormat.JPEG, 72, outDir(), "d", FakeRasterizer())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(outDir().exists())
    }

    @Test
    fun rasterizerSize_scalesWithDpi_andIsCappedForHugePages() {
        assertEquals(595 to 842, AndroidPageRasterizer.sizeInPixels(595, 842, 72))
        assertEquals(1240 to 1754, AndroidPageRasterizer.sizeInPixels(595, 842, 150))
        val (w, h) = AndroidPageRasterizer.sizeInPixels(5000, 5000, 300)
        assertTrue("$w x $h", w.toLong() * h <= AndroidPageRasterizer.MAX_PIXELS + w + h)
        assertEquals(w, h)
    }
}
