package io.github.wzehirx.bpdf.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PasswordDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val submitted = mutableListOf<String>()

    private fun show(wrong: Boolean = false) =
        rule.setContent { PasswordDialog({ submitted += it }, {}, wrong) }

    @Test
    fun field_isFocusedOnOpen_andEmptyPasswordCannotBeSubmitted() {
        show()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNodeWithText("OK").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun typedPassword_submitsViaButtonAndIme() {
        show()
        rule.onNode(hasSetTextAction()).performTextInput("s3cret")
        rule.onNodeWithText("OK").assertIsEnabled().performClick()
        rule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("s3cret", "s3cret"), submitted)
    }

    @Test
    fun toggle_hasFixedLabelAndStateDescription() {
        show()
        fun state() = rule.onNodeWithText("Show password").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Hidden", state())
        rule.onNodeWithText("Show password").performClick()
        assertEquals("Visible", state())
    }

    @Test
    fun wrongPassword_showsErrorOnField() {
        show(wrong = true)
        rule.onNodeWithText("That password is incorrect. Try again.").assertExists()
    }

    @Test
    fun field_isAPasswordField() {
        show()
        rule.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun fileName_isShownWhenGiven() {
        rule.setContent { PasswordDialog({}, {}, false, "locked.pdf") }
        rule.onNodeWithText("Enter the password for \"locked.pdf\".").assertExists()
    }
}
