package io.github.wzehirx.bpdf.feature.metadata

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.MetadataOperation
import io.github.wzehirx.bpdf.core.pdf.PdfMetadata
import io.github.wzehirx.bpdf.core.pdf.PdfPermissions
import io.github.wzehirx.bpdf.core.pdf.ProtectOperation
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
class MetadataViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var vm: MetadataViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MetadataViewModel(repo, dispatcher) as T
        })[MetadataViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private val turkish = "Şükrü Öğüt — İçerik"

    private fun uri(name: String, password: String? = null, title: String? = turkish): Uri {
        val f = TestPdfs.create(dir, 2, "Doc")
        val tagged = File(dir, "tagged-$name")
        val md = PdfMetadata(title, "Yazar Çağrı", null, "çay, ışık", null, null, 2, false, 1.7f)
        MetadataOperation.write(f, md, tagged)
        if (password != null) {
            // MetadataOperation writes unencrypted copies, so protect the tagged file afterwards.
            val protectedCopy = File(dir, "locked-$name")
            ProtectOperation.run(tagged, password, null, PdfPermissions(), protectedCopy)
            protectedCopy.copyTo(tagged, overwrite = true)
        }
        return Uri.fromFile(File(dir, name).also { tagged.copyTo(it, overwrite = true) })
    }

    private fun pick(name: String = "doc.pdf") {
        vm.pickFile(listOf(uri(name)))
        await { vm.state.first { it.file?.displayName == name && it.info != null && !it.importing } }
    }

    @Test
    fun pick_showsReadOnlyInfo_andPrefillsFields_withTurkishText() {
        pick()
        val s = vm.state.value
        assertEquals(2, s.info!!.pageCount)
        assertFalse(s.info!!.encrypted)
        assertEquals(turkish, s.title)
        assertEquals("Yazar Çağrı", s.author)
        assertEquals("çay, ışık", s.keywords)
        assertEquals("", s.subject)
    }

    @Test
    fun edit_thenWrite_savesNewTitle_andKeepsOtherFields() {
        pick()
        vm.setTitle("İkinci Başlık ğüşiöç")
        vm.setSubject("Konu")
        await { vm.state.first { it.title == "İkinci Başlık ğüşiöç" && it.subject == "Konu" } }
        vm.write()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertEquals("doc_info.pdf", done.value.suggestedName)
        val md = MetadataOperation.read(done.value.file)
        assertEquals("İkinci Başlık ğüşiöç", md.title)
        assertEquals("Konu", md.subject)
        assertEquals("Yazar Çağrı", md.author)
        assertEquals(listOf("Doc1", "Doc2"), TestPdfs.text(done.value.file))
    }

    @Test
    fun clearedField_isRemovedFromTheFile() {
        pick()
        vm.setTitle("")
        await { vm.state.first { it.title.isEmpty() } }
        vm.write()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertEquals(null, MetadataOperation.read(done.value.file).title)
    }

    @Test
    fun protectedFile_asksForPassword_thenShowsInfo() {
        vm.pickFile(listOf(uri("locked.pdf", password = "pw")))
        await { vm.state.first { it.passwordPrompt != null } }
        vm.submitPassword("wrong")
        await { vm.state.first { it.passwordPrompt?.wrong == true } }
        vm.submitPassword("pw")
        val s = await { vm.state.first { it.info != null && it.passwordPrompt == null } }
        assertTrue(s.info!!.encrypted)
        assertEquals(turkish, s.title)
        vm.write()
        val done = await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success
        assertFalse(MetadataOperation.read(done.value.file).encrypted)
    }

    @Test
    fun editsAreIgnoredWithoutFile_andOnCleared_deletesFiles() {
        vm.setTitle("x")
        vm.write()
        assertEquals(OperationState.Idle, vm.state.value.operation)
        pick()
        val input = vm.state.value.file!!.file
        vm.write()
        val out = (await { vm.state.first { it.operation is OperationState.Success } }.operation as OperationState.Success).value.file
        store.clear()
        assertFalse(input.exists())
        assertFalse(out.exists())
    }
}
