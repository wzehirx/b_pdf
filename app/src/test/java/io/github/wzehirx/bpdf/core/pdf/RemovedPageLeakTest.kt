package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A user who removes a page must be able to trust that its content is gone from the file, whatever
 * still pointed at the page in the source. Page 2 of every fixture carries [MARKER] and is removed.
 */
@RunWith(RobolectricTestRunner::class)
class RemovedPageLeakTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private companion object {
        const val MARKER = "LEAKMARK-SECRET"
    }

    private fun out() = File(tmp.root, "out.pdf")

    private fun fixture(decorate: PDDocument.(removed: PDPage, kept: PDPage) -> Unit): File {
        val file = File(tmp.root, "leak-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            val texts = listOf("KEEPONE", MARKER, "KEEPTHREE")
            texts.forEach { text ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            doc.documentInformation.title = "Leak ğüşıöç"
            doc.documentCatalog.language = "tr-TR"
            doc.decorate(doc.getPage(1), doc.getPage(0))
            doc.save(file)
        }
        return file
    }

    private fun orphanPages(file: File): Int = PDDocument.load(file).use { doc ->
        val inTree = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<COSDictionary, Boolean>())
        (0 until doc.numberOfPages).forEach { inTree += doc.getPage(it).cosObject }
        doc.document.getObjectsByType(COSName.PAGE).count { (it.getObject() as? COSDictionary) !in inTree }
    }

    /** True when any (decoded) stream of the file contains [MARKER]. */
    private fun containsMarker(file: File): Boolean = PDDocument.load(file).use { doc ->
        doc.document.xrefTable.keys.any { key ->
            val stream = doc.document.getObjectFromPool(key).getObject() as? COSStream ?: return@any false
            stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }.contains(MARKER)
        }
    }

    /** Removes page 2 and asserts it is gone without the safety net having to rebuild the file. */
    private fun removeAndAssertGone(input: File, expectFallback: Boolean = false) {
        assertTrue("fixture must contain the marker", containsMarker(input))
        val before = PageSelectOperation.fallbackRebuilds.get()
        assertEquals(2, PageSelectOperation.remove(input, listOf(1), out()))
        assertEquals(0, orphanPages(out()))
        assertFalse("removed page content is still in the file", containsMarker(out()))
        assertEquals(
            if (expectFallback) before + 1 else before,
            PageSelectOperation.fallbackRebuilds.get(),
        )
        PDDocument.load(out()).use { doc ->
            assertEquals("Leak ğüşıöç", doc.documentInformation.title)
            assertEquals("tr-TR", doc.documentCatalog.language)
        }
    }

    private fun fit(page: PDPage) = PDPageFitDestination().apply { this.page = page }

    @Test
    fun namedDestinationInNamesTree() {
        val input = fixture { removed, kept ->
            val names = COSDictionary()
            val dests = COSDictionary()
            val array = COSArray().apply { add(COSString("secret")); add(fit(removed).cosObject) }
            dests.setItem(COSName.NAMES, array)
            names.setItem(COSName.DESTS, dests)
            documentCatalog.cosObject.setItem(COSName.NAMES, names)
            val link = PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 50f, 30f) }
            link.cosObject.setItem(COSName.DEST, COSString("secret"))
            kept.annotations = listOf(link)
        }
        removeAndAssertGone(input)
    }

    @Test
    fun namedDestinationInCatalogDestsDictionary() {
        val input = fixture { removed, _ ->
            val dests = COSDictionary()
            dests.setItem(COSName.getPDFName("secret"), fit(removed).cosObject)
            documentCatalog.cosObject.setItem(COSName.DESTS, dests)
        }
        removeAndAssertGone(input)
    }

    @Test
    fun acroFormFieldOnRemovedPage() {
        val input = fixture { removed, _ ->
            val form = PDAcroForm(this)
            documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "secretField"
            val widget = field.widgets[0]
            widget.rectangle = PDRectangle(50f, 600f, 100f, 20f)
            widget.page = removed
            removed.annotations = removed.annotations + widget
            form.fields = listOf(field)
        }
        removeAndAssertGone(input)
        PDDocument.load(out()).use { doc -> assertTrue(doc.documentCatalog.acroForm?.fields.orEmpty().isEmpty()) }
    }

    @Test
    fun openActionTargetingRemovedPage() {
        val input = fixture { removed, _ -> documentCatalog.openAction = fit(removed) }
        removeAndAssertGone(input)
        PDDocument.load(out()).use { doc ->
            assertEquals(null, doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction")))
        }
    }

    @Test
    fun threadBeadOnRemovedPage() {
        val input = fixture { removed, _ ->
            val bead = COSDictionary()
            bead.setItem(COSName.getPDFName("P"), removed.cosObject)
            bead.setItem(COSName.getPDFName("N"), bead)
            bead.setItem(COSName.getPDFName("V"), bead)
            bead.setItem(COSName.getPDFName("R"), COSArray().apply { repeat(4) { add(com.tom_roush.pdfbox.cos.COSInteger.get(it.toLong())) } })
            val thread = COSDictionary()
            thread.setItem(COSName.F, bead)
            documentCatalog.cosObject.setItem(COSName.getPDFName("Threads"), COSArray().apply { add(thread) })
        }
        removeAndAssertGone(input)
    }

    @Test
    fun structureObjectReferenceToAnnotationOnRemovedPage() {
        val input = fixture { removed, kept ->
            val note = PDAnnotationText().apply { rectangle = PDRectangle(10f, 10f, 30f, 30f) }
            note.cosObject.setItem(COSName.P, removed.cosObject)
            removed.annotations = removed.annotations + note
            val objr = COSDictionary()
            objr.setName(COSName.TYPE, "OBJR")
            objr.setItem(COSName.getPDFName("Obj"), note.cosObject)
            val root = com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot()
            root.cosObject.setItem(COSName.K, COSArray().apply { add(objr) })
            documentCatalog.structureTreeRoot = root
        }
        removeAndAssertGone(input)
    }

    @Test
    fun popupOfDroppedLink() {
        val input = fixture { removed, kept ->
            val link = PDAnnotationLink().apply { rectangle = PDRectangle(10f, 10f, 50f, 30f); destination = fit(removed) }
            val popup = PDAnnotationPopup().apply { rectangle = PDRectangle(60f, 10f, 100f, 40f) }
            popup.cosObject.setItem(COSName.PARENT, link.cosObject)
            kept.annotations = listOf(link, popup)
        }
        removeAndAssertGone(input)
    }

    @Test
    fun unknownReference_fallsBackToRebuildingFromTheKeptPages() {
        val input = fixture { removed, _ ->
            documentCatalog.cosObject.setItem(COSName.getPDFName("BpdfUnknownKey"), removed.cosObject)
        }
        removeAndAssertGone(input, expectFallback = true)
        assertEquals(listOf("KEEPONE", "KEEPTHREE"), TestPdfs.text(out()))
    }

    @Test
    fun unknownReferenceToPageWithoutType_isStillDetectedAndRebuilt() {
        // PDFBox puts /Type /Page back on every page it walks, so the stray copy has to be a
        // separate dictionary: no /Type, not in the page tree, but with a page-tree parent, a media
        // box and the removed page's contents (a page-like object that a type search cannot see).
        val input = fixture { removed, _ ->
            val stray = COSDictionary(removed.cosObject).apply { removeItem(COSName.TYPE) }
            documentCatalog.cosObject.setItem(COSName.getPDFName("BpdfUnknownKey"), stray)
        }
        removeAndAssertGone(input, expectFallback = true)
    }

    @Test
    fun nestedPageTreeWithInheritedBoxes_isNotMistakenForOrphanPages() {
        // Intermediate page-tree nodes carry /MediaBox and have a page-tree parent too; they are
        // not pages and must not make the safety net rebuild a perfectly clean output.
        val input = fixture { _, _ ->
            val root = documentCatalog.pages.cosObject
            val node = COSDictionary()
            node.setItem(COSName.TYPE, COSName.PAGES)
            node.setItem(COSName.KIDS, COSArray().apply { addAll(root.getDictionaryObject(COSName.KIDS) as COSArray) })
            node.setItem(COSName.COUNT, COSInteger.get(3))
            node.setItem(COSName.PARENT, root)
            node.setItem(COSName.MEDIA_BOX, PDRectangle.LETTER.cosArray)
            val kids = node.getDictionaryObject(COSName.KIDS) as COSArray
            for (i in 0 until kids.size()) (kids.getObject(i) as COSDictionary).setItem(COSName.PARENT, node)
            root.setItem(COSName.KIDS, COSArray().apply { add(node) })
        }
        removeAndAssertGone(input, expectFallback = false)
    }

    @Test
    fun xmpMetadataIsPreserved_inPlaceAndInFallback() {
        for (unknownKey in listOf(false, true)) {
            val input = fixture { removed, _ ->
                val xmp = PDMetadata(this)
                xmp.importXMPMetadata("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><!--bpdf-xmp-test--></x:xmpmeta>".toByteArray())
                documentCatalog.metadata = xmp
                if (unknownKey) documentCatalog.cosObject.setItem(COSName.getPDFName("BpdfUnknownKey"), removed.cosObject)
            }
            PageSelectOperation.remove(input, listOf(1), out())
            PDDocument.load(out()).use { doc ->
                val xmp = doc.documentCatalog.metadata
                assertNotNull("unknownKey=$unknownKey", xmp)
                assertTrue(String(xmp!!.createInputStream().use { it.readBytes() }).contains("bpdf-xmp-test"))
            }
        }
    }

    @Test
    fun taggedPdf_keepsStructureTree_withoutLeaking() {
        val input = fixture { removed, kept ->
            val root = com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot()
            documentCatalog.structureTreeRoot = root
            val keptElement = com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement("P", root)
            keptElement.page = kept
            val removedElement = com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement("P", root)
            removedElement.page = removed
            root.appendKid(keptElement)
            root.appendKid(removedElement)
            documentCatalog.markInfo = com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo().apply { isMarked = true }
        }
        removeAndAssertGone(input)
        PDDocument.load(out()).use { doc ->
            assertNotNull(doc.documentCatalog.structureTreeRoot)
            assertTrue(doc.documentCatalog.markInfo.isMarked)
        }
    }
}
