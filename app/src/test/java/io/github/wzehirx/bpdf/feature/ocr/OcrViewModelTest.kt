package io.github.wzehirx.bpdf.feature.ocr

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.ocr.FakeOcrEngine
import io.github.wzehirx.bpdf.core.ocr.FakeRasterizer
import io.github.wzehirx.bpdf.core.ocr.OcrEngine
import io.github.wzehirx.bpdf.core.ocr.OcrFailed
import io.github.wzehirx.bpdf.core.ocr.OcrLanguage
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.feature.pdftext.TextResult
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class OcrViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var repo: FileRepository
    private lateinit var dir: File

    private var engine = FakeOcrEngine()
    private var rasterizer = FakeRasterizer()
    private val engineRequests = mutableListOf<String>()
    private var engineFailure: Throwable? = null
    private var fontHook: (() -> Unit)? = null

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

    private fun newVm(): OcrViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = OcrViewModel(
                repo,
                fontLoader = { fontHook?.invoke(); context.assets.open(Fonts.ASSET_PATH) },
                engineFactory = { languages ->
                    engineRequests += languages
                    engineFailure?.let { throw it }
                    engine as OcrEngine
                },
                rasterizerFactory = { rasterizer },
                ioDispatcher = dispatcher,
                pageHeader = { "== Page $it ==" },
            ) as T
        })[OcrViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pdf(pages: Int, name: String, password: String? = null): Uri {
        val f = TestPdfs.create(dir, pages, "P", password)
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun OcrViewModel.pick(pages: Int, name: String = "scan.pdf") {
        pickFile(listOf(pdf(pages, name)))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun OcrViewModel.settled(condition: (OcrUiState) -> Boolean) = await { state.first(condition) }

    private fun OcrViewModel.success(): TextResult {
        recognize()
        val op = await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation
        return (op as OperationState.Success).value
    }

    private fun sharedFiles() = File(context.cacheDir, "shared").listFiles().orEmpty().toList()

    @Test
    fun defaults_turkishAndEnglish_allPages() {
        val s = newVm().state.value
        assertEquals(OcrLanguage.TURKISH_ENGLISH, s.language)
        assertEquals(PageScope.ALL, s.scope)
    }

    @Test
    fun recognizeAll_readsEveryPageInOrder_withTurkishAndEnglish_andWritesTheTxt() {
        val vm = newVm()
        vm.pick(3)
        val result = vm.success()
        assertEquals(listOf("text of page 1", "text of page 2", "text of page 3"), result.pages.map { it.text })
        assertEquals("scan_text.txt", result.suggestedName)
        assertEquals(listOf("tur+eng"), engineRequests)
        assertEquals(listOf("tur+eng", "tur+eng", "tur+eng"), engine.languages)
        assertEquals(
            "== Page 1 ==\ntext of page 1\n\n== Page 2 ==\ntext of page 2\n\n== Page 3 ==\ntext of page 3\n",
            result.file!!.readText(),
        )
        assertEquals(listOf(300, 300, 300), rasterizer.dpis)
    }

    @Test
    fun theEngineAndRasterizerAreReleased_afterSuccess() {
        val vm = newVm()
        vm.pick(2)
        vm.success()
        assertTrue(engine.closed)
        assertTrue(rasterizer.closed)
    }

    @Test
    fun languageChoice_isPassedToTheEngine() {
        val vm = newVm()
        vm.pick(1)
        vm.setLanguage(OcrLanguage.TURKISH)
        vm.settled { it.language == OcrLanguage.TURKISH }
        vm.success()
        assertEquals(listOf("tur"), engineRequests)
    }

    @Test
    fun selectedPages_onlyThose() {
        val vm = newVm()
        vm.pick(5)
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("2,4")
        val result = vm.success()
        assertEquals(listOf(1, 3), result.pages.map { it.pageIndex })
        assertEquals(listOf(1, 3), rasterizer.rendered)
    }

    @Test
    fun invalidRange_blocksWithSpecificError_andStartsNothing() {
        val vm = newVm()
        vm.pick(5)
        vm.setScope(PageScope.SELECTED)
        vm.setRangeText("5-2")
        vm.recognize()
        val s = vm.settled { it.failedAttempts == 1 && it.rangeError != null }
        assertEquals(RangeErrorReason.REVERSED, s.rangeError?.reason)
        assertEquals(OperationState.Idle, s.operation)
        assertTrue(engineRequests.isEmpty())
    }

    @Test
    fun nothingRecognized_hasNoTxtFile() {
        engine = FakeOcrEngine(textFor = { "" })
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        assertTrue(result.allBlank)
        assertNull(result.file)
    }

    @Test
    fun progress_namesThePageBeingRead_andClearsWhenDone() {
        val atPageTwo = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine = FakeOcrEngine(onRecognize = { call ->
            if (call == 2) {
                atPageTwo.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        })
        val vm = newVm()
        vm.pick(3)
        vm.recognize()
        assertTrue(atPageTwo.await(10, TimeUnit.SECONDS))
        assertEquals(OcrPageProgress(2, 3, 2), vm.settled { it.progress?.position == 2 }.progress)
        release.countDown()
        val done = vm.settled { it.operation is OperationState.Success }
        assertNull(done.progress)
    }

    @Test
    fun cancel_stopsBetweenPages_releasesTheEngine_andLeavesNoOutput() {
        val atPageTwo = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine = FakeOcrEngine(onRecognize = { call ->
            if (call == 2) {
                atPageTwo.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        })
        val vm = newVm()
        vm.pick(5)
        vm.recognize()
        assertTrue(atPageTwo.await(10, TimeUnit.SECONDS))
        vm.cancel()
        // the engine is asked to stop at once, even while it is inside page 2
        val deadline = System.currentTimeMillis() + 10_000
        while (!engine.stopped && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(engine.stopped)
        release.countDown()
        while (!engine.closed && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("engine released", engine.closed)
        // the worker closes the engine first and the rasterizer right after, on another thread
        while (!rasterizer.closed && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(OperationState.Idle, vm.settled { it.operation == OperationState.Idle }.operation)
        assertEquals("no page after the cancelled one", listOf(0, 1), rasterizer.rendered)
        assertTrue(rasterizer.closed)
        assertTrue(rasterizer.bitmaps.all { it.isRecycled })
        assertTrue(sharedFiles().isEmpty())
    }

    @Test
    fun engineThatCannotStart_failsWithOcrFailed_andLeavesNothingBehind() {
        engineFailure = OcrFailed("no data")
        val vm = newVm()
        vm.pick(2)
        vm.recognize()
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is OcrFailed)
        assertTrue(rasterizer.rendered.isEmpty())
        assertTrue(sharedFiles().isEmpty())
    }

    @Test
    fun encryptedInput_asksForPassword_andRendersADecryptedCopy_thatIsDeleted() {
        val vm = newVm()
        vm.pickFile(listOf(pdf(2, "locked.pdf", password = "pw")))
        assertEquals("locked.pdf", await { vm.state.first { it.passwordPrompt != null } }.passwordPrompt?.fileName)
        vm.submitPassword("pw")
        vm.settled { it.file != null && it.passwordPrompt == null }
        val result = vm.success()
        assertEquals(2, result.pages.size)
        assertEquals(listOf(false, false), rasterizer.encryptedWhenRendered)
        assertTrue(rasterizer.files.none { it.exists() })
        assertTrue(vm.state.value.copyUnprotected)
    }

    @Test
    fun failedRun_leavesNoOutput() {
        val vm = newVm()
        vm.pick(2)
        vm.state.value.file!!.file.writeText("garbage") // corrupt after import
        vm.recognize()
        assertTrue(await { vm.state.first { it.operation is OperationState.Failed } }.operation is OperationState.Failed)
        assertTrue(sharedFiles().isEmpty())
    }

    @Test
    fun saveText_writesTheTxt_emitsSaved_andKeepsResult() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        val target = File(dir, "saved.txt")
        vm.saveText(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(result.file!!.readText(), target.readText())
        assertTrue(result.file.exists())
    }

    @Test
    fun saveSearchablePdf_keepsOriginalPages_andAddsTheRecognizedText() {
        engine = FakeOcrEngine(textFor = { "Ayşe Çağlı okudu ${it + 1}" })
        val vm = newVm()
        vm.pick(2)
        vm.success()
        assertEquals("scan_searchable.pdf", vm.searchablePdfName())
        val target = File(dir, "searchable.pdf")
        vm.saveSearchablePdf(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        val text = TestPdfs.text(target)
        assertEquals(2, text.size)
        assertTrue(text[0], text[0].contains("P1") && text[0].contains("Ayşe Çağlı okudu 1"))
        assertTrue(text[1], text[1].contains("P2") && text[1].contains("Ayşe Çağlı okudu 2"))
        assertFalse(vm.settled { !it.saving }.saving)
        assertTrue("temporary build is cleaned up", sharedFiles().none { it.extension == "pdf" })
    }

    @Test
    fun saveSearchablePdf_ofAnEncryptedInput_isUnprotected() {
        val vm = newVm()
        vm.pickFile(listOf(pdf(1, "locked.pdf", password = "pw")))
        await { vm.state.first { it.passwordPrompt != null } }
        vm.submitPassword("pw")
        vm.settled { it.file != null && it.passwordPrompt == null }
        vm.success()
        val target = File(dir, "out.pdf")
        vm.saveSearchablePdf(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertFalse(PdfLoader.isEncrypted(target))
        assertTrue(TestPdfs.text(target).single().contains("text of page 1"))
    }

    @Test
    fun saveSearchablePdf_cancelledWhileBuilding_writesNothing_andKeepsTheResult() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        // The build runs inline on the test dispatcher; cancel it from inside, at the font load.
        var buildingWhenCancelled: Boolean? = null
        fontHook = { buildingWhenCancelled = runBlocking { withTimeout(5_000) { vm.state.first { it.buildingPdf } } }.buildingPdf; vm.cancelBuild() }
        val target = File(dir, "cancelled.pdf")
        vm.saveSearchablePdf(Uri.fromFile(target))
        vm.settled { !it.saving && !it.buildingPdf }
        assertEquals("the build state was on while building", true, buildingWhenCancelled)
        assertFalse(target.exists())
        assertTrue("temporary build is cleaned up", sharedFiles().none { it.extension == "pdf" })
        assertTrue(vm.state.value.operation is OperationState.Success)
        assertTrue(result.file!!.exists())
        // and it can be saved afterwards
        fontHook = null
        vm.saveSearchablePdf(Uri.fromFile(File(dir, "later.pdf")))
        await { vm.savedEvents.first() }
        assertFalse(vm.settled { !it.saving }.buildingPdf)
    }

    @Test
    fun saveSearchablePdf_withNothingRecognized_doesNothing() {
        engine = FakeOcrEngine(textFor = { "" })
        val vm = newVm()
        vm.pick(1)
        vm.success()
        val target = File(dir, "none.pdf")
        vm.saveSearchablePdf(Uri.fromFile(target))
        assertFalse(vm.state.value.saving)
        assertFalse(target.exists())
    }

    @Test
    fun saveSearchablePdf_whenTheTargetFails_showsAnErrorNotACrash() {
        val vm = newVm()
        vm.pick(1)
        vm.success()
        vm.saveSearchablePdf(Uri.parse("content://no.such.provider/doc"))
        assertTrue(await { vm.state.first { it.operation is OperationState.Failed } }.operation is OperationState.Failed)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun prepareShare_returnsUriForTheTxt_orNull() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(1)
        vm.success()
        assertEquals("scan_text.txt", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun editingAfterSuccess_clearsResultAndDeletesTheTxt() {
        val vm = newVm()
        vm.pick(2)
        val result = vm.success()
        vm.setLanguage(OcrLanguage.ENGLISH)
        assertEquals(OperationState.Idle, vm.settled { it.language == OcrLanguage.ENGLISH }.operation)
        assertFalse(result.file!!.exists())
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
        val out = vm.success().file!!
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }

    @Test
    fun whileRunning_changesAreIgnored() {
        val atPageOne = CountDownLatch(1)
        val release = CountDownLatch(1)
        engine = FakeOcrEngine(onRecognize = { call ->
            if (call == 1) {
                atPageOne.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        })
        val vm = newVm()
        vm.pick(3, "a.pdf")
        vm.recognize()
        assertTrue(atPageOne.await(10, TimeUnit.SECONDS))
        vm.setLanguage(OcrLanguage.ENGLISH)
        vm.setScope(PageScope.SELECTED)
        vm.remove()
        vm.pickFile(listOf(pdf(1, "b.pdf")))
        vm.recognize() // a second start is ignored
        vm.settled { it.language == OcrLanguage.TURKISH_ENGLISH && it.scope == PageScope.ALL && it.file?.displayName == "a.pdf" && it.operation is OperationState.Running }
        release.countDown()
        await { vm.state.first { it.operation is OperationState.Success } }
        assertEquals(1, engineRequests.size)
    }
}
