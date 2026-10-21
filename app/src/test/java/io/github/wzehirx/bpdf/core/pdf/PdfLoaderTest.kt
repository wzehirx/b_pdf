package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())
    }

    @Test fun loadsThreePageFixture() {
        val f = TestPdfs.create(tmp.root, 3)
        assertEquals(3, PdfLoader.pageCount(f))
        PdfLoader.load(f).use { assertEquals(3, it.numberOfPages) }
        assertEquals(listOf("Page1", "Page2", "Page3"), TestPdfs.text(f))
    }

    @Test fun isEncrypted() {
        assertFalse(PdfLoader.isEncrypted(TestPdfs.create(tmp.root, 1)))
        assertTrue(PdfLoader.isEncrypted(TestPdfs.create(tmp.root, 1, password = "pw")))
    }

    @Test fun encryptedWithoutPassword_throwsPasswordRequired() {
        val f = TestPdfs.create(tmp.root, 2, password = "secret")
        try { PdfLoader.load(f); org.junit.Assert.fail() } catch (e: PdfError.PasswordRequired) { }
    }

    @Test fun wrongPassword_throwsWrongPassword() {
        val f = TestPdfs.create(tmp.root, 2, password = "secret")
        try { PdfLoader.load(f, "nope"); org.junit.Assert.fail() } catch (e: PdfError.WrongPassword) { }
    }

    @Test fun correctPassword_loads() {
        val f = TestPdfs.create(tmp.root, 2, password = "secret")
        assertEquals(2, PdfLoader.pageCount(f, "secret"))
    }

    @Test fun randomBytes_throwsCorrupt() {
        val f = tmp.newFile("junk.pdf").apply { writeBytes(ByteArray(2048) { (it * 31 + 7).toByte() }) }
        try { PdfLoader.load(f); org.junit.Assert.fail() } catch (e: PdfError.Corrupt) { }
    }

    @Test fun usesTempFileMemory() {
        val f = TestPdfs.create(tmp.root, 300)
        PdfLoader.load(f).use { assertEquals(300, it.numberOfPages) }
    }
}
