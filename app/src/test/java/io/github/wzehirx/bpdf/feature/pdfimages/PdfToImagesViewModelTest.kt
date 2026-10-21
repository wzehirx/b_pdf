package io.github.wzehirx.bpdf.feature.pdfimages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.ImageFormat
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
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
class PdfToImagesViewModelTest {
    private class FakeRasterizer : PageRasterizer, Closeable {
        val calls = mutableListOf<Pair<Int, Int>>()
        var closed = false

        override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
            calls += pageIndex to dpi
            return Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        }

        override fun close() {
            closed = true
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val rasterizers = mutableListOf<FakeRasterizer>()
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var vm: PdfToImagesViewModel

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
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PdfToImagesViewModel(repo, dispatcher) { FakeRasterizer().also { rasterizers += it } } as T
        })[PdfToImagesViewModel::class.java]
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

    private fun settled(condition: (PdfToImagesUiState) -> Boolean) = await { vm.state.first(condition) }

    private fun convertAndAwait(): OperationState<PdfToImagesResult> {
        vm.convert()
        return await { vm.state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
    }

    private fun success() = (convertAndAwait() as OperationState.Success).value

    @Test
    fun pickFile_loadsNameAndPageCount_corruptFails() {
        pick(5)
        assertEquals(5, vm.state.value.pageCount)
        val bad = File(dir, "bad.pdf").apply { writeText("nope") }
        vm.pickFile(listOf(Uri.fromFile(bad)))
        val op = settled { it.operation is OperationState.Failed }.operation as OperationState.Failed
        assertTrue(op.error is PdfError.Corrupt)
    }

    @Test
    fun convert_allPages_defaultsTo150DpiJpeg_namedAfterFile() {
        pick(3)
        val result = success()
        assertEquals(listOf("book_p1.jpg", "book_p2.jpg", "book_p3.jpg"), result.files.map { it.name })
        assertEquals("book_images.zip", result.zipName)
        assertEquals(listOf(0 to 150, 1 to 150, 2 to 150), rasterizers.single().calls)
        assertTrue(rasterizers.single().closed)
    }

    @Test
    fun convert_selectedPages_usesOptions_sortedAscending() {
        pick(5)
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("4,1-2")
        vm.setDpi(DpiChoice.HIGH)
        vm.setFormat(ImageFormat.PNG)
        val result = success()
        assertEquals(listOf("book_p1.png", "book_p2.png", "book_p4.png"), result.files.map { it.name })
        assertEquals(listOf(0 to 300, 1 to 300, 3 to 300), rasterizers.single().calls)
    }

    @Test
    fun selectedPages_invalidRange_blocksConvertAndCountsAttempt() {
        pick(5)
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("9")
        settled { it.rangeError != null }
        vm.convert()
        val s = settled { it.failedAttempts == 1 }
        assertEquals(OperationState.Idle, s.operation)
        assertTrue(rasterizers.isEmpty())
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun selectedPages_blankShowsNoErrorUntilAttempted_thenEmptyError() {
        pick(5)
        vm.setScope(PageScope.SELECTED)
        assertNull(settled { it.scope == PageScope.SELECTED }.rangeError)
        vm.convert()
        assertTrue(settled { it.failedAttempts == 1 }.rangeError != null)
    }

    @Test
    fun allPagesScope_ignoresRangeText() {
        pick(2)
        vm.setRangeText("abc")
        assertNull(settled { it.rangeText == "abc" }.rangeError)
        assertEquals(2, success().files.size)
    }

    @Test
    fun convert_withoutFile_doesNothing() {
        vm.convert()
        settled { it.operation == OperationState.Idle && it.file == null }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun changingOptionsAfterSuccess_clearsResultAndDeletesOutput() {
        pick(2)
        val result = success()
        vm.setDpi(DpiChoice.LOW)
        settled { it.operation == OperationState.Idle && it.dpi == DpiChoice.LOW }
        assertFalse(result.outDir.exists())
    }

    @Test
    fun saveZip_writesAllEntries_emitsSavedAndCleansTemp() {
        pick(3)
        success()
        val target = File(dir, "out.zip")
        vm.saveZip(Uri.fromFile(target))
        assertEquals(3, await { vm.savedEvents.first() })
        ZipFile(target).use { z -> assertEquals(listOf("book_p1.jpg", "book_p2.jpg", "book_p3.jpg"), z.entries().toList().map { it.name }) }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().none { it.extension == "zip" })
    }

    @Test
    fun saveToFolder_failure_surfacesExportFailed_andLeavesResult() {
        pick(2)
        val result = success()
        vm.saveToFolder(Uri.parse("content://no.such.provider/tree/root"))
        val op = settled { it.operation is OperationState.Failed }.operation as OperationState.Failed
        assertTrue(op.error is io.github.wzehirx.bpdf.core.files.ExportFailed)
        assertTrue(result.files.all { it.exists() })
        settled { !it.saving }
    }

    @Test
    fun prepareShare_returnsUriPerFile() {
        assertTrue(await { vm.prepareShare() }.isEmpty())
        pick(2)
        success()
        assertEquals(listOf("book_p1.jpg", "book_p2.jpg"), await { vm.prepareShare() }.map { it.lastPathSegment })
    }

    @Test
    fun remove_deletesCopy_onClearedDeletesImportAndOutput() {
        pick(2)
        val input = vm.state.value.file!!.file
        val result = success()
        store.clear()
        assertFalse(input.exists())
        assertFalse(result.outDir.exists())
    }

    @Test
    fun failedRender_leavesNoOutput_andClosesRasterizer() {
        val failing = PdfToImagesViewModel(repo, dispatcher) {
            object : PageRasterizer, Closeable {
                override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap = throw IllegalStateException("boom")
                override fun close() { rasterizers += FakeRasterizer().apply { closed = true } }
            }
        }
        failing.pickFile(listOf(pdf(2, "x.pdf")))
        await { failing.state.first { it.file != null && !it.importing } }
        failing.convert()
        val op = await { failing.state.first { it.operation is OperationState.Failed } }.operation as OperationState.Failed
        assertTrue(op.error is PdfError.Corrupt)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
        assertTrue(rasterizers.single().closed)
    }

    @Test
    fun whileRunning_changesAreIgnored() {
        val gate = kotlinx.coroutines.test.StandardTestDispatcher()
        val slow = PdfToImagesViewModel(repo, gate) { FakeRasterizer() }
        slow.pickFile(listOf(pdf(2, "a.pdf")))
        while (slow.state.value.file == null) {
            gate.scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
        await { slow.state.first { !it.importing } }
        slow.convert()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setDpi(DpiChoice.HIGH)
        slow.setFormat(ImageFormat.PNG)
        slow.remove()
        await { slow.state.first { it.dpi == DpiChoice.MEDIUM && it.file != null && it.operation is OperationState.Running } }
        gate.scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }
}
