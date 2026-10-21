package io.github.wzehirx.bpdf.feature.compress

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.pdf.CompressLevel
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class CompressViewModelTest {
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

    private fun newVm(io: CoroutineDispatcher = dispatcher): CompressViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CompressViewModel(repo, io) as T
        })[CompressViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun CompressViewModel.pick(pages: Int, name: String = "book.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun CompressViewModel.settled(condition: (CompressUiState) -> Boolean) = await { state.first(condition) }

    private fun CompressViewModel.success(): CompressUiResult {
        compress()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    private fun slowVmWithFile(): CompressViewModel {
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
        gates[slow] = gate
        return slow
    }

    private val gates = mutableMapOf<CompressViewModel, kotlinx.coroutines.test.TestDispatcher>()

    @Test
    fun compress_reportsSizes_andNamesTheCopy() {
        val vm = newVm()
        vm.pick(3)
        val result = vm.success()
        assertEquals("book_compressed.pdf", result.suggestedName)
        assertEquals(result.file.length(), result.sizes.afterBytes)
        assertTrue(result.sizes.afterBytes <= result.sizes.beforeBytes)
        assertEquals(3, io.github.wzehirx.bpdf.core.pdf.PdfLoader.pageCount(result.file))
    }

    @Test
    fun defaults_areBalanced_andKeepMetadata() {
        val s = newVm().state.value
        assertEquals(CompressLevel.MEDIUM, s.level)
        assertFalse(s.removeMetadata)
    }

    @Test
    fun removeMetadata_isPassedToTheEngine() {
        val vm = newVm()
        val source = File(dir, "meta.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(TestPdfs.create(dir, 1)).use { doc ->
            doc.documentInformation.title = "Private"
            doc.save(source)
        }
        vm.pickFile(listOf(Uri.fromFile(source)))
        await { vm.state.first { !it.importing && it.file != null } }
        vm.setRemoveMetadata(true)
        val result = vm.success()
        io.github.wzehirx.bpdf.core.pdf.PdfLoader.load(result.file).use { assertNull(it.documentInformation.title) }
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        vm.setLevel(CompressLevel.HIGH)
        assertEquals(OperationState.Idle, vm.settled { it.level == CompressLevel.HIGH }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.compress()
        assertTrue(await { vm.state.first { it.operation is OperationState.Failed } }.operation is OperationState.Failed)
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
    fun compress_withoutFile_doesNothing() {
        val vm = newVm()
        vm.compress()
        val s = vm.settled { it.operation == OperationState.Idle && it.file == null && !it.importing }
        assertEquals(OperationState.Idle, s.operation)
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
        assertEquals(2, io.github.wzehirx.bpdf.core.pdf.PdfLoader.pageCount(target))
        assertTrue(result.file.exists())
    }

    @Test
    fun prepareShare_returnsUriForResult_orNull() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(2)
        vm.success()
        assertEquals("book_compressed.pdf", await { vm.prepareShare() }?.lastPathSegment)
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
        val slow = slowVmWithFile()
        slow.setLevel(CompressLevel.MEDIUM)
        slow.compress()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setLevel(CompressLevel.HIGH)
        slow.setRemoveMetadata(true)
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        // Never read `state.value` right after an intent: the collector may still be busy on another thread.
        slow.settled { it.level == CompressLevel.MEDIUM && !it.removeMetadata && it.file?.displayName == "a.pdf" && it.operation is OperationState.Running }
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val slow = slowVmWithFile()
        slow.setLevel(CompressLevel.MEDIUM)
        slow.compress()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
