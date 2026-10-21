package io.github.wzehirx.bpdf.core.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReorderActionsTest {
    @get:Rule
    val rule = createComposeRule()

    private val moves = mutableListOf<Pair<Int, Int>>()
    private var removed = 0

    private fun setItems(count: Int, withRemove: Boolean = true) {
        rule.setContent {
            Column {
                for (i in 0 until count) {
                    Box(
                        Modifier.size(48.dp).testTag("item$i").reorderable(
                            index = i,
                            count = count,
                            onMove = { from, to -> moves += from to to },
                            onRemove = if (withRemove) ({ removed++ }) else null,
                        ),
                    )
                }
            }
        }
    }

    private fun labels(tag: String): List<String> =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun SemanticsNodeInteraction.invoke(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        rule.runOnUiThread { action.action() }
    }

    @Test
    fun firstItem_hasNoUpOrTop() {
        setItems(3)
        assertEquals(listOf("Move down", "Move to bottom", "Remove"), labels("item0"))
    }

    @Test
    fun lastItem_hasNoDownOrBottom() {
        setItems(3)
        assertEquals(listOf("Move up", "Move to top", "Remove"), labels("item2"))
    }

    @Test
    fun middleItem_hasAllMoves() {
        setItems(3)
        assertEquals(
            listOf("Move up", "Move down", "Move to top", "Move to bottom", "Remove"),
            labels("item1"),
        )
    }

    @Test
    fun noRemoveWhenCallbackNull() {
        setItems(3, withRemove = false)
        assertFalse("Remove" in labels("item1"))
    }

    @Test
    fun moveDown_callsOnMove() {
        setItems(3)
        rule.onNodeWithTag("item0").invoke("Move down")
        assertEquals(listOf(0 to 1), moves)
    }

    @Test
    fun otherActions_callOnMoveAndRemove() {
        setItems(3)
        rule.onNodeWithTag("item1").invoke("Move up")
        rule.onNodeWithTag("item1").invoke("Move to top")
        rule.onNodeWithTag("item1").invoke("Move to bottom")
        rule.onNodeWithTag("item1").invoke("Remove")
        assertEquals(listOf(1 to 0, 1 to 0, 1 to 2), moves)
        assertEquals(1, removed)
    }

    @Test
    fun singleItem_hasOnlyRemove() {
        setItems(1)
        assertEquals(listOf("Remove"), labels("item0"))
        assertTrue(moves.isEmpty())
    }
}
