package io.github.wzehirx.bpdf.feature.merge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.work.OperationState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MergeViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: MergeViewModel
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MergeViewModel(repo, dispatcher) as T
        })[MergeViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun pdf(pages: Int, prefix: String, name: String): Uri {
        val f = TestPdfs.create(dir, pages, prefix)
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    // Imports and exports hop to the real IO dispatcher, so wait for the state instead of assuming it.
    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun add(vararg uris: Uri) {
        vm.addFiles(uris.toList())
        await { vm.state.first { !it.importing } }
    }

    private fun mergeAndAwait(): OperationState<MergeResult> {
        vm.merge()
        return await { vm.state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
    }

    private fun names() = vm.state.value.files.map { it.displayName }

    @Test
    fun addFiles_importsInOrder() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        assertEquals(listOf("a.pdf", "b.pdf"), names())
        assertFalse(vm.state.value.importing)
    }

    @Test
    fun moveAndRemove_reorderState() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"), pdf(1, "C", "c.pdf"))
        vm.move(2, 0)
        assertEquals(listOf("c.pdf", "a.pdf", "b.pdf"), names())
        vm.remove(1)
        assertEquals(listOf("c.pdf", "b.pdf"), names())
        vm.move(5, 0) // out of range is ignored
        assertEquals(listOf("c.pdf", "b.pdf"), names())
    }

    @Test
    fun merge_success_hasPageCountOrderAndSuggestedName() {
        add(pdf(1, "A", "first.pdf"), pdf(2, "B", "b.pdf"))
        vm.move(1, 0)
        val op = mergeAndAwait()
        assertTrue(op.toString(), op is OperationState.Success)
        val result = (op as OperationState.Success).value
        assertEquals(3, result.pageCount)
        assertEquals(2, result.fileCount)
        assertEquals("b_merged.pdf", result.suggestedName)
        assertEquals(listOf("B1", "B2", "A1"), TestPdfs.text(result.file))
    }

    @Test
    fun merge_withOneFile_doesNothing() {
        add(pdf(1, "A", "a.pdf"))
        vm.merge()
        assertEquals(OperationState.Idle, vm.state.value.operation)
    }

    @Test
    fun changingFiles_afterMerge_clearsStaleResult() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        mergeAndAwait()
        vm.remove(0)
        assertEquals(OperationState.Idle, vm.state.value.operation)
    }

    @Test
    fun corruptFile_failsMergeWithPdfError_notCrash() {
        val bad = File(dir, "bad.pdf").apply { writeText("not a pdf") }
        add(pdf(1, "A", "a.pdf"), Uri.fromFile(bad))
        val op = mergeAndAwait()
        assertTrue(op.toString(), op is OperationState.Failed && op.error is PdfError.Corrupt)
    }

    @Test
    fun unreadableUri_surfacesAsFailedState_andRetryDismisses() {
        add(pdf(1, "A", "a.pdf"), Uri.fromFile(File(dir, "missing.pdf")))
        // The combined state is published asynchronously after the IO thread's update.
        await { vm.state.first { it.operation is OperationState.Failed } }
        assertEquals(listOf("a.pdf"), names())
        vm.retry()
        await { vm.state.first { it.operation == OperationState.Idle } }
    }

    @Test
    fun save_writesTargetAndEmitsEvent() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        mergeAndAwait()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(listOf("A1", "B1"), TestPdfs.text(target))
    }

    @Test
    fun prepareShare_usesSuggestedName() {
        assertEquals(null, await { vm.prepareShare() })
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        mergeAndAwait()
        assertEquals("a_merged.pdf", await { vm.prepareShare() }!!.lastPathSegment)
    }

    @Test
    fun remove_deletesCachedCopy() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        val (first, second) = vm.state.value.files.map { it.file }
        vm.remove(0)
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun changingFilesAfterMerge_deletesDiscardedOutput() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        val out = (mergeAndAwait() as OperationState.Success).value.file
        assertTrue(out.exists())
        vm.move(0, 1)
        assertFalse(out.exists())
    }

    @Test
    fun mergingAgain_deletesPreviousOutput() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        val first = (mergeAndAwait() as OperationState.Success).value.file
        val second = (mergeAndAwait() as OperationState.Success).value.file
        assertFalse(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun failedMerge_leavesNoOutputFile() {
        val bad = File(dir, "bad.pdf").apply { writeText("not a pdf") }
        add(pdf(1, "A", "a.pdf"), Uri.fromFile(bad))
        mergeAndAwait()
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun onCleared_deletesImportsAndOutput() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        val inputs = vm.state.value.files.map { it.file }
        val out = (mergeAndAwait() as OperationState.Success).value.file
        store.clear()
        assertTrue(inputs.none { it.exists() })
        assertFalse(out.exists())
    }

    @Test
    fun whileRunning_moveRemoveAddAreIgnored() {
        val gate = kotlinx.coroutines.test.StandardTestDispatcher()
        val slow = MergeViewModel(repo, gate)
        slow.addFiles(listOf(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf")))
        await { slow.state.first { !it.importing && it.files.size == 2 } }
        slow.merge()
        assertTrue(slow.state.value.operation is OperationState.Running)
        slow.move(0, 1)
        slow.remove(0)
        slow.addFiles(listOf(pdf(1, "C", "c.pdf")))
        assertEquals(listOf("a.pdf", "b.pdf"), slow.state.value.files.map { it.displayName })
        assertFalse(slow.state.value.importing)
        assertTrue(slow.state.value.operation is OperationState.Running)
        gate.scheduler.advanceUntilIdle()
        assertTrue(slow.state.value.operation is OperationState.Success)
    }

    @Test
    fun whileImporting_addMergeMoveAreIgnored() {
        val latch = java.util.concurrent.CountDownLatch(1)
        val uri = Uri.parse("content://test.slow/slow.pdf")
        val stream = object : java.io.InputStream() {
            override fun read(): Int { latch.await(); return -1 }
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, stream)
        vm.addFiles(listOf(uri))
        assertTrue(vm.state.value.importing)
        vm.addFiles(listOf(pdf(1, "A", "a.pdf")))
        vm.merge()
        latch.countDown()
        await { vm.state.first { !it.importing } }
        assertEquals(listOf("slow.pdf"), names())
        assertEquals(OperationState.Idle, vm.state.value.operation)
    }

    @Test
    fun whileSaving_secondSaveAndEditsAreIgnored_untilSaveFinishes() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        mergeAndAwait()
        val latch = java.util.concurrent.CountDownLatch(1)
        val uri = Uri.parse("content://test.slow/one.pdf")
        val stream = object : java.io.OutputStream() {
            override fun write(b: Int) { latch.await() }
            override fun write(b: ByteArray, off: Int, len: Int) { latch.await() }
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(uri, stream)
        vm.save(uri)
        await { vm.state.first { it.saving } }
        vm.save(uri) // ignored
        vm.move(0, 1)
        vm.remove(0)
        vm.addFiles(listOf(pdf(1, "C", "c.pdf")))
        vm.merge()
        val during = vm.state.value
        assertEquals(listOf("a.pdf", "b.pdf"), during.files.map { it.displayName })
        assertTrue(during.operation is OperationState.Success)
        latch.countDown()
        await { vm.savedEvents.first() }
        await { vm.state.first { !it.saving } }
        vm.move(0, 1)
        await { vm.state.first { it.files.map { f -> f.displayName } == listOf("b.pdf", "a.pdf") } }
    }
}
