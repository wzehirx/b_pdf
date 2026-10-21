package io.github.wzehirx.bpdf.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.feature.merge.MergeViewModel
import io.github.wzehirx.bpdf.feature.organize.OrganizeViewModel
import io.github.wzehirx.bpdf.feature.pages.PageSelectMode
import io.github.wzehirx.bpdf.feature.pages.PageSelectViewModel
import io.github.wzehirx.bpdf.feature.pdfimages.PdfToImagesViewModel
import io.github.wzehirx.bpdf.feature.rotate.RotateViewModel
import io.github.wzehirx.bpdf.feature.split.SplitModeChoice
import io.github.wzehirx.bpdf.feature.split.SplitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every PDF-input tool asks for the password of a protected file, re-asks after a wrong one and then works. */
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PasswordFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
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

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun uri(name: String, pages: Int, prefix: String, password: String? = null): Uri {
        val f = TestPdfs.create(dir, pages, prefix, password)
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun locked(name: String = "locked.pdf", pages: Int = 2) = uri(name, pages, "Sec", "right")

    private fun <S> StateFlow<S>.until(condition: (S) -> Boolean): S = await { first(condition) }

    @Test
    fun merge_encryptedFile_asksForPassword_wrongPasswordReprompts_thenMerges() {
        val vm = MergeViewModel(repo, dispatcher)
        vm.addFiles(listOf(locked(pages = 1), uri("plain.pdf", 1, "Pln")))
        val asked = vm.state.until { it.passwordPrompt != null }
        assertEquals("locked.pdf", asked.passwordPrompt!!.fileName)
        assertFalse(asked.passwordPrompt!!.wrong)
        assertTrue(asked.importing)

        vm.submitPassword("bad")
        assertTrue(vm.state.until { it.passwordPrompt?.wrong == true }.passwordPrompt!!.wrong)

        vm.submitPassword("right")
        vm.state.until { !it.importing && it.files.size == 2 && it.passwordPrompt == null }
        vm.merge()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        assertEquals(listOf("Sec1", "Pln1"), TestPdfs.text(done.value.file))
    }

    @Test
    fun merge_dismissedPrompt_skipsThatFileOnly() {
        val vm = MergeViewModel(repo, dispatcher)
        vm.addFiles(listOf(locked(), uri("plain.pdf", 1, "Pln")))
        vm.state.until { it.passwordPrompt != null }
        vm.dismissPassword()
        val state = vm.state.until { !it.importing && it.passwordPrompt == null && it.files.isNotEmpty() }
        assertEquals(listOf("plain.pdf"), state.files.map { it.displayName })
        assertFalse(state.operation is OperationState.Failed)
    }

    @Test
    fun rotate_encryptedFile_promptsThenRotatesWithPassword() {
        val vm = RotateViewModel(repo, dispatcher)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("nope")
        vm.state.until { it.passwordPrompt?.wrong == true }
        vm.submitPassword("right")
        val picked = vm.state.until { it.file != null && it.passwordPrompt == null && !it.importing }
        assertEquals(2, picked.pageCount)
        assertEquals("right", vm.passwordOf(picked.file!!.file))
        vm.rotate()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        // Opens without a password and keeps both pages (rotated text is extracted in pieces).
        PdfLoader.load(done.value.file).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun rotate_dismissedPrompt_discardsFileWithoutError() {
        val vm = RotateViewModel(repo, dispatcher)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.dismissPassword()
        val state = vm.state.until { it.passwordPrompt == null && !it.importing }
        assertNull(state.file)
        assertFalse(state.operation is OperationState.Failed)
        assertEquals(emptyList<String>(), File(context.cacheDir, "imports").list().orEmpty().toList())
    }

    @Test
    fun split_encryptedFile_promptsThenSplits() {
        val vm = SplitViewModel(repo, dispatcher)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("right")
        vm.state.until { it.file != null && !it.importing }
        vm.setMode(SplitModeChoice.EACH_PAGE)
        vm.split()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        assertEquals(listOf("Sec1"), TestPdfs.text(done.value.files.first()))
        assertEquals(2, done.value.files.size)
    }

    @Test
    fun pages_encryptedFile_promptsThenExtracts() {
        val vm = PageSelectViewModel(PageSelectMode.EXTRACT, repo, dispatcher)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("right")
        vm.state.until { it.file != null && !it.importing }
        vm.setRangeText("2")
        vm.run()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        assertEquals(listOf("Sec2"), TestPdfs.text(done.value.file))
    }

    @Test
    fun organize_encryptedFile_promptsThenOrganizes() {
        val vm = OrganizeViewModel(repo, dispatcher)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("right")
        vm.state.until { it.file != null && !it.importing }
        vm.moveTo(0, 1)
        vm.organize()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        assertEquals(listOf("Sec2", "Sec1"), TestPdfs.text(done.value.file))
    }

    @Test
    fun pdfToImages_encryptedFile_rendersDecryptedCopyAndDeletesIt() {
        val rendered = mutableListOf<File>()
        var readableWithoutPassword = true
        val rasterizer = object : PageRasterizer {
            override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
                rendered += file
                readableWithoutPassword = readableWithoutPassword && runCatching { PdfLoader.load(file).close() }.isSuccess
                return Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            }
        }
        val vm = PdfToImagesViewModel(repo, dispatcher) { rasterizer }
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("right")
        val picked = vm.state.until { it.file != null && !it.importing }
        vm.convert()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        assertEquals(2, done.value.files.size)
        assertTrue(readableWithoutPassword)
        assertTrue(rendered.none { it == picked.file!!.file })
        assertTrue(rendered.all { !it.exists() })
        assertNotNull(picked.file)
    }

    @Test
    fun copyUnprotected_isFlaggedOnlyWhenAPasswordWasNeeded() {
        val vm = RotateViewModel(repo, dispatcher)
        vm.pickFile(listOf(uri("plain.pdf", 1, "Pln")))
        assertFalse(vm.state.until { it.file != null && !it.importing }.copyUnprotected)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("right")
        assertTrue(vm.state.until { it.file?.displayName == "locked.pdf" && !it.importing }.copyUnprotected)
        vm.remove()
        assertFalse(vm.state.until { it.file == null }.copyUnprotected)
    }

    @Test
    fun merge_copyUnprotected_followsProtectedInputs() {
        val vm = MergeViewModel(repo, dispatcher)
        vm.addFiles(listOf(locked(pages = 1), uri("plain.pdf", 1, "Pln")))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("right")
        assertTrue(vm.state.until { it.files.size == 2 && !it.importing }.copyUnprotected)
        vm.remove(0)
        assertFalse(vm.state.until { it.files.size == 1 }.copyUnprotected)
    }

    @Test
    fun compress_encryptedFile_promptsThenCompressesToAnUnprotectedCopy() {
        val vm = io.github.wzehirx.bpdf.feature.compress.CompressViewModel(repo, dispatcher)
        vm.pickFile(listOf(locked()))
        vm.state.until { it.passwordPrompt != null }
        vm.submitPassword("nope")
        vm.state.until { it.passwordPrompt?.wrong == true }
        vm.submitPassword("right")
        assertTrue(vm.state.until { it.file != null && it.passwordPrompt == null && !it.importing }.copyUnprotected)
        vm.compress()
        val done = vm.state.until { it.operation is OperationState.Success }.operation as OperationState.Success
        assertEquals(listOf("Sec1", "Sec2"), TestPdfs.text(done.value.file))
    }
}
