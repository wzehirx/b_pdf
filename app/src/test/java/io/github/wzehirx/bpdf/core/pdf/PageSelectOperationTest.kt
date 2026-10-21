package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
