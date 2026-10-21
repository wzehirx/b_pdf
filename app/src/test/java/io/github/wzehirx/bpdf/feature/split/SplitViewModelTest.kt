package io.github.wzehirx.bpdf.feature.split

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.work.OperationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SplitViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var vm: SplitViewModel

    @Before
    fun setUp() {
        // FileProvider caches its root map per authority in a static; each Robolectric test has a fresh cache dir.
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SplitViewModel(repo, dispatcher) as T
        })[SplitViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun pick(pages: Int, name: String = "book.pdf") {
        vm.pickFile(listOf(pdf(pages, name)))
        await { vm.state.first { !it.importing && it.file?.displayName == name } }
    }

    // The combined state is republished asynchronously after an update from another thread.
    private fun settled(condition: (SplitUiState) -> Boolean): SplitUiState = await { vm.state.first(condition) }

    private fun splitAndAwait(): OperationState<SplitResult> {
        vm.split()
        return await { vm.state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
    }

    private fun success() = (splitAndAwait() as OperationState.Success).value

    @Test
    fun pickFile_loadsNameAndPageCount() {
        pick(5)
        assertEquals("book.pdf", vm.state.value.file?.displayName)
        assertEquals(5, vm.state.value.pageCount)
    }

    @Test
    fun pickFile_corrupt_failsAndKeepsNoFile() {
        val bad = File(dir, "bad.pdf").apply { writeText("nope") }
        vm.pickFile(listOf(Uri.fromFile(bad)))
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is PdfError.Corrupt)
        assertNull(vm.state.value.file)
        assertEquals(0, File(context.cacheDir, "imports").listFiles().orEmpty().size)
    }

    @Test
    fun pickingAnotherFile_replacesAndDeletesOldCopy() {
        pick(3, "a.pdf")
        val old = vm.state.value.file!!.file
        pick(4, "b.pdf")
        assertFalse(old.exists())
        assertEquals(4, vm.state.value.pageCount)
        assertEquals("b.pdf", vm.state.value.file?.displayName)
    }

    @Test
    fun split_withoutFile_doesNothing() {
        vm.split()
        settled { it.operation == OperationState.Idle }
    }

    @Test
    fun ranges_success_listsOneFilePerRange() {
        pick(5)
        vm.setRangeText("1-2,4")
        val result = success()
        assertEquals(2, result.files.size)
        assertEquals(listOf("book_1.pdf", "book_2.pdf"), result.files.map { it.name })
        assertEquals("book_split.zip", result.zipName)
        assertEquals(listOf("P1", "P2"), TestPdfs.text(result.files[0]))
    }

    @Test
    fun ranges_invalid_blocksSplitAndShowsSpecificError() {
        pick(5)
        vm.setRangeText("5-2")
        vm.split()
        val s = settled { it.failedAttempts == 1 && it.rangeError != null }
        assertEquals(OperationState.Idle, s.operation)
        assertEquals(RangeErrorReason.REVERSED, s.rangeError?.reason)
        assertEquals(1, s.failedAttempts)
        assertEquals(0, File(context.cacheDir, "shared").listFiles().orEmpty().size)
    }

    @Test
    fun ranges_outOfBoundsAndLiveValidation() {
        pick(3)
        vm.setRangeText("1-9")
        assertEquals(RangeErrorReason.OUT_OF_BOUNDS, settled { it.rangeError != null }.rangeError?.reason)
        vm.setRangeText("1-3")
        assertNull(settled { it.rangeText == "1-3" }.rangeError)
    }

    @Test
    fun ranges_blank_showsNoErrorUntilSplitAttempted() {
        pick(3)
        vm.split()
        vm.split()
        val s = settled { it.rangeError != null }
        assertEquals(RangeErrorReason.EMPTY, s.rangeError?.reason)
        assertEquals(OperationState.Idle, s.operation)
    }

    @Test
    fun everyN_success_chunks() {
        pick(5)
        vm.setMode(SplitModeChoice.EVERY_N)
        vm.setEveryNText("2")
        assertEquals(3, success().files.size)
    }

    @Test
    fun everyN_invalidValues_blockSplit() {
        pick(5)
        vm.setMode(SplitModeChoice.EVERY_N)
        for (bad in listOf("", "0", "6", "x", "-1", "2.5")) {
            vm.setEveryNText(bad)
            vm.split()
            val s = settled { it.everyNText == bad && it.everyNError != null }
            assertEquals(bad, OperationState.Idle, s.operation)
            assertEquals(bad, if (bad.isEmpty()) EveryNError.EMPTY else EveryNError.INVALID, s.everyNError)
        }
        vm.setEveryNText("5")
        assertNull(settled { it.everyNText == "5" }.everyNError)
    }

    @Test
    fun eachPage_success_oneFilePerPage() {
        pick(3)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        assertEquals(3, success().files.size)
    }

    @Test
    fun changingOptions_afterSuccess_clearsResultAndDeletesOutput() {
        pick(3)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        val result = success()
        vm.setMode(SplitModeChoice.EVERY_N)
        assertEquals(OperationState.Idle, settled { it.mode == SplitModeChoice.EVERY_N }.operation)
        assertFalse(result.outDir.exists())
    }

    @Test
    fun splittingAgain_deletesPreviousOutput() {
        pick(2)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        val first = success()
        val second = success()
        assertFalse(first.outDir.exists())
        assertTrue(second.outDir.exists())
    }

    @Test
    fun failedSplit_leavesNoOutput() {
        pick(3)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.setMode(SplitModeChoice.EACH_PAGE)
        val op = splitAndAwait()
        assertTrue(op is OperationState.Failed)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun saveZip_writesAllEntries_emitsSavedAndCleansTemp() {
        pick(3)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        success()
        val target = File(dir, "out.zip")
        vm.saveZip(Uri.fromFile(target))
        assertEquals(3, await { vm.savedEvents.first() })
        ZipFile(target).use { z -> assertEquals(listOf("book_1.pdf", "book_2.pdf", "book_3.pdf"), z.entries().toList().map { it.name }) }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().none { it.extension == "zip" })
    }

    @Test
    fun prepareShare_returnsUriPerFile() {
        assertTrue(await { vm.prepareShare() }.isEmpty())
        pick(3)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        success()
        assertEquals(listOf("book_1.pdf", "book_2.pdf", "book_3.pdf"), await { vm.prepareShare() }.map { it.lastPathSegment })
    }

    @Test
    fun remove_deletesCopyAndResetsState() {
        pick(3)
        val copy = vm.state.value.file!!.file
        vm.remove()
        assertFalse(copy.exists())
        val s = settled { it.file == null }
        assertEquals(0, s.pageCount)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        pick(3)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        val copy = vm.state.value.file!!.file
        val out = success().outDir
        store.clear()
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }

    @Test
    fun whileRunning_changesAreIgnored() {
        val gate = StandardTestDispatcher()
        val slow = SplitViewModel(repo, gate)
        slow.pickFile(listOf(pdf(3, "a.pdf")))
        await { slow.state.first { !it.importing && it.file != null } }
        slow.setMode(SplitModeChoice.EACH_PAGE)
        slow.split()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setMode(SplitModeChoice.EVERY_N)
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        // Never read `state.value` right after an intent: the collector may still be busy on another thread.
        await {
            slow.state.first {
                it.mode == SplitModeChoice.EACH_PAGE && it.file?.displayName == "a.pdf" && !it.importing &&
                    it.operation is OperationState.Running
            }
        }
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    private fun blockedTarget(name: String, latch: java.util.concurrent.CountDownLatch, sink: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()): Uri {
        val uri = Uri.parse("content://test.slow/$name")
        val stream = object : java.io.OutputStream() {
            override fun write(b: Int) { latch.await(); sink.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { latch.await(); sink.write(b, off, len) }
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(uri, stream)
        return uri
    }

    @Test
    fun whileSaving_secondSaveAndEditsAreIgnored_untilSaveFinishes() {
        pick(3)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        success()
        val latch = java.util.concurrent.CountDownLatch(1)
        vm.saveZip(blockedTarget("one.zip", latch))
        assertTrue(settled { it.saving }.saving)
        vm.saveZip(blockedTarget("two.zip", latch)) // ignored
        vm.setMode(SplitModeChoice.EVERY_N)
        vm.remove()
        vm.split()
        settled { it.mode == SplitModeChoice.EACH_PAGE && it.file != null && it.saving && it.operation is OperationState.Success }
        latch.countDown()
        assertEquals(3, await { vm.savedEvents.first() })
        assertFalse(settled { !it.saving }.saving)
        // Back to normal: edits work again.
        vm.setMode(SplitModeChoice.EVERY_N)
        assertEquals(SplitModeChoice.EVERY_N, settled { it.mode == SplitModeChoice.EVERY_N }.mode)
    }

    @Test
    fun saveToFolder_failure_surfacesExportFailed_andLeavesResult() {
        pick(2)
        vm.setMode(SplitModeChoice.EACH_PAGE)
        val result = success()
        vm.saveToFolder(Uri.parse("content://no.such.provider/tree/root")) // nothing can create documents there
        val op = settled { it.operation is OperationState.Failed }.operation as OperationState.Failed
        assertTrue(op.error is io.github.wzehirx.bpdf.core.files.ExportFailed)
        assertTrue(result.files.all { it.exists() })
        assertFalse(settled { !it.saving }.saving)
    }

    @Test
    fun cancelMidRun_removesOutputDirectory() {
        val big = TestPdfs.create(dir, 400, "P")
        val shared = File(context.cacheDir, "shared")
        val realVm = SplitViewModel(repo) // real IO dispatcher so the run really is in flight
        realVm.pickFile(listOf(Uri.fromFile(big)))
        await { realVm.state.first { it.file != null && !it.importing } }
        realVm.setMode(SplitModeChoice.EACH_PAGE)
        realVm.split()
        await {
            while (shared.listFiles().orEmpty().none { d -> d.list().orEmpty().isNotEmpty() }) kotlinx.coroutines.delay(5)
        }
        await { realVm.state.first { it.operation is OperationState.Running } }
        realVm.cancel()
        await { while (shared.listFiles().orEmpty().isNotEmpty()) kotlinx.coroutines.delay(10) }
        await { realVm.state.first { it.operation == OperationState.Idle } }
    }
}
