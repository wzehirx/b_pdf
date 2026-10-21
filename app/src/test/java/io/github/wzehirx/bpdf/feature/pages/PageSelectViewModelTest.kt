package io.github.wzehirx.bpdf.feature.pages

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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PageSelectViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File

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
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newVm(mode: PageSelectMode, io: kotlinx.coroutines.CoroutineDispatcher = dispatcher): PageSelectViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PageSelectViewModel(mode, repo, io) as T
        })[PageSelectViewModel::class.java]

    private val stores = mutableListOf<ViewModelStore>()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun PageSelectViewModel.pick(pages: Int, name: String = "book.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun PageSelectViewModel.settled(condition: (PageSelectUiState) -> Boolean) = await { state.first(condition) }

    private fun PageSelectViewModel.runAndAwait(): OperationState<PageSelectResult> {
        run()
        return await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
    }

    private fun PageSelectViewModel.success() = (runAndAwait() as OperationState.Success).value

    @Test
    fun remove_success_dropsPagesAndReportsCounts() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(4)
        vm.setRangeText("2,4")
        val result = vm.success()
        assertEquals(2, result.selectedCount)
        assertEquals(2, result.remainingCount)
        assertEquals("book_removed.pdf", result.suggestedName)
        assertEquals(listOf("P1", "P3"), TestPdfs.text(result.file))
    }

    @Test
    fun extract_success_keepsTypedOrder() {
        val vm = newVm(PageSelectMode.EXTRACT)
        vm.pick(4)
        vm.setRangeText("3,1")
        val result = vm.success()
        assertEquals(2, result.selectedCount)
        assertEquals(2, result.remainingCount)
        assertEquals("book_extracted.pdf", result.suggestedName)
        assertEquals(listOf("P3", "P1"), TestPdfs.text(result.file))
    }

    @Test
    fun extract_allPages_isAllowed() {
        val vm = newVm(PageSelectMode.EXTRACT)
        vm.pick(2)
        vm.setRangeText("1-2")
        assertFalse(vm.settled { it.rangeText == "1-2" }.removesAllPages)
        assertEquals(2, vm.success().remainingCount)
    }

    @Test
    fun remove_allPages_blocksRunAndFlagsIt() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(3)
        vm.setRangeText("1-3")
        assertTrue(vm.settled { it.rangeText == "1-3" }.removesAllPages)
        vm.run()
        val s = vm.settled { it.failedAttempts == 1 }
        assertEquals(OperationState.Idle, s.operation)
        assertTrue(s.removesAllPages)
        assertNull(s.rangeError)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun invalidRange_blocksRunWithSpecificError() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(5)
        for ((text, reason) in listOf(
            "5-2" to RangeErrorReason.REVERSED,
            "0" to RangeErrorReason.OUT_OF_BOUNDS,
            "abc" to RangeErrorReason.NOT_A_NUMBER,
            "1-" to RangeErrorReason.INCOMPLETE,
            "9" to RangeErrorReason.OUT_OF_BOUNDS,
        )) {
            vm.setRangeText(text)
            val before = vm.state.value.failedAttempts
            vm.run()
            val s = vm.settled { it.rangeText == text && it.failedAttempts == before + 1 }
            assertEquals(text, OperationState.Idle, s.operation)
            assertEquals(text, reason, s.rangeError?.reason)
        }
    }

    @Test
    fun blankRange_showsNoErrorUntilRunAttempted() {
        val vm = newVm(PageSelectMode.EXTRACT)
        vm.pick(3)
        assertNull(vm.settled { it.file != null && it.pageCount == 3 }.rangeError)
        vm.run()
        assertEquals(RangeErrorReason.EMPTY, vm.settled { it.rangeError != null }.rangeError?.reason)
        vm.setRangeText("2")
        assertNull(vm.settled { it.rangeText == "2" }.rangeError)
    }

    @Test
    fun run_withoutFile_doesNothing() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.run()
        val s = vm.settled { it.operation == OperationState.Idle && it.file == null && !it.importing }
        assertEquals(0, s.failedAttempts)
        // Nothing was started: no output file was even allocated.
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pickFile_corrupt_failsAndKeepsNoFile() {
        val vm = newVm(PageSelectMode.REMOVE)
        val bad = File(dir, "bad.pdf").apply { writeText("nope") }
        vm.pickFile(listOf(Uri.fromFile(bad)))
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is PdfError.Corrupt)
        assertNull(vm.state.value.file)
        assertEquals(0, File(context.cacheDir, "imports").listFiles().orEmpty().size)
    }

    @Test
    fun pickingAnotherFile_replacesAndDeletesOldCopy() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(3, "a.pdf")
        val old = vm.state.value.file!!.file
        vm.pick(4, "b.pdf")
        assertFalse(old.exists())
        assertEquals(4, vm.state.value.pageCount)
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(3)
        vm.setRangeText("1")
        val result = vm.success()
        vm.setRangeText("2")
        assertEquals(OperationState.Idle, vm.settled { it.rangeText == "2" }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun runningAgain_deletesPreviousOutput() {
        val vm = newVm(PageSelectMode.EXTRACT)
        vm.pick(3)
        vm.setRangeText("1")
        val first = vm.success()
        val second = vm.success()
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(3)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.setRangeText("1")
        assertTrue(vm.runAndAwait() is OperationState.Failed)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun save_writesFile_emitsSaved_andKeepsResult() {
        val vm = newVm(PageSelectMode.EXTRACT)
        vm.pick(3)
        vm.setRangeText("2")
        val result = vm.success()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(listOf("P2"), TestPdfs.text(target))
        assertTrue(result.file.exists())
    }

    @Test
    fun prepareShare_returnsUriForResult_orNull() {
        val vm = newVm(PageSelectMode.EXTRACT)
        assertNull(await { vm.prepareShare() })
        vm.pick(3)
        vm.setRangeText("2")
        vm.success()
        assertEquals("book_extracted.pdf", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun remove_deletesCopyAndResetsState() {
        val vm = newVm(PageSelectMode.REMOVE)
        vm.pick(3)
        val copy = vm.state.value.file!!.file
        vm.remove()
        assertFalse(copy.exists())
        assertEquals(0, vm.settled { it.file == null }.pageCount)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        val vm = newVm(PageSelectMode.EXTRACT)
        vm.pick(3)
        vm.setRangeText("1")
        val copy = vm.state.value.file!!.file
        val out = vm.success().file
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }

    @Test
    fun whileRunning_changesAreIgnored() {
        val gate = StandardTestDispatcher()
        val slow = newVm(PageSelectMode.REMOVE, gate)
        slow.pickFile(listOf(pdf(3, "a.pdf")))
        // The page count is read on the injected (gated) dispatcher, so keep it moving until the file is in.
        await {
            while (slow.state.value.file == null) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        await { slow.state.first { !it.importing && it.file != null } }
        slow.setRangeText("1")
        slow.run()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setRangeText("2")
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        // Never read `state.value` right after an intent: the collector may still be busy on another thread.
        slow.settled { it.rangeText == "1" && it.file?.displayName == "a.pdf" && !it.importing && it.operation is OperationState.Running }
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val gate = StandardTestDispatcher()
        val slow = newVm(PageSelectMode.EXTRACT, gate)
        slow.pickFile(listOf(pdf(3, "a.pdf")))
        // The page count is read on the injected (gated) dispatcher, so keep it moving until the file is in.
        await {
            while (slow.state.value.file == null) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        await { slow.state.first { !it.importing && it.file != null } }
        slow.setRangeText("1")
        slow.run()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
