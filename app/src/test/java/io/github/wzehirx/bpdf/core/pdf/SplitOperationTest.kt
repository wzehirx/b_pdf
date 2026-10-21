package io.github.wzehirx.bpdf.core.pdf

import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.zipFiles
import org.junit.Assert.assertEquals
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
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
class SplitOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun outDir() = File(tmp.root, "out")

    @Test
    fun ranges_producesOneFilePerGroup() {
        val input = TestPdfs.create(tmp.root, 5, "P")
        val files = SplitOperation.run(input, SplitMode.Ranges("1-2,4"), outDir(), "doc")
        assertEquals(listOf("doc_1.pdf", "doc_2.pdf"), files.map { it.name })
        assertEquals(listOf("P1", "P2"), TestPdfs.text(files[0]))
        assertEquals(listOf("P4"), TestPdfs.text(files[1]))
    }

    @Test
    fun everyN_splitsIntoChunksWithShortRemainder() {
        val input = TestPdfs.create(tmp.root, 5, "P")
        val files = SplitOperation.run(input, SplitMode.EveryN(2), outDir(), "doc")
        assertEquals(listOf(2, 2, 1), files.map { TestPdfs.text(it).size })
        assertEquals(listOf("P5"), TestPdfs.text(files[2]))
    }

    @Test
    fun eachPage_producesOneFilePerPage() {
        val input = TestPdfs.create(tmp.root, 3, "P")
        val files = SplitOperation.run(input, SplitMode.EachPage, outDir(), "doc")
        assertEquals(3, files.size)
        assertEquals(listOf("doc_1.pdf", "doc_2.pdf", "doc_3.pdf"), files.map { it.name })
        assertEquals(listOf(listOf("P1"), listOf("P2"), listOf("P3")), files.map { TestPdfs.text(it) })
    }

    @Test
    fun invalidRange_throwsAndWritesNothing() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            SplitOperation.run(input, SplitMode.Ranges("2-9"), outDir(), "doc")
            fail("expected InvalidRange")
        } catch (e: PdfError.InvalidRange) {
            assertEquals(RangeErrorReason.OUT_OF_BOUNDS, e.reason)
        }
        assertTrue(outDir().listFiles().orEmpty().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun everyN_zero_throwsIllegalArgument() {
        SplitOperation.run(TestPdfs.create(tmp.root, 3), SplitMode.EveryN(0), outDir(), "doc")
    }

    @Test
    fun encryptedInput_usesPassword() {
        val locked = TestPdfs.create(tmp.root, 2, "L", password = "secret")
        val files = SplitOperation.run(locked, SplitMode.EachPage, outDir(), "doc", password = "secret")
        assertEquals(listOf(listOf("L1"), listOf("L2")), files.map { TestPdfs.text(it) })
        try {
            SplitOperation.run(locked, SplitMode.EachPage, File(tmp.root, "o2"), "doc")
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
    }

    @Test
    fun progress_endsAtOne() {
        val seen = mutableListOf<Float>()
        SplitOperation.run(TestPdfs.create(tmp.root, 3), SplitMode.EachPage, outDir(), "d", progress = { seen += it })
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun cancelledMidway_deletesPartialOutputs() {
        val input = TestPdfs.create(tmp.root, 4)
        var checks = 0
        try {
            SplitOperation.run(input, SplitMode.EachPage, outDir(), "d", isCancelled = { ++checks > 2 })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(outDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancelledAfterLastFile_deletesAllOutputs() {
        val input = TestPdfs.create(tmp.root, 2)
        var checks = 0
        try {
            SplitOperation.run(input, SplitMode.EachPage, outDir(), "d", isCancelled = { ++checks > 2 })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(outDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun corruptInput_throwsCorrupt() {
        val bad = File(tmp.root, "bad.pdf").apply { writeText("nope") }
        try {
            SplitOperation.run(bad, SplitMode.EachPage, outDir(), "d")
            fail("expected Corrupt")
        } catch (_: PdfError.Corrupt) {
        }
    }

    @Test
    fun zipFiles_containsAllEntriesByFileName() {
        val input = TestPdfs.create(tmp.root, 3, "P")
        val files = SplitOperation.run(input, SplitMode.EachPage, outDir(), "doc")
        val zip = File(tmp.root, "all.zip")
        zipFiles(files, zip)
        ZipFile(zip).use { z ->
            assertEquals(listOf("doc_1.pdf", "doc_2.pdf", "doc_3.pdf"), z.entries().toList().map { it.name })
            files.forEach { f ->
                assertEquals(f.length(), z.getEntry(f.name).size)
                assertTrue(z.getInputStream(z.getEntry(f.name)).readBytes().contentEquals(f.readBytes()))
            }
        }
    }
}
