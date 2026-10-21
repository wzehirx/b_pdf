package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
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
class ImagesToPdfOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private var counter = 0

    private fun image(w: Int, h: Int, png: Boolean = false, color: Int = Color.RED): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val file = File(tmp.root, "img${counter++}.${if (png) "png" else "jpg"}")
        file.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun pageSizes(pdf: File): List<Pair<Float, Float>> =
        PdfLoader.load(pdf).use { doc -> doc.pages.map { it.mediaBox.width to it.mediaBox.height } }

    private fun out() = File(tmp.root, "out.pdf")

    @Test
    fun threeImages_makeThreePages() {
        val images = listOf(image(100, 50), image(50, 100, png = true), image(80, 80))
        ImagesToPdfOperation.run(images, ImagesToPdfOptions(), out())
        assertEquals(3, PdfLoader.pageCount(out()))
    }

    @Test
    fun fitImage_pageHasImageAspect() {
        ImagesToPdfOperation.run(listOf(image(400, 200)), ImagesToPdfOptions(pageSize = PageSizeOption.FIT_IMAGE), out())
        val (w, h) = pageSizes(out()).single()
        assertEquals(2f, w / h, 0.01f)
    }

    @Test
    fun a4Auto_landscapeImage_givesLandscapePage_portraitImage_givesPortrait() {
        ImagesToPdfOperation.run(listOf(image(400, 200), image(200, 400)), ImagesToPdfOptions(), out())
        val (landscape, portrait) = pageSizes(out())
        assertEquals(842f, landscape.first, 1f)
        assertEquals(595f, landscape.second, 1f)
        assertEquals(595f, portrait.first, 1f)
        assertEquals(842f, portrait.second, 1f)
    }

    @Test
    fun forcedOrientation_overridesImageShape() {
        ImagesToPdfOperation.run(
            listOf(image(400, 200)), ImagesToPdfOptions(pageSize = PageSizeOption.LETTER, orientation = Orientation.PORTRAIT), out(),
        )
        val (w, h) = pageSizes(out()).single()
        assertEquals(612f, w, 1f)
        assertEquals(792f, h, 1f)
    }

    @Test
    fun exifOrientation_isHonored() {
        val img = image(400, 200)
        ExifInterface(img.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        ImagesToPdfOperation.run(listOf(img), ImagesToPdfOptions(pageSize = PageSizeOption.FIT_IMAGE), out())
        val (w, h) = pageSizes(out()).single()
        assertEquals(0.5f, w / h, 0.01f)
    }

    @Test
    fun hugeImage_isDownsampledToThreeThousandPixels() {
        ImagesToPdfOperation.run(listOf(image(6000, 3000)), ImagesToPdfOptions(pageSize = PageSizeOption.FIT_IMAGE), out())
        val decoded = ImagesToPdfOperation.decode(image(6000, 3000), "x")
        try {
            assertEquals(3000, maxOf(decoded.width, decoded.height))
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun margin_shrinksNothingButAddsPaddingToFitImagePage() {
        ImagesToPdfOperation.run(
            listOf(image(300, 150)), ImagesToPdfOptions(pageSize = PageSizeOption.FIT_IMAGE, marginPt = 10f), out(),
        )
        val (w, h) = pageSizes(out()).single()
        // 300x150 px at 150 dpi = 144x72 pt, plus 10 pt on every side.
        assertEquals(164f, w, 0.5f)
        assertEquals(92f, h, 0.5f)
    }

    @Test
    fun undecodableImage_throwsUnreadableImage_andLeavesNoOutput() {
        val bad = File(tmp.root, "bad.jpg").apply { writeText("not an image") }
        try {
            ImagesToPdfOperation.run(listOf(image(10, 10), bad), ImagesToPdfOptions(), out())
            fail("expected UnreadableImage")
        } catch (e: PdfError.UnreadableImage) {
            assertEquals("bad.jpg", e.fileName)
        }
        assertFalse(out().exists())
    }

    @Test
    fun cancelled_throwsAndLeavesNoOutput() {
        var polls = 0
        try {
            ImagesToPdfOperation.run(
                listOf(image(10, 10), image(10, 10)), ImagesToPdfOptions(), out(), isCancelled = { ++polls > 1 },
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun progress_reachesOne() {
        val seen = mutableListOf<Float>()
        ImagesToPdfOperation.run(listOf(image(10, 10), image(10, 10)), ImagesToPdfOptions(), out(), progress = { seen += it })
        assertTrue(seen.toString(), seen.last() == 1f && seen == seen.sorted())
    }

    @Test
    fun noImages_isRejected() {
        try {
            ImagesToPdfOperation.run(emptyList(), ImagesToPdfOptions(), out())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
