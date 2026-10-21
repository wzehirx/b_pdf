package io.github.wzehirx.bpdf.feature.organize

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoveToPositionDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val confirmed = mutableListOf<Int>()
    private var dismissed = 0

    private fun show() = rule.setContent {
        MoveToPositionDialog(current = 2, count = 5, onConfirm = { confirmed += it }, onDismiss = { dismissed++ })
    }

    @Test
    fun parsePosition_acceptsOnlyWholeNumbersInRange() {
        assertEquals(3, parsePosition(" 3 ", 5))
        assertEquals(5, parsePosition("5", 5))
        assertNull(parsePosition("0", 5))
        assertNull(parsePosition("6", 5))
        assertNull(parsePosition("-1", 5))
        assertNull(parsePosition("2.5", 5))
        assertNull(parsePosition("", 5))
        assertNull(parsePosition("99999999999999999999", 5))
    }

    @Test
    fun opensWithTheCurrentPosition() {
        show()
        rule.onNodeWithText("Move page 2").assertExists()
        rule.onNodeWithText("Enter a number from 1 to 5.").assertExists()
    }

    @Test
    fun validNumber_confirms() {
        show()
        val field = rule.onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("4")
        rule.onNodeWithText("Move").performClick()
        assertEquals(listOf(4), confirmed)
    }
}
