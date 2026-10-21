package io.github.wzehirx.bpdf.feature.watermark

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.pdf.WatermarkPosition
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WatermarkScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val texts = mutableListOf<String>()
    private val sizes = mutableListOf<Int>()
    private val opacities = mutableListOf<Int>()
    private val rotations = mutableListOf<Int>()
    private val positions = mutableListOf<WatermarkPosition>()
    private val colors = mutableListOf<WatermarkColor>()
    private val scopes = mutableListOf<PageScope>()

    private val loaded = WatermarkUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5, text = "DRAFT")

    private fun show(state: WatermarkUiState) = rule.setContent {
        WatermarkScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onTextChange = { texts += it },
            onFontSizeChange = { sizes += it },
            onOpacityChange = { opacities += it },
            onRotationChange = { rotations += it },
            onPositionChange = { positions += it },
            onColorChange = { colors += it },
            onScopeChange = { scopes += it },
            onRangeChange = {},
            onApply = { calls += "apply" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun applyButton() = rule.onNode(hasText("Add watermark") and hasClickAction())

    private fun slider(label: String) = rule.onNode(hasContentDescription(label))

    private fun stateOf(label: String) = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_buttonIsDisabledAndSaysWhy_andOptionsAreHidden() {
        show(WatermarkUiState())
        applyButton().assertIsNotEnabled()
        assertEquals("Choose a PDF first", applyButton().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Watermark text").assertDoesNotExist()
    }

    @Test
    fun sliders_haveLabelAndSpokenValueWithUnit_andAreAtLeast48dpTall() {
        show(loaded.copy(fontSize = 48, opacityPercent = 30, rotation = 45))
        assertEquals("48 points", stateOf("Text size"))
        assertEquals("30 percent", stateOf("Opacity"))
        assertEquals("45 degrees counter-clockwise", stateOf("Rotation"))
        for (label in listOf("Text size", "Opacity", "Rotation")) {
            slider(label).performScrollTo().assertHeightIsAtLeast(48.dp)
            assertNotNull("$label is adjustable", slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress))
            assertNotNull(slider(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
        }
    }

    @Test
    fun rotationSpeech_coversClockwiseAndHorizontal() {
        show(loaded.copy(rotation = -30))
        assertEquals("30 degrees clockwise", stateOf("Rotation"))
    }

    @Test
    fun horizontalRotation_isSpokenAsSuch() {
        show(loaded.copy(rotation = 0))
        assertEquals("0 degrees, horizontal", stateOf("Rotation"))
    }

    @Test
    fun adjustingSliders_callsBackWithWholeNumbers() {
        show(loaded)
        slider("Opacity").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(55f) }
        slider("Text size").performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        slider("Rotation").performSemanticsAction(SemanticsActions.SetProgress) { it(-45f) }
        assertEquals(listOf(55), opacities)
        assertEquals(listOf(100), sizes)
        assertEquals(listOf(-45), rotations)
    }

    @Test
    fun positionAndColorChoices_areNamedRadios_andCallBack() {
        show(loaded)
        rule.onNodeWithText("Center").assertIsSelected()
        rule.onNodeWithText("Repeated across the page").assertIsNotSelected()
        rule.onNodeWithText("Gray").assertIsSelected()
        for (label in listOf("Center", "Top", "Bottom", "Repeated across the page", "Gray", "Red", "Blue", "Black")) {
            assertEquals(Role.RadioButton, rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithText("Repeated across the page").performScrollTo().performClick()
        rule.onNodeWithText("Red").performScrollTo().performClick()
        assertEquals(listOf(WatermarkPosition.TILED), positions)
        assertEquals(listOf(WatermarkColor.RED), colors)
    }

    @Test
    fun textField_editsCallBack() {
        show(loaded)
        rule.onNode(hasText("DRAFT") and hasSetTextAction()).performTextReplacement("GİZLİ")
        assertEquals(listOf("GİZLİ"), texts)
    }

    @Test
    fun missingText_exposesErrorSemantics() {
        show(loaded.copy(text = "", textMissing = true))
        rule.onNodeWithText("Enter the watermark text.").assertExists()
        val node = rule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("Enter the watermark text.", node.config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun scope_rangeFieldOnlyForSelectedPages_andInvalidRangeShowsLocalizedError() {
        show(loaded)
        rule.onNodeWithText("Pages").assertDoesNotExist()
        rule.onNodeWithText("Selected pages").performScrollTo().performClick()
        assertEquals(listOf(PageScope.SELECTED), scopes)
    }

    @Test
    fun selectedScope_invalidRange_exposesError() {
        show(loaded.copy(scope = PageScope.SELECTED, rangeText = "5-2", rangeError = PdfError.InvalidRange(RangeErrorReason.REVERSED, "5-2")))
        val message = "The range \"5-2\" goes backwards. Write the smaller page first, for example 2-5."
        rule.onNodeWithText(message).assertExists()
        assertEquals(message, rule.onNode(hasText("5-2")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun apply_callsBack() {
        show(loaded)
        applyButton().performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("apply"), calls)
    }

    @Test
    fun success_showsCount_saveAndShare() {
        show(loaded.copy(operation = OperationState.Success(WatermarkResult(File("o.pdf"), 2, 5, "n.pdf"))))
        rule.onNodeWithText("Added the watermark to 2 pages").assertExists().assertIsFocused()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun running_disablesEveryControl_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        applyButton().assertIsNotEnabled()
        rule.onNodeWithText("Red").assertIsNotEnabled()
        rule.onNodeWithText("Center").assertIsNotEnabled()
        assertTrue(slider("Opacity").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun fileCard_exposesRemoveCustomAction() {
        show(loaded)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        assertEquals(listOf("remove"), calls)
    }
}
