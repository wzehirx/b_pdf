package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MergeOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    @Test
    fun merge_threeFiles_pageCountIsSum_andOrderKept() {
        val a = TestPdfs.create(tmp.root, 1, "A")
        val b = TestPdfs.create(tmp.root, 2, "B")
        val c = TestPdfs.create(tmp.root, 1, "C")
        val out = File(tmp.root, "out.pdf")
        val pages = MergeOperation.run(listOf(a, b, c), out)
        assertEquals(4, pages)
        assertEquals(listOf("A1", "B1", "B2", "C1"), TestPdfs.text(out))
    }

    @Test
    fun merge_reportsProgressUpToOne() {
        val seen = mutableListOf<Float>()
        MergeOperation.run(
            listOf(TestPdfs.create(tmp.root, 1), TestPdfs.create(tmp.root, 1)),
            File(tmp.root, "out.pdf"),
            progress = { seen += it },
        )
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (x, y) -> x <= y })
    }

    @Test(expected = IllegalArgumentException::class)
    fun merge_singleFile_throwsIllegalArgument() {
        MergeOperation.run(listOf(TestPdfs.create(tmp.root, 1)), File(tmp.root, "out.pdf"))
    }

    @Test
    fun merge_encryptedInput_usesSuppliedPassword() {
        val plain = TestPdfs.create(tmp.root, 1, "P")
        val locked = TestPdfs.create(tmp.root, 1, "L", password = "secret")
        val out = File(tmp.root, "out.pdf")
        MergeOperation.run(listOf(plain, locked), out, passwords = mapOf(locked to "secret"))
        assertEquals(listOf("P1", "L1"), TestPdfs.text(out))
    }

    @Test
    fun merge_encryptedInputWithoutPassword_throwsPasswordRequired() {
        val plain = TestPdfs.create(tmp.root, 1)
        val locked = TestPdfs.create(tmp.root, 1, password = "secret")
        try {
            MergeOperation.run(listOf(plain, locked), File(tmp.root, "out.pdf"))
            org.junit.Assert.fail("expected PasswordRequired")
        } catch (e: PdfError.PasswordRequired) {
            // expected
        }
    }
}
