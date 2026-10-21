package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
class MetadataRepairOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    private fun metadata(title: String? = null) = PdfMetadata(
        title = title, author = null, subject = null, keywords = null, creator = null, producer = null,
        pageCount = 0, encrypted = false, pdfVersion = 1.7f,
    )

    // ---- metadata ----

    @Test
    fun read_reportsPagesEncryptionAndVersion() {
        val input = TestPdfs.create(tmp.root, 3)
        val md = MetadataOperation.read(input)
        assertEquals(3, md.pageCount)
        assertFalse(md.encrypted)
        assertTrue(md.pdfVersion >= 1.0f)
        assertNull(md.title)
    }

    @Test
    fun read_encryptedFile_needsPassword_andReportsEncrypted() {
        val input = TestPdfs.create(tmp.root, 2, password = "pw")
        try {
            MetadataOperation.read(input)
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        val md = MetadataOperation.read(input, "pw")
        assertTrue(md.encrypted)
        assertEquals(2, md.pageCount)
    }

    @Test
    fun write_thenRead_roundTripsAllFields_andKeepsPages() {
        val input = TestPdfs.create(tmp.root, 2)
        val edited = metadata("Report").copy(
            author = "Ada", subject = "Quarterly", keywords = "a, b", creator = "Me", producer = "b PDF",
        )
        MetadataOperation.write(input, edited, out())
        val md = MetadataOperation.read(out())
        assertEquals("Report", md.title)
        assertEquals("Ada", md.author)
        assertEquals("Quarterly", md.subject)
        assertEquals("a, b", md.keywords)
        assertEquals("Me", md.creator)
        assertEquals("b PDF", md.producer)
        assertEquals(listOf("Page1", "Page2"), TestPdfs.text(out()))
    }

    @Test
    fun metadata_turkishText_roundTrips() {
        val input = TestPdfs.create(tmp.root, 1)
        val text = "Şükrü Öğüt — İçerik"
        val edited = metadata(text).copy(author = "ğüşıöçİĞÜŞÖÇ", subject = text, keywords = "çay, ışık", creator = text)
        MetadataOperation.write(input, edited, out())
        val md = MetadataOperation.read(out())
        assertEquals(text, md.title)
        assertEquals("ğüşıöçİĞÜŞÖÇ", md.author)
        assertEquals(text, md.subject)
        assertEquals("çay, ışık", md.keywords)
        assertEquals(text, md.creator)
    }

    @Test
    fun write_blankField_removesIt() {
        val input = TestPdfs.create(tmp.root, 1)
        MetadataOperation.write(input, metadata("Old").copy(author = "Someone"), out("a.pdf"))
        MetadataOperation.write(out("a.pdf"), metadata("  ").copy(author = ""), out("b.pdf"))
        val md = MetadataOperation.read(out("b.pdf"))
        assertNull(md.title)
        assertNull(md.author)
    }

    @Test
    fun write_encryptedInput_usesPassword_andWritesAnUnencryptedCopy() {
        val input = TestPdfs.create(tmp.root, 2, password = "pw")
        try {
            MetadataOperation.write(input, metadata("Secret"), out())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        assertFalse(out().exists())
        MetadataOperation.write(input, metadata("Secret"), out(), password = "pw")
        val md = MetadataOperation.read(out())
        assertEquals("Secret", md.title)
        assertFalse(md.encrypted)
        assertEquals(listOf("Page1", "Page2"), TestPdfs.text(out()))
    }

    @Test
    fun write_cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 1)
        try {
            MetadataOperation.write(input, metadata("x"), out(), isCancelled = { true })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }

    // ---- repair ----

    private fun withTrailingGarbage(file: File): File =
        File(tmp.root, "garbage.pdf").also { it.writeBytes(file.readBytes() + ByteArray(4000) { (it % 251).toByte() }) }

    private fun withBrokenXrefOffset(file: File): File {
        val text = file.readBytes().toString(Charsets.ISO_8859_1)
        val i = text.lastIndexOf("startxref")
        val broken = text.substring(0, i) + "startxref\n999999\n%%EOF\n"
        return File(tmp.root, "brokenxref.pdf").also { it.writeBytes(broken.toByteArray(Charsets.ISO_8859_1)) }
    }

    private fun truncatedBeforeXref(file: File): File {
        val bytes = file.readBytes()
        val i = bytes.toString(Charsets.ISO_8859_1).lastIndexOf("\nxref")
        return File(tmp.root, "truncated.pdf").also { it.writeBytes(bytes.copyOf(i)) }
    }

    @Test
    fun repair_trailingGarbage_recoversAllPages() {
        val input = withTrailingGarbage(TestPdfs.create(tmp.root, 4))
        val result = RepairOperation.run(input, out())
        assertEquals(4, result.pagesRecovered)
        assertEquals(listOf("Page1", "Page2", "Page3", "Page4"), TestPdfs.text(out()))
        assertTrue(out().length() < input.length())
    }

    @Test
    fun repair_brokenXrefOffset_recoversAllPages() {
        val input = withBrokenXrefOffset(TestPdfs.create(tmp.root, 3))
        val result = RepairOperation.run(input, out())
        assertEquals(3, result.pagesRecovered)
        assertEquals(listOf("Page1", "Page2", "Page3"), TestPdfs.text(out()))
        // The rewritten file has a correct cross-reference table: it parses without any rebuilding.
        val bytes = out().readBytes().toString(Charsets.ISO_8859_1)
        assertNotEquals("999999", bytes.substringAfterLast("startxref").trim().substringBefore("\n").trim())
    }

    @Test
    fun repair_truncatedFileWithoutXref_recoversAllPages() {
        val input = truncatedBeforeXref(TestPdfs.create(tmp.root, 3))
        val result = RepairOperation.run(input, out())
        assertEquals(3, result.pagesRecovered)
        assertEquals(listOf("Page1", "Page2", "Page3"), TestPdfs.text(out()))
    }

    @Test
    fun repair_hopelessFile_throwsCorrupt_andLeavesNoOutput() {
        val input = File(tmp.root, "junk.pdf").apply { writeText("this is not a pdf at all") }
        try {
            RepairOperation.run(input, out())
            fail("expected Corrupt")
        } catch (_: PdfError.Corrupt) {
        }
        assertFalse(out().exists())
    }

    @Test
    fun repair_encryptedInput_usesPassword() {
        val input = TestPdfs.create(tmp.root, 2, password = "pw")
        try {
            RepairOperation.run(input, out())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        assertEquals(2, RepairOperation.run(input, out(), password = "pw").pagesRecovered)
    }

    @Test
    fun repair_cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 1)
        try {
            RepairOperation.run(input, out(), isCancelled = { true })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertFalse(out().exists())
    }
}
