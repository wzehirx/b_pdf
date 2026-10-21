package io.github.wzehirx.bpdf.feature.sign

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.SignPosition
import io.github.wzehirx.bpdf.core.pdf.SignaturePoint
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SignViewModelTest {
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

    private fun newVm(io: CoroutineDispatcher = dispatcher): SignViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SignViewModel(repo, { context.assets.open(Fonts.ASSET_PATH) }, io) as T
        })[SignViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String, password: String? = null): Uri {
        val f = TestPdfs.create(dir, pages, "P", password)
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun SignViewModel.pick(pages: Int, name: String = "contract.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun SignViewModel.settled(condition: (SignUiState) -> Boolean) = await { state.first(condition) }

    private fun SignViewModel.success(): SignResult {
        sign()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    private fun slowVmWithFile(): SignViewModel {
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
        gates[slow] = gate
        return slow
    }

    private val gates = mutableMapOf<SignViewModel, kotlinx.coroutines.test.TestDispatcher>()

    private val scribble = listOf(listOf(SignaturePoint(0.1f, 0.2f), SignaturePoint(0.5f, 0.8f), SignaturePoint(0.9f, 0.3f)))

    @Test
    fun defaults_typedMode_bottomRight_noDate() {
        val s = newVm().state.value
        assertEquals(SignMode.TYPED, s.mode)
        assertEquals(SignPosition.BOTTOM_RIGHT, s.position)
        assertFalse(s.addDate)
        assertFalse(s.hasDrawing)
    }

    @Test
    fun pickingAFile_selectsTheLastPage() {
        val vm = newVm()
        vm.pick(4)
        assertEquals("4", vm.settled { it.pageText == "4" }.pageText)
    }

    @Test
    fun typedSignature_turkishText_onTheChosenPage() {
        val vm = newVm()
        vm.pick(3)
        vm.setTypedText("Ayşe Çağlı")
        vm.setPageText("2")
        vm.setPosition(SignPosition.TOP_LEFT)
        val result = vm.success()
        assertEquals(2, result.page)
        assertEquals("contract_signed.pdf", result.suggestedName)
        val pages = TestPdfs.text(result.file)
        assertEquals(listOf(false, true, false), pages.map { it.contains("Ayşe Çağlı") })
    }

    @Test
    fun drawnSignature_addsAnImage_andCleansUpItsTempPicture() {
        val vm = newVm()
        vm.pick(2)
        vm.setMode(SignMode.DRAWN)
        vm.addStroke(scribble[0])
        assertTrue(vm.settled { it.hasDrawing }.hasDrawing)
        val result = vm.success()
        PdfLoader.load(result.file).use { doc ->
            val res = doc.getPage(1).resources
            assertEquals(1, res.xObjectNames.count { res.isImageXObject(it) })
        }
        assertEquals(listOf(result.file.name), File(context.cacheDir, "shared").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun clearDrawing_emptiesIt() {
        val vm = newVm()
        vm.setMode(SignMode.DRAWN)
        vm.addStroke(scribble[0])
        vm.settled { it.hasDrawing }
        vm.clearDrawing()
        assertFalse(vm.settled { !it.hasDrawing && it.strokes.isEmpty() }.hasDrawing)
    }

    @Test
    fun emptyTypedText_blocksWithSpecificError_andStartsNothing() {
        val vm = newVm()
        vm.pick(2)
        vm.setTypedText("   ")
        vm.sign()
        val s = vm.settled { it.failedAttempts == 1 && SignFieldError.TEXT_MISSING in it.fieldErrors }
        assertEquals(OperationState.Idle, s.operation)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
        vm.setTypedText("x") // typing clears the error
        assertTrue(vm.settled { it.typedText == "x" }.fieldErrors.isEmpty())
    }

    @Test
    fun emptyDrawing_blocksWithSpecificError() {
        val vm = newVm()
        vm.pick(2)
        vm.setMode(SignMode.DRAWN)
        vm.sign()
        val s = vm.settled { it.failedAttempts == 1 }
        assertEquals(setOf(SignFieldError.DRAWING_EMPTY), s.fieldErrors)
    }

    @Test
    fun invalidPage_isFlaggedLive_andBlocksSigning() {
        val vm = newVm()
        vm.pick(3)
        vm.setTypedText("A")
        vm.setPageText("9")
        assertEquals(setOf(SignFieldError.PAGE_INVALID), vm.settled { it.pageText == "9" }.fieldErrors)
        vm.sign()
        assertEquals(OperationState.Idle, vm.settled { it.failedAttempts == 1 }.operation)
        vm.setPageText("0")
        assertEquals(setOf(SignFieldError.PAGE_INVALID), vm.settled { it.pageText == "0" }.fieldErrors)
        vm.setPageText("")
        vm.sign()
        assertEquals(setOf(SignFieldError.PAGE_INVALID), vm.settled { it.failedAttempts == 2 }.fieldErrors)
    }

    @Test
    fun pageText_keepsOnlyDigits() {
        val vm = newVm()
        vm.setPageText("1a-2")
        assertEquals("12", vm.settled { it.pageText == "12" }.pageText)
    }

    @Test
    fun encryptedFile_asksForPassword_thenSigns_andNotesUnprotectedCopy() {
        val vm = newVm()
        vm.pickFile(listOf(pdf(2, "secret.pdf", password = "pw")))
        assertEquals("secret.pdf", await { vm.state.first { it.passwordPrompt != null } }.passwordPrompt?.fileName)
        vm.submitPassword("pw")
        vm.settled { it.file != null && it.passwordPrompt == null }
        vm.setTypedText("Ali")
        val result = vm.success()
        assertTrue(vm.state.value.copyUnprotected)
        assertFalse(PdfLoader.isEncrypted(result.file))
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.setTypedText("A")
        val result = vm.success()
        vm.setPosition(SignPosition.CENTER)
        assertEquals(OperationState.Idle, vm.settled { it.position == SignPosition.CENTER }.operation)
        assertFalse(result.file.exists())
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.setTypedText("x")
        vm.sign()
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
    fun sign_withoutFile_doesNothing() {
        val vm = newVm()
        vm.sign()
        val s = vm.settled { it.operation == OperationState.Idle && it.file == null && !it.importing }
        assertEquals(0, s.failedAttempts)
    }

    @Test
    fun save_writesFile_emitsSaved_andKeepsResult() {
        val vm = newVm()
        vm.pick(2)
        vm.setTypedText("A")
        val result = vm.success()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(2, PdfLoader.pageCount(target))
        assertTrue(result.file.exists())
    }

    @Test
    fun prepareShare_returnsUriForResult_orNull() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(2)
        vm.setTypedText("A")
        vm.success()
        assertEquals("contract_signed.pdf", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun remove_deletesCopyAndResetsState() {
        val vm = newVm()
        vm.pick(3)
        val copy = vm.state.value.file!!.file
        vm.remove()
        assertFalse(copy.exists())
        val s = vm.settled { it.file == null }
        assertEquals(0, s.pageCount)
        assertEquals("", s.pageText)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.setTypedText("A")
        val copy = vm.state.value.file!!.file
        val out = vm.success().file
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }

    @Test
    fun whileRunning_changesAreIgnored() {
        val slow = slowVmWithFile()
        slow.setTypedText("x")
        slow.sign()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.setPosition(SignPosition.CENTER)
        slow.setTypedText("changed")
        slow.addStroke(scribble[0])
        slow.remove()
        slow.pickFile(listOf(pdf(1, "b.pdf")))
        slow.settled { it.position == SignPosition.BOTTOM_RIGHT && it.typedText == "x" && !it.hasDrawing && it.file?.displayName == "a.pdf" && it.operation is OperationState.Running }
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation is OperationState.Success } }
    }

    @Test
    fun cancel_whileRunning_leavesNoOutput() {
        val slow = slowVmWithFile()
        slow.setTypedText("x")
        slow.sign()
        await { slow.state.first { it.operation is OperationState.Running } }
        slow.cancel()
        gates.getValue(slow).scheduler.advanceUntilIdle()
        await { slow.state.first { it.operation == OperationState.Idle } }
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }
}
