package io.github.wzehirx.bpdf.ui.components

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OperationStatusPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: OperationState<*>) {
        rule.setContent {
            OperationStatusPanel(
                state = state,
                successText = { "Saved ${it}" },
                onSave = { calls += "save" },
                onShare = { calls += "share" },
                onCancel = { calls += "cancel" },
                onRetry = { calls += "retry" },
            )
        }
    }

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher("liveRegion=$mode") {
        it.config.getOrNull(SemanticsProperties.LiveRegion) == mode
    }

    @Test
    fun running_determinate_exposesProgressRange_andPercentage() {
        show(OperationState.Running(0.5f))
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)))
            .assertIsDisplayed()
        rule.onNodeWithText("Working… 50%").assertIsDisplayed()
        rule.onNode(liveRegion(LiveRegionMode.Polite)).assertDoesNotExist()
        rule.onNode(liveRegion(LiveRegionMode.Assertive)).assertDoesNotExist()
    }

    @Test
    fun running_indeterminate_showsWorking_andCancel() {
        show(OperationState.Running(null))
        rule.onNodeWithText("Working…").assertIsDisplayed()
        rule.onNode(SemanticsMatcher("indeterminate") {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
        }).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun failed_isFocusedWithoutLiveRegion_andOffersRetry() {
        show(OperationState.Failed(PdfError.WrongPassword()))
        rule.onNode(liveRegion(LiveRegionMode.Assertive)).assertDoesNotExist()
        rule.onNodeWithText("That password is incorrect. Try again.").assertIsFocused()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun failed_nonPdfError_showsGenericMessage() {
        show(OperationState.Failed(IllegalStateException("boom")))
        rule.onNodeWithText("Something went wrong").assertIsDisplayed()
        rule.onAllNodes(hasText("boom")).assertCountEquals(0)
    }

    @Test
    fun success_showsText_focusesIt_andSaveShareWork() {
        show(OperationState.Success("out.pdf"))
        rule.onNodeWithText("Saved out.pdf").assertIsFocused()
        rule.onNode(liveRegion(LiveRegionMode.Polite)).assertDoesNotExist()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Share").performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun idle_showsNothing() {
        show(OperationState.Idle)
        rule.onAllNodes(hasText("Cancel")).assertCountEquals(0)
    }
}
