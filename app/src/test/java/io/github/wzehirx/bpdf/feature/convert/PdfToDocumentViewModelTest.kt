package io.github.wzehirx.bpdf.feature.convert

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.ConversionLabels
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLayout
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
import org.robolectric.annotation.GraphicsMode
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PdfToDocumentViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var repo: FileRepository
    private lateinit var dir: File

    private class FakeRasterizer : PageRasterizer, Closeable {
        var closed = false
        var renders = 0
        override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
            renders++
            return Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        }
        override fun close() { closed = true }
    }

    private val rasterizers = mutableListOf<FakeRasterizer>()

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

    private fun newVm(): PdfToDocumentViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PdfToDocumentViewModel(
                    repo, dispatcher, ConversionLabels(pageHeader = { "Sayfa $it" }), { "tr-TR" },
                    { FakeRasterizer().also { rasterizers += it } },
                ) as T
        })[PdfToDocumentViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun uriOf(source: File, name: String): Uri =
        Uri.fromFile(File(dir, name).also { source.copyTo(it, overwrite = true) })

    private fun PdfToDocumentViewModel.pick(source: File, name: String = "book.pdf") {
        pickFile(listOf(uriOf(source, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun PdfToDocumentViewModel.settled(condition: (PdfToDocumentUiState) -> Boolean) = await { state.first(condition) }

    private fun PdfToDocumentViewModel.finish(): OperationState<PdfToDocumentResult> {
        convert()
        return await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation as OperationState<PdfToDocumentResult>
    }

    private fun PdfToDocumentViewModel.success() = (finish() as OperationState.Success).value

    @Test
    fun word_isTheDefault_andWritesADocxNamedAfterTheSource() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 3, "Tx"))
        val r = vm.success()
        assertEquals("book_word.docx", r.suggestedName)
        assertEquals(DocumentFormat.WORD, r.format)
        assertEquals(3, r.stats.pages)
        ZipFile(r.file).use { z ->
            val doc = z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(doc.contains("Sayfa 3") && doc.contains("Tx3"))
        }
        assertTrue(rasterizers.isEmpty()) // no rasterizer unless the exact look is asked for
    }

    @Test
    fun excelAndHtml_useTheirExtensions() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 1, "Q"))
        vm.setFormat(DocumentFormat.EXCEL)
        vm.settled { it.format == DocumentFormat.EXCEL }
        assertEquals("book_excel.xlsx", vm.success().suggestedName)
        vm.setFormat(DocumentFormat.HTML)
        vm.settled { it.format == DocumentFormat.HTML && it.operation is OperationState.Idle }
        val html = vm.success()
        assertEquals("book_html.html", html.suggestedName)
        assertTrue(html.file.readText().contains("<h2 id=\"page1\">Sayfa 1</h2>"))
    }

    @Test
    fun exactLook_usesTheRasterizer_andClosesIt() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 2))
        vm.setLayout(PdfLayout.EXACT_LOOK)
        vm.settled { it.layout == PdfLayout.EXACT_LOOK }
        val r = vm.success()
        assertEquals(2, r.stats.pictures)
        val fake = rasterizers.single()
        assertEquals(2, fake.renders)
        assertTrue(fake.closed)
    }

    @Test
    fun changingFormat_orLayout_clearsStaleResult_andDeletesOutput() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 1))
        val r = vm.success()
        vm.setFormat(DocumentFormat.HTML)
        assertEquals(OperationState.Idle, vm.settled { it.format == DocumentFormat.HTML }.operation)
        assertFalse(r.file.exists())
    }

    @Test
    fun encryptedFile_promptsThenConverts_andFlagsUnprotectedCopy() {
        val vm = newVm()
        vm.pickFile(listOf(uriOf(TestPdfs.create(dir, 2, "Sec", "right"), "locked.pdf")))
        vm.settled { it.passwordPrompt != null }
        vm.submitPassword("right")
        val s = vm.settled { it.file != null && it.passwordPrompt == null && !it.importing }
        assertTrue(s.copyUnprotected)
        assertEquals(2, vm.success().stats.pages)
    }

    @Test
    fun wrongPassword_keepsPromptWithWrongFlag() {
        val vm = newVm()
        vm.pickFile(listOf(uriOf(TestPdfs.create(dir, 1, "x", "right"), "locked.pdf")))
        vm.settled { it.passwordPrompt != null }
        vm.submitPassword("nope")
        assertTrue(vm.settled { it.passwordPrompt?.wrong == true }.passwordPrompt!!.wrong)
        vm.dismissPassword()
        assertNull(vm.settled { it.passwordPrompt == null && !it.importing }.file)
    }

    @Test
    fun corruptPdf_failsWithoutFile() {
        val vm = newVm()
        vm.pickFile(listOf(Uri.fromFile(File(dir, "bad.pdf").apply { writeText("nope") })))
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is PdfError.Corrupt)
        assertNull(vm.state.value.file)
        vm.retry()
        assertEquals(OperationState.Idle, vm.settled { it.operation is OperationState.Idle }.operation)
    }

    @Test
    fun failedConversion_leavesNoOutputFile() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 1))
        vm.state.value.file!!.file.writeText("damaged after import")
        assertTrue(vm.finish() is OperationState.Failed)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun save_writesTarget_emitsSaved_andKeepsResult() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 1, "Sv"))
        val r = vm.success()
        val target = File(dir, "saved.docx")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertTrue(target.length() > 0)
        assertTrue(r.file.exists())
    }

    @Test
    fun prepareShare_usesSuggestedName() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(TestPdfs.create(dir, 1))
        vm.success()
        assertEquals("book_word.docx", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun remove_deletesImport_andClearsState() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 1))
        val copy = vm.state.value.file!!.file
        vm.remove()
        assertNull(vm.settled { it.file == null }.file)
        assertFalse(copy.exists())
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        val vm = newVm()
        vm.pick(TestPdfs.create(dir, 1))
        val copy = vm.state.value.file!!.file
        val out = vm.success().file
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }
}
