package io.github.wzehirx.bpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class PageGridTest {
    @get:Rule
    val rule = createComposeRule()

    private val toggled = mutableListOf<Int>()
    private val calls = mutableListOf<String>()
    private val loaded = Collections.synchronizedList(mutableListOf<Int>())

    private fun show(
        pageCount: Int = 3,
        selected: Set<Int> = emptySet(),
        selectable: Boolean = true,
        rotations: Map<Int, Int> = emptyMap(),
        enabled: Boolean = true,
        overflow: Boolean = false,
        actions: (Int) -> List<CustomAccessibilityAction> = { emptyList() },
        thumbnail: suspend (Int) -> Bitmap? = { loaded += it; null },
    ) = rule.setContent {
        PageGrid(
            pageCount = pageCount,
            thumbnail = thumbnail,
            selected = selected,
            onToggle = if (selectable) { i -> toggled += i } else null,
            rotations = rotations,
            pageActions = actions,
            enabled = enabled,
            onSelectAll = { calls += "all" },
            onClearSelection = { calls += "clear" },
            overflowMenu = overflow,
        )
    }

    private fun cell(label: String): SemanticsNodeInteraction = rule.onNodeWithContentDescription(label)

    private fun SemanticsNodeInteraction.state() = fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun SemanticsNodeInteraction.role() = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

    @Test
    fun cell_isOneLabelledNode_withStateAndCheckboxRole() {
        show(selected = setOf(0))
        assertEquals("Selected", cell("Page 1 of 3").state())
        assertEquals("Not selected", cell("Page 2 of 3").state())
        assertEquals(Role.Checkbox, cell("Page 1 of 3").role())
        // The cell's own number and thumbnail add no nodes of their own: one node per page.
        assertEquals(1, rule.onAllNodes(hasContentDescription("Page 1 of 3")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("1") and hasNoClickAction(), useUnmergedTree = false).fetchSemanticsNodes().size)
    }

    @Test
    fun cell_clickTogglesThatPage() {
        show()
        cell("Page 2 of 3").performClick()
        assertEquals(listOf(1), toggled)
    }

    @Test
    fun rotatedPage_isAnnouncedInTheLabel() {
        show(rotations = mapOf(1 to 90, 2 to 360, 0 to 0))
        cell("Page 2 of 3, rotated 90°").assertExists()
        cell("Page 1 of 3").assertExists()
        cell("Page 3 of 3").assertExists()
    }

    @Test
    fun notSelectable_hasNoStateOrCheckboxRole_andNoBulkControls() {
        show(selectable = false)
        assertNull(cell("Page 1 of 3").state())
        assertFalse(cell("Page 1 of 3").role() == Role.Checkbox)
        rule.onNodeWithText("Select all").assertDoesNotExist()
        rule.onNodeWithText("Clear selection").assertDoesNotExist()
        rule.onNodeWithText("0 pages selected").assertDoesNotExist()
        cell("Page 1 of 3").performClick()
        assertTrue(toggled.isEmpty())
    }

    @Test
    fun customActions_areSuppliedPerPage_andInvokeCallbacks() {
        show(actions = { i ->
            listOf(
                CustomAccessibilityAction("Do A") { calls += "A$i"; true },
                CustomAccessibilityAction("Do B") { calls += "B$i"; true },
            )
        })
        val actions = cell("Page 2 of 3").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Do A", "Do B"), actions.map { it.label })
        rule.runOnUiThread { actions.first { it.label == "Do B" }.action() }
        assertEquals(listOf("B1"), calls)
    }

    @Test
    fun selectAllAndClear_callBack_andCountIsPlainText() {
        show(selected = setOf(0, 2))
        rule.onNodeWithText("2 pages selected").assertExists()
        assertNull(rule.onNodeWithText("2 pages selected").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Clear selection").performClick()
        assertEquals(listOf("all", "clear"), calls)
    }

    @Test
    fun count_isSingularForOne() {
        show(selected = setOf(1))
        rule.onNodeWithText("1 page selected").assertExists()
    }

    @Test
    fun bulkButtons_areDisabledWhenThereIsNothingToDo() {
        show(selected = emptySet())
        rule.onNodeWithText("Clear selection").assertIsNotEnabled()
        rule.onNodeWithText("Select all").performClick()
        assertEquals(listOf("all"), calls)
    }

    @Test
    fun disabled_blocksToggleAndActions() {
        show(enabled = false, overflow = true, actions = { listOf(CustomAccessibilityAction("Do A") { true }) })
        val config = cell("Page 1 of 3").fetchSemanticsNode().config
        assertTrue(config.getOrNull(SemanticsActions.CustomActions).orEmpty().isEmpty())
        rule.onNodeWithText("Select all").assertIsNotEnabled()
        cell("Page 1 of 3").performClick()
        assertTrue(toggled.isEmpty())
        rule.onNodeWithContentDescription("Actions for page 1").assertDoesNotExist()
    }

    @Test
    fun overflowMenu_listsTheSameActions_andInvokesThem() {
        show(overflow = true, selectable = false, actions = { i ->
            listOf(
                CustomAccessibilityAction("Do A") { calls += "A$i"; true },
                CustomAccessibilityAction("Do B") { calls += "B$i"; true },
            )
        })
        rule.onNode(hasContentDescription("Actions for page 2")).performClick()
        rule.onNodeWithText("Do A").assertExists()
        rule.onNodeWithText("Do B").performClick()
        assertEquals(listOf("B1"), calls)
        rule.onNodeWithText("Do A").assertDoesNotExist() // menu closed
    }

    @Test
    fun thumbnails_areDecorative_andLoadedOnlyForVisibleCells() {
        show(pageCount = 600)
        rule.waitForIdle()
        assertTrue("page 0 requested", 0 in loaded)
        assertTrue("only visible cells are loaded, got ${loaded.size}", loaded.size in 1..60)
        // No node other than the cell, the buttons and the count carries a content description.
        val described = rule.onAllNodes(hasContentDescription("Page 1 of 600", substring = false)).fetchSemanticsNodes()
        assertEquals(1, described.size)
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishLabels() {
        show(selected = setOf(0), rotations = mapOf(1 to 90))
        assertEquals("Seçili", cell("Sayfa 1 / 3").state())
        assertEquals("Seçili değil", cell("Sayfa 3 / 3").state())
        cell("Sayfa 2 / 3, 90° döndürülmüş").assertExists()
        rule.onNodeWithText("Tümünü seç").assertExists()
        rule.onNodeWithText("Seçimi temizle").assertExists()
        rule.onNodeWithText("1 sayfa seçili").assertExists()
    }
}
