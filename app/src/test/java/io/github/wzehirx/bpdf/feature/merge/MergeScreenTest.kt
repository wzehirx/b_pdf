package io.github.wzehirx.bpdf.feature.merge

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MergeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val moves = mutableListOf<Pair<Int, Int>>()
    private val removes = mutableListOf<Int>()

    private fun actions(name: String) = rule.onNode(hasText(name, substring = true)).fetchSemanticsNode()
        .config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun invoke(name: String, label: String) {
        val action = actions(name).first { it.label == label }
        rule.runOnUiThread { action.action() }
    }

    private val three = listOf(file("a.pdf"), file("b.pdf"), file("c.pdf"))

    private fun file(name: String) = ImportedFile(File(name), name, 1000)

    private fun show(state: MergeUiState) = rule.setContent {
        MergeScreenContent(
            state = state,
            onBack = {},
            onAddFiles = {},
            onMove = { f, t -> moves += f to t },
            onRemove = { removes += it },
            onMerge = { calls += "merge" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun stateDescription(text: String) = rule.onNodeWithText(text).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun fileRows_exposeMoveAndRemoveCustomActions() {
        show(MergeUiState(files = listOf(file("a.pdf"), file("b.pdf"), file("c.pdf"))))
        val labels = rule.onNode(hasText("b.pdf", substring = true)).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertTrue(labels.toString(), listOf("Move up", "Move down", "Remove").all { it in labels })
    }

    @Test
    fun mergeButton_withFewerThanTwoFiles_isDisabledAndSaysWhy() {
        show(MergeUiState(files = listOf(file("a.pdf"))))
        rule.onNodeWithText("Merge").assertIsNotEnabled()
        assertEquals("Add at least 2 files", stateDescription("Merge"))
    }

    @Test
    fun mergeButton_withTwoFiles_isEnabledAndCallsBack() {
        show(MergeUiState(files = listOf(file("a.pdf"), file("b.pdf"))))
        rule.onNodeWithText("Merge").assertIsEnabled().performClick()
        assertEquals(null, stateDescription("Merge"))
        assertEquals(listOf("merge"), calls)
    }

    @Test
    fun success_showsSummaryAndSaveShare() {
        val result = MergeResult(File("o.pdf"), pageCount = 12, fileCount = 3, suggestedName = "a_merged.pdf")
        show(
            MergeUiState(
                files = listOf(file("a.pdf"), file("b.pdf"), file("c.pdf")),
                operation = OperationState.Success(result),
            ),
        )
        rule.onNodeWithText("Merged 3 files into 12 pages").assertExists()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun running_disablesMerge() {
        show(MergeUiState(files = listOf(file("a.pdf"), file("b.pdf")), operation = OperationState.Running(0.5f)))
        rule.onNodeWithText("Merge").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun customActions_invokeMoveAndRemoveCallbacks() {
        show(MergeUiState(files = three))
        invoke("b.pdf", "Move up")
        invoke("b.pdf", "Move down")
        invoke("b.pdf", "Remove")
        assertEquals(listOf(1 to 0, 1 to 2), moves)
        assertEquals(listOf(1), removes)
    }

    @Test
    fun edgeRows_omitImpossibleActions() {
        show(MergeUiState(files = three))
        assertTrue(actions("a.pdf").none { it.label == "Move up" })
        assertTrue(actions("a.pdf").any { it.label == "Move down" })
        assertTrue(actions("c.pdf").none { it.label == "Move down" })
        assertTrue(actions("c.pdf").any { it.label == "Move up" })
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(
            MergeUiState(
                files = three,
                operation = OperationState.Failed(io.github.wzehirx.bpdf.core.pdf.PdfError.Corrupt(null)),
            ),
        )
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun busy_disablesPickerAndRowControls_andHidesActions() {
        show(MergeUiState(files = three, importing = true))
        rule.onNodeWithText("Add PDF files").assertIsNotEnabled()
        rule.onNodeWithText("Merge").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Remove b.pdf").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move b.pdf down").assertIsNotEnabled()
        assertTrue(actions("b.pdf").isEmpty())
    }
}
