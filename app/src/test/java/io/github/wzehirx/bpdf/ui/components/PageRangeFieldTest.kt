package io.github.wzehirx.bpdf.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageRangeFieldTest {
    @get:Rule
    val rule = createComposeRule()

    private val hasError = SemanticsMatcher("has Error semantics") {
        it.config.getOrNull(SemanticsProperties.Error) != null
    }

    @Test
    fun withError_exposesErrorSemantics_andShowsMessageAsSupportingText() {
        rule.setContent {
            PageRangeField("5-2", {}, pageCount = 10, error = PdfError.InvalidRange(RangeErrorReason.REVERSED, "5-2"))
        }
        rule.onNode(hasError).assertExists()
        val msg = rule.onNode(hasError).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error)
        assertNotNull(msg)
        rule.onNodeWithText(msg!!, substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun withoutError_hasNoErrorSemantics_andShowsHintWithPageCount() {
        rule.setContent { PageRangeField("1-3", {}, pageCount = 10, error = null) }
        rule.onNode(hasError).assertDoesNotExist()
        rule.onNodeWithText("For example 1-3, 5. The document has 10 pages.", useUnmergedTree = true).assertIsDisplayed()
    }
}
