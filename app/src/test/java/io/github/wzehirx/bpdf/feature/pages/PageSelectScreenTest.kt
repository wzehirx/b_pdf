package io.github.wzehirx.bpdf.feature.pages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
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
class PageSelectScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val ranges = mutableListOf<String>()

    private fun loaded(mode: PageSelectMode) =
        PageSelectUiState(mode = mode, file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: PageSelectUiState) = rule.setContent {
        PageSelectScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onRangeChange = { ranges += it },
            onRun = { calls += "run" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun stateDescription(text: String) = rule.onNodeWithText(text).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_buttonIsDisabledAndSaysWhy_forBothModes() {
        show(PageSelectUiState(mode = PageSelectMode.REMOVE))
        rule.onNode(hasText("Remove pages") and androidx.compose.ui.test.hasClickAction()).assertIsNotEnabled()
        assertEquals("Choose a PDF first", rule.onNode(hasText("Remove pages") and androidx.compose.ui.test.hasClickAction())
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Pages").assertDoesNotExist()
    }

    @Test
    fun extractMode_usesExtractTexts() {
        show(loaded(PageSelectMode.EXTRACT))
        // Screen title (heading) and the action button.
        rule.onAllNodesWithText("Extract pages").assertCountEquals(2)
        rule.onNodeWithText("Type the pages to keep, for example 2, 4-6. They are saved in the order you type them.").assertExists()
        rule.onNodeWithText("book.pdf, 5 pages").assertExists()
    }

    @Test
    fun rangeEdits_callBack_andButtonRuns() {
        show(loaded(PageSelectMode.REMOVE).copy(rangeText = "2"))
        rule.onNode(hasText("2") and androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("3")
        assertEquals(listOf("3"), ranges)
        val button = rule.onNode(hasText("Remove pages") and androidx.compose.ui.test.hasClickAction())
        button.performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("run"), calls)
    }

    @Test
    fun invalidRange_exposesLocalizedErrorSemantics() {
        show(loaded(PageSelectMode.EXTRACT).copy(
            rangeText = "5-2", rangeError = PdfError.InvalidRange(RangeErrorReason.REVERSED, "5-2"),
        ))
        val message = "The range \"5-2\" goes backwards. Write the smaller page first, for example 2-5."
        rule.onNodeWithText(message).assertExists()
        val field = rule.onNode(hasText("5-2")).fetchSemanticsNode().config
        assertEquals(message, field.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun removingEveryPage_marksFieldAsError_andDisablesButtonWithReason() {
        show(loaded(PageSelectMode.REMOVE).copy(rangeText = "1-5", removesAllPages = true))
        val message = "You cannot remove every page. Leave at least one page."
        // One message only: shown as the field's supporting text and exposed as its error, no extra live region.
        rule.onNodeWithText(message).assertExists()
        val field = rule.onNode(hasText("1-5") and androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNode().config
        assertEquals(message, field.getOrNull(SemanticsProperties.Error))
        assertEquals(null, rule.onNodeWithText(message).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        val button = rule.onNode(hasText("Remove pages") and androidx.compose.ui.test.hasClickAction())
        button.assertIsNotEnabled()
        assertEquals(message, button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun success_removeAndExtract_useSpecificTexts_andSaveShare() {
        val result = PageSelectResult(File("o.pdf"), selectedCount = 2, remainingCount = 3, suggestedName = "n.pdf")
        show(loaded(PageSelectMode.REMOVE).copy(operation = OperationState.Success(result)))
        rule.onNodeWithText("Removed 2 pages; 3 pages remain").assertExists()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun success_remove_singularRemain() {
        show(loaded(PageSelectMode.REMOVE).copy(
            operation = OperationState.Success(PageSelectResult(File("o.pdf"), 1, 1, "n.pdf")),
        ))
        rule.onNodeWithText("Removed 1 page; 1 page remains").assertExists()
    }

    @Test
    fun success_extract_singularAndPlural() {
        show(loaded(PageSelectMode.EXTRACT).copy(
            operation = OperationState.Success(PageSelectResult(File("o.pdf"), 1, 1, "n.pdf")),
        ))
        rule.onNodeWithText("Extracted 1 page").assertExists()
    }

    @Test
    fun running_disablesControls_andCancelWorks() {
        show(loaded(PageSelectMode.REMOVE).copy(operation = OperationState.Running(0.4f)))
        rule.onNode(hasText("Remove pages") and androidx.compose.ui.test.hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Choose another PDF").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun fileCard_exposesRemoveCustomAction_andButton() {
        show(loaded(PageSelectMode.REMOVE))
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        rule.onNode(hasContentDescription("Remove book.pdf")).performClick()
        assertEquals(listOf("remove", "remove"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded(PageSelectMode.REMOVE).copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun saving_disablesResultButtons() {
        show(loaded(PageSelectMode.EXTRACT).copy(
            saving = true, operation = OperationState.Success(PageSelectResult(File("o.pdf"), 1, 1, "n.pdf")),
        ))
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
    }
}
