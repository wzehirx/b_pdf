package io.github.wzehirx.bpdf.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.wzehirx.bpdf.core.files.ImportedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SelectedFileListTest {
    @get:Rule
    val rule = createComposeRule()

    private val moves = mutableListOf<Pair<Int, Int>>()
    private val removed = mutableListOf<Int>()
    private val files = listOf(
        ImportedFile(File("a"), "report.pdf", 1_200_000),
        ImportedFile(File("b"), "notes.pdf", 2048),
        ImportedFile(File("c"), "last.pdf", 10),
    )

    private fun show() = rule.setContent {
        SelectedFileList(files, onMove = { f, t -> moves += f to t }, onRemove = { removed += it })
    }

    @Test
    fun row_isOneMergedItem_withNameSizeAndPosition() {
        show()
        val text = rule.onNode(hasText("report.pdf", substring = true)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertTrue(text, text.contains("report.pdf"))
        assertTrue(text, text.contains("1.2 MB"))
        assertTrue(text, text.contains("1 of 3"))
        val actions = rule.onNode(hasText("report.pdf", substring = true)).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertTrue(actions.toString(), "Remove" in actions && "Move down" in actions)
    }

    @Test
    fun buttons_haveNamedLabels_andCallBack() {
        show()
        rule.onNodeWithContentDescription("Move report.pdf up").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move notes.pdf up").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Move notes.pdf down").performClick()
        rule.onNodeWithContentDescription("Remove last.pdf").performClick()
        assertEquals(listOf(1 to 0, 1 to 2), moves)
        assertEquals(listOf(2), removed)
        rule.onNodeWithContentDescription("Move last.pdf down").assertIsNotEnabled()
    }
}
