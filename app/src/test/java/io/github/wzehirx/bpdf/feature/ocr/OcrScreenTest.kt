package io.github.wzehirx.bpdf.feature.ocr

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.ocr.OcrFailed
import io.github.wzehirx.bpdf.core.ocr.OcrLanguage
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.feature.pdftext.TextResult
import io.github.wzehirx.bpdf.ui.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OcrScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val languages = mutableListOf<OcrLanguage>()
    private val scopes = mutableListOf<PageScope>()

    private val loaded = OcrUiState(file = ImportedFile(File("scan.pdf"), "scan.pdf", 1000), pageCount = 12)

    private fun result(vararg texts: String, file: File? = File("t.txt")) =
        TextResult(texts.mapIndexed { i, t -> PageText(i, t) }, file, "scan_text.txt")

    private fun show(state: OcrUiState) = rule.setContent {
        OcrScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onLanguageChange = { languages += it },
            onScopeChange = { scopes += it },
            onRangeChange = {},
            onRecognize = { calls += "recognize" },
            onCancel = { calls += "cancel" },
            onSaveText = { calls += "saveText" },
            onShareText = { calls += "shareText" },
            onCopyAll = { calls += "copy" },
            onSaveSearchablePdf = { calls += "savePdf" },
            onRetry = { calls += "retry" },
        )
    }

    private fun button(text: String) = rule.onNode(hasText(text) and hasClickAction())

    @Test
    fun ocrTool_isAvailable() {
        assertTrue(Tool.OCR.available)
    }

    @Test
    fun withoutFile_buttonIsDisabledAndSaysWhy_andOptionsAreHidden() {
        show(OcrUiState())
        button("Recognize text").assertIsNotEnabled()
        assertEquals(
            "Choose a PDF first",
            button("Recognize text").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        rule.onNodeWithText("Language of the text").assertDoesNotExist()
    }

    @Test
    fun languageChoice_turkishAndEnglishIsTheDefault_andEachIsANamedRadio() {
        show(loaded)
        rule.onNodeWithText("Turkish and English").assertIsSelected()
        for (label in listOf("Turkish and English", "Turkish", "English")) {
            assertEquals(Role.RadioButton, rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithText("English").performScrollTo().performClick()
        assertEquals(listOf(OcrLanguage.ENGLISH), languages)
    }

    @Test
    fun scope_rangeFieldOnlyForSelectedPages() {
        show(loaded)
        rule.onNodeWithText("Pages").assertDoesNotExist()
        rule.onNodeWithText("Selected pages").performScrollTo().performClick()
        assertEquals(listOf(PageScope.SELECTED), scopes)
    }

    @Test
    fun selectedScope_invalidRange_exposesError() {
        show(loaded.copy(scope = PageScope.SELECTED, rangeText = "5-2", rangeError = PdfError.InvalidRange(RangeErrorReason.REVERSED, "5-2")))
        val message = "The range \"5-2\" goes backwards. Write the smaller page first, for example 2-5."
        rule.onNodeWithText(message).assertExists()
        assertEquals(message, rule.onNode(hasText("5-2")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun recognize_callsBack() {
        show(loaded)
        button("Recognize text").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("recognize"), calls)
    }

    @Test
    fun running_announcesThePageBeingRead_politely_andCancelWorks() {
        show(loaded.copy(progress = OcrPageProgress(3, 12, 3), operation = OperationState.Running(0.2f)))
        val node = rule.onNodeWithText("Recognizing page 3 of 12")
        node.assertExists()
        assertEquals(LiveRegionMode.Polite, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        rule.onNodeWithText("Working… 20%").assertDoesNotExist()
        button("Recognize text").assertIsNotEnabled()
        rule.onNodeWithText("Turkish").assertIsNotEnabled()
        button("Cancel").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun running_beforeTheFirstPage_showsWorking() {
        show(loaded.copy(operation = OperationState.Running(null)))
        rule.onNodeWithText("Working…").assertExists()
    }

    @Test
    fun success_showsCount_offersFourActions_andOneHeadingPerPage() {
        show(loaded.copy(operation = OperationState.Success(result("alpha", "beta", "gamma"))))
        rule.onNodeWithText("Recognized text on 3 pages.").assertIsFocused()
        button("Save as text file").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        button("Share text").performScrollTo().performClick()
        button("Copy all text").performScrollTo().performClick()
        button("Save searchable PDF").performScrollTo().performClick()
        assertEquals(listOf("saveText", "shareText", "copy", "savePdf"), calls)
        rule.onAllNodes(isHeading() and hasText("Page ", substring = true)).assertCountEquals(3)
        rule.onNodeWithText("beta").assertExists()
    }

    @Test
    fun success_summaryCountsBlankPages_andBlankPageSaysNothingWasRecognized() {
        show(loaded.copy(operation = OperationState.Success(result("alpha", ""))))
        rule.onNodeWithText("Recognized text on 2 pages. 1 page has no text.").assertExists()
        rule.onNodeWithText("No text was recognized on this page.").assertExists()
    }

    @Test
    fun success_ofAnUnlockedInput_notesThatTheSearchablePdfHasNoPassword() {
        show(loaded.copy(copyUnprotected = true, operation = OperationState.Success(result("alpha"))))
        rule.onNodeWithText("The saved copy has no password.").performScrollTo().assertExists()
    }

    @Test
    fun success_whileSaving_disablesTheActions() {
        show(loaded.copy(saving = true, operation = OperationState.Success(result("alpha"))))
        button("Save as text file").performScrollTo().assertIsNotEnabled()
        button("Save searchable PDF").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun nothingRecognized_explains_withoutActionsOrPageList() {
        show(loaded.copy(operation = OperationState.Success(result("", "", file = null))))
        rule.onNodeWithText(
            "No text could be recognized. Check that the language matches the document and that the pages are clear scans.",
        ).assertIsFocused()
        rule.onNodeWithText("Save searchable PDF").assertDoesNotExist()
        rule.onNode(hasText("Page 1") and isHeading()).assertDoesNotExist()
    }

    @Test
    fun failed_showsFocusedError_andRetry() {
        show(loaded.copy(operation = OperationState.Failed(OcrFailed("no data"))))
        rule.onNodeWithText("Text recognition could not start. Check the available storage and try again.").assertIsFocused()
        button("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun fileCard_exposesRemoveCustomAction() {
        show(loaded)
        val actions = rule.onNode(hasText("scan.pdf, 12 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        assertEquals(listOf("remove"), calls)
    }
}
