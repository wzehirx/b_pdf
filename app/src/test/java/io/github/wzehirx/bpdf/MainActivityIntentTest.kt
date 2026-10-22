package io.github.wzehirx.bpdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

/** Another app opening a PDF with this app ends up in the viewer on that document. */
@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pdfBytes by lazy {
        PdfBoxInit.ensure(context)
        TestPdfs.create(context.cacheDir, 2, "In").readBytes()
    }

    @Before
    fun setUp() {
        // Pre-warm PdfBox resource loading and a first parse so the budget below is spent on the app, not on cold start.
        PdfBoxInit.ensure(context)
        io.github.wzehirx.bpdf.core.pdf.PdfLoader.pageCount(TestPdfs.create(context.cacheDir, 1, "Warm"))
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun contentUri(name: String): Uri =
        Uri.parse("content://io.test.docs/files/${Uri.encode(name)}").also {
            shadowOf(context.contentResolver).registerInputStream(it, ByteArrayInputStream(pdfBytes))
        }

    private fun viewIntent(uri: Uri, flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) =
        Intent(Intent.ACTION_VIEW, uri).setClass(context, MainActivity::class.java).addFlags(flags)

    private fun waitForViewerOn(name: String) {
        rule.waitUntil(30_000) {
            rule.onAllNodes(isHeading() and hasText(name)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Page 1 of 2")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun viewIntent_atLaunch_opensTheViewerOnThatDocument() {
        ActivityScenario.launch<MainActivity>(viewIntent(contentUri("Quarterly report.pdf")))
        waitForViewerOn("Quarterly report.pdf")
    }

    @Test
    fun viewIntent_whileRunning_opensTheViewer() {
        val uri = contentUri("Later.pdf")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNode(hasText("b PDF") and isHeading()).assertExists()
        scenario.onActivity { it.onNewIntent(viewIntent(uri)) }
        waitForViewerOn("Later.pdf")
    }

    @Test
    fun viewIntent_withPersistableFlagButNoGrant_doesNotCrash() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ActivityScenario.launch<MainActivity>(viewIntent(contentUri("Kept.pdf"), flags))
        waitForViewerOn("Kept.pdf")
    }

    @Test
    fun sendIntent_withPdfStream_opensTheViewer() {
        val uri = contentUri("Shared.pdf")
        val send = Intent(Intent.ACTION_SEND).setClass(context, MainActivity::class.java)
            .setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        ActivityScenario.launch<MainActivity>(send)
        waitForViewerOn("Shared.pdf")
    }

    @Test
    fun launcherIntent_staysOnHome() {
        ActivityScenario.launch(MainActivity::class.java)
        rule.onNode(hasText("b PDF") and isHeading()).assertExists()
        assertEquals(0, rule.onAllNodes(hasText("Page 1 of 2")).fetchSemanticsNodes().size)
    }

    private val app get() = context as BPdfApp

    @Test
    fun recentFile_isListedOnHome_opensTheViewer() {
        val uri = contentUri("Recent one.pdf")
        runBlocking { app.container.recentFiles.record(uri.toString(), "Recent one.pdf") }
        ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Recent one.pdf")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Recent one.pdf").performClick()
        waitForViewerOn("Recent one.pdf")
    }

    @Test
    fun recentFile_canBeRemovedFromHome_withTheCustomAction() {
        val uri = contentUri("Gone soon.pdf")
        runBlocking { app.container.recentFiles.record(uri.toString(), "Gone soon.pdf") }
        ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Gone soon.pdf")).fetchSemanticsNodes().isNotEmpty() }
        val actions = rule.onNodeWithText("Gone soon.pdf").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.single { it.label == "Remove from recent files" }.action() }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Gone soon.pdf")).fetchSemanticsNodes().isEmpty() }
        assertEquals(emptyList<String>(), runBlocking { app.container.recentFiles.recents.first() }.map { it.name })
    }
}
