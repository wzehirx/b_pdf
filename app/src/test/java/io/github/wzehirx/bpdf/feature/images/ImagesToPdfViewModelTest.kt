package io.github.wzehirx.bpdf.feature.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.pdf.Orientation
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
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
import org.junit.Assert.assertNotNull
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
class ImagesToPdfViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var repo: FileRepository
    private lateinit var vm: ImagesToPdfViewModel
    private lateinit var dir: File
    private var counter = 0

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
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ImagesToPdfViewModel(repo, dispatcher) as T
        })[ImagesToPdfViewModel::class.java]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun picture(name: String, w: Int = 40, h: Int = 20): Uri {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun awaitState(vm: ImagesToPdfViewModel = this.vm, condition: (ImagesToPdfUiState) -> Boolean) =
        await { vm.state.first(condition) }

    private fun add(vararg uris: Uri) {
        val expected = vm.state.value.images.size + uris.size
        vm.addImages(uris.toList())
        awaitState { !it.importing && it.images.size == expected }
    }

    private fun assertNames(vararg expected: String) {
        awaitState { state -> state.images.map { it.displayName } == expected.toList() }
    }

    private fun convertAndAwait(): OperationState<ImagesToPdfResult> {
        vm.convert()
        return await { vm.state.first { it.operation is OperationState.Success || it.operation is OperationState.Failed } }
            .operation
    }

    @Test
    fun addImages_importsInOrder_moveAndRemoveReorder() {
        add(picture("a.jpg"), picture("b.jpg"), picture("c.jpg"))
        assertNames("a.jpg", "b.jpg", "c.jpg")
        vm.move(2, 0)
        assertNames("c.jpg", "a.jpg", "b.jpg")
        vm.remove(1)
        assertNames("c.jpg", "b.jpg")
        vm.move(5, 0) // out of range is ignored
        assertNames("c.jpg", "b.jpg")
    }

    @Test
    fun addedEvents_reportGalleryCountAndCameraPhoto() {
        vm.addImages(listOf(picture("a.jpg"), picture("b.jpg")))
        assertEquals(2, await { vm.addedEvents.first() })
        vm.prepareCamera()
        File(context.cacheDir, "camera").listFiles().orEmpty().single().writeBytes(byteArrayOf(1))
        vm.cameraResult(true)
        assertEquals(1, await { vm.addedEvents.first() })
    }

    @Test
    fun convert_buildsOnePagePerImage_withSuggestedName() {
        add(picture("trip.jpg"), picture("b.jpg"))
        val op = convertAndAwait()
        assertTrue(op.toString(), op is OperationState.Success)
        val result = (op as OperationState.Success).value
        assertEquals(2, result.pageCount)
        assertEquals("trip_images.pdf", result.suggestedName)
        assertEquals(2, PdfLoader.pageCount(result.file))
    }

    @Test
    fun convert_usesChosenOptions() {
        add(picture("wide.jpg", 400, 200))
        vm.setOrientation(Orientation.PORTRAIT)
        val result = (convertAndAwait() as OperationState.Success).value
        PdfLoader.load(result.file).use { doc ->
            val box = doc.getPage(0).mediaBox
            assertTrue(box.height > box.width)
        }
    }

    @Test
    fun convert_withNoImages_doesNothing() {
        vm.convert()
        awaitState { it.operation == OperationState.Idle }
    }

    @Test
    fun undecodableImage_failsWithPickedName_notCacheName() {
        val bad = File(dir, "notes.jpg").apply { writeText("not an image") }
        add(picture("a.jpg"), Uri.fromFile(bad))
        val op = convertAndAwait()
        assertTrue(op.toString(), op is OperationState.Failed)
        val error = (op as OperationState.Failed).error
        assertTrue(error.toString(), error is PdfError.UnreadableImage && error.fileName == "notes.jpg")
        assertTrue(File(context.cacheDir, "shared").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun changingImagesOrOptions_afterConvert_clearsAndDeletesStaleResult() {
        add(picture("a.jpg"))
        val out = (convertAndAwait() as OperationState.Success).value.file
        assertTrue(out.exists())
        vm.setMargin(MarginChoice.LARGE)
        awaitState { it.operation == OperationState.Idle && it.margin == MarginChoice.LARGE }
        assertFalse(out.exists())
    }

    @Test
    fun camera_takenPhotoIsAppended_cancelledPhotoIsDiscarded() {
        add(picture("a.jpg"))
        val uri = vm.prepareCamera()
        assertNotNull(uri)
        val cancelled = File(context.cacheDir, "camera").listFiles().orEmpty().single()
        vm.cameraResult(false)
        assertFalse(cancelled.exists())

        vm.prepareCamera()
        val taken = File(context.cacheDir, "camera").listFiles().orEmpty().single()
        taken.writeBytes(byteArrayOf(1, 2, 3))
        vm.cameraResult(true)
        assertNames("a.jpg", "photo_1.jpg")
        assertTrue(taken.exists())
    }

    @Test
    fun camera_emptyFileCountsAsNoPhoto() {
        vm.prepareCamera()
        val file = File(context.cacheDir, "camera").listFiles().orEmpty().single()
        vm.cameraResult(true)
        assertFalse(file.exists())
        assertTrue(vm.state.value.images.isEmpty())
    }

    @Test
    fun prepareCamera_whileImporting_returnsNull() {
        val latch = java.util.concurrent.CountDownLatch(1)
        val uri = Uri.parse("content://test.slow/slow.jpg")
        val stream = object : java.io.InputStream() {
            override fun read(): Int { latch.await(); return -1 }
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, stream)
        vm.addImages(listOf(uri))
        awaitState { it.importing }
        assertNull(vm.prepareCamera())
        latch.countDown()
        await { vm.state.first { !it.importing } }
    }

    @Test
    fun remove_deletesCachedCopy_andOnClearedDeletesEverything() {
        add(picture("a.jpg"), picture("b.jpg"))
        val (first, second) = vm.state.value.images.map { it.file }
        vm.remove(0)
        assertFalse(first.exists())
        val out = (convertAndAwait() as OperationState.Success).value.file
        vm.prepareCamera()
        val camera = File(context.cacheDir, "camera").listFiles().orEmpty().single().apply { writeText("x") }
        store.clear()
        assertFalse(second.exists())
        assertFalse(out.exists())
        assertFalse(camera.exists())
    }

    @Test
    fun save_writesTargetAndEmitsEvent_prepareShareUsesSuggestedName() {
        assertNull(await { vm.prepareShare() })
        add(picture("a.jpg"))
        convertAndAwait()
        val target = File(dir, "saved.pdf")
        vm.save(Uri.fromFile(target))
        await { vm.savedEvents.first() }
        assertEquals(1, PdfLoader.pageCount(target))
        assertEquals("a_images.pdf", await { vm.prepareShare() }!!.lastPathSegment)
    }

    @Test
    fun whileRunning_editsAreIgnored() {
        val gate = kotlinx.coroutines.test.StandardTestDispatcher()
        val slow = ImagesToPdfViewModel(repo, gate)
        slow.addImages(listOf(picture("a.jpg"), picture("b.jpg")))
        await { slow.state.first { !it.importing && it.images.size == 2 } }
        slow.convert()
        awaitState(slow) { it.operation is OperationState.Running }
        slow.move(0, 1)
        slow.remove(0)
        slow.setMargin(MarginChoice.LARGE)
        assertNull(slow.prepareCamera())
        awaitState(slow) {
            it.images.map { f -> f.displayName } == listOf("a.jpg", "b.jpg") && it.margin == MarginChoice.NONE &&
                it.operation is OperationState.Running
        }
        gate.scheduler.advanceUntilIdle()
        awaitState(slow) { it.operation is OperationState.Success }
    }
}
