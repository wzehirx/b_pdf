package io.github.wzehirx.bpdf.ui.home

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.ui.Tool
import io.github.wzehirx.bpdf.ui.ToolCategory
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Tool>()

    private fun show(available: Set<Tool> = emptySet()) {
        rule.setContent {
            BPdfTheme { HomeScreen(onOpenTool = { opened += it }, isAvailable = { it in available }) }
        }
    }

    private fun label(tool: Tool) =
        "${context.getString(tool.title)}. ${context.getString(tool.description)}"

    @Test
    fun oneHeadingPerCategory() {
        show()
        ToolCategory.entries.forEach {
            rule.onAllNodes(hasText(context.getString(it.title)) and isHeading()).assertCountEquals(1)
        }
        // The app title is the only other heading.
        rule.onAllNodes(isHeading()).assertCountEquals(ToolCategory.entries.size + 1)
    }

    @Test
    fun everyToolShowsTitleAndDescriptionInMergedLabel() {
        show()
        Tool.entries.forEach { rule.onNodeWithContentDescription(label(it)).assertExists() }
    }

    @Test
    fun unavailableTool_isDisabledWithComingSoonState_andDoesNotNavigate() {
        show()
        val node = rule.onNodeWithContentDescription(label(Tool.MERGE))
        node.assertIsNotEnabled()
        val state = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Coming soon", state)
        node.performClick()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun availableTool_isClickableAndNavigates() {
        show(available = setOf(Tool.MERGE))
        rule.onNodeWithContentDescription(label(Tool.MERGE)).performClick()
        assertEquals(listOf(Tool.MERGE), opened)
        val state = rule.onNodeWithContentDescription(label(Tool.MERGE))
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(null, state)
    }

    @Test
    fun toolEnum_listsAllToolsAllUnavailableForNow() {
        assertEquals(19, Tool.entries.size)
        assertTrue(Tool.entries.none { it.available })
        assertEquals(Tool.entries.size, Tool.entries.map { it.route }.toSet().size)
    }
}
