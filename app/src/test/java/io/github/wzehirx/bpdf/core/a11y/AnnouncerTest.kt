package io.github.wzehirx.bpdf.core.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnouncerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun announcer_composesAndCanBeInvoked() {
        var announce: ((String) -> Unit)? = null
        rule.setContent { announce = rememberAnnouncer() }
        rule.waitForIdle()
        assertNotNull(announce)
        rule.runOnUiThread { announce!!.invoke("Hello") }
    }
}
