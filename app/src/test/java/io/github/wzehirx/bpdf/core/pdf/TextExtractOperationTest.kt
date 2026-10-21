package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
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
class TextExtractOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(context)

    private fun blankPdf(pages: Int): File {
        val f = File(tmp.root, "blank-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            doc.save(f)
        }
        return f
    }

    @Test
    fun threePageFixture_givesTextPerPageInOrder() {
        val input = TestPdfs.create(tmp.root, 3, "Alpha")
        val pages = TextExtractOperation.run(input)
        assertEquals(listOf(0, 1, 2), pages.map { it.pageIndex })
        assertEquals(listOf("Alpha1", "Alpha2", "Alpha3"), pages.map { it.text.trim() })
        assertTrue(pages.none { it.isBlank })
    }

    @Test
    fun selectedPages_onlyThoseAreReturned_inRequestedOrderWithoutDuplicates() {
        val input = TestPdfs.create(tmp.root, 4, "P")
        val pages = TextExtractOperation.run(input, pages = listOf(2, 0, 2))
        assertEquals(listOf(2, 0), pages.map { it.pageIndex })
        assertEquals(listOf("P3", "P1"), pages.map { it.text.trim() })
    }

    @Test
    fun emptyPages_areFlaggedBlank() {
        val pages = TextExtractOperation.run(blankPdf(2))
        assertEquals(2, pages.size)
        assertTrue(pages.all { it.isBlank })
        assertTrue(pages.all { it.text.isEmpty() })
    }

    @Test
    fun mixedDocument_flagsOnlyTheBlankPage() {
        val f = File(tmp.root, "mixed.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Hello")
                cs.endText()
            }
            doc.save(f)
        }
        val pages = TextExtractOperation.run(f)
        assertEquals(listOf(true, false), pages.map { it.isBlank })
    }

    @Test
    fun encryptedInput_needsPassword() {
        val input = TestPdfs.create(tmp.root, 2, "Secret", password = "pw")
        try {
            TextExtractOperation.run(input)
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        try {
            TextExtractOperation.run(input, password = "nope")
            fail("expected WrongPassword")
        } catch (_: PdfError.WrongPassword) {
        }
        assertEquals(listOf("Secret1", "Secret2"), TextExtractOperation.run(input, password = "pw").map { it.text.trim() })
    }

    @Test
    fun pageOutsideDocument_isRejected() {
        val input = TestPdfs.create(tmp.root, 2)
        try {
            TextExtractOperation.run(input, pages = listOf(5))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun cancellation_stopsTheRun() {
        val input = TestPdfs.create(tmp.root, 3)
        var polls = 0
        try {
            TextExtractOperation.run(input, isCancelled = { ++polls > 1 })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun progress_reportsFractionsUpToOne() {
        val input = TestPdfs.create(tmp.root, 4)
        val seen = mutableListOf<Float>()
        TextExtractOperation.run(input, progress = { seen += it })
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f), seen)
    }

    @Test
    fun turkishText_roundTrips() {
        val turkish = "ğüşıöçİĞÜŞÖÇ"
        val f = File(tmp.root, "tr.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val font = Fonts.unicode(doc) { context.assets.open(Fonts.ASSET_PATH) }
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(font, 14f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText(turkish)
                cs.endText()
            }
            doc.save(f)
        }
        assertEquals(turkish, TextExtractOperation.run(f).single().text.trim())
    }

    @Test
    fun reader_readsPagesLazilyAndOutOfOrder() {
        val input = TestPdfs.create(tmp.root, 5, "R")
        PdfTextReader(input).use { reader ->
            assertEquals(5, reader.pageCount)
            assertEquals("R4", reader.text(3).trim())
            assertEquals("R1", reader.text(0).trim())
            assertEquals("R4", reader.text(3).trim())
        }
    }

    @Test
    fun toPlainText_putsAHeaderBeforeEachPage() {
        val pages = listOf(PageText(0, "one\n"), PageText(2, ""), PageText(3, "four"))
        val text = pages.toPlainText { "== Page $it ==" }
        assertEquals("== Page 1 ==\none\n\n== Page 3 ==\n\n== Page 4 ==\nfour\n", text)
    }

    @Test
    fun toPlainText_ofNothing_isEmpty() {
        assertFalse(emptyList<PageText>().toPlainText { "x" }.isNotEmpty())
    }
}
