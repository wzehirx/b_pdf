package io.github.wzehirx.bpdf.core.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
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
class SearchablePdfOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontLoader = { context.assets.open(Fonts.ASSET_PATH) }

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(context)

    private fun out() = File(tmp.root, "searchable.pdf")

    @Test
    fun recognizedText_isExtractableFromThePage_originalContentKept() {
        val input = TestPdfs.create(tmp.root, 3)
        val pages = listOf(PageText(0, "first line\nsecond line"), PageText(2, "Ayşe Çağlı okudu"))
        assertEquals(2, SearchablePdfOperation.run(input, pages, out(), fontLoader))
        val text = TestPdfs.text(out())
        assertEquals(3, text.size)
        assertTrue(text[0], text[0].contains("Page1") && text[0].contains("first line") && text[0].contains("second line"))
        assertEquals("Page2", text[1]) // not recognized: untouched
        assertTrue(text[2], text[2].contains("Ayşe Çağlı okudu"))
    }

    @Test
    fun textLayer_isInvisible_renderModeNeither() {
        val input = TestPdfs.create(tmp.root, 1)
        SearchablePdfOperation.run(input, listOf(PageText(0, "hidden words")), out(), fontLoader)
        PdfLoader.load(out()).use { doc ->
            val page = doc.getPage(0)
            val streams = page.contentStreams.asSequence().map { it.createInputStream().readBytes().decodeToString() }.toList()
            // "3 Tr" selects text rendering mode 3 (neither fill nor stroke).
            assertTrue(streams.joinToString("\n"), streams.any { Regex("(^|\\s)3\\s+Tr(\\s|$)").containsMatchIn(it) })
        }
    }

    @Test
    fun theTextOfEachPage_isOneBlock_inReadingOrder() {
        val input = TestPdfs.create(tmp.root, 1)
        SearchablePdfOperation.run(input, listOf(PageText(0, "alpha\nbeta\ngamma")), out(), fontLoader)
        val text = PdfLoader.load(out()).use { PDFTextStripper().getText(it) }
        assertTrue(text, text.indexOf("alpha") < text.indexOf("beta") && text.indexOf("beta") < text.indexOf("gamma"))
        PdfLoader.load(out()).use { doc ->
            val content = doc.getPage(0).contentStreams.asSequence().map { it.createInputStream().readBytes().decodeToString() }.joinToString("\n")
            // The fixture page has one text block of its own; the recognized text adds exactly one more (one block per page).
            assertEquals(2, Regex("(^|\\s)BT(\\s|$)").findAll(content).count())
            assertEquals(1, Regex("(^|\\s)3\\s+Tr(\\s|$)").findAll(content).count())
        }
    }

    @Test
    fun veryLongText_isScaledToFitThePage() {
        val input = TestPdfs.create(tmp.root, 1)
        val long = (1..200).joinToString("\n") { "line number $it with some words to make it wider than usual for a page" }
        SearchablePdfOperation.run(input, listOf(PageText(0, long)), out(), fontLoader)
        val text = TestPdfs.text(out()).single()
        assertTrue(text.contains("line number 200"))
    }

    @Test
    fun blankPages_getNoLayer_andCountIsReturned() {
        val input = TestPdfs.create(tmp.root, 2)
        assertEquals(0, SearchablePdfOperation.run(input, listOf(PageText(0, ""), PageText(1, "  \n ")), out(), fontLoader))
        assertEquals(listOf("Page1", "Page2"), TestPdfs.text(out()))
    }

    @Test
    fun encryptedInput_needsPassword_andOutputIsNotEncrypted() {
        val input = TestPdfs.create(tmp.root, 1, password = "pw")
        try {
            SearchablePdfOperation.run(input, listOf(PageText(0, "x")), out(), fontLoader)
            fail()
        } catch (_: PdfError.PasswordRequired) {
        }
        assertFalse(out().exists())
        SearchablePdfOperation.run(input, listOf(PageText(0, "hello")), out(), fontLoader, password = "pw")
        assertFalse(PdfLoader.isEncrypted(out()))
        assertTrue(TestPdfs.text(out()).single().contains("hello"))
    }

    @Test
    fun pageOutsideTheDocument_isRejected_withoutOutput() {
        val input = TestPdfs.create(tmp.root, 1)
        try {
            SearchablePdfOperation.run(input, listOf(PageText(1, "x")), out(), fontLoader)
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 2)
        var polls = 0
        try {
            SearchablePdfOperation.run(input, listOf(PageText(0, "a"), PageText(1, "b")), out(), fontLoader, isCancelled = { ++polls > 2 })
            fail()
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }
}
