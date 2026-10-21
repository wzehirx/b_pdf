package io.github.wzehirx.bpdf.feature.pagenumbers

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.NumberPosition
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PageNumbersScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val positions = mutableListOf<NumberPosition>()
    private val formats = mutableListOf<NumberFormat>()
    private val customs = mutableListOf<String>()
    private val starts = mutableListOf<String>()
    private val sizes = mutableListOf<Int>()
    private val scopes = mutableListOf<PageScope>()

    private val loaded = PageNumbersUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: PageNumbersUiState) = rule.setContent {
        PageNumbersScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onPositionChange = { positions += it },
            onFormatChange = { formats += it },
            onCustomFormatChange = { customs += it },
            onStartChange = { starts += it },
            onFontSizeChange = { sizes += it },
            onScopeChange = { scopes += it },
            onRangeChange = {},
            onApply = { calls += "apply" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun applyButton() = rule.onNode(hasText("Add page numbers") and hasClickAction())

    @Test
    fun withoutFile_buttonIsDisabledAndSaysWhy_andOptionsAreHidden() {
        show(PageNumbersUiState())
        applyButton().assertIsNotEnabled()
        assertEquals("Choose a PDF first", applyButton().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Bottom center").assertDoesNotExist()
    }

    @Test
    fun positions_areSixRadios_withSelection_andCallBack() {
        show(loaded)
        rule.onNodeWithText("Bottom center").assertIsSelected()
        val labels = listOf("Top left", "Top center", "Top right", "Bottom left", "Bottom center", "Bottom right")
        for (label in labels) {
            assertEquals(Role.RadioButton, rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithText("Top right").performScrollTo().performClick()
        assertEquals(listOf(NumberPosition.TOP_RIGHT), positions)
    }

    @Test
    fun formatPresets_showExamplesWithTheRealPageCount_andCallBack() {
        show(loaded)
        rule.onNodeWithText("Number only (1)").assertIsSelected()
        rule.onNodeWithText("Number and total (1 / 5)").assertExists()
        rule.onNodeWithText("Page 1 of 5").performScrollTo().performClick()
        rule.onNodeWithText("Custom format").performScrollTo().performClick()
        assertEquals(listOf(NumberFormat.LOCALIZED, NumberFormat.CUSTOM), formats)
    }

    @Test
    fun slashPreset_isSpokenWithoutTheSlash() {
        show(loaded)
        val config = rule.onNodeWithText("Number and total (1 / 5)").fetchSemanticsNode().config
        assertEquals(listOf("Number and total (1 of 5)"), config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishPreset_isSpokenAsBolu() {
        show(loaded)
        val spoken = rule.onNodeWithText("Sayfa 1 / 5").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Sayfa 1 bölü 5"), spoken)
    }

    @Test
    fun customFormat_fieldOnlyForCustom_andErrorIsExposed() {
        show(loaded)
        rule.onNodeWithText("Write {n} where the page number goes and {total} for the last number.").assertDoesNotExist()
    }

    @Test
    fun customFormat_editsCallBack_andEmptyShowsError() {
        show(loaded.copy(format = NumberFormat.CUSTOM, customFormat = "", fieldErrors = setOf(NumberFieldError.FORMAT_EMPTY)))
        rule.onNodeWithText("Enter a format, for example {n}.").assertExists()
        val field = rule.onNode(hasSetTextAction() and hasText("Custom format"))
        assertEquals("Enter a format, for example {n}.", field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        field.performTextReplacement("Page {n}")
        assertEquals(listOf("Page {n}"), customs)
    }

    @Test
    fun startField_invalidShowsError_andEditsCallBack() {
        show(loaded.copy(startText = "", fieldErrors = setOf(NumberFieldError.START_INVALID)))
        rule.onNodeWithText("Enter a whole number from 1 to 99999.").assertExists()
        val field = rule.onNode(hasSetTextAction() and hasText("First number"))
        assertEquals("Enter a whole number from 1 to 99999.", field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        field.performTextReplacement("7")
        assertEquals(listOf("7"), starts)
    }

    @Test
    fun fontSizeSlider_hasLabelSpokenValue_andAdjusts() {
        show(loaded.copy(fontSize = 12))
        val slider = rule.onNode(hasContentDescription("Text size")).performScrollTo().assertHeightIsAtLeast(48.dp)
        assertEquals("12 points", slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
        assertEquals(listOf(18), sizes)
    }

    @Test
    fun scope_selectedShowsRangeField() {
        show(loaded)
        rule.onNodeWithText("Pages").assertDoesNotExist()
        rule.onNodeWithText("Selected pages").performScrollTo().performClick()
        assertEquals(listOf(PageScope.SELECTED), scopes)
    }

    @Test
    fun apply_callsBack() {
        show(loaded)
        applyButton().performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("apply"), calls)
    }

    @Test
    fun success_showsCount_saveAndShare() {
        show(loaded.copy(operation = OperationState.Success(PageNumbersResult(File("o.pdf"), 3, 5, "n.pdf"))))
        rule.onNodeWithText("Numbered 3 pages").assertExists().assertIsFocused()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun running_disablesEveryControl_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        applyButton().assertIsNotEnabled()
        rule.onNodeWithText("Top left").assertIsNotEnabled()
        rule.onNodeWithText("Page 1 of 5").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun fileCard_exposesRemoveCustomAction() {
        show(loaded)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        assertEquals(listOf("remove"), calls)
    }
}
