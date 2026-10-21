package io.github.wzehirx.bpdf.feature.pagenumbers

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
import io.github.wzehirx.bpdf.core.pdf.NumberPosition
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
class PageNumbersViewModelTest {
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

    private fun newVm(io: CoroutineDispatcher = dispatcher): PageNumbersViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PageNumbersViewModel(repo, { context.assets.open(Fonts.ASSET_PATH) }, { "Sayfa {n} / {total}" }, io) as T
        })[PageNumbersViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String): Uri {
        val f = TestPdfs.create(dir, pages, "P")
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun PageNumbersViewModel.pick(pages: Int, name: String = "book.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun PageNumbersViewModel.settled(condition: (PageNumbersUiState) -> Boolean) = await { state.first(condition) }

    private fun PageNumbersViewModel.success(): PageNumbersResult {
        addPageNumbers()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    private fun slowVmWithFile(): PageNumbersViewModel {
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

    private val gates = mutableMapOf<PageNumbersViewModel, kotlinx.coroutines.test.TestDispatcher>()

    private fun texts(file: File) = TestPdfs.text(file)

    @Test
    fun defaults_numberBottomCenter_startingAtOne() {
        val s = newVm().state.value
        assertEquals(NumberPosition.BOTTOM_CENTER, s.position)
        assertEquals(NumberFormat.NUMBER, s.format)
        assertEquals("1", s.startText)
        assertEquals(12, s.fontSize)
    }

    @Test
    fun numberOfTotalPreset_numbersEachPage() {
        val vm = newVm()
        vm.pick(3)
        vm.setFormat(NumberFormat.NUMBER_OF_TOTAL)
        val result = vm.success()
        assertEquals(3, result.numberedCount)
        assertEquals("book_numbered.pdf", result.suggestedName)
        assertTrue(texts(result.file)[1].contains("2 / 3"))
    }

    @Test
    fun localizedPreset_usesTheLanguagePattern_andTurkishLettersRoundTrip() {
        val vm = newVm()
        vm.pick(2)
        vm.setFormat(NumberFormat.LOCALIZED)
        assertTrue(texts(vm.success().file)[0].contains("Sayfa 1 / 2"))
        vm.setFormat(NumberFormat.CUSTOM)
        vm.setCustomFormat("Sayfa {n} · ğüşıöçİ")
        assertTrue(texts(vm.success().file)[1].contains("Sayfa 2 · ğüşıöçİ"))
    }

    @Test
    fun startNumber_shiftsNumbering_andKeepsOnlyDigits() {
        val vm = newVm()
        vm.pick(2)
        vm.setStartText("5x")
        assertEquals("5", vm.settled { it.startText == "5" }.startText)
        vm.setFormat(NumberFormat.CUSTOM)
        vm.setCustomFormat("<{n}>")
        val result = vm.success()
        assertTrue(texts(result.file)[0].contains("<5>"))
        assertTrue(texts(result.file)[1].contains("<6>"))
    }

    @Test
    fun selectedPages_onlyThoseAreNumbered() {
        val vm = newVm()
        vm.pick(4)
        vm.setFormat(NumberFormat.CUSTOM)
        vm.setCustomFormat("#{n}")
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("2,4")
        val result = vm.success()
        assertEquals(2, result.numberedCount)
        assertEquals(listOf(false, true, false, true), texts(result.file).map { it.contains("#") })
    }

    @Test
    fun invalidStart_andEmptyCustomFormat_blockWithSpecificErrors() {
        val vm = newVm()
        vm.pick(2)
        vm.setStartText("")
        vm.setFormat(NumberFormat.CUSTOM)
        vm.setCustomFormat(" ")
        vm.addPageNumbers()
        val s = vm.settled { it.failedAttempts == 1 && it.fieldErrors.size == 2 }
        assertEquals(setOf(NumberFieldError.FORMAT_EMPTY, NumberFieldError.START_INVALID), s.fieldErrors)
        assertEquals(OperationState.Idle, s.operation)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
        vm.setStartText("0") // zero is not allowed either
        vm.setCustomFormat("{n}")
        vm.addPageNumbers()
        assertEquals(setOf(NumberFieldError.START_INVALID), vm.settled { it.failedAttempts == 2 }.fieldErrors)
    }

    @Test
    fun selected_invalidRange_blocksWithSpecificError() {
        val vm = newVm()
        vm.pick(5)
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("0")
        vm.addPageNumbers()
        val s = vm.settled { it.failedAttempts == 1 && it.rangeError != null }
        assertEquals(OperationState.Idle, s.operation)
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        vm.setPosition(NumberPosition.TOP_RIGHT)
        assertEquals(OperationState.Idle, vm.settled { it.position == NumberPosition.TOP_RIGHT }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.addPageNumbers()
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
    fun addPageNumbers_withoutFile_doesNothing() {
        val vm = newVm()
        vm.addPageNumbers()
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
        assertEquals("book_numbered.pdf", await { vm.prepareShare() }?.lastPathSegment)
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
        slow.setFormat(NumberFormat.NUMBER)
        slow.addPageNumbers()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setPosition(NumberPosition.TOP_LEFT)
        slow.setFontSize(20)
        slow.setScope(PageScope.SELECTED)
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        // Never read `state.value` right after an intent: the collector may still be busy on another thread.
        slow.settled { it.position == NumberPosition.BOTTOM_CENTER && it.fontSize == 12 && it.scope == PageScope.ALL && it.file?.displayName == "a.pdf" && it.operation is OperationState.Running }
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val slow = slowVmWithFile()
        slow.setFormat(NumberFormat.NUMBER)
        slow.addPageNumbers()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
