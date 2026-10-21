package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.util.Random
import java.util.concurrent.CancellationException

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class CompressOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    /** A photo-like picture: smooth gradient plus noise, so lossless storage is large and JPEG is small. */
    private fun photo(w: Int, h: Int, alpha: Boolean = false): Bitmap {
        val random = Random(42)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = (x * 255 / w + random.nextInt(24)).coerceIn(0, 255)
            val g = (y * 255 / h + random.nextInt(24)).coerceIn(0, 255)
            val b = ((x + y) * 255 / (w + h) + random.nextInt(24)).coerceIn(0, 255)
            ((if (alpha) 0x80 else 0xFF) shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(alpha) }
    }

    /** One page per entry of [widthsPt]; page i shows the same lossless photo [photoPx] wide, drawn [widthsPt] points wide. */
    private fun photoPdf(photoPx: Int = 1600, drawnWidthPt: Float = 612f, pages: Int = 1, alpha: Boolean = false): File {
        val file = File(tmp.root, "photo-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            val bitmap = photo(photoPx, photoPx * 3 / 4, alpha)
            val image = LosslessFactory.createFromImage(doc, bitmap)
            bitmap.recycle()
            repeat(pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 0f, 100f, drawnWidthPt, drawnWidthPt * 3 / 4)
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun images(file: File): List<PDImageXObject> =
        PdfLoader.load(file).use { doc ->
            val found = ArrayList<PDImageXObject>()
            fun walk(resources: PDResources?) {
                resources ?: return
                resources.xObjectNames.forEach { name ->
                    when (val x = resources.getXObject(name)) {
                        is PDImageXObject -> found += x
                        is PDFormXObject -> walk(x.resources)
                        else -> Unit
                    }
                }
            }
            doc.pages.forEach { walk(it.resources) }
            found.map { it.also { img -> img.width } }
        }

    private fun filterOf(image: PDImageXObject) = image.cosObject.getDictionaryObject(COSName.FILTER).toString()

    @Test
    fun medium_shrinksPhotoPdf_andDownscalesToMaxDpi() {
        val input = photoPdf(photoPx = 1600) // 1600 px over 8.5 in = ~188 dpi
        val result = CompressOperation.run(input, CompressLevel.MEDIUM, out())
        assertEquals(input.length(), result.beforeBytes)
        assertEquals(out().length(), result.afterBytes)
        assertTrue("${result.afterBytes} < ${result.beforeBytes}", result.afterBytes < result.beforeBytes)
        assertTrue(result.savedPercent > 0)
        val image = images(out()).single()
        assertTrue(filterOf(image), filterOf(image).contains("DCT"))
        assertEquals(1275, image.width) // 150 dpi * 8.5 in
    }

    @Test
    fun highQualityLevels_orderBySize() {
        val input = photoPdf(photoPx = 1200)
        val low = CompressOperation.run(input, CompressLevel.LOW, out("low.pdf"))
        val high = CompressOperation.run(input, CompressLevel.HIGH, out("high.pdf"))
        assertTrue("${high.afterBytes} <= ${low.afterBytes}", high.afterBytes <= low.afterBytes)
        assertEquals(850, images(out("high.pdf")).single().width) // 100 dpi * 8.5 in
    }

    @Test
    fun lowResolutionPicture_isNotUpscaled() {
        val input = photoPdf(photoPx = 400, drawnWidthPt = 612f) // ~47 dpi
        CompressOperation.run(input, CompressLevel.MEDIUM, out())
        assertEquals(400, images(out()).single().width)
    }

    @Test
    fun pictureSharedByTwoPages_isReplacedOnBoth() {
        val input = photoPdf(photoPx = 1000, pages = 2)
        val result = CompressOperation.run(input, CompressLevel.MEDIUM, out())
        assertTrue(result.afterBytes < result.beforeBytes)
        val found = images(out())
        assertEquals(2, found.size)
        assertTrue(found.all { filterOf(it).contains("DCT") })
    }

    @Test
    fun pictureInsideFormXObject_isCompressed() {
        val input = File(tmp.root, "form.pdf")
        PDDocument().use { doc ->
            val bitmap = photo(1400, 1050)
            val image = LosslessFactory.createFromImage(doc, bitmap)
            bitmap.recycle()
            val form = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(doc)
            form.bBox = PDRectangle(0f, 0f, 612f, 459f)
            form.resources = PDResources()
            PDPageContentStream(doc, form).use { cs -> cs.drawImage(image, 0f, 0f, 612f, 459f) }
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.saveGraphicsState()
                cs.transform(Matrix(1f, 0f, 0f, 1f, 0f, 100f))
                cs.drawForm(form)
                cs.restoreGraphicsState()
            }
            doc.save(input)
        }
        val result = CompressOperation.run(input, CompressLevel.MEDIUM, out())
        assertTrue(result.afterBytes < result.beforeBytes)
        val image = images(out()).single()
        assertTrue(filterOf(image).contains("DCT"))
        assertEquals(1275, image.width)
    }

    @Test
    fun pictureWithTransparency_isLeftAlone() {
        val input = photoPdf(photoPx = 600, alpha = true)
        CompressOperation.run(input, CompressLevel.HIGH, out())
        val image = images(out()).single()
        assertFalse(filterOf(image), filterOf(image).contains("DCT"))
        assertEquals(600, image.width)
        assertTrue(image.softMask != null)
    }

    @Test
    fun textOnlyPdf_doesNotGrow_andReportsNoSaving() {
        val input = TestPdfs.create(tmp.root, 3)
        val result = CompressOperation.run(input, CompressLevel.HIGH, out())
        assertTrue(result.afterBytes <= result.beforeBytes)
        assertEquals(0, result.savedPercent)
        assertEquals(TestPdfs.text(input), TestPdfs.text(out()))
    }

    @Test
    fun removeMetadata_clearsInfoAndXmp() {
        val input = File(tmp.root, "meta.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, 1)).use { doc ->
            doc.documentInformation.title = "Secret title"
            doc.documentInformation.author = "Someone"
            doc.save(input)
        }
        CompressOperation.run(input, CompressLevel.LOW, out("kept.pdf"))
        PdfLoader.load(out("kept.pdf")).use { assertEquals("Secret title", it.documentInformation.title) }
        CompressOperation.run(input, CompressLevel.LOW, out("clean.pdf"), removeMetadata = true)
        PdfLoader.load(out("clean.pdf")).use {
            assertNull(it.documentInformation.title)
            assertNull(it.documentInformation.author)
            assertNull(it.documentCatalog.metadata)
        }
    }

    @Test
    fun encryptedInput_needsPassword_andOutputOpensWithoutOne() {
        val input = TestPdfs.create(tmp.root, 2, password = "secret")
        try {
            CompressOperation.run(input, CompressLevel.MEDIUM, out())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        assertFalse(out().exists())
        CompressOperation.run(input, CompressLevel.MEDIUM, out(), password = "secret")
        assertEquals(2, PdfLoader.pageCount(out()))
    }

    @Test
    fun corruptInput_throwsCorrupt_andLeavesNoOutput() {
        val input = File(tmp.root, "bad.pdf").apply { writeText("not a pdf") }
        try {
            CompressOperation.run(input, CompressLevel.MEDIUM, out())
            fail("expected Corrupt")
        } catch (_: PdfError.Corrupt) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val input = photoPdf(photoPx = 800)
        for (cancelAfter in 0..4) {
            var polls = 0
            try {
                CompressOperation.run(input, CompressLevel.MEDIUM, out(), isCancelled = { polls++ >= cancelAfter })
                fail("expected CancellationException after $cancelAfter polls")
            } catch (_: CancellationException) {
            }
            assertFalse(out().exists())
        }
    }

    @Test
    fun progress_endsAtOne_andNeverGoesBack() {
        val input = photoPdf(photoPx = 800, pages = 2)
        val seen = mutableListOf<Float>()
        CompressOperation.run(input, CompressLevel.MEDIUM, out(), progress = { seen += it })
        assertEquals(1f, seen.last(), 0f)
        assertEquals(seen.sorted(), seen)
    }
}
