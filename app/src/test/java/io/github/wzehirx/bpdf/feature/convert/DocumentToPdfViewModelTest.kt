package io.github.wzehirx.bpdf.feature.convert

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.DocumentKind
import io.github.wzehirx.bpdf.core.pdf.DocumentUnreadable
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class DocumentToPdfViewModelTest {
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

    private fun newVm(): DocumentToPdfViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DocumentToPdfViewModel(repo, dispatcher, { context.assets.open(Fonts.ASSET_PATH) }, { "tr-TR" }) as T
        })[DocumentToPdfViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun txt(name: String, text: String): Uri = Uri.fromFile(File(dir, name).apply { writeText(text) })

    private fun DocumentToPdfViewModel.pick(uri: Uri, name: String) {
        pickFile(listOf(uri))
        await { state.first { !it.importing && it.file?.displayName == name } }
    }

    private fun DocumentToPdfViewModel.finish(): OperationState<DocumentToPdfResult> {
        convert()
        @Suppress("UNCHECKED_CAST")
        return await { state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }.operation as OperationState<DocumentToPdfResult>
    }

    @Test
    fun txt_isDetected_andConvertedToAPdfWithTurkishText() {
        val vm = newVm()
        vm.pick(txt("notlar.txt", "Şükrü Öğüt İçerik"), "notlar.txt")
        assertEquals(DocumentKind.TEXT, vm.state.value.kind)
        val r = (vm.finish() as OperationState.Success).value
        assertEquals("notlar_converted.pdf", r.suggestedName)
        assertEquals(1, r.stats.pages)
        assertTrue(TestPdfs.text(r.file).single().contains("Şükrü Öğüt İçerik"))
    }

    @Test
    fun oldDocFormat_failsWithUnreadable_andKeepsNoFile() {
        val vm = newVm()
        vm.pickFile(listOf(txt("eski.doc", "binary")))
        val op = await { vm.state.first { it.operation is OperationState.Failed } }.operation
        assertTrue((op as OperationState.Failed).error is DocumentUnreadable)
        assertNull(vm.state.value.file)
        assertTrue(File(context.cacheDir, "imports").listFiles().orEmpty().isEmpty())
        vm.retry()
        assertEquals(OperationState.Idle, await { vm.state.first { it.operation is OperationState.Idle } }.operation)
    }

    @Test
    fun brokenDocx_failsConversion_andLeavesNoOutput() {
        val vm = newVm()
        vm.pick(txt("bozuk.docx", "not a zip"), "bozuk.docx")
        val op = vm.finish()
        assertTrue((op as OperationState.Failed).error is DocumentUnreadable)
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun replacingFile_deletesOldCopy_andResetsResult() {
        val vm = newVm()
        vm.pick(txt("a.txt", "bir"), "a.txt")
        val first = vm.state.value.file!!.file
        val r = (vm.finish() as OperationState.Success).value
        vm.pick(txt("b.txt", "iki"), "b.txt")
        assertFalse(first.exists())
        assertFalse(r.file.exists())
        assertEquals(OperationState.Idle, vm.state.value.operation)
    }

    @Test
    fun save_writesTarget_andEmitsSaved() {
        val vm = newVm()
        vm.pick(txt("s.txt", "kaydet"), "s.txt")
        vm.finish()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertTrue(target.length() > 0)
    }

    @Test
    fun prepareShare_usesSuggestedName() {
        val vm = newVm()
        assertNull(await { vm.prepareShare() })
        vm.pick(txt("p.txt", "paylaş"), "p.txt")
        vm.finish()
        assertEquals("p_converted.pdf", await { vm.prepareShare() }?.lastPathSegment)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        val vm = newVm()
        vm.pick(txt("c.txt", "temizle"), "c.txt")
        val copy = vm.state.value.file!!.file
        val out = (vm.finish() as OperationState.Success).value.file
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
        assertFalse(out.exists())
    }
}
