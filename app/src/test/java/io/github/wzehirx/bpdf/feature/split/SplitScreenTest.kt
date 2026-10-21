package io.github.wzehirx.bpdf.feature.split

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SplitScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val modes = mutableListOf<SplitModeChoice>()
    private val ranges = mutableListOf<String>()
    private val everyNs = mutableListOf<String>()

    private val loaded = SplitUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: SplitUiState) = rule.setContent {
        SplitScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onModeChange = { modes += it },
            onRangeChange = { ranges += it },
            onEveryNChange = { everyNs += it },
            onSplit = { calls += "split" },
            onCancel = { calls += "cancel" },
            onSaveToFolder = { calls += "folder" },
            onSaveZip = { calls += "zip" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun stateDescription(text: String) = rule.onNodeWithText(text).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_splitIsDisabledAndSaysWhy_andModesAreHidden() {
        show(SplitUiState())
        rule.onNodeWithText("Split").assertIsNotEnabled()
        assertEquals("Choose a PDF first", stateDescription("Split"))
        rule.onNodeWithText("By page ranges").assertDoesNotExist()
    }

    @Test
    fun withFile_showsNameAndPageCount() {
        show(loaded)
        rule.onNodeWithText("book.pdf, 5 pages").assertExists()
        rule.onNodeWithText("Split").assertIsEnabled()
        assertEquals(null, stateDescription("Split"))
    }

    @Test
    fun modeOptions_areRadiosWithSelection_andCallBack() {
        show(loaded.copy(mode = SplitModeChoice.EVERY_N))
        rule.onNodeWithText("Every N pages").assertIsSelected()
        rule.onNodeWithText("By page ranges").assertIsNotSelected()
        rule.onNodeWithText("One file per page").assertIsNotSelected()
        val role = rule.onNodeWithText("One file per page").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Role)
        assertEquals(androidx.compose.ui.semantics.Role.RadioButton, role)
        rule.onNodeWithText("One file per page").performScrollTo().performClick()
        assertEquals(listOf(SplitModeChoice.EACH_PAGE), modes)
    }

    @Test
    fun rangesMode_validRange_hasNoErrorSemantics_andEditsCallBack() {
        show(loaded.copy(rangeText = "1-2"))
        rule.onNodeWithText("Pages").assertExists()
        rule.onNode(hasText("1-2")).performTextReplacement("3")
        assertEquals(listOf("3"), ranges)
        rule.onNodeWithText("For example 1-3, 5. The document has 5 pages.").assertExists()
    }

    @Test
    fun rangesMode_invalidRange_exposesLocalizedErrorSemantics() {
        show(loaded.copy(rangeText = "5-2", rangeError = PdfError.InvalidRange(RangeErrorReason.REVERSED, "5-2")))
        val message = "The range \"5-2\" goes backwards. Write the smaller page first, for example 2-5."
        rule.onNodeWithText(message).assertExists()
        val field = rule.onNode(hasText("5-2")).fetchSemanticsNode().config
        assertEquals(message, field.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun everyNMode_showsNumericFieldWithHint() {
        show(loaded.copy(mode = SplitModeChoice.EVERY_N, everyNText = "2"))
        rule.onNodeWithText("Pages per file").assertExists()
        rule.onNodeWithText("Enter a whole number from 1 to 5.").assertExists()
        rule.onNode(hasText("2") and androidx.compose.ui.test.hasSetTextAction()).performTextReplacement("3")
        assertEquals(listOf("3"), everyNs)
    }

    @Test
    fun everyNMode_invalid_exposesErrorSemantics() {
        show(loaded.copy(mode = SplitModeChoice.EVERY_N, everyNText = "9", everyNError = EveryNError.INVALID))
        val message = "\"9\" is not valid. Enter a whole number from 1 to 5."
        rule.onNodeWithText(message).assertExists()
        val field = rule.onNode(hasText("9") and androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNode().config
        assertEquals(message, field.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun everyNMode_empty_exposesErrorSemantics() {
        show(loaded.copy(mode = SplitModeChoice.EVERY_N, everyNText = "", everyNError = EveryNError.EMPTY))
        rule.onNodeWithText("Enter how many pages each file should have.").assertExists()
    }

    @Test
    fun eachPageMode_hasNoExtraField() {
        show(loaded.copy(mode = SplitModeChoice.EACH_PAGE))
        rule.onNodeWithText("Pages").assertDoesNotExist()
        rule.onNodeWithText("Pages per file").assertDoesNotExist()
    }

    @Test
    fun split_callsBack() {
        show(loaded)
        rule.onNodeWithText("Split").performScrollTo().performClick()
        assertEquals(listOf("split"), calls)
    }

    @Test
    fun success_showsCountAndThreeLabelledButtons() {
        val files = List(3) { File("book_${it + 1}.pdf") }
        show(loaded.copy(operation = OperationState.Success(SplitResult(files, File("d"), "book_split.zip"))))
        rule.onNodeWithText("Created 3 files").assertExists()
        for (label in listOf("Save to folder", "Save as ZIP", "Share")) {
            val node = rule.onNodeWithText(label).performScrollTo()
            node.assertIsEnabled()
            assertNotNull(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
            node.performClick()
        }
        assertEquals(listOf("folder", "zip", "share"), calls)
    }

    @Test
    fun success_singleFile_usesSingular() {
        show(loaded.copy(operation = OperationState.Success(SplitResult(listOf(File("a_1.pdf")), File("d"), "z.zip"))))
        rule.onNodeWithText("Created 1 file").assertExists()
    }

    @Test
    fun running_disablesControls_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        rule.onNodeWithText("Split").assertIsNotEnabled()
        rule.onNodeWithText("Choose another PDF").assertIsNotEnabled()
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
        val remove = actions.first { it.label == "Remove file" }
        rule.runOnUiThread { remove.action() }
        rule.onNodeWithContentDescriptionCompat("Remove book.pdf").performClick()
        assertEquals(listOf("remove", "remove"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionCompat(d: String) =
        onNode(androidx.compose.ui.test.hasContentDescription(d))

    @Test
    fun busy_disablesModeRadios() {
        show(loaded.copy(saving = true))
        rule.onNodeWithText("By page ranges").assertIsNotEnabled()
        rule.onNodeWithText("Every N pages").assertIsNotEnabled()
        rule.onNodeWithText("Split").assertIsNotEnabled()
    }

    @Test
    fun idle_modeRadiosAreEnabled() {
        show(loaded)
        rule.onNodeWithText("Every N pages").assertIsEnabled()
    }

    @Test
    fun saving_disablesResultButtons() {
        val files = listOf(File("book_1.pdf"))
        show(loaded.copy(saving = true, operation = OperationState.Success(SplitResult(files, File("d"), "z.zip"))))
        for (label in listOf("Save to folder", "Save as ZIP", "Share")) {
            rule.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun exportFailed_showsClearMessage() {
        val error = io.github.wzehirx.bpdf.core.files.ExportFailed(1, java.io.IOException("x"))
        show(loaded.copy(operation = OperationState.Failed(error)))
        rule.onNodeWithText("Saving to the folder failed. No files were kept there.").assertIsFocused()
    }
}
