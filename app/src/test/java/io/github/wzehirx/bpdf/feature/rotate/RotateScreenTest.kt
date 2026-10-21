package io.github.wzehirx.bpdf.feature.rotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import org.robolectric.annotation.Config
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RotateScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val angles = mutableListOf<RotateAngle>()
    private val scopes = mutableListOf<RotateScope>()
    private val ranges = mutableListOf<String>()

    private val loaded = RotateUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: RotateUiState) = rule.setContent {
        RotateScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onAngleChange = { angles += it },
            onScopeChange = { scopes += it },
            onRangeChange = { ranges += it },
            onRotate = { calls += "rotate" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun rotateButton() = rule.onNode(hasText("Rotate") and hasClickAction())

    @Test
    fun withoutFile_rotateIsDisabledAndSaysWhy_andOptionsAreHidden() {
        show(RotateUiState())
        rotateButton().assertIsNotEnabled()
        assertEquals("Choose a PDF first", rotateButton().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithText("All pages").assertDoesNotExist()
    }

    @Test
    fun angleOptions_areRadiosWithSelection_andCallBack() {
        show(loaded.copy(angle = RotateAngle.HALF_TURN))
        rule.onNodeWithText("180°").assertIsSelected()
        rule.onNodeWithText("90° clockwise").assertIsNotSelected()
        rule.onNodeWithText("90° counter-clockwise").assertIsNotSelected()
        for (label in listOf("90° clockwise", "180°", "90° counter-clockwise")) {
            assertEquals(Role.RadioButton, rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithText("90° counter-clockwise").performScrollTo().performClick()
        assertEquals(listOf(RotateAngle.COUNTER_CLOCKWISE_90), angles)
    }

    @Test
    fun scopeOptions_areRadios_andCallBack() {
        show(loaded)
        rule.onNodeWithText("All pages").assertIsSelected()
        rule.onNodeWithText("Selected pages").assertIsNotSelected()
        assertEquals(Role.RadioButton, rule.onNodeWithText("Selected pages").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        rule.onNodeWithText("Selected pages").performScrollTo().performClick()
        assertEquals(listOf(RotateScope.SELECTED), scopes)
    }

    @Test
    fun rangeField_onlyShownForSelectedPages() {
        show(loaded)
        rule.onNodeWithText("Pages").assertDoesNotExist()
    }

    @Test
    fun selectedScope_showsRangeField_andEditsCallBack() {
        show(loaded.copy(scope = RotateScope.SELECTED, rangeText = "1"))
        rule.onNodeWithText("Pages").assertExists()
        rule.onNode(hasText("1") and androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("2-3")
        assertEquals(listOf("2-3"), ranges)
    }

    @Test
    fun invalidRange_exposesLocalizedErrorSemantics() {
        show(loaded.copy(
            scope = RotateScope.SELECTED, rangeText = "5-2",
            rangeError = PdfError.InvalidRange(RangeErrorReason.REVERSED, "5-2"),
        ))
        val message = "The range \"5-2\" goes backwards. Write the smaller page first, for example 2-5."
        rule.onNodeWithText(message).assertExists()
        assertEquals(message, rule.onNode(hasText("5-2")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun rotate_callsBack() {
        show(loaded)
        rotateButton().performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("rotate"), calls)
    }

    @Test
    fun success_showsCount_saveAndShare() {
        show(loaded.copy(operation = OperationState.Success(RotateResult(File("o.pdf"), 2, 5, "n.pdf"))))
        rule.onNodeWithText("Rotated 2 pages").assertExists()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun running_disablesControls_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        rotateButton().assertIsNotEnabled()
        rule.onNodeWithText("90° clockwise").assertIsNotEnabled()
        rule.onNodeWithText("All pages").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun fileCard_exposesRemoveCustomAction_andButton() {
        show(loaded)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        rule.onNode(hasContentDescription("Remove book.pdf")).performClick()
        assertEquals(listOf("remove", "remove"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun saving_disablesResultButtons() {
        show(loaded.copy(saving = true, operation = OperationState.Success(RotateResult(File("o.pdf"), 1, 5, "n.pdf"))))
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
    }
    @Test
    @Config(qualifiers = "w400dp-h2000dp")
    fun chooseFromGrid_updatesRangeText_andTextUpdatesGrid() {
        var text by mutableStateOf("")
        rule.setContent {
            RotateScreenContent(
                state = loaded.copy(scope = RotateScope.SELECTED, rangeText = text),
                onBack = {}, onPickFile = {}, onRemoveFile = {}, onAngleChange = {}, onScopeChange = {},
                onRangeChange = { text = it },
                onRotate = {}, onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
            )
        }
        rule.onNodeWithText("Choose pages").performClick()
        rule.onNodeWithContentDescription("Page 2 of 5").performClick()
        rule.onNodeWithContentDescription("Page 3 of 5").performClick()
        assertEquals("2-3", text)
        rule.runOnUiThread { text = "5" }
        rule.waitForIdle()
        assertEquals(
            "Selected",
            rule.onNodeWithContentDescription("Page 5 of 5").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
    }

    @Test
    fun allPagesScope_hidesTheGridChooser() {
        show(loaded)
        rule.onNodeWithText("Choose pages").assertDoesNotExist()
    }
}
