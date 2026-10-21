package io.github.wzehirx.bpdf.feature.pdftext

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
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
class PdfToTextViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var repo: FileRepository
    private lateinit var dir: File

    @Before
    fun setUp() {
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

    private fun newVm(): PdfToTextViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PdfToTextViewModel(repo, dispatcher, pageHeader = { "== Page $it ==" }) as T
        })[PdfToTextViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun uriOf(source: File, name: String): Uri =
        Uri.fromFile(File(dir, name).also { source.copyTo(it, overwrite = true) })

    private fun PdfToTextViewModel.pick(source: File, name: String = "book.pdf") {
        pickFile(listOf(uriOf(source, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun PdfToTextViewModel.settled(condition: (PdfToTextUiState) -> Boolean) = await { state.first(condition) }

    private fun PdfToTextViewModel.success(): TextResult {
        extract()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    private fun blankPdf(pages: Int): File = File(dir, "blank-${System.nanoTime()}.pdf").also { f ->
        PDDocument().use { doc -> repeat(pages) { doc.addPage(PDPage()) }; doc.save(f) }
    }

    @Test
    fun extractAll_givesTextPerPage_andWritesATxtWithHeaders() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 3, "Tx"))
        val result = vm.success()
        assertEquals(listOf("Tx1", "Tx2", "Tx3"), result.pages.map { it.text })
        assertEquals("book_text.txt", result.suggestedName)
        assertEquals(0, result.blankCount)
        assertEquals("== Page 1 ==\nTx1\n\n== Page 2 ==\nTx2\n\n== Page 3 ==\nTx3\n", result.file!!.readText())
    }

    @Test
    fun selectedPages_extractOnlyThose() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 4, "S"))
        vm.setScope(TextScope.SELECTED)
        vm.setRangeText("2,4")
        assertEquals(listOf("S2", "S4"), vm.success().pages.map { it.text })
    }

    @Test
    fun selected_invalidRange_blocksExtractionWithSpecificError() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 5))
        vm.setScope(TextScope.SELECTED)
        vm.setRangeText("5-2")
        vm.extract()
        val s = vm.settled { it.failedAttempts == 1 && it.rangeError != null }
        assertEquals(OperationState.Idle, s.operation)
        assertEquals(RangeErrorReason.REVERSED, s.rangeError?.reason)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun documentWithoutText_isAllBlank_andWritesNoFile() {
        val vm = newVm()
        vm.pick(blankPdf(2))
        val result = vm.success()
        assertTrue(result.allBlank)
        assertEquals(2, result.blankCount)
        assertNull(result.file)
        assertNull(await { vm.prepareShare() })
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun encryptedFile_promptsThenExtractsWithPassword() {
        val vm = newVm()
        vm.pickFile(listOf(uriOf(TestPdfs.create(dir, 2, "Sec", "right"), "locked.pdf")))
        vm.settled { it.passwordPrompt != null }
        vm.submitPassword("right")
        vm.settled { it.file != null && it.passwordPrompt == null && !it.importing }
        assertEquals(listOf("Sec1", "Sec2"), vm.success().pages.map { it.text })
    }

    @Test
    fun pickFile_corrupt_failsAndKeepsNoFile() {
        val vm = newVm()
        vm.pickFile(listOf(Uri.fromFile(File(dir, "bad.pdf").apply { writeText("nope") })))
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is PdfError.Corrupt)
        assertNull(vm.state.value.file)
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 2))
        val result = vm.success()
        vm.setScope(TextScope.SELECTED)
        assertEquals(OperationState.Idle, vm.settled { it.scope == TextScope.SELECTED }.operation)
        assertFalse(result.file!!.exists())
    }

    @Test
    fun save_writesTxt_emitsSaved_andKeepsResult() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 2, "Sv"))
        val result = vm.success()
        val target = File(dir, "saved.txt")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertTrue(target.readText().contains("Sv2"))
        assertTrue(result.file!!.exists())
    }

    @Test
    fun prepareShare_usesSuggestedName() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(TestPdfs.create(dir, 1))
        vm.success()
        assertEquals("book_text.txt", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 2))
        val copy = vm.state.value.file!!.file
        val out = vm.success().file!!
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }
}
