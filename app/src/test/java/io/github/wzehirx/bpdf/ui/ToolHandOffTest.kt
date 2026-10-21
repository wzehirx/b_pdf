package io.github.wzehirx.bpdf.ui

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.pdf.PdfBoxInit
import io.github.wzehirx.bpdf.core.pdf.TestPdfs
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** "Open with tool…" in the viewer starts the other tool with the document already chosen. */
@RunWith(RobolectricTestRunner::class)
class ToolHandOffTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        PdfBoxInit.ensure(context)
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
        rule.setContent {
            BPdfTheme {
                nav = rememberNavController()
                BPdfNavHost(nav)
            }
        }
    }

    private fun pdf(name: String, pages: Int): Uri {
        val src = TestPdfs.create(context.cacheDir, pages)
        return Uri.fromFile(File(context.cacheDir, name).also { src.copyTo(it, overwrite = true) })
    }

    private fun navigate(route: String) = rule.runOnUiThread { nav.navigate(route) }

    private fun waitForText(text: String) =
        rule.waitUntil(15_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun rotate_receivesTheFile() {
        navigate(IncomingIntents.toolRoute(Tool.ROTATE, pdf("my file ğ & co.pdf", 3)))
        waitForText("my file ğ & co.pdf, 3 pages")
    }

    @Test
    fun pdfToText_receivesTheFile() {
        navigate(IncomingIntents.toolRoute(Tool.PDF_TO_TEXT, pdf("notes.pdf", 2)))
        waitForText("notes.pdf, 2 pages")
    }

    @Test
    fun removePages_receivesTheFile() {
        navigate(IncomingIntents.toolRoute(Tool.REMOVE_PAGES, pdf("book.pdf", 4)))
        waitForText("book.pdf, 4 pages")
    }

    @Test
    fun sign_receivesTheFile() {
        navigate(IncomingIntents.toolRoute(Tool.SIGN, pdf("contract.pdf", 3)))
        waitForText("contract.pdf, 3 pages")
    }

    @Test
    fun toolOpenedFromHome_startsEmpty() {
        navigate(Tool.ROTATE.route)
        waitForText("Choose PDF")
    }
}
