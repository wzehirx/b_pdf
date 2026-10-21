package io.github.wzehirx.bpdf.feature.organize

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PageEdit
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
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
class OrganizeViewModelTest {
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

    private fun newVm(io: CoroutineDispatcher = dispatcher): OrganizeViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OrganizeViewModel(repo, io) as T
        })[OrganizeViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun OrganizeViewModel.pick(pages: Int, name: String = "book.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun OrganizeViewModel.settled(condition: (OrganizeUiState) -> Boolean) = await { state.first(condition) }

    private fun OrganizeViewModel.next(): OrganizeEvent = await { events.first() }

    private fun OrganizeViewModel.sources() = settled { true }.edits.map { it.sourceIndex }

    private fun OrganizeViewModel.success(): OrganizeResult {
        organize()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    @Test
    fun picking_startsWithEveryPageInOrder_andNothingToUndo() {
        val vm = newVm()
        vm.pick(4)
        val s = vm.settled { it.edits.size == 4 }
        assertEquals(listOf(0, 1, 2, 3), s.edits.map { it.sourceIndex })
        assertTrue(s.edits.all { it.rotation == 0 })
        assertEquals(4, s.pageCount)
        assertFalse(s.canUndo)
    }

    @Test
    fun moveUp_andMoveDown_swapNeighbours_andReportThePage() {
        val vm = newVm()
        vm.pick(4)
        vm.moveDown(0)
        assertEquals(OrganizeEvent.Moved(page = 1, position = 2), vm.next())
        assertEquals(listOf(1, 0, 2, 3), vm.settled { it.edits[0].sourceIndex == 1 }.edits.map { it.sourceIndex })
        vm.moveUp(3)
        assertEquals(OrganizeEvent.Moved(page = 4, position = 3), vm.next())
        assertEquals(listOf(1, 0, 3, 2), vm.settled { it.edits[2].sourceIndex == 3 }.edits.map { it.sourceIndex })
    }

    @Test
    fun moveUp_atTop_andMoveDown_atBottom_doNothing() {
        val vm = newVm()
        vm.pick(3)
        vm.moveUp(0)
        vm.moveDown(2)
        vm.moveUp(-1)
        vm.moveDown(7)
        vm.moveTo(1, 1)
        vm.moveTo(1, 3)
        vm.moveTo(1, -1)
        assertEquals(listOf(0, 1, 2), vm.sources())
        assertFalse(vm.settled { true }.canUndo)
        // No event was produced: the next one is the one from this later, real change.
        vm.moveDown(0)
        assertEquals(OrganizeEvent.Moved(1, 2), vm.next())
    }

    @Test
    fun moveTo_putsThePageAtThatPosition() {
        val vm = newVm()
        vm.pick(5)
        vm.moveTo(from = 2, to = 0)
        assertEquals(OrganizeEvent.Moved(page = 3, position = 1), vm.next())
        assertEquals(listOf(2, 0, 1, 3, 4), vm.settled { it.edits[0].sourceIndex == 2 }.edits.map { it.sourceIndex })
        vm.moveTo(from = 0, to = 4)
        assertEquals(OrganizeEvent.Moved(page = 1, position = 5), vm.next())
        assertEquals(listOf(0, 1, 3, 4, 2), vm.settled { it.edits[4].sourceIndex == 2 }.edits.map { it.sourceIndex })
    }

    @Test
    fun rotateRight_andLeft_turnByQuarterTurns_andWrap() {
        val vm = newVm()
        vm.pick(2)
        vm.rotateRight(1)
        assertEquals(OrganizeEvent.RotatedRight(2), vm.next())
        assertEquals(90, vm.settled { it.edits[1].rotation == 90 }.edits[1].rotation)
        vm.rotateLeft(1)
        assertEquals(OrganizeEvent.RotatedLeft(2), vm.next())
        assertEquals(0, vm.settled { it.edits[1].rotation == 0 }.edits[1].rotation)
        vm.rotateLeft(1)
        vm.next()
        assertEquals(270, vm.settled { it.edits[1].rotation == 270 }.edits[1].rotation)
        vm.rotateRight(0)
        vm.rotateRight(0)
        vm.rotateRight(0)
        vm.rotateRight(0)
        assertEquals(0, vm.settled { it.edits.size == 2 && it.undoDepth == 7 }.edits[0].rotation)
    }

    @Test
    fun duplicate_insertsACopyRightAfter_keepingItsRotation() {
        val vm = newVm()
        vm.pick(3)
        vm.rotateRight(1)
        vm.next()
        vm.duplicate(1)
        assertEquals(OrganizeEvent.Duplicated(page = 2), vm.next())
        val edits = vm.settled { it.edits.size == 4 }.edits
        assertEquals(listOf(PageEdit(0), PageEdit(1, 90), PageEdit(1, 90), PageEdit(2)), edits)
    }

    @Test
    fun delete_removesThePage_andUndoBringsItBack() {
        val vm = newVm()
        vm.pick(4)
        vm.delete(1)
        assertEquals(OrganizeEvent.Deleted(page = 2), vm.next())
        val s = vm.settled { it.edits.size == 3 }
        assertEquals(listOf(0, 2, 3), s.edits.map { it.sourceIndex })
        assertTrue(s.canUndo)
        vm.undo()
        assertEquals(OrganizeEvent.Undone, vm.next())
        val back = vm.settled { it.edits.size == 4 }
        assertEquals(listOf(0, 1, 2, 3), back.edits.map { it.sourceIndex })
        assertFalse(back.canUndo)
    }

    @Test
    fun deletingTheLastRemainingPage_isBlocked_withAReason() {
        val vm = newVm()
        vm.pick(2)
        vm.delete(0)
        vm.next()
        vm.settled { it.edits.size == 1 }
        vm.undo()
        vm.next()
        vm.settled { it.edits.size == 2 }
        vm.delete(1)
        vm.next()
        vm.settled { it.edits.size == 1 && it.undoDepth == 1 }
        vm.delete(0)
        assertEquals(OrganizeEvent.LastPageBlocked, vm.next())
        val s = vm.settled { true }
        assertEquals(1, s.edits.size)
        assertEquals(1, s.undoDepth) // the blocked attempt is not an undoable change
    }

    @Test
    fun undo_stepsBackThroughSeveralChanges_andIsIgnoredWhenEmpty() {
        val vm = newVm()
        vm.pick(3)
        vm.undo() // nothing to undo: no event, no change
        vm.moveDown(0)
        vm.rotateRight(2)
        vm.duplicate(0)
        vm.settled { it.undoDepth == 3 }
        repeat(3) { vm.undo() }
        val s = vm.settled { it.undoDepth == 0 }
        assertEquals(listOf(PageEdit(0), PageEdit(1), PageEdit(2)), s.edits)
        // The first event is the move (the empty undo produced none).
        assertEquals(OrganizeEvent.Moved(1, 2), vm.next())
    }

    @Test
    fun undoStack_keepsTheLastTwentyChanges() {
        val vm = newVm()
        vm.pick(3)
        repeat(25) { vm.rotateRight(0) }
        assertEquals(20, vm.settled { it.undoDepth == 20 && it.edits[0].rotation == 90 }.undoDepth)
        repeat(20) { vm.undo() }
        // 25 right turns = 90; undoing the 20 most recent leaves the first 5 = 90.
        val s = vm.settled { it.undoDepth == 0 && it.edits[0].rotation == 90 }
        assertEquals(90, s.edits[0].rotation)
    }

    @Test
    fun organize_writesTheEditedDocument_namedAfterTheSource() {
        val vm = newVm()
        vm.pick(3)
        vm.moveTo(2, 0)
        vm.rotateRight(1)
        vm.duplicate(2)
        vm.delete(0)
        vm.settled { it.edits.size == 3 && it.undoDepth == 4 }
        val result = vm.success()
        assertEquals(3, result.pageCount)
        assertEquals("book_organized.pdf", result.suggestedName)
        // [0,1,2] -> move page 3 to the top [2,0,1] -> turn the 2nd [2,0*,1] -> copy the 3rd [2,0*,1,1] -> drop the 1st.
        val edits = vm.settled { true }.edits
        assertEquals(listOf(PageEdit(0, 90), PageEdit(1), PageEdit(1)), edits)
        val texts = TestPdfs.text(result.file).map { it.filterNot(Char::isWhitespace) }
        assertEquals(edits.map { "P${it.sourceIndex + 1}" }, texts)
        val rotations = PDDocument.load(result.file).use { d -> (0 until d.numberOfPages).map { d.getPage(it).rotation } }
        assertEquals(edits.map { it.rotation }, rotations)
    }

    @Test
    fun changingEditsAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        vm.moveDown(0)
        assertEquals(OperationState.Idle, vm.settled { it.edits[0].sourceIndex == 1 }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun organize_withoutFile_doesNothing() {
        val vm = newVm()
        vm.organize()
        vm.moveDown(0)
        vm.delete(0)
        val s = vm.settled { it.operation == OperationState.Idle && !it.importing }
        assertNull(s.file)
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
    fun pickingAnotherFile_resetsEditsAndUndo() {
        val vm = newVm()
        vm.pick(3, "a.pdf")
        vm.delete(0)
        vm.settled { it.undoDepth == 1 }
        vm.pick(2, "b.pdf")
        val s = vm.settled { it.file?.displayName == "b.pdf" && it.edits.size == 2 }
        assertEquals(0, s.undoDepth)
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage")
        vm.organize()
        assertTrue(await { vm.state.first { it.operation is OperationState.Failed } }.operation is OperationState.Failed)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun save_writesFile_emitsSaved_andKeepsResult() {
        val vm = newVm()
        vm.pick(2)
        vm.moveDown(0)
        val result = vm.success()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(listOf("P2", "P1"), TestPdfs.text(target))
        assertTrue(result.file.exists())
    }

    @Test
    fun prepareShare_returnsUriForResult_orNull() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(2)
        vm.success()
        assertEquals("book_organized.pdf", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun remove_deletesCopyAndResetsState() {
        val vm = newVm()
        vm.pick(3)
        val copy = vm.state.value.file!!.file
        vm.delete(0)
        vm.remove()
        assertFalse(copy.exists())
        val s = vm.settled { it.file == null }
        assertEquals(0, s.pageCount)
        assertTrue(s.edits.isEmpty())
        assertFalse(s.canUndo)
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
        await {
            while (slow.state.value.file == null) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        await { slow.state.first { !it.importing && it.file != null } }
        slow.organize()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.moveDown(0)
        slow.delete(1)
        slow.rotateRight(0)
        slow.duplicate(0)
        slow.undo()
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        slow.settled { it.file?.displayName == "a.pdf" && it.operation is OperationState.Running && it.edits.size == 3 }
        assertEquals(0, slow.state.value.undoDepth)
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val gate = StandardTestDispatcher()
        val slow = newVm(gate)
        slow.pickFile(listOf(pdf(3, "a.pdf")))
        await {
            while (slow.state.value.file == null) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        await { slow.state.first { !it.importing && it.file != null } }
        slow.organize()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
