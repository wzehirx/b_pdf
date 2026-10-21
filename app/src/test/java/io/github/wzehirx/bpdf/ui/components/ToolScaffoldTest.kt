package io.github.wzehirx.bpdf.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolScaffoldTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun titleIsHeading_backIsLabelled_contentShown() {
        var backs = 0
        rule.setContent { ToolScaffold("Merge", { backs++ }) { Text("body") } }
        rule.onNode(hasText("Merge") and isHeading()).assertExists()
        rule.onNodeWithText("body").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
