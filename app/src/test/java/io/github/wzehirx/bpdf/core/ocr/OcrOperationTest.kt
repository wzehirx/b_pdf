package io.github.wzehirx.bpdf.core.ocr

import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class OcrOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() = PdfBoxInit.ensure(ApplicationProvider.getApplicationContext())

    private fun <T> run(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun allPages_areReadInOrder_at300dpi_withTheLanguages() {
        val input = TestPdfs.create(tmp.root, 3)
        val rasterizer = FakeRasterizer()
        val engine = FakeOcrEngine()
        val result = run { OcrOperation.run(input, null, "tur+eng", rasterizer, engine) }
        assertEquals(listOf(0, 1, 2), result.map { it.pageIndex })
        assertEquals(listOf("text of page 1", "text of page 2", "text of page 3"), result.map { it.text })
        assertEquals(listOf(300, 300, 300), rasterizer.dpis)
        assertEquals(listOf("tur+eng", "tur+eng", "tur+eng"), engine.languages)
        assertFalse("a normal run does not stop the engine", engine.stopped)
    }

    @Test
    fun selectedPages_keepTheGivenOrder_andDropDuplicates() {
        val input = TestPdfs.create(tmp.root, 5)
        val rasterizer = FakeRasterizer()
        val result = run { OcrOperation.run(input, listOf(3, 1, 3), "eng", rasterizer, FakeOcrEngine()) }
        assertEquals(listOf(3, 1), result.map { it.pageIndex })
        assertEquals(listOf(3, 1), rasterizer.rendered)
    }

    @Test
    fun progress_isReportedBeforeEachPage_andOnceAtTheEnd() {
        val input = TestPdfs.create(tmp.root, 3)
        val events = mutableListOf<Triple<Int, Int, Int>>()
        run {
            OcrOperation.run(input, listOf(0, 2), "eng", FakeRasterizer(), FakeOcrEngine(), progress = { done, total, page -> events += Triple(done, total, page) })
        }
        assertEquals(listOf(Triple(0, 2, 1), Triple(1, 2, 3), Triple(2, 2, 3)), events)
    }

    @Test
    fun everyBitmap_isRecycledAfterItsPage_andOnlyOneIsAliveAtATime() {
        val input = TestPdfs.create(tmp.root, 3)
        val rasterizer = FakeRasterizer()
        val engine = FakeOcrEngine(onRecognize = { call ->
            // while page `call` is read, the earlier ones are already recycled
            val alive = rasterizer.bitmaps.count { !it.isRecycled }
            assertEquals("bitmaps alive during page $call", 1, alive)
        })
        run { OcrOperation.run(input, null, "eng", rasterizer, engine) }
        assertTrue(rasterizer.bitmaps.all { it.isRecycled })
        assertEquals(listOf(false, false, false), engine.recycledWhenCalled)
    }

    @Test
    fun text_isTrimmed_andBlankPagesStayBlank() {
        val input = TestPdfs.create(tmp.root, 2)
        val result = run {
            OcrOperation.run(input, null, "eng", FakeRasterizer(), FakeOcrEngine(textFor = { if (it == 0) "  hello \n" else "" }))
        }
        assertEquals("hello", result[0].text)
        assertTrue(result[1].isBlank)
    }

    @Test
    fun cancelledBetweenPages_stopsAndReturnsNothing() {
        val input = TestPdfs.create(tmp.root, 5)
        val rasterizer = FakeRasterizer()
        val firstPageRead = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val slow = FakeOcrEngine(onRecognize = { call ->
            if (call == 2) {
                firstPageRead.complete(Unit)
                runBlocking { resume.await() }
            }
        })
        run {
            val job = async(Dispatchers.Default) {
                OcrOperation.run(input, null, "eng", rasterizer, slow)
            }
            firstPageRead.await()
            job.cancel()
            // The engine is told to stop at once, even though the page it is on has not returned yet.
            while (!slow.stopped) kotlinx.coroutines.delay(5)
            resume.complete(Unit)
            try {
                job.await()
                fail("expected cancellation")
            } catch (_: CancellationException) {
            }
        }
        assertEquals("no page after the cancelled one", listOf(0, 1), rasterizer.rendered)
        assertTrue(rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun cancelledBeforeStart_readsNothing() {
        val input = TestPdfs.create(tmp.root, 2)
        val rasterizer = FakeRasterizer()
        run {
            val job = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                OcrOperation.run(input, null, "eng", rasterizer, FakeOcrEngine())
            }
            job.cancelAndJoin()
        }
        assertTrue(rasterizer.rendered.isEmpty())
    }

    @Test
    fun pageThatCannotBeDrawn_isCorrupt() {
        val input = TestPdfs.create(tmp.root, 3)
        try {
            run { OcrOperation.run(input, null, "eng", FakeRasterizer(failOnPage = 1), FakeOcrEngine()) }
            fail()
        } catch (_: PdfError.Corrupt) {
        }
    }

    @Test
    fun engineFailure_isPassedOn_andTheBitmapIsRecycled() {
        val input = TestPdfs.create(tmp.root, 2)
        val rasterizer = FakeRasterizer()
        val engine = FakeOcrEngine(onRecognize = { throw OcrFailed("no data") })
        try {
            run { OcrOperation.run(input, null, "eng", rasterizer, engine) }
            fail()
        } catch (e: OcrFailed) {
            assertEquals("no data", e.message)
        }
        assertTrue(rasterizer.bitmaps.all { it.isRecycled })
    }

    @Test
    fun invalidInput_isRejected() {
        val input = TestPdfs.create(tmp.root, 2)
        fun rejects(pages: List<Int>?, languages: String = "eng") {
            try {
                run { OcrOperation.run(input, pages, languages, FakeRasterizer(), FakeOcrEngine()) }
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
        rejects(emptyList())
        rejects(listOf(2))
        rejects(listOf(-1))
        rejects(null, languages = " ")
    }

    @Test
    fun runsOffTheCallersThread() {
        val input = TestPdfs.create(tmp.root, 1)
        val caller = Thread.currentThread()
        var worker: Thread? = null
        run { OcrOperation.run(input, null, "eng", FakeRasterizer(), FakeOcrEngine(onRecognize = { worker = Thread.currentThread() })) }
        assertTrue(worker != null && worker !== caller)
    }
}
