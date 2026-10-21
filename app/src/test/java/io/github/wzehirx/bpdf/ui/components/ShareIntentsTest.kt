package io.github.wzehirx.bpdf.ui.components

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShareIntentsTest {
    @Test
    fun shareFile_startsChooserWithStreamAndReadGrant() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.parse("content://x.fileprovider/shared/a.pdf")
        app.shareFile(uri, "application/pdf", "Share")
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
