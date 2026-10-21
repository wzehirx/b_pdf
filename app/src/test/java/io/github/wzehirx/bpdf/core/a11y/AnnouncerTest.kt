package io.github.wzehirx.bpdf.core.a11y

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AnnouncerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun announcer_sendsAnnouncementEventWithText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val shadow = shadowOf(manager)
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)

        var announce: ((String) -> Unit)? = null
        rule.setContent { announce = rememberAnnouncer() }
        rule.waitForIdle()
        assertNotNull(announce)
        rule.runOnUiThread { announce!!.invoke("Merge finished") }

        val events = shadow.sentAccessibilityEvents.filter {
            it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT
        }
        assertEquals(1, events.size)
        assertEquals("Merge finished", events[0].text.joinToString())
    }
}
