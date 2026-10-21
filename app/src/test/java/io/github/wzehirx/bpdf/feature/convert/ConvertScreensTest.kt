package io.github.wzehirx.bpdf.feature.convert

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.ConversionStats
import io.github.wzehirx.bpdf.core.pdf.DocumentKind
import io.github.wzehirx.bpdf.core.pdf.DocumentUnreadable
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLayout
import io.github.wzehirx.bpdf.core.pdf.ToPdfStats
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
class ConvertScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun stateDescription(text: String) =
        rule.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    // ---- PDF to Word, Excel, HTML ----

    private val loaded = PdfToDocumentUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 3)

    private fun showFromPdf(state: PdfToDocumentUiState) = rule.setContent {
        PdfToDocumentScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onFormatChange = { calls += "format:${it.name}" },
            onLayoutChange = { calls += "layout:${it.name}" },
            onConvert = { calls += "convert" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun result(format: DocumentFormat = DocumentFormat.WORD, stats: ConversionStats = ConversionStats(3, 0)) =
        PdfToDocumentResult(File("o"), "book_word.docx", format, stats)

    @Test
    fun fromPdf_titleIsAHeading_andPickerIsLabelled() {
        showFromPdf(PdfToDocumentUiState())
        rule.onNode(isHeading() and hasText("PDF to Word, Excel, HTML")).assertExists()
        rule.onNode(hasText("Choose PDF") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun fromPdf_withoutFile_convertIsDisabledAndSaysWhy() {
        showFromPdf(PdfToDocumentUiState())
        rule.onNode(hasText("Convert") and hasClickAction()).assertIsNotEnabled()
        assertEquals("Choose a PDF first", stateDescription("Convert"))
    }

    @Test
    fun fromPdf_formatChoiceIsARadioGroup_defaultsToWord_andCallsBack() {
        showFromPdf(loaded)
        rule.onNodeWithText("Word document (docx)").assertIsSelected()
        rule.onNodeWithText("Excel spreadsheet (xlsx), basic and experimental").performScrollTo().performClick()
        rule.onNodeWithText("Web page (html)").performScrollTo().performClick()
        assertEquals(listOf("format:EXCEL", "format:HTML"), calls)
    }

    @Test
    fun fromPdf_layoutChoice_defaultsToTextAndPictures_andIsHiddenForExcel() {
        showFromPdf(loaded)
        rule.onNodeWithText("Text and pictures (editable)").assertIsSelected()
        rule.onNodeWithText("Exact look (each page as a picture, followed by its text)").performScrollTo().performClick()
        assertEquals(listOf("layout:EXACT_LOOK"), calls)
    }

    @Test
    fun fromPdf_excel_showsExperimentalNote_insteadOfLayoutChoice() {
        showFromPdf(loaded.copy(format = DocumentFormat.EXCEL))
        rule.onNodeWithText("Excel conversion is basic", substring = true).assertExists()
        rule.onNodeWithText("Text and pictures (editable)").assertDoesNotExist()
    }

    @Test
    fun fromPdf_convertCallsBack_andRemoveHasAccessibleLabel() {
        showFromPdf(loaded)
        rule.onNode(hasText("Convert") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Remove book.pdf").performClick()
        assertEquals(listOf("convert", "remove"), calls)
    }

    @Test
    fun fromPdf_success_announcesSummaryWithPicturesAndBlankPages_andOffersSaveShare() {
        showFromPdf(loaded.copy(operation = OperationState.Success(result(stats = ConversionStats(3, 1, 2)))))
        rule.onNodeWithText("Converted 3 pages to a Word document. 2 pictures included. 1 page has no text. It may be a scanned picture.")
            .assertIsFocused()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun fromPdf_protectedInput_saysTheCopyHasNoPassword() {
        showFromPdf(loaded.copy(copyUnprotected = true, operation = OperationState.Success(result(DocumentFormat.HTML))))
        rule.onNode(hasText("Converted 3 pages to a web page.", substring = true) and hasText("password", substring = true)).assertIsFocused()
    }

    @Test
    fun fromPdf_failed_showsFocusedErrorAndRetry() {
        showFromPdf(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun fromPdf_running_disablesEverything_andCancelWorks() {
        showFromPdf(loaded.copy(operation = OperationState.Running(0.4f)))
        rule.onNode(hasText("Convert") and hasClickAction()).assertIsNotEnabled()
        rule.onNode(hasText("Choose another PDF") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    // ---- Word, Excel, Text to PDF ----

    private val doc = DocumentToPdfUiState(file = ImportedFile(File("rapor.docx"), "rapor.docx", 1000), kind = DocumentKind.DOCX)

    private fun showToPdf(state: DocumentToPdfUiState) = rule.setContent {
        DocumentToPdfScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onConvert = { calls += "convert" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    @Test
    fun toPdf_titleIsAHeading_andLimitsAreStated() {
        showToPdf(DocumentToPdfUiState())
        rule.onNode(isHeading() and hasText("Word, Excel, Text to PDF")).assertExists()
        rule.onNodeWithText("Word files keep headings", substring = true).assertExists()
        rule.onNodeWithText("Old doc and xls files are not supported", substring = true).assertExists()
        rule.onNode(hasText("Choose Word, Excel or text file") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun toPdf_withoutFile_convertIsDisabledAndSaysWhy() {
        showToPdf(DocumentToPdfUiState())
        rule.onNode(hasText("Convert to PDF") and hasClickAction()).assertIsNotEnabled()
        assertEquals("Choose a file first", stateDescription("Convert to PDF"))
    }

    @Test
    fun toPdf_fileCard_namesTheDetectedKind_andRemoveWorks() {
        showToPdf(doc)
        rule.onNodeWithText("rapor.docx, Word document").assertExists()
        rule.onNodeWithContentDescription("Remove rapor.docx").performClick()
        rule.onNode(hasText("Convert to PDF") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("remove", "convert"), calls)
    }

    @Test
    fun toPdf_success_isFocused_withExtraSentences_andSaveShare() {
        val stats = ToPdfStats(pages = 4, pictures = 1, sheets = 2, truncated = true)
        showToPdf(doc.copy(operation = OperationState.Success(DocumentToPdfResult(File("o"), "rapor_converted.pdf", DocumentKind.XLSX, stats))))
        rule.onNodeWithText(
            "Created a PDF with 4 pages. 1 picture included. 2 sheets converted. Large sheets were cut: only the first 5000 rows and 40 columns of a sheet are included.",
        ).assertIsFocused()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun toPdf_unreadable_isSpecific_focused_andRetryable() {
        showToPdf(doc.copy(operation = OperationState.Failed(DocumentUnreadable("eski.doc"))))
        rule.onNodeWithText("\"eski.doc\" could not be read.", substring = true).assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun toPdf_running_disablesPicker_andCancelWorks() {
        showToPdf(doc.copy(operation = OperationState.Running(null)))
        rule.onNode(hasText("Convert to PDF") and hasClickAction()).assertIsNotEnabled()
        rule.onNode(hasText("Choose another file") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }
}
