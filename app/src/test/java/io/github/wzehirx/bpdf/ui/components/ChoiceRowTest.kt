package io.github.wzehirx.bpdf.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChoiceRowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun radioOptions_exposeRoleAndSelectedState_andSelect() {
        var picked = "a"
        rule.setContent {
            ChoiceRow("Mode", listOf("a", "b"), picked, { "Option $it" }, { picked = it })
        }
        rule.onNodeWithText("Mode").assertExists()
        rule.onNodeWithText("Option a").assertIsSelected()
        rule.onNodeWithText("Option b").assertIsNotSelected()
        assertEquals(Role.RadioButton, rule.onNodeWithText("Option b").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        rule.onNodeWithText("Option b").performClick()
        assertEquals("b", picked)
    }
}
