package io.github.wzehirx.bpdf.feature.rotate

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.work.OperationState
import kotlinx.coroutines.CoroutineDispatcher
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
class RotateViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
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

    private fun newVm(io: CoroutineDispatcher = dispatcher): RotateViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RotateViewModel(repo, io) as T
        })[RotateViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun RotateViewModel.pick(pages: Int, name: String = "book.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun RotateViewModel.settled(condition: (RotateUiState) -> Boolean) = await { state.first(condition) }

    private fun RotateViewModel.success(): RotateResult {
        rotate()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
        return (op as OperationState.Success).value
    }

    private fun rotations(file: File) = PDDocument.load(file).use { d -> (0 until d.numberOfPages).map { d.getPage(it).rotation } }

    @Test
    fun angleDegrees_matchPdfClockwiseValues() {
        assertEquals(listOf(90, 180, 270), RotateAngle.entries.map { it.degrees })
    }

    @Test
    fun rotateAll_defaultsToNinetyClockwise() {
        val vm = newVm()
        vm.pick(3)
        val result = vm.success()
        assertEquals(listOf(90, 90, 90), rotations(result.file))
        assertEquals(3, result.rotatedCount)
        assertEquals(3, result.pageCount)
        assertEquals("book_rotated.pdf", result.suggestedName)
    }

    @Test
    fun counterClockwise_isTwoSeventy_andHalfTurnIsOneEighty() {
        val vm = newVm()
        vm.pick(2)
        vm.setAngle(RotateAngle.COUNTER_CLOCKWISE_90)
        assertEquals(listOf(270, 270), rotations(vm.success().file))
        vm.setAngle(RotateAngle.HALF_TURN)
        assertEquals(listOf(180, 180), rotations(vm.success().file))
    }

    @Test
    fun selectedPages_rotateOnlyThose() {
        val vm = newVm()
        vm.pick(4)
        vm.setScope(RotateScope.SELECTED)
        vm.setRangeText("2,4")
        val result = vm.success()
        assertEquals(listOf(0, 90, 0, 90), rotations(result.file))
        assertEquals(2, result.rotatedCount)
        assertEquals(4, result.pageCount)
    }

    @Test
    fun selected_invalidRange_blocksRotateWithSpecificError() {
        val vm = newVm()
        vm.pick(5)
        vm.setScope(RotateScope.SELECTED)
        vm.setRangeText("5-2")
        vm.rotate()
        val s = vm.settled { it.failedAttempts == 1 && it.rangeError != null }
        assertEquals(OperationState.Idle, s.operation)
        assertEquals(RangeErrorReason.REVERSED, s.rangeError?.reason)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun selected_blankRange_showsNoErrorUntilAttempted_andAllScopeIgnoresRange() {
        val vm = newVm()
        vm.pick(3)
        vm.setScope(RotateScope.SELECTED)
        assertNull(vm.settled { it.file != null && it.pageCount == 3 }.rangeError)
        vm.rotate()
        assertEquals(RangeErrorReason.EMPTY, vm.settled { it.rangeError != null }.rangeError?.reason)
        vm.setScope(RotateScope.ALL)
        assertNull(vm.settled { it.scope == RotateScope.ALL }.rangeError)
        vm.setRangeText("abc") // not validated while all pages are rotated
        assertNull(vm.settled { it.rangeText == "abc" }.rangeError)
        assertEquals(listOf(90, 90, 90), rotations(vm.success().file))
    }

    @Test
    fun rotate_withoutFile_doesNothing() {
        val vm = newVm()
        vm.rotate()
        val s = vm.settled { it.operation == OperationState.Idle && it.file == null && !it.importing }
        assertEquals(0, s.failedAttempts)
        // Nothing was started: no output file was even allocated.
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pickFile_corrupt_failsAndKeepsNoFile() {
        val vm = newVm()
        val bad = File(dir, "bad.pdf").apply { writeText("nope") }
        vm.pickFile(listOf(Uri.fromFile(bad)))
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is PdfError.Corrupt)
        assertNull(vm.state.value.file)
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        vm.setAngle(RotateAngle.HALF_TURN)
        assertEquals(OperationState.Idle, vm.settled { it.angle == RotateAngle.HALF_TURN }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.rotate()
        assertTrue(await { vm.state.first { it.operation is OperationState.Failed } }.operation is OperationState.Failed)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun save_writesFile_emitsSaved_andKeepsResult() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(listOf(90, 90), rotations(target))
        assertTrue(result.file.exists())
    }

    @Test
    fun prepareShare_returnsUriForResult_orNull() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(2)
        vm.success()
        assertEquals("book_rotated.pdf", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun remove_deletesCopyAndResetsState() {
        val vm = newVm()
        vm.pick(3)
        val copy = vm.state.value.file!!.file
        vm.remove()
        assertFalse(copy.exists())
        assertEquals(0, vm.settled { it.file == null }.pageCount)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        val vm = newVm()
        vm.pick(2)
        val copy = vm.state.value.file!!.file
        val out = vm.success().file
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }

    @Test
    fun whileRunning_changesAreIgnored() {
        val gate = StandardTestDispatcher()
        val slow = newVm(gate)
        slow.pickFile(listOf(pdf(3, "a.pdf")))
        // The page count is read on the injected (gated) dispatcher, so keep it moving until the file is in.
        await {
            while (slow.state.value.file == null) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        await { slow.state.first { !it.importing && it.file != null } }
        slow.rotate()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setAngle(RotateAngle.HALF_TURN)
        slow.setScope(RotateScope.SELECTED)
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        // Never read `state.value` right after an intent: the collector may still be busy on another thread.
        slow.settled {
            it.angle == RotateAngle.CLOCKWISE_90 && it.scope == RotateScope.ALL && it.file?.displayName == "a.pdf" &&
                it.operation is OperationState.Running
        }
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val gate = StandardTestDispatcher()
        val slow = newVm(gate)
        slow.pickFile(listOf(pdf(3, "a.pdf")))
        // The page count is read on the injected (gated) dispatcher, so keep it moving until the file is in.
        await {
            while (slow.state.value.file == null) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        await { slow.state.first { !it.importing && it.file != null } }
        slow.rotate()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
