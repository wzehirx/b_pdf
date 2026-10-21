package io.github.wzehirx.bpdf.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.files.RecentFilesStore
import io.github.wzehirx.bpdf.core.files.SourceUnavailable
import io.github.wzehirx.bpdf.core.pdf.PageBitmapSource
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.core.pdf.ThumbnailLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewerViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: FileRepository
    private lateinit var dir: File
    private lateinit var recents: RecentFilesStore

    /** Stands in for PdfRenderer (which cannot draw under Robolectric); remembers what it was asked for. */
    private class FakeSource : PageBitmapSource {
        override val pageCount = 10
        override fun render(pageIndex: Int, widthPx: Int): Bitmap =
            Bitmap.createBitmap(widthPx, widthPx * 2, Bitmap.Config.ARGB_8888)
        override fun close() {}
    }

    private val opened = mutableListOf<Pair<File, String?>>()
    private var persisted = mutableSetOf<Uri>()
    private val released = mutableListOf<Uri>()
    private val persistCalls = mutableListOf<Uri>()

    @Before
    fun setUp() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
        Dispatchers.setMain(dispatcher)
        PdfBoxInit.ensure(context)
        dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        repo = FileRepository(context)
        recents = RecentFilesStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "recent.preferences_pb") }),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private val access = UriAccess(
        persist = { persistCalls += it; persisted += it; true },
        isPersisted = { it in persisted },
        release = { released += it },
    )

    private fun newVm(initial: Uri? = null): ViewerViewModel =
        ViewModelProvider(ViewModelStore().also { stores += it }, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val loader = ThumbnailLoader(File(tmp.root, "render"), 50_000_000, dispatcher) { file, password, _ ->
                    opened += file to password
                    FakeSource()
                }
                return ViewerViewModel(repo, recents, loader, initial, access, dispatcher) as T
            }
        })[ViewerViewModel::class.java]

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun uriOf(source: File, name: String): Uri =
        Uri.fromFile(File(dir, name).also { source.copyTo(it, overwrite = true) })

    private fun ViewerViewModel.open(pages: Int, name: String = "book.pdf", prefix: String = "V", keep: Boolean = false): Uri {
        val uri = uriOf(TestPdfs.create(dir, pages, prefix), name)
        if (keep) persisted += uri
        open(uri)
        await { state.first { !it.importing && it.file?.displayName == name } }
        return uri
    }

    private fun ViewerViewModel.settled(condition: (ViewerUiState) -> Boolean) = await { state.first(condition) }

    @Test
    fun open_importsTheFile_startsOnPageOneInPageMode() {
        val vm = newVm()
        vm.open(4)
        val s = vm.state.value
        assertEquals(4, s.pageCount)
        assertEquals(ViewerMode.PAGE, s.mode)
        assertEquals(0, s.currentPage)
        assertEquals(1f, s.zoom, 0f)
    }

    @Test
    fun initialUri_isOpenedByTheViewModelItself() {
        val vm = newVm(initial = uriOf(TestPdfs.create(dir, 2), "given.pdf"))
        val s = vm.settled { it.file?.displayName == "given.pdf" && !it.importing }
        assertEquals(2, s.pageCount)
    }

    @Test
    fun navigation_isClampedToTheDocument() {
        val vm = newVm()
        vm.open(3)
        vm.previousPage()
        assertEquals(0, vm.settled { true }.currentPage)
        vm.nextPage(); vm.nextPage(); vm.nextPage(); vm.nextPage()
        assertEquals(2, vm.settled { it.currentPage == 2 }.currentPage)
        vm.goTo(-5)
        assertEquals(0, vm.settled { it.currentPage == 0 }.currentPage)
        vm.goTo(99)
        assertEquals(2, vm.settled { it.currentPage == 2 }.currentPage)
    }

    @Test
    fun navigation_withoutFile_doesNothing() {
        val vm = newVm()
        vm.goTo(3); vm.nextPage()
        assertEquals(0, vm.settled { true }.currentPage)
    }

    @Test
    fun modeSwitch_keepsOrSetsThePage() {
        val vm = newVm()
        vm.open(6)
        vm.goTo(2)
        vm.setMode(ViewerMode.TEXT)
        assertEquals(2, vm.settled { it.mode == ViewerMode.TEXT }.currentPage)
        vm.setMode(ViewerMode.PAGE, page = 4) // the reader scrolled to page 5 in text mode
        val s = vm.settled { it.mode == ViewerMode.PAGE }
        assertEquals(4, s.currentPage)
    }

    @Test
    fun zoom_stepsByHalvesWithinLimits_andPinchContinuesFromTheNearestStep() {
        val vm = newVm()
        vm.open(1)
        vm.zoomOut()
        assertEquals(1f, vm.settled { true }.zoom, 0f)
        vm.zoomIn()
        assertEquals(1.5f, vm.settled { it.zoom == 1.5f }.zoom, 0f)
        repeat(10) { vm.zoomIn() }
        assertEquals(4f, vm.settled { it.zoom == 4f }.zoom, 0f)
        vm.setZoom(2.3f) // pinch
        assertEquals(2.3f, vm.settled { it.zoom == 2.3f }.zoom, 0f)
        vm.zoomIn() // from the nearest step (2.5)
        assertEquals(3f, vm.settled { it.zoom == 3f }.zoom, 0f)
        vm.setZoom(9f)
        assertEquals(4f, vm.settled { it.zoom == 4f }.zoom, 0f)
    }

    @Test
    fun pageText_isExtractedPerPageOnDemand() {
        val vm = newVm()
        vm.open(5, prefix = "Txt")
        assertEquals("Txt3", await { vm.pageText(2) })
        assertEquals("Txt1", await { vm.pageText(0) })
        assertEquals("Txt3", await { vm.pageText(2) }) // cached
        assertNull(await { vm.pageText(99) }) // outside the document: no crash, no text
    }

    @Test
    fun pageText_ofABlankPage_isEmptyNotNull() {
        val vm = newVm()
        val blank = File(dir, "blank.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { d ->
            d.addPage(com.tom_roush.pdfbox.pdmodel.PDPage()); d.save(blank)
        }
        vm.open(Uri.fromFile(blank))
        vm.settled { it.file != null }
        assertEquals("", await { vm.pageText(0) })
    }

    @Test
    fun pageText_followsTheReplacementFile() {
        val vm = newVm()
        vm.open(2, "a.pdf", prefix = "Aa")
        assertEquals("Aa1", await { vm.pageText(0) })
        vm.open(2, "b.pdf", prefix = "Bb")
        assertEquals("Bb1", await { vm.pageText(0) })
    }

    @Test
    fun pageImage_rendersThroughTheLoaderAtTheRequestedWidth() {
        val vm = newVm()
        assertNull(await { vm.pageImage(0, 300) }) // nothing open
        vm.open(2)
        val bitmap = await { vm.pageImage(1, 300) }
        assertNotNull(bitmap)
        assertEquals(300, bitmap!!.width)
        assertEquals(vm.state.value.file!!.file, opened.single().first)
    }

    @Test
    fun encryptedFile_asksForPassword_thenRendersAndReadsWithIt() {
        val vm = newVm()
        vm.open(uriOf(TestPdfs.create(dir, 2, "Sec", "right"), "locked.pdf"))
        vm.settled { it.passwordPrompt != null }
        vm.submitPassword("nope")
        vm.settled { it.passwordPrompt?.wrong == true }
        vm.submitPassword("right")
        val s = vm.settled { it.file != null && it.passwordPrompt == null && !it.importing }
        assertEquals(2, s.pageCount)
        assertEquals("Sec2", await { vm.pageText(1) })
        await { vm.pageImage(0, 200) }
        assertEquals("right", opened.single().second)
    }

    @Test
    fun dismissedPasswordPrompt_keepsThePreviousDocument() {
        val vm = newVm()
        vm.open(2, "plain.pdf")
        vm.open(uriOf(TestPdfs.create(dir, 2, "Sec", "right"), "locked.pdf"))
        vm.settled { it.passwordPrompt != null }
        vm.dismissPassword()
        val s = vm.settled { it.passwordPrompt == null && !it.importing }
        assertEquals("plain.pdf", s.file?.displayName)
        assertNull(s.error)
    }

    @Test
    fun corruptFile_reportsPdfError_andKeepsNothingOpen() {
        val vm = newVm()
        vm.open(Uri.fromFile(File(dir, "bad.pdf").apply { writeText("nope") }))
        val s = vm.settled { it.error != null }
        assertTrue(s.error is PdfError.Corrupt)
        assertNull(s.file)
    }

    @Test
    fun unreadableSource_isReported_andDroppedFromRecents() {
        val vm = newVm()
        val gone = Uri.fromFile(File(dir, "gone.pdf")) // does not exist
        await { recents.record(gone.toString(), "gone.pdf") }
        vm.open(gone)
        val s = vm.settled { it.error != null }
        assertTrue(s.error is SourceUnavailable)
        assertTrue(s.removedFromRecents)
        assertEquals(emptyList<String>(), await { recents.recents.first() }.map { it.uri })
        assertEquals(listOf(gone), released)
    }

    @Test
    fun openedDocument_isRecorded_onlyWhenAccessCanBeKept() {
        val vm = newVm()
        val kept = vm.open(2, "kept.pdf", keep = true)
        vm.open(1, "other.pdf") // not persisted: must not show up in the recent files
        assertEquals(listOf(kept.toString()), await { recents.recents.first() }.map { it.uri })
        assertEquals(listOf("kept.pdf"), await { recents.recents.first() }.map { it.name })
    }

    @Test
    fun pickedDocument_asksTheSystemToKeepAccess_andIsRecorded() {
        val vm = newVm()
        val uri = uriOf(TestPdfs.create(dir, 1), "picked.pdf")
        vm.openPicked(uri)
        vm.settled { it.file?.displayName == "picked.pdf" && !it.importing }
        assertEquals(listOf(uri), persistCalls)
        assertEquals(listOf("picked.pdf"), await { recents.recents.first() }.map { it.name })
    }

    @Test
    fun openingAnother_deletesTheOldCopy_andResetsPageAndZoom() {
        val vm = newVm()
        vm.open(3, "a.pdf")
        val first = vm.state.value.file!!.file
        vm.goTo(2); vm.zoomIn(); vm.setMode(ViewerMode.TEXT)
        vm.settled { it.mode == ViewerMode.TEXT && it.zoom == 1.5f }
        vm.open(2, "b.pdf")
        val s = vm.state.value
        assertFalse(first.exists())
        assertEquals(0, s.currentPage)
        assertEquals(1f, s.zoom, 0f)
        assertEquals(ViewerMode.PAGE, s.mode)
    }

    @Test
    fun prepareHandOff_givesAUriNamedLikeTheDocument() {
        val vm = newVm()
        assertNull(await { vm.prepareHandOff() })
        vm.open(1, "report.pdf")
        assertEquals("report.pdf", await { vm.prepareHandOff() }?.lastPathSegment)
    }

    @Test
    fun onCleared_deletesTheImport() {
        val vm = newVm()
        vm.open(2)
        val copy = vm.state.value.file!!.file
        stores.forEach { it.clear() }
        assertFalse(copy.exists())
    }
}
