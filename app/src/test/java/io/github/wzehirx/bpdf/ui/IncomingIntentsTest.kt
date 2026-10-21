package io.github.wzehirx.bpdf.ui

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingIntentsTest {
    private val content = Uri.parse("content://com.example.files/docs/report%20one.pdf")

    @Test
    fun viewIntentWithContentUri_givesTheUri() {
        assertEquals(content, IncomingIntents.pdfUri(Intent(Intent.ACTION_VIEW, content)))
    }

    @Test
    fun viewIntentWithFileUri_isIgnored() {
        assertNull(IncomingIntents.pdfUri(Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.pdf"))))
    }

    @Test
    fun sendIntentWithPdfStream_givesTheUri() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, content)
        assertEquals(content, IncomingIntents.pdfUri(intent))
    }

    @Test
    fun sendIntentOfOtherTypeOrWithoutStream_isIgnored() {
        assertNull(IncomingIntents.pdfUri(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, content)))
        assertNull(IncomingIntents.pdfUri(Intent(Intent.ACTION_SEND).setType("application/pdf")))
    }

    @Test
    fun otherIntents_areIgnored() {
        assertNull(IncomingIntents.pdfUri(null))
        assertNull(IncomingIntents.pdfUri(Intent(Intent.ACTION_MAIN)))
        assertNull(IncomingIntents.pdfUri(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun viewerRoute_encodesTheUriIntoOneArgument() {
        assertEquals("tool/viewer", IncomingIntents.viewerRoute())
        val route = IncomingIntents.viewerRoute(content)
        assertEquals("tool/viewer?uri=content%3A%2F%2Fcom.example.files%2Fdocs%2Freport%2520one.pdf", route)
        assertEquals(content.toString(), Uri.parse(route).getQueryParameter("uri"))
    }

    @Test
    fun toolRoute_handsTheFileToThatTool() {
        assertEquals("tool/rotate", IncomingIntents.toolRoute(Tool.ROTATE))
        assertEquals(
            "tool/rotate?uri=content%3A%2F%2Fcom.example.files%2Fdocs%2Freport%2520one.pdf",
            IncomingIntents.toolRoute(Tool.ROTATE, content),
        )
    }
}
