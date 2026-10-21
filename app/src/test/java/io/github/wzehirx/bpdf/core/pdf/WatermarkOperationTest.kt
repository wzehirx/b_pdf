package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
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
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class WatermarkOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontLoader = { context.assets.open(Fonts.ASSET_PATH) }

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(context)

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    private fun run(input: File, options: WatermarkOptions, name: String = "out.pdf") =
        WatermarkOperation.run(input, options, out(name), fontLoader)

    @Test
    fun watermark_turkishText_roundTrips() {
        val input = TestPdfs.create(tmp.root, 1)
        run(input, WatermarkOptions("GİZLİ ŞİRKET ğüşıöç", rotation = 0f))
        val text = TestPdfs.text(out()).single()
        assertTrue(text, text.contains("GİZLİ ŞİRKET ğüşıöç"))
        assertTrue(text, text.contains("Page1")) // original content kept
    }

    @Test
    fun diagonalWatermark_keepsEveryCharacter() {
        // Slanted text is split into pieces by text extraction, but nothing is lost.
        val input = TestPdfs.create(tmp.root, 1)
        run(input, WatermarkOptions("GİZLİ ŞİRKET")) // default: 45 degrees
        val text = TestPdfs.text(out()).single().replace(Regex("\\s"), "")
        assertTrue(text, text.contains("GİZLİŞİRKET") || "GİZLİŞİRKET".all { it in text })
    }

    @Test
    fun onlySelectedPages_areWatermarked() {
        val input = TestPdfs.create(tmp.root, 3)
        assertEquals(2, run(input, WatermarkOptions("SECRET", rotation = 0f, pages = listOf(0, 2))))
        val pages = TestPdfs.text(out())
        assertTrue(pages[0].contains("SECRET"))
        assertFalse(pages[1].contains("SECRET"))
        assertTrue(pages[2].contains("SECRET"))
    }

    @Test
    fun everyPosition_drawsTheText_andTiledRepeatsIt() {
        val input = TestPdfs.create(tmp.root, 1)
        for (position in WatermarkPosition.entries) {
            run(input, WatermarkOptions("MARK", position = position, fontSize = 20f, rotation = 0f), "$position.pdf")
            val count = Regex("MARK").findAll(TestPdfs.text(out("$position.pdf")).single()).count()
            if (position == WatermarkPosition.TILED) assertTrue("$position $count", count > 4) else assertEquals("$position", 1, count)
        }
    }

    @Test
    fun opacity_isWrittenAsConstantAlpha_andColorAsRgb() {
        val input = TestPdfs.create(tmp.root, 1)
        run(input, WatermarkOptions("A", opacity = 0.25f, colorArgb = 0xFFC62828.toInt()))
        PdfLoader.load(out()).use { doc ->
            val resources = doc.getPage(0).resources
            val state = resources.getExtGState(resources.extGStateNames.first())
            assertEquals(0.25f, state.nonStrokingAlphaConstant, 0.001f)
        }
        // The stream sets the chosen colour (198, 40, 40 -> 0.776 0.157 0.157).
        val content = PdfLoader.load(out()).use { doc ->
            doc.getPage(0).contents.readBytes().toString(Charsets.ISO_8859_1)
        }
        assertTrue(content, content.contains("0.77647 0.15686 0.15686 rg"))
    }

    @Test
    fun rotatedPage_watermarkStaysUprightRelativeToViewer() {
        val input = File(tmp.root, "rot.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, 1)).use { doc ->
            doc.getPage(0).rotation = 90
            doc.save(input)
        }
        run(input, WatermarkOptions("ZQXJ", rotation = 0f))
        val dirs = mutableListOf<Float>()
        PDDocument.load(out()).use { doc ->
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    if (text.unicode.trim().isNotEmpty() && text.unicode in "ZQXJ") dirs += text.dir
                }
            }.getText(doc)
        }
        assertEquals(4, dirs.size)
        // On a page turned 90 degrees clockwise the text is turned 90 degrees the other way to read upright.
        assertTrue(dirs.toString(), dirs.all { it == 90f })
    }

    @Test
    fun invalidArguments_throwIllegalArgument_andWriteNothing() {
        val input = TestPdfs.create(tmp.root, 2)
        val bad = listOf(
            WatermarkOptions("  "),
            WatermarkOptions("x", fontSize = 0f),
            WatermarkOptions("x", pages = emptyList()),
            WatermarkOptions("x", pages = listOf(2)),
            WatermarkOptions("x", pages = listOf(-1)),
            WatermarkOptions("😀"), // nothing the font can draw
        )
        for (options in bad) {
            try {
                run(input, options)
                fail("expected IllegalArgumentException for $options")
            } catch (_: IllegalArgumentException) {
            }
            assertFalse(out().exists())
        }
    }

    @Test
    fun missingGlyphs_areSkipped_restIsDrawn() {
        val input = TestPdfs.create(tmp.root, 1)
        run(input, WatermarkOptions("OK😀 DONE"))
        assertTrue(TestPdfs.text(out()).single().contains("OK DONE"))
    }

    @Test
    fun encryptedInput_needsPassword_andOutputIsReadableWithoutOne() {
        val input = TestPdfs.create(tmp.root, 1, password = "secret")
        try {
            run(input, WatermarkOptions("X"))
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        WatermarkOperation.run(input, WatermarkOptions("X"), out(), fontLoader, password = "secret")
        assertTrue(TestPdfs.text(out()).single().contains("X"))
    }

    @Test
    fun corruptInput_throwsCorrupt() {
        val input = File(tmp.root, "bad.pdf").apply { writeText("not a pdf") }
        try {
            run(input, WatermarkOptions("X"))
            fail("expected Corrupt")
        } catch (_: PdfError.Corrupt) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 2)
        for (cancelAfter in 0..3) {
            var polls = 0
            try {
                WatermarkOperation.run(input, WatermarkOptions("X"), out(), fontLoader, isCancelled = { polls++ >= cancelAfter })
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }
            assertFalse(out().exists())
        }
    }

    @Test
    fun progress_endsAtOne() {
        val input = TestPdfs.create(tmp.root, 2)
        var last = 0f
        WatermarkOperation.run(input, WatermarkOptions("X"), out(), fontLoader, progress = { last = it })
        assertEquals(1f, last, 0f)
    }
}
