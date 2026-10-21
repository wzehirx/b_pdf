package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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
class OrganizeOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    /** Page texts without whitespace: the extractor breaks the words of rotated pages across lines. */
    private fun texts(file: File): List<String> = TestPdfs.text(file).map { it.filterNot(Char::isWhitespace) }

    private fun rotations(file: File): List<Int> =
        PDDocument.load(file).use { doc -> (0 until doc.numberOfPages).map { doc.getPage(it).rotation } }

    @Test
    fun organize_reorderRotateDeleteDuplicate() {
        val input = TestPdfs.create(tmp.root, 3)
        val count = OrganizeOperation.run(
            input, listOf(PageEdit(2), PageEdit(0, rotation = 90), PageEdit(0)), out(),
        )
        assertEquals(3, count)
        assertEquals(listOf("Page3", "Page1", "Page1"), texts(out()))
        assertEquals(listOf(0, 90, 0), rotations(out()))
    }

    @Test
    fun omittedPagesAreDeleted() {
        val input = TestPdfs.create(tmp.root, 4)
        assertEquals(2, OrganizeOperation.run(input, listOf(PageEdit(3), PageEdit(1)), out()))
        assertEquals(listOf("Page4", "Page2"), texts(out()))
    }

    @Test
    fun rotationIsAddedToTheExistingOne_andNormalized() {
        val input = TestPdfs.create(tmp.root, 2)
        OrganizeOperation.run(input, listOf(PageEdit(0, 270), PageEdit(1, 90)), out("once.pdf"))
        OrganizeOperation.run(out("once.pdf"), listOf(PageEdit(0, 180), PageEdit(1, 270)), out("twice.pdf"))
        assertEquals(listOf(270, 90), rotations(out("once.pdf")))
        assertEquals(listOf(90, 0), rotations(out("twice.pdf")))
    }

    @Test
    fun duplicatesRotateIndependently() {
        val input = TestPdfs.create(tmp.root, 1)
        OrganizeOperation.run(input, listOf(PageEdit(0, 90), PageEdit(0), PageEdit(0, 180)), out())
        assertEquals(listOf(90, 0, 180), rotations(out()))
        assertEquals(listOf("Page1", "Page1", "Page1"), texts(out()))
    }

    @Test
    fun linksFollowTheirTargetPage_andStayValidAfterReorder() {
        val input = TestPdfs.create(tmp.root, 3)
        val linked = File(tmp.root, "linked.pdf")
        PDDocument.load(input).use { doc ->
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(10f, 10f, 100f, 30f)
            link.action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { page = doc.getPage(1) } }
            doc.getPage(0).annotations = listOf(link)
            doc.save(linked)
        }
        OrganizeOperation.run(linked, listOf(PageEdit(1), PageEdit(0)), out())
        PDDocument.load(out()).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val annotation = doc.getPage(1).annotations.single() as PDAnnotationLink
            val dest = (annotation.action as PDActionGoTo).destination as PDPageDestination
            assertEquals(0, dest.retrievePageNumber())
        }
    }

    @Test
    fun encryptedInput_withPassword_producesOpenOutput() {
        val input = TestPdfs.create(tmp.root, 2, password = "secret")
        OrganizeOperation.run(input, listOf(PageEdit(1), PageEdit(0)), out(), password = "secret")
        assertEquals(listOf("Page2", "Page1"), texts(out()))
    }

    @Test
    fun encryptedInput_errors() {
        val input = TestPdfs.create(tmp.root, 2, password = "secret")
        try {
            OrganizeOperation.run(input, listOf(PageEdit(0)), out())
            fail()
        } catch (_: PdfError.PasswordRequired) {
        }
        try {
            OrganizeOperation.run(input, listOf(PageEdit(0)), out(), password = "nope")
            fail()
        } catch (_: PdfError.WrongPassword) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun invalidEdits_areRejected_andLeaveNoOutput() {
        val input = TestPdfs.create(tmp.root, 3)
        val bad = listOf(
            emptyList(),
            listOf(PageEdit(3)),
            listOf(PageEdit(-1)),
            listOf(PageEdit(0, rotation = 45)),
        )
        for (edits in bad) {
            try {
                OrganizeOperation.run(input, edits, out())
                fail("expected IllegalArgumentException for $edits")
            } catch (_: IllegalArgumentException) {
            }
            assertFalse(out().exists())
        }
    }

    @Test
    fun cancellation_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            OrganizeOperation.run(input, listOf(PageEdit(0)), out(), isCancelled = { true })
            fail()
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun documentInformationIsCarriedOver() {
        val input = File(tmp.root, "info.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, 2)).use { doc ->
            doc.documentInformation.title = "Başlık ğüşıöç"
            doc.documentCatalog.language = "tr-TR"
            doc.save(input)
        }
        OrganizeOperation.run(input, listOf(PageEdit(1)), out())
        PDDocument.load(out()).use { doc ->
            assertEquals("Başlık ğüşıöç", doc.documentInformation.title)
            assertEquals("tr-TR", doc.documentCatalog.language)
        }
        assertTrue(out().length() > 0)
    }
}
