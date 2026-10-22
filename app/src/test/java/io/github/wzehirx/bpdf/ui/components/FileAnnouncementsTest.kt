package io.github.wzehirx.bpdf.ui.components

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.ImportedFile
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** A chosen file is announced when it loads and when it is removed; nothing else says so after the picker closes. */
@RunWith(RobolectricTestRunner::class)
class FileAnnouncementsTest {
    @get:Rule
    val rule = createComposeRule()

    private val removed = mutableListOf<Int>()
    private val file = ImportedFile(File("/x/a.pdf"), "report.pdf", 10)

    private val shadow by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
    }

    @Before
    fun enableAccessibility() {
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)
    }

    private fun spoken() = shadow.sentAccessibilityEvents
        .filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }
        .map { it.text.joinToString() }

    @Test
    fun card_announcesNameAndPageCountOnce_notAgainAfterRecreation() {
        val tester = StateRestorationTester(rule)
        tester.setContent {
            SelectedPdfCard(
                file = file, pageCount = 5, summary = "report.pdf, 5 pages", removeActionLabel = "Remove file",
                removeButtonDescription = "Remove report.pdf", enabled = true, onRemove = {},
            )
        }
        rule.waitForIdle()
        assertEquals(listOf("report.pdf loaded, 5 pages"), spoken())
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("not repeated after a rotation", listOf("report.pdf loaded, 5 pages"), spoken())
    }

    @Test
    fun card_announcesAgainForAnotherFile_andOnRemove() {
        val current = mutableStateOf(file)
        rule.setContent {
            SelectedPdfCard(
                file = current.value, pageCount = 1, summary = "x", removeActionLabel = "Remove file",
                removeButtonDescription = "Remove it", enabled = true, onRemove = { removed += 0 },
            )
        }
        rule.waitForIdle()
        rule.runOnUiThread { current.value = ImportedFile(File("/x/b.pdf"), "other.pdf", 1) }
        rule.waitForIdle()
        assertEquals(listOf("report.pdf loaded, 1 page", "other.pdf loaded, 1 page"), spoken())
        rule.onNodeWithContentDescription("Remove it").performClick()
        assertEquals(listOf(0), removed)
        assertEquals("other.pdf removed", spoken().last())
    }

    @Test
    fun list_announcesAdditionsAndRemovals() {
        val files = mutableStateOf(listOf(file))
        rule.setContent { SelectedFileList(files.value, onMove = { _, _ -> }, onRemove = { removed += it }) }
        rule.waitForIdle()
        assertEquals("nothing is announced for what was there at the start", emptyList<String>(), spoken())
        rule.runOnUiThread { files.value = files.value + ImportedFile(File("/x/b.pdf"), "notes.pdf", 1) }
        rule.waitForIdle()
        assertEquals(listOf("notes.pdf added, 2 files in the list"), spoken())
        rule.runOnUiThread {
            files.value = files.value + listOf(ImportedFile(File("/x/c.pdf"), "c.pdf", 1), ImportedFile(File("/x/d.pdf"), "d.pdf", 1))
        }
        rule.waitForIdle()
        assertEquals("2 files added", spoken().last())
        rule.onNodeWithContentDescription("Remove notes.pdf").performClick()
        assertEquals("notes.pdf removed, 3 files left", spoken().last())
        assertEquals(listOf(1), removed)
    }
}
