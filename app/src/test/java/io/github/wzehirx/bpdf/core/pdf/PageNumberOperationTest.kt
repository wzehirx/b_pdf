package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
class PageNumberOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontLoader = { context.assets.open(Fonts.ASSET_PATH) }

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(context)

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    private fun run(input: File, options: PageNumberOptions, name: String = "out.pdf") =
        PageNumberOperation.run(input, options, out(name), fontLoader)

    /** Positions (x, y from the top, in points as the viewer sees them) of the first character of [first] on page 1. */
    private fun firstPosition(file: File, first: String): TextPosition {
        var found: TextPosition? = null
        PDDocument.load(file).use { doc ->
            object : PDFTextStripper() {
                init { endPage = 1 }
                override fun processTextPosition(text: TextPosition) {
                    if (text.unicode == first && found == null) found = text
                }
            }.getText(doc)
        }
        return found!!
    }

    @Test
    fun ofTotalFormat_numbersEachPage() {
        val input = TestPdfs.create(tmp.root, 3)
        assertEquals(3, run(input, PageNumberOptions(format = "{n} / {total}")))
        val pages = TestPdfs.text(out())
        assertTrue(pages[0], pages[0].contains("1 / 3"))
        assertTrue(pages[1], pages[1].contains("2 / 3"))
        assertTrue(pages[2], pages[2].contains("3 / 3"))
    }

    @Test
    fun pageNumbers_turkishText_roundTrips() {
        val input = TestPdfs.create(tmp.root, 2)
        run(input, PageNumberOptions(format = "Sayfa {n} / {total} · ğüşıöçİ"))
        val text = TestPdfs.text(out())[0]
        assertTrue(text, text.contains("Sayfa 1 / 2"))
        assertTrue(text, text.contains("ğüşıöçİ"))
    }

    @Test
    fun startAt_shiftsNumbers() {
        val input = TestPdfs.create(tmp.root, 3)
        run(input, PageNumberOptions(startAt = 5, format = "<{n}>"))
        val pages = TestPdfs.text(out())
        assertTrue(pages[0], pages[0].contains("<5>"))
        assertTrue(pages[1], pages[1].contains("<6>"))
        assertTrue(pages[2], pages[2].contains("<7>"))
    }

    @Test
    fun selectedPages_areNumberedFromStartAt_othersUntouched() {
        val input = TestPdfs.create(tmp.root, 4)
        assertEquals(2, run(input, PageNumberOptions(format = "#{n} of {total}", pages = listOf(1, 3))))
        val pages = TestPdfs.text(out())
        assertFalse(pages[0].contains("#"))
        assertTrue(pages[1], pages[1].contains("#1 of 2"))
        assertFalse(pages[2].contains("#"))
        assertTrue(pages[3], pages[3].contains("#2 of 2"))
    }

    @Test
    fun positions_putTheNumberInTheRightCorner() {
        val input = TestPdfs.create(tmp.root, 1) // Letter, 612 x 792
        val cases = mapOf(
            NumberPosition.BOTTOM_LEFT to (24f to 770f),
            NumberPosition.BOTTOM_RIGHT to (588f to 770f),
            NumberPosition.TOP_LEFT to (24f to 30f),
        )
        for ((position, expected) in cases) {
            run(input, PageNumberOptions(position = position, format = "Z"), "$position.pdf")
            val found = PDDocument.load(out("$position.pdf")).use { doc ->
                var hit: TextPosition? = null
                object : PDFTextStripper() {
                    init { endPage = 1 }
                    override fun processTextPosition(text: TextPosition) {
                        if (text.unicode == "Z") hit = text
                    }
                }.getText(doc)
                hit!!
            }
            val x = if (position == NumberPosition.BOTTOM_RIGHT) found.xDirAdj + found.width else found.xDirAdj
            assertEquals("$position x", expected.first, x, 2f)
            assertEquals("$position y", expected.second, found.yDirAdj, if (expected.second > 700f) 6f else 14f)
        }
    }

    @Test
    fun centerPosition_isCentered() {
        val input = TestPdfs.create(tmp.root, 1)
        run(input, PageNumberOptions(position = NumberPosition.TOP_CENTER, format = "Z"))
        val z = firstPosition(out(), "Z")
        assertTrue(z.yDirAdj < 60f)
        assertEquals(306f, z.xDirAdj + z.width / 2, 2f)
    }

    @Test
    fun rotatedPage_numberStaysUpright() {
        val input = File(tmp.root, "rot.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, 1)).use { doc ->
            doc.getPage(0).rotation = 270
            doc.save(input)
        }
        run(input, PageNumberOptions(format = "Z"))
        PDDocument.load(out()).use { doc ->
            var dir = -1f
            var y = 0f
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    if (text.unicode == "Z") { dir = text.dir; y = text.yDirAdj }
                }
            }.getText(doc)
            assertEquals(270f, dir, 0f) // turned back so it reads upright
            assertEquals(612f - 24f, y, 8f) // rotated page is 792 wide, 612 tall: bottom edge
        }
    }

    @Test
    fun formatTokens_areReplaced() {
        assertEquals("Page 2 of 9", PageNumberOperation.format("Page {n} of {total}", 2, 9))
        assertEquals("7", PageNumberOperation.format("{n}", 7, 9))
        assertEquals("literal", PageNumberOperation.format("literal", 1, 1))
    }

    @Test
    fun invalidArguments_throwIllegalArgument_andWriteNothing() {
        val input = TestPdfs.create(tmp.root, 2)
        val bad = listOf(
            PageNumberOptions(fontSize = 0f),
            PageNumberOptions(marginPt = -1f),
            PageNumberOptions(startAt = -1),
            PageNumberOptions(pages = emptyList()),
            PageNumberOptions(pages = listOf(2)),
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
    fun encryptedInput_needsPassword() {
        val input = TestPdfs.create(tmp.root, 1, password = "secret")
        try {
            run(input, PageNumberOptions())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        PageNumberOperation.run(input, PageNumberOptions(format = "N{n}"), out(), fontLoader, password = "secret")
        assertTrue(TestPdfs.text(out()).single().contains("N1"))
    }

    @Test
    fun corruptInput_throwsCorrupt() {
        val input = File(tmp.root, "bad.pdf").apply { writeText("not a pdf") }
        try {
            run(input, PageNumberOptions())
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
                PageNumberOperation.run(input, PageNumberOptions(), out(), fontLoader, isCancelled = { polls++ >= cancelAfter })
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
        PageNumberOperation.run(input, PageNumberOptions(), out(), fontLoader, progress = { last = it })
        assertEquals(1f, last, 0f)
    }
}
