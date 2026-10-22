package io.github.wzehirx.bpdf.ui.home

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.RecentFile
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

    private val openedRecents = mutableListOf<String>()
    private val removedRecents = mutableListOf<String>()
    private var settingsOpened = 0

    private fun show(available: Set<Tool> = emptySet(), recents: List<RecentFile> = emptyList()) {
        rule.setContent {
            BPdfTheme {
                HomeScreen(
                    onOpenTool = { opened += it },
                    isAvailable = { it in available },
                    recents = recents,
                    onOpenRecent = { openedRecents += it.name },
                    onRemoveRecent = { removedRecents += it.name },
                    onOpenSettings = { settingsOpened++ },
                )
            }
        }
    }

    private val recentFiles = listOf(
        RecentFile("content://a/1", "annual report.pdf", 2),
        RecentFile("content://a/2", "ders notları.pdf", 1),
    )

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
    fun noRecents_noRecentSection() {
        show()
        rule.onNodeWithText("Recent files").assertDoesNotExist()
    }

    @Test
    fun recents_haveOneHeading_andEachFileIsAnOpenableItem() {
        show(recents = recentFiles)
        rule.onAllNodes(hasText("Recent files") and isHeading()).assertCountEquals(1)
        rule.onAllNodes(isHeading()).assertCountEquals(ToolCategory.entries.size + 2)
        rule.onNodeWithText("annual report.pdf").performClick()
        rule.onNodeWithText("ders notları.pdf").performClick()
        assertEquals(listOf("annual report.pdf", "ders notları.pdf"), openedRecents)
        val onClick = rule.onNodeWithText("annual report.pdf").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.OnClick)
        assertEquals("Open in reader", onClick?.label)
    }

    @Test
    fun recentItem_hasRemoveCustomAction_andAVisibleRemoveButton() {
        show(recents = recentFiles)
        val actions = rule.onNodeWithText("annual report.pdf").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Remove from recent files"), actions.map { it.label })
        rule.runOnUiThread { actions.single().action() }
        rule.onNodeWithContentDescription("Remove ders notları.pdf from recent files").performClick()
        assertEquals(listOf("annual report.pdf", "ders notları.pdf"), removedRecents)
    }

    @Test
    fun recentRemoveButton_is48dpOrLarger() {
        show(recents = recentFiles)
        val bounds = rule.onNodeWithContentDescription("Remove annual report.pdf from recent files")
            .fetchSemanticsNode().touchBoundsInRoot
        val min = 48 * rule.density.density
        org.junit.Assert.assertTrue("${bounds.width}x${bounds.height} < $min", bounds.width >= min - 0.5f && bounds.height >= min - 0.5f)
    }

    @Test
    fun settingsAction_isLabelled_48dp_andOpensSettings() {
        show()
        val node = rule.onNodeWithContentDescription("Settings")
        node.performClick()
        assertEquals(1, settingsOpened)
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val min = 48 * rule.density.density
        assertTrue("${bounds.width}x${bounds.height}", bounds.width >= min - 0.5f && bounds.height >= min - 0.5f)
    }

    @Test
    fun toolEnum_listsAllTools_withUniqueRoutes() {
        assertEquals(21, Tool.entries.size)
        assertEquals(Tool.entries.size, Tool.entries.map { it.route }.toSet().size)
    }
}
