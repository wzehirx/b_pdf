package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.ui.assertNoDoubleSpeech
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The shared components, in each of their states, never speak the same phrase twice (see SpokenText). */
@RunWith(RobolectricTestRunner::class)
class ComponentSpeechTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { BPdfTheme { Column { content() } } }

    private fun file(name: String) = ImportedFile(File(name), name, 2048)

    @Test
    fun selectedPdfCard() {
        show {
            SelectedPdfCard(file("report.pdf"), 5, "report.pdf, 5 pages", "Remove file", "Remove report.pdf", enabled = true, onRemove = {})
        }
        rule.assertNoDoubleSpeech("SelectedPdfCard")
    }

    @Test
    fun selectedFileList() {
        show { SelectedFileList(listOf(file("a.pdf"), file("b.pdf"), file("c.pdf")), onMove = { _, _ -> }, onRemove = {}) }
        rule.assertNoDoubleSpeech("SelectedFileList")
    }

    @Test
    fun choiceRow_withSpokenReplacement_isSpokenOnce() {
        show {
            ChoiceRow(
                label = "Format", options = listOf("a", "b"), selected = "a", optionLabel = { "Page $it / 5" }, onSelect = {},
                optionDescription = { "Page $it of 5" },
            )
        }
        rule.assertNoDoubleSpeech("ChoiceRow")
        // The replacement is the node's description; the slash label is only drawn.
        rule.onNodeWithContentDescription("Page a of 5").assertExists()
        rule.onNodeWithText("Page a / 5").assertDoesNotExist()
    }

    @Test
    fun choiceRow_withoutReplacement_isSpokenOnce() {
        show { ChoiceRow("Mode", listOf("a", "b"), "a", { "Option $it" }, {}) }
        rule.assertNoDoubleSpeech("ChoiceRow")
    }

    @Test
    fun switchRow() {
        show { SwitchRow("Remove metadata", checked = true, stateText = "Metadata will be removed", enabled = true, onChange = {}) }
        rule.assertNoDoubleSpeech("SwitchRow")
    }

    @Test
    fun labelledSlider() {
        show {
            LabelledSlider("Opacity", 40f, {}, 10f..100f, 10f, { "${it.toInt()} percent" })
        }
        rule.assertNoDoubleSpeech("LabelledSlider")
        assertEquals(1, rule.onAllNodesWithContentDescriptionCount("Opacity"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescriptionCount(d: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(d)).fetchSemanticsNodes().size

    @Test
    fun operationStatusPanel_running() {
        show { OperationStatusPanel<String>(OperationState.Running(0.4f), { it }, onCancel = {}, onRetry = {}) }
        rule.assertNoDoubleSpeech("OperationStatusPanel running")
    }

    @Test
    fun operationStatusPanel_runningWithLabel() {
        show { OperationStatusPanel<String>(OperationState.Running(null), { it }, onCancel = {}, onRetry = {}, runningLabel = "Page 3 of 9") }
        rule.assertNoDoubleSpeech("OperationStatusPanel running with label")
    }

    @Test
    fun operationStatusPanel_success() {
        show { OperationStatusPanel(OperationState.Success("Merged 3 files into 12 pages."), { it }, onCancel = {}, onRetry = {}) }
        rule.assertNoDoubleSpeech("OperationStatusPanel success")
    }

    @Test
    fun operationStatusPanel_failed() {
        show { OperationStatusPanel<String>(OperationState.Failed(PdfError.Corrupt(null)), { it }, onCancel = {}, onRetry = {}) }
        rule.assertNoDoubleSpeech("OperationStatusPanel failed")
    }

    @Test
    fun pageGrid_selectable() {
        show {
            PageGrid(
                pageCount = 4, thumbnail = { null }, selected = setOf(1), onToggle = {}, rotations = mapOf(2 to 90),
                pageActions = { emptyList() }, onSelectAll = {}, onClearSelection = {},
            )
        }
        rule.assertNoDoubleSpeech("PageGrid selectable")
    }

    @Test
    fun pageGrid_withActionsAndMenu() {
        show {
            PageGrid(
                pageCount = 3, thumbnail = { null }, selected = emptySet(), onToggle = null, rotations = emptyMap(),
                pageActions = { listOf(CustomAccessibilityAction("Delete") { true }) }, overflowMenu = true,
            )
        }
        rule.assertNoDoubleSpeech("PageGrid with menu")
    }

    @Test
    fun pageChooser_open() {
        show { PageChooser(rangeText = "1-2", pageCount = 4, onRangeChange = {}, thumbnail = { null }) }
        rule.onNodeWithText("Choose pages").performClick()
        rule.assertNoDoubleSpeech("PageChooser open")
    }
}
