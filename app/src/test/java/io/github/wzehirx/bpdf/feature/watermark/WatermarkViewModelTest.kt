package io.github.wzehirx.bpdf.feature.watermark

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.pdf.WatermarkPosition
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
class WatermarkViewModelTest {
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

    private fun newVm(io: CoroutineDispatcher = dispatcher): WatermarkViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WatermarkViewModel(repo, { context.assets.open(Fonts.ASSET_PATH) }, io) as T
        })[WatermarkViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun WatermarkViewModel.pick(pages: Int, name: String = "book.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun WatermarkViewModel.settled(condition: (WatermarkUiState) -> Boolean) = await { state.first(condition) }

    private fun WatermarkViewModel.success(): WatermarkResult {
        if (state.value.text.isBlank()) setText("WM")
        addWatermark()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    private fun slowVmWithFile(): WatermarkViewModel {
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

    private val gates = mutableMapOf<WatermarkViewModel, kotlinx.coroutines.test.TestDispatcher>()

    private fun texts(file: File) = TestPdfs.text(file)

    @Test
    fun defaults_matchTheSpecifiedOptions() {
        val s = newVm().state.value
        assertEquals(48, s.fontSize)
        assertEquals(30, s.opacityPercent)
        assertEquals(45, s.rotation)
        assertEquals(WatermarkPosition.CENTER, s.position)
        assertEquals(WatermarkColor.GRAY, s.color)
        assertEquals(PageScope.ALL, s.scope)
    }

    @Test
    fun watermarkAllPages_stampsTurkishText() {
        val vm = newVm()
        vm.pick(3)
        vm.setText("GİZLİ ŞİRKET")
        vm.setRotation(0)
        val result = vm.success()
        assertEquals(3, result.markedCount)
        assertEquals(3, result.pageCount)
        assertEquals("book_watermarked.pdf", result.suggestedName)
        assertTrue(texts(result.file).all { it.contains("GİZLİ ŞİRKET") })
    }

    @Test
    fun selectedPages_onlyThose() {
        val vm = newVm()
        vm.pick(4)
        vm.setText("MARK")
        vm.setRotation(0)
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("2,4")
        val result = vm.success()
        assertEquals(2, result.markedCount)
        assertEquals(listOf(false, true, false, true), texts(result.file).map { it.contains("MARK") })
    }

    @Test
    fun optionsAreClampedToTheirRanges() {
        val vm = newVm()
        vm.setFontSize(1000)
        vm.setOpacityPercent(0)
        vm.setRotation(-500)
        val s = vm.settled { it.fontSize == 200 && it.opacityPercent == 10 && it.rotation == -90 }
        assertEquals(200, s.fontSize)
    }

    @Test
    fun blankText_blocksWithSpecificError_andStartsNothing() {
        val vm = newVm()
        vm.pick(2)
        vm.setText("   ")
        vm.addWatermark()
        val s = vm.settled { it.failedAttempts == 1 && it.textMissing }
        assertEquals(OperationState.Idle, s.operation)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
        vm.setText("x") // typing clears the error
        assertFalse(vm.settled { it.text == "x" }.textMissing)
    }

    @Test
    fun selected_invalidRange_blocksWithSpecificError() {
        val vm = newVm()
        vm.pick(5)
        vm.setText("x")
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("5-2")
        vm.addWatermark()
        val s = vm.settled { it.failedAttempts == 1 && it.rangeError != null }
        assertEquals(RangeErrorReason.REVERSED, s.rangeError?.reason)
        assertEquals(OperationState.Idle, s.operation)
    }

    @Test
    fun colorPresets_haveNamedRgbValues() {
        assertEquals(listOf(0xFF808080.toInt(), 0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF000000.toInt()), WatermarkColor.entries.map { it.argb })
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        vm.setPosition(WatermarkPosition.TOP)
        assertEquals(OperationState.Idle, vm.settled { it.position == WatermarkPosition.TOP }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.setText("x")
        vm.addWatermark()
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
    fun addWatermark_withoutFile_doesNothing() {
        val vm = newVm()
        vm.addWatermark()
        val s = vm.settled { it.operation == OperationState.Idle && it.file == null && !it.importing }
        assertEquals(0, s.failedAttempts)
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
        assertEquals("book_watermarked.pdf", await { vm.prepareShare() }?.lastPathSegment)
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
        slow.setText("x")
        slow.addWatermark()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setPosition(WatermarkPosition.TILED)
        slow.setText("changed")
        slow.setScope(PageScope.SELECTED)
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        // Never read `state.value` right after an intent: the collector may still be busy on another thread.
        slow.settled { it.position == WatermarkPosition.CENTER && it.text == "x" && it.scope == PageScope.ALL && it.file?.displayName == "a.pdf" && it.operation is OperationState.Running }
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val slow = slowVmWithFile()
        slow.setText("x")
        slow.addWatermark()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
