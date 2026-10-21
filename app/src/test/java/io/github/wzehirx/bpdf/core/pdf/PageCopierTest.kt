package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PageCopierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun link(doc: PDDocument, from: Int, to: Int, viaAction: Boolean = false) {
        val link = PDAnnotationLink()
        link.rectangle = PDRectangle(10f, 10f, 100f, 30f)
        val dest = PDPageFitDestination().apply { page = doc.getPage(to) }
        if (viaAction) link.action = PDActionGoTo().apply { destination = dest } else link.destination = dest
        link.cosObject.setItem(COSName.P, doc.getPage(from).cosObject)
        val page = doc.getPage(from)
        page.annotations = page.annotations + link
    }

    private fun linkedFixture(): File {
        val file = TestPdfs.create(tmp.root, 3)
        val out = File(tmp.root, "linked-${file.name}")
        PDDocument.load(file).use { doc ->
            link(doc, from = 0, to = 1)
            link(doc, from = 0, to = 2, viaAction = true)
            doc.save(out)
        }
        return out
    }

    private fun copy(input: File, pages: List<Int>): File {
        val out = File(tmp.root, "copy-${System.nanoTime()}.pdf")
        PdfLoader.load(input).use { source ->
            val target = PDDocument(MemoryUsageSetting.setupTempFileOnly())
            try {
                PageCopier.copyPages(source, pages, target)
                target.save(out)
            } finally {
                target.close()
            }
        }
        return out
    }

    private fun PDDocument.targetPage(annotation: PDAnnotationLink): Int {
        val dest = annotation.destination
            ?: (annotation.action as PDActionGoTo).destination
        return (dest as PDPageDestination).retrievePageNumber()
    }

    @Test
    fun linkToCopiedPage_isKeptAndPointsAtTheImportedPage() {
        val out = copy(linkedFixture(), listOf(0, 1))
        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val annotations = doc.getPage(0).annotations
            // The link to page 2 (copied) stays, the action link to page 3 (not copied) goes.
            assertEquals(1, annotations.size)
            assertEquals(1, doc.targetPage(annotations[0] as PDAnnotationLink))
            assertNull(annotations[0].cosObject.getDictionaryObject(COSName.P))
        }
    }

    @Test
    fun linkToCopiedPage_followsReorderedPages() {
        val out = copy(linkedFixture(), listOf(2, 1, 0))
        PDDocument.load(out).use { doc ->
            val annotations = doc.getPage(2).annotations
            assertEquals(2, annotations.size)
            val targets = annotations.map { doc.targetPage(it as PDAnnotationLink) }.sorted()
            assertEquals(listOf(0, 1), targets)
        }
    }

    @Test
    fun linkToPageOutsideTheCopy_isRemoved() {
        val out = copy(linkedFixture(), listOf(0))
        PDDocument.load(out).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertTrue(doc.getPage(0).annotations.isEmpty())
        }
    }

    @Test
    fun sourceAnnotationsAreLeftUntouched() {
        val input = linkedFixture()
        PdfLoader.load(input).use { source ->
            val target = PDDocument(MemoryUsageSetting.setupTempFileOnly())
            try {
                PageCopier.copyPages(source, listOf(0), target)
                val annotations = source.getPage(0).annotations
                assertEquals(2, annotations.size)
                annotations.forEach { assertNotNull(it.cosObject.getDictionaryObject(COSName.P)) }
                assertEquals(1, source.targetPage(annotations[0] as PDAnnotationLink))
                assertEquals(2, source.targetPage(annotations[1] as PDAnnotationLink))
            } finally {
                target.close()
            }
        }
    }
}
