package io.github.wzehirx.bpdf.feature.protect

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
import io.github.wzehirx.bpdf.core.pdf.PdfPermissions
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProtectViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var vm: ProtectViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProtectViewModel(repo, dispatcher) as T
        })[ProtectViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun pick(name: String = "book.pdf") {
        val f = TestPdfs.create(dir, 2, "Doc")
        vm.pickFile(listOf(Uri.fromFile(File(dir, name).also { f.copyTo(it, overwrite = true) })))
        await { vm.state.first { it.file?.displayName == name && !it.importing } }
    }

    private fun type(user: String, confirm: String, owner: String = "") {
        vm.setUserPassword(user)
        vm.setConfirmPassword(confirm)
        vm.setOwnerPassword(owner)
        await { vm.state.first { it.userPassword == user && it.confirmPassword == confirm && it.ownerPassword == owner } }
    }

    @Test
    fun mismatchedPasswords_areRefused_withErrorAndAttemptCount() {
        pick()
        type("abc", "abd")
        vm.protect()
        val state = await { vm.state.first { it.failedAttempts == 1 } }
        assertEquals(ProtectError.MISMATCH, state.passwordError)
        assertEquals(OperationState.Idle, state.operation)
    }

    @Test
    fun emptyPassword_isRefused() {
        pick()
        vm.protect()
        val state = await { vm.state.first { it.failedAttempts == 1 } }
        assertEquals(ProtectError.EMPTY, state.passwordError)
        // Typing again clears the error until the next attempt.
        type("a", "a")
        assertEquals(null, vm.state.value.passwordError)
    }

    @Test
    fun protect_producesFileThatNeedsThePassword_withChosenPermissions() {
        pick("book.pdf")
        type("open-me", "open-me", "boss")
        vm.setPermissions(PdfPermissions(print = false, copy = true, modify = false))
        await { vm.state.first { !it.permissions.print } }
        vm.protect()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertEquals("book_protected.pdf", done.value.suggestedName)
        try {
            PdfLoader.load(done.value.file)
            fail("expected PasswordRequired")
        } catch (_: PdfError.PasswordRequired) {
        }
        PdfLoader.load(done.value.file, "open-me").use {
            assertFalse(it.currentAccessPermission.canPrint())
            assertTrue(it.currentAccessPermission.canExtractContent())
            assertFalse(it.currentAccessPermission.canModify())
        }
        PdfLoader.load(done.value.file, "boss").use { assertTrue(it.currentAccessPermission.isOwnerPermission) }
    }

    @Test
    fun stateToString_neverContainsPasswords() {
        pick()
        type("sup3r-secret", "sup3r-secret", "own3r-secret")
        assertFalse(vm.state.value.toString().contains("secret"))
    }

    @Test
    fun onCleared_deletesImportAndOutput() {
        pick()
        type("pw", "pw")
        val input = vm.state.value.file!!.file
        vm.protect()
        val out = (await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success).value.file
        store.clear()
        assertFalse(input.exists())
        assertFalse(out.exists())
    }
}
