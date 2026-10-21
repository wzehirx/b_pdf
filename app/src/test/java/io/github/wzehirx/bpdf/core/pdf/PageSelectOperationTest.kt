package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
class PageSelectOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun out() = File(tmp.root, "out.pdf")

    @Test
    fun remove_dropsTheGivenPages() {
        val input = TestPdfs.create(tmp.root, 3)
        val remaining = PageSelectOperation.remove(input, listOf(1), out())
        assertEquals(2, remaining)
        assertEquals(listOf("Page1", "Page3"), TestPdfs.text(out()))
    }

    @Test
    fun remove_ignoresOrderAndDuplicates() {
        val input = TestPdfs.create(tmp.root, 5)
        PageSelectOperation.remove(input, listOf(3, 0, 3), out())
        assertEquals(listOf("Page2", "Page3", "Page5"), TestPdfs.text(out()))
    }

    @Test
    fun remove_allPages_throwsIllegalArgumentAndWritesNothing() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            PageSelectOperation.remove(input, listOf(0, 1, 2), out())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun remove_pageOutOfRange_throwsIllegalArgument() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            PageSelectOperation.remove(input, listOf(3), out())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun extract_keepsTheGivenOrder() {
        val input = TestPdfs.create(tmp.root, 3)
        val count = PageSelectOperation.extract(input, listOf(2, 0), out())
        assertEquals(2, count)
        assertEquals(listOf("Page3", "Page1"), TestPdfs.text(out()))
    }

    @Test
    fun extract_emptySelection_throwsIllegalArgument() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            PageSelectOperation.extract(input, emptyList(), out())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun encryptedInput_usesPassword_andOutputIsNotEncrypted() {
        val input = TestPdfs.create(tmp.root, 3, password = "secret")
        try {
            PageSelectOperation.extract(input, listOf(0), out())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        PageSelectOperation.extract(input, listOf(1), out(), password = "secret")
        assertEquals(listOf("Page2"), TestPdfs.text(out()))
    }

    @Test
    fun corruptInput_throwsCorrupt() {
        val input = File(tmp.root, "bad.pdf").apply { writeText("not a pdf") }
        try {
            PageSelectOperation.remove(input, listOf(0), out())
            fail("expected Corrupt")
        } catch (_: PdfError.Corrupt) {
        }
    }

    @Test
    fun cancelledBeforeSaving_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            PageSelectOperation.extract(input, listOf(0), out(), isCancelled = { true })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun cancelledAfterSaving_deletesOutput() {
        val input = TestPdfs.create(tmp.root, 3)
        var polls = 0
        try {
            PageSelectOperation.remove(input, listOf(0), out(), isCancelled = { ++polls > 2 })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun progress_endsAtOne() {
        val input = TestPdfs.create(tmp.root, 3)
        var last = 0f
        PageSelectOperation.extract(input, listOf(0, 1), out(), progress = { last = it })
        assertEquals(1f, last, 0f)
    }

    private fun titledFixture(pages: Int = 3): File {
        val input = File(tmp.root, "titled-${System.nanoTime()}.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, pages)).use { doc ->
            doc.documentInformation.title = "Title ğüşıöç"
            doc.documentInformation.author = "Someone"
            doc.documentCatalog.language = "tr-TR"
            doc.save(input)
        }
        return input
    }

    private fun assertInfoKept(file: File) = PDDocument.load(file).use { doc ->
        assertEquals("Title ğüşıöç", doc.documentInformation.title)
        assertEquals("Someone", doc.documentInformation.author)
        assertEquals("tr-TR", doc.documentCatalog.language)
    }

    @Test
    fun remove_keepsInfoAndLanguage() {
        PageSelectOperation.remove(titledFixture(), listOf(1), out())
        assertInfoKept(out())
    }

    @Test
    fun extract_keepsInfoAndLanguage() {
        PageSelectOperation.extract(titledFixture(), listOf(2, 0), out())
        assertInfoKept(out())
    }

    @Test
    fun remove_keepsOutlineEntriesOfKeptPages() {
        val input = File(tmp.root, "outlined.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, 3)).use { doc ->
            val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            for (i in 0..2) {
                val item = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
                item.title = "Chapter ${i + 1}"
                item.destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
                    .apply { page = doc.getPage(i) }
                outline.addLast(item)
            }
            doc.save(input)
        }
        PageSelectOperation.remove(input, listOf(1), out())
        PDDocument.load(out()).use { doc ->
            val items = generateSequence(doc.documentCatalog.documentOutline.firstChild) { it.nextSibling }.toList()
            assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), items.map { it.title })
            // The entry of the removed page loses its target instead of keeping that page alive.
            assertEquals(0, doc.pages.indexOf(items[0].findDestinationPage(doc)))
            assertEquals(null, items[1].findDestinationPage(doc))
            assertEquals(1, doc.pages.indexOf(items[2].findDestinationPage(doc)))
        }
    }

    /** Removing a heavy page must not leave its content in the file through links or the outline. */
    @Test
    fun remove_dropsReferencesToRemovedPages_soTheirContentIsGone() {
        val input = File(tmp.root, "heavy.pdf")
        PDDocument().use { doc ->
            repeat(3) { i ->
                val page = com.tom_roush.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(40f, 700f)
                    repeat(if (i == 2) 4000 else 1) { cs.showText("Heavy content line number $it with filler text"); cs.newLineAtOffset(0f, -1f) }
                    cs.endText()
                }
            }
            val link = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink()
            link.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(10f, 10f, 100f, 30f)
            link.destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
                .apply { page = doc.getPage(2) }
            doc.getPage(0).annotations = listOf(link)
            val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val item = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
            item.title = "Heavy"
            item.destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination()
                .apply { page = doc.getPage(2) }
            outline.addLast(item)
            doc.save(input)
        }
        PageSelectOperation.remove(input, listOf(2), out())
        assertTrue("output is ${out().length()} bytes, input ${input.length()}", out().length() < input.length() / 2)
        PDDocument.load(out()).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertTrue(doc.getPage(0).annotations.isEmpty())
        }
    }
}
