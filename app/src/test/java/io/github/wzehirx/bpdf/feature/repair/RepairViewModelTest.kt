package io.github.wzehirx.bpdf.feature.repair

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RepairViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var vm: RepairViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RepairViewModel(repo, dispatcher) as T
        })[RepairViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun uri(name: String, password: String? = null, damage: (File) -> File = { it }): Uri {
        val f = damage(TestPdfs.create(dir, 3, "Doc", password))
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun brokenXref(f: File): File {
        val text = f.readBytes().toString(Charsets.ISO_8859_1)
        val broken = text.substring(0, text.lastIndexOf("startxref")) + "startxref\n999999\n%%EOF\n"
        return File(dir, "x-${f.name}").also { it.writeBytes(broken.toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test
    fun damagedFile_canBePicked_andIsRepaired() {
        vm.pickFile(listOf(uri("broken.pdf", damage = ::brokenXref)))
        val picked = await { vm.state.first { it.file != null && !it.importing } }
        assertEquals(3, picked.pageCount) // PDFBox can still read this one
        vm.repair()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertEquals(3, done.value.pagesRecovered)
        assertEquals("broken_repaired.pdf", done.value.suggestedName)
        assertEquals(listOf("Doc1", "Doc2", "Doc3"), TestPdfs.text(done.value.file))
    }

    @Test
    fun hopelessFile_isPickedAsUnreadable_andRepairFailsWithCorrupt() {
        val junk = File(dir, "junk.pdf").apply { writeText("not a pdf at all") }
        vm.pickFile(listOf(Uri.fromFile(junk)))
        val picked = await { vm.state.first { it.file != null && !it.importing } }
        assertEquals(null, picked.pageCount)
        vm.repair()
        val failed = await { vm.state.first { it.operation is OperationState.Failed } }.operation as OperationState.Failed
        assertTrue(failed.error is PdfError.Corrupt)
        assertEquals(emptyList<String>(), File(context.cacheDir, "shared").list().orEmpty().toList())
    }

    @Test
    fun protectedFile_asksForPassword_thenRepairs() {
        vm.pickFile(listOf(uri("locked.pdf", password = "pw")))
        await { vm.state.first { it.passwordPrompt != null } }
        vm.submitPassword("pw")
        await { vm.state.first { it.file != null && !it.importing } }
        vm.repair()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertEquals(3, done.value.pagesRecovered)
        PdfLoader.load(done.value.file).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        vm.pickFile(listOf(uri("a.pdf")))
        val input = await { vm.state.first { it.file != null && !it.importing } }.file!!.file
        vm.repair()
        val out = (await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success).value.file
        store.clear()
        assertFalse(input.exists())
        assertFalse(out.exists())
    }
}
