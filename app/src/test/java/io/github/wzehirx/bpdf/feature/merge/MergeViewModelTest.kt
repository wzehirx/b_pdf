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

    // The combined state is republished by a collector that may still be busy on another thread, so
    // never read `state.value` right after an intent: wait for the state that is expected instead.
    private fun awaitState(vm: MergeViewModel = this.vm, condition: (MergeUiState) -> Boolean): MergeUiState =
        await { vm.state.first(condition) }

    private fun add(vararg uris: Uri) {
        val expected = vm.state.value.files.size + uris.size
        vm.addFiles(uris.toList())
        awaitState { !it.importing && it.files.size == expected }
    }

    private fun mergeAndAwait(): OperationState<MergeResult> {
        vm.merge()
        return await { vm.state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
    }

    private fun assertNames(vararg expected: String) {
        awaitState { state -> state.files.map { it.displayName } == expected.toList() }
    }

    @Test
    fun addFiles_importsInOrder() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        assertNames("a.pdf", "b.pdf")
        awaitState { !it.importing }
    }

    @Test
    fun moveAndRemove_reorderState() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"), pdf(1, "C", "c.pdf"))
        vm.move(2, 0)
        assertNames("c.pdf", "a.pdf", "b.pdf")
        vm.remove(1)
        assertNames("c.pdf", "b.pdf")
        vm.move(5, 0) // out of range is ignored
        assertNames("c.pdf", "b.pdf")
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
        awaitState { it.operation == OperationState.Idle }
    }

    @Test
    fun changingFiles_afterMerge_clearsStaleResult() {
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        mergeAndAwait()
        vm.remove(0)
        awaitState { it.operation == OperationState.Idle }
    }

    @Test
    fun corruptFile_isRejectedAtImportWithPdfError_notCrash() {
        val bad = File(dir, "bad.pdf").apply { writeText("not a pdf") }
        vm.addFiles(listOf(pdf(1, "A", "a.pdf"), Uri.fromFile(bad)))
        val state = awaitState { it.operation is OperationState.Failed && !it.importing }
        assertTrue(state.operation.toString(), (state.operation as OperationState.Failed).error is PdfError.Corrupt)
        assertNames("a.pdf")
        // The rejected copy is not left behind in the cache.
        assertEquals(1, File(context.cacheDir, "imports").listFiles().orEmpty().size)
    }

    @Test
    fun unreadableUri_surfacesAsFailedState_andRetryDismisses() {
        vm.addFiles(listOf(pdf(1, "A", "a.pdf"), Uri.fromFile(File(dir, "missing.pdf"))))
        // The combined state is published asynchronously after the IO thread's update.
        awaitState { it.operation is OperationState.Failed && !it.importing }
        assertNames("a.pdf")
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
        add(pdf(1, "A", "a.pdf"), pdf(1, "B", "b.pdf"))
        vm.state.value.files.last().file.delete() // an input vanishes after import
        assertTrue(mergeAndAwait() is OperationState.Failed)
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
        // The password check runs on the gated dispatcher, so keep releasing it while waiting.
        await {
            while (!(slow.state.value.files.size == 2 && !slow.state.value.importing)) {
                gate.scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(10)
            }
        }
        slow.merge()
        awaitState(slow) { it.operation is OperationState.Running }
        slow.move(0, 1)
        slow.remove(0)
        slow.addFiles(listOf(pdf(1, "C", "c.pdf")))
        awaitState(slow) {
            it.files.map { f -> f.displayName } == listOf("a.pdf", "b.pdf") && !it.importing &&
                it.operation is OperationState.Running
        }
        gate.scheduler.advanceUntilIdle()
        awaitState(slow) { it.operation is OperationState.Success }
    }

    @Test
    fun whileImporting_addMergeMoveAreIgnored() {
        val latch = java.util.concurrent.CountDownLatch(1)
        val uri = Uri.parse("content://test.slow/slow.pdf")
        val content = TestPdfs.create(dir, 1, "S").readBytes().inputStream()
        val stream = object : java.io.InputStream() {
            override fun read(): Int { latch.await(); return content.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { latch.await(); return content.read(b, off, len) }
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, stream)
        vm.addFiles(listOf(uri))
        awaitState { it.importing }
        vm.addFiles(listOf(pdf(1, "A", "a.pdf")))
        vm.merge()
        latch.countDown()
        await { vm.state.first { !it.importing } }
        assertNames("slow.pdf")
        awaitState { it.operation == OperationState.Idle }
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
        awaitState {
            it.files.map { f -> f.displayName } == listOf("a.pdf", "b.pdf") && it.saving &&
                it.operation is OperationState.Success
        }
        latch.countDown()
        await { vm.savedEvents.first() }
        await { vm.state.first { !it.saving } }
        vm.move(0, 1)
        await { vm.state.first { it.files.map { f -> f.displayName } == listOf("b.pdf", "a.pdf") } }
    }
}
