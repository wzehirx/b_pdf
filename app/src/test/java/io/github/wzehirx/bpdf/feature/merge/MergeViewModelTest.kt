package io.github.wzehirx.bpdf.feature.merge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.work.OperationState
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
    private lateinit var dir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        vm = MergeViewModel(FileRepository(context), dispatcher)
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
        assertTrue(vm.state.value.operation is OperationState.Failed)
        assertEquals(listOf("a.pdf"), names())
        vm.retry()
        assertEquals(OperationState.Idle, vm.state.value.operation)
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
}
