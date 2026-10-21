package io.github.wzehirx.bpdf.feature.unlock

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
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
class UnlockViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var vm: UnlockViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UnlockViewModel(repo, dispatcher) as T
        })[UnlockViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun uri(name: String, password: String?): Uri {
        val f = TestPdfs.create(dir, 2, "Doc", password)
        return Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })
    }

    private fun pickProtected() {
        vm.pickFile(listOf(uri("locked.pdf", "pw")))
        await { vm.state.first { it.passwordPrompt != null } }
        vm.submitPassword("pw")
        await { vm.state.first { it.file != null && !it.importing && it.passwordPrompt == null } }
    }

    @Test
    fun protectedFile_unlocks_toACopyThatOpensWithoutPassword() {
        pickProtected()
        assertFalse(vm.state.value.notProtected)
        vm.unlock()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertEquals("locked_unlocked.pdf", done.value.suggestedName)
        assertEquals(listOf("Doc1", "Doc2"), TestPdfs.text(done.value.file))
        PdfLoader.load(done.value.file).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun wrongPassword_repromptsWithoutFailing() {
        vm.pickFile(listOf(uri("locked.pdf", "pw")))
        await { vm.state.first { it.passwordPrompt != null } }
        vm.submitPassword("nope")
        val state = await { vm.state.first { it.passwordPrompt?.wrong == true } }
        assertTrue(state.importing)
        assertEquals(OperationState.Idle, state.operation)
    }

    @Test
    fun unprotectedFile_isFlagged_andUnlockDoesNothing() {
        vm.pickFile(listOf(uri("plain.pdf", null)))
        val state = await { vm.state.first { it.file != null && !it.importing } }
        assertTrue(state.notProtected)
        vm.unlock()
        assertEquals(OperationState.Idle, vm.state.value.operation)
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        pickProtected()
        val input = vm.state.value.file!!.file
        vm.unlock()
        val out = (await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success).value.file
        store.clear()
        assertFalse(input.exists())
        assertFalse(out.exists())
    }
}
