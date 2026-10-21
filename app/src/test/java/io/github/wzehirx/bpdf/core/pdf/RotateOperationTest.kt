package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
class RotateOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun out(name: String = "out.pdf") = File(tmp.root, name)

    private fun rotations(file: File): List<Int> =
        PDDocument.load(file).use { doc -> (0 until doc.numberOfPages).map { doc.getPage(it).rotation } }

    @Test
    fun rotateAll_90_setsEveryPage() {
        val input = TestPdfs.create(tmp.root, 3)
        assertEquals(3, RotateOperation.run(input, null, 90, out()))
        assertEquals(listOf(90, 90, 90), rotations(out()))
    }

    @Test
    fun rotateSelectedPages_onlyTouchesThose() {
        val input = TestPdfs.create(tmp.root, 3)
        RotateOperation.run(input, listOf(0, 2), 180, out())
        assertEquals(listOf(180, 0, 180), rotations(out()))
    }

    @Test
    fun rotatingTwice_addsAndNormalizesModulo360() {
        val input = TestPdfs.create(tmp.root, 3)
        RotateOperation.run(input, listOf(1), 270, out("once.pdf"))
        RotateOperation.run(out("once.pdf"), listOf(1), 270, out("twice.pdf"))
        assertEquals(listOf(0, 180, 0), rotations(out("twice.pdf")))
    }

    @Test
    fun existingRotation_isAddedTo() {
        val input = File(tmp.root, "rotated.pdf")
        PDDocument.load(TestPdfs.create(tmp.root, 2)).use { doc ->
            doc.getPage(0).rotation = 270
            doc.save(input)
        }
        RotateOperation.run(input, null, 180, out())
        assertEquals(listOf(90, 180), rotations(out()))
    }

    @Test
    fun invalidDegrees_throwIllegalArgument_andWriteNothing() {
        val input = TestPdfs.create(tmp.root, 2)
        for (bad in listOf(0, 45, 360, -90, 450)) {
            try {
                RotateOperation.run(input, null, bad, out())
                fail("expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(out().exists())
    }

    @Test
    fun invalidPages_throwIllegalArgument() {
        val input = TestPdfs.create(tmp.root, 2)
        for (bad in listOf(emptyList(), listOf(2), listOf(-1))) {
            try {
                RotateOperation.run(input, bad, 90, out())
                fail("expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(out().exists())
    }

    @Test
    fun encryptedInput_needsPassword_andOutputIsReadableWithoutOne() {
        val input = TestPdfs.create(tmp.root, 2, password = "secret")
        try {
            RotateOperation.run(input, null, 90, out())
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        RotateOperation.run(input, null, 90, out(), password = "secret")
        assertEquals(listOf(90, 90), rotations(out()))
    }

    @Test
    fun corruptInput_throwsCorrupt() {
        val input = File(tmp.root, "bad.pdf").apply { writeText("not a pdf") }
        try {
            RotateOperation.run(input, null, 90, out())
            fail("expected Corrupt")
        } catch (_: PdfError.Corrupt) {
        }
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val input = TestPdfs.create(tmp.root, 2)
        for (cancelAfter in 0..2) {
            var polls = 0
            try {
                RotateOperation.run(input, null, 90, out(), isCancelled = { polls++ >= cancelAfter })
                fail("expected CancellationException")
            } catch (_: CancellationException) {
            }
            assertFalse(out().exists())
        }
    }

    @Test
    fun progress_endsAtOne() {
        val input = TestPdfs.create(tmp.root, 2)
        var last = 0f
        RotateOperation.run(input, null, 90, out(), progress = { last = it })
        assertEquals(1f, last, 0f)
    }
}
