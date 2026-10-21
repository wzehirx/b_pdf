package io.github.wzehirx.bpdf.feature.pdftext

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class PdfToTextScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val loaded = PdfToTextUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 3)

    private fun show(state: PdfToTextUiState, ocrAvailable: Boolean = false) = rule.setContent {
        PdfToTextScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onScopeChange = {},
            onRangeChange = {},
            onExtract = { calls += "extract" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onCopyAll = { calls += "copy" },
            onRetry = { calls += "retry" },
            ocrAvailable = ocrAvailable,
            onOpenOcr = { calls += "ocr" },
        )
    }

    private fun result(vararg texts: String, file: File? = File("o.txt")) =
        TextResult(texts.mapIndexed { i, t -> PageText(i, t) }, file, "book_text.txt")

    private fun stateDescription(text: String) =
        rule.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_extractIsDisabledAndSaysWhy() {
        show(PdfToTextUiState())
        rule.onNode(hasText("Extract text") and hasClickAction()).assertIsNotEnabled()
        assertEquals("Choose a PDF first", stateDescription("Extract text"))
    }

    @Test
    fun extract_callsBack() {
        show(loaded)
        rule.onNode(hasText("Extract text") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("extract"), calls)
    }

    @Test
    fun result_hasOneHeadingPerPage_withSelectableText() {
        show(loaded.copy(operation = OperationState.Success(result("alpha", "beta", "gamma"))))
        rule.onAllNodes(isHeading() and hasText("Page ", substring = true)).assertCountEquals(3)
        rule.onNode(hasText("Page 2") and isHeading()).assertExists()
        rule.onNodeWithText("beta").assertExists()
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp")
    fun longResult_keepsEveryHeadingComposed_butBuildsOnlyNearbyText() {
        val pages = (1..30).map { "body $it" }.toTypedArray()
        show(loaded.copy(pageCount = 30, operation = OperationState.Success(result(*pages))))
        rule.onAllNodes(isHeading() and hasText("Page ", substring = true)).assertCountEquals(30)
        rule.onNode(isHeading() and hasText("Page 30")).assertExists()
        rule.onNodeWithText("body 30").assertDoesNotExist()
    }

    @Test
    fun blankPageInMixedResult_saysSoUnderItsHeading_andSummaryCountsIt() {
        show(loaded.copy(operation = OperationState.Success(result("alpha", "", "gamma"))))
        rule.onNodeWithText("Text extracted from 3 pages. 1 page has no text.").assertExists()
        rule.onNodeWithText("No text on this page. It may be a picture of text.").assertExists()
    }

    @Test
    fun success_offersSaveShareAndCopyAll() {
        show(loaded.copy(operation = OperationState.Success(result("alpha"))))
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        rule.onNodeWithText("Copy all text").performScrollTo().performClick()
        assertEquals(listOf("save", "share", "copy"), calls)
    }

    @Test
    fun emptyText_explainsAndOffersOcr_withoutSaveOrPageList() {
        show(loaded.copy(operation = OperationState.Success(result("", "", file = null))), ocrAvailable = true)
        rule.onNodeWithText(
            "No text found. This PDF has no text layer; its pages may be scanned pictures. Text recognition (OCR) can read them.",
        ).assertIsFocused()
        rule.onNodeWithText("Save").assertDoesNotExist()
        rule.onNode(hasText("Page 1") and isHeading()).assertDoesNotExist()
        rule.onNodeWithText("Try text recognition (OCR)").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("ocr"), calls)
    }

    @Test
    fun emptyText_withoutOcrTool_showsDisabledButtonWithExplanation() {
        show(loaded.copy(operation = OperationState.Success(result("", file = null))), ocrAvailable = false)
        rule.onNode(hasText("Try text recognition (OCR)") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        assertEquals("Text recognition is not available in this version yet.", stateDescription("Try text recognition (OCR)"))
        rule.onAllNodes(hasText("Text recognition is not available in this version yet.")).assertCountEquals(1)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun running_disablesExtract_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.5f)))
        rule.onNode(hasText("Extract text") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }
}
