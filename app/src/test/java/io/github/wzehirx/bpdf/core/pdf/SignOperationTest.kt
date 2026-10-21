package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import java.time.LocalDate
import java.util.concurrent.CancellationException

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SignOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontLoader = { context.assets.open(Fonts.ASSET_PATH) }

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(context)

    private fun out() = File(tmp.root, "signed.pdf")

    private fun png(): File {
        val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        for (x in 10 until 190) for (y in 30 until 34) bitmap.setPixel(x, y, Color.BLUE)
        val file = File(tmp.root, "sig.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun imageCount(pdf: File, page: Int): Int = PdfLoader.load(pdf).use { doc ->
        val resources = doc.getPage(page).resources ?: return@use 0
        resources.xObjectNames.count { resources.isImageXObject(it) }
    }

    @Test
    fun sign_turkishText_roundTrips() {
        val input = TestPdfs.create(tmp.root, 3)
        SignOperation.run(input, SignatureSource.Typed("Ayşe Çağlı"), SignOptions(page = 1), out(), fontLoader)
        val pages = TestPdfs.text(out())
        assertTrue(pages[1], pages[1].contains("Ayşe Çağlı"))
        assertTrue(pages[1], pages[1].contains("Page2")) // original content kept
        assertFalse(pages[0].contains("Ayşe"))
        assertFalse(pages[2].contains("Ayşe"))
    }

    @Test
    fun sign_withDate_printsTheDate() {
        val input = TestPdfs.create(tmp.root, 1)
        SignOperation.run(
            input, SignatureSource.Typed("Ali"), SignOptions(page = 0, addDate = true), out(), fontLoader,
            today = { LocalDate.of(2026, 10, 21) },
        )
        val text = TestPdfs.text(out()).single()
        assertTrue(text, text.contains("Ali"))
        assertTrue(text, text.contains("2026-10-21"))
    }

    @Test
    fun imageSignature_addsAnImageToTheTargetPageOnly() {
        val input = TestPdfs.create(tmp.root, 2)
        SignOperation.run(input, SignatureSource.Image(png()), SignOptions(page = 1), out(), fontLoader)
        assertEquals(0, imageCount(out(), 0))
        assertEquals(1, imageCount(out(), 1))
        assertEquals(listOf("Page1", "Page2"), TestPdfs.text(out()))
    }

    @Test
    fun everyPosition_staysInsideThePage() {
        val input = TestPdfs.create(tmp.root, 1)
        for (position in SignPosition.entries) {
            SignOperation.run(input, SignatureSource.Typed("Ayşe"), SignOptions(page = 0, position = position), out(), fontLoader)
            PdfLoader.load(out()).use { doc ->
                val page = doc.getPage(0)
                val stripper = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                    val xs = ArrayList<Pair<Float, Float>>()
                    override fun processTextPosition(text: com.tom_roush.pdfbox.text.TextPosition) {
                        xs += (text.x + text.width) to text.y
                    }
                }
                stripper.getText(doc)
                val w = page.mediaBox.width
                val h = page.mediaBox.height
                assertTrue(position.name, stripper.xs.isNotEmpty() && stripper.xs.all { (x, y) -> x in 0f..w && y in 0f..h })
            }
        }
    }

    @Test
    fun rightPositions_areRightOfLeftPositions() {
        val input = TestPdfs.create(tmp.root, 1)
        fun firstX(position: SignPosition): Float {
            SignOperation.run(input, SignatureSource.Typed("Zed"), SignOptions(page = 0, position = position), out(), fontLoader)
            return PdfLoader.load(out()).use { doc ->
                var x = -1f
                object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                    override fun processTextPosition(text: com.tom_roush.pdfbox.text.TextPosition) {
                        if (text.unicode == "Z") x = text.x
                    }
                }.getText(doc)
                x
            }
        }
        assertTrue(firstX(SignPosition.BOTTOM_LEFT) < firstX(SignPosition.BOTTOM_CENTER))
        assertTrue(firstX(SignPosition.BOTTOM_CENTER) < firstX(SignPosition.BOTTOM_RIGHT))
    }

    @Test
    fun encryptedInput_needsPassword_andOutputIsNotEncrypted() {
        val input = TestPdfs.create(tmp.root, 1, password = "pw")
        try {
            SignOperation.run(input, SignatureSource.Typed("A"), SignOptions(page = 0), out(), fontLoader)
            fail()
        } catch (_: PdfError.PasswordRequired) {
        }
        assertFalse(out().exists())
        SignOperation.run(input, SignatureSource.Typed("A"), SignOptions(page = 0), out(), fontLoader, password = "pw")
        assertFalse(PdfLoader.isEncrypted(out()))
    }

    @Test
    fun invalidInput_isRejected_andLeavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 2)
        fun rejects(source: SignatureSource, options: SignOptions) {
            try {
                SignOperation.run(input, source, options, out(), fontLoader)
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
            assertFalse(out().exists())
        }
        rejects(SignatureSource.Typed("   "), SignOptions(page = 0))
        rejects(SignatureSource.Typed("A"), SignOptions(page = 2))
        rejects(SignatureSource.Typed("A"), SignOptions(page = -1))
        rejects(SignatureSource.Typed("A"), SignOptions(page = 0, widthPt = 0f))
        rejects(SignatureSource.Image(File(tmp.root, "missing.png")), SignOptions(page = 0))
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 1)
        try {
            SignOperation.run(input, SignatureSource.Typed("A"), SignOptions(page = 0), out(), fontLoader, isCancelled = { true })
            fail()
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }
}
