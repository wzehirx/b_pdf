package io.github.wzehirx.bpdf.feature.merge

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun file(name: String) = ImportedFile(File(name), name, 1000)

    private fun show(state: MergeUiState) = rule.setContent {
        MergeScreenContent(
            state = state,
            onBack = {},
            onAddFiles = {},
            onMove = { f, t -> calls += "move $f $t" },
            onRemove = { calls += "remove $it" },
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
}
