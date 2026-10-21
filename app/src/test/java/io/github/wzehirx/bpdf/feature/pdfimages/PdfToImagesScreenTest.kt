package io.github.wzehirx.bpdf.feature.pdfimages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.ImageFormat
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RangeErrorReason
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfToImagesScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val scopes = mutableListOf<PageScope>()
    private val ranges = mutableListOf<String>()
    private val dpis = mutableListOf<DpiChoice>()
    private val formats = mutableListOf<ImageFormat>()

    private val loaded = PdfToImagesUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: PdfToImagesUiState) = rule.setContent {
        PdfToImagesScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onScopeChange = { scopes += it },
            onRangeChange = { ranges += it },
            onDpiChange = { dpis += it },
            onFormatChange = { formats += it },
            onConvert = { calls += "convert" },
            onCancel = { calls += "cancel" },
            onSaveToFolder = { calls += "folder" },
            onSaveZip = { calls += "zip" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun stateDescription(text: String) = rule.onNodeWithText(text).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_convertIsDisabledAndSaysWhy_optionsAreHidden() {
        show(PdfToImagesUiState())
        rule.onNodeWithText("Convert to pictures").assertIsNotEnabled()
        assertEquals("Choose a PDF first", stateDescription("Convert to pictures"))
        rule.onNodeWithText("Image format").assertDoesNotExist()
    }

    @Test
    fun withFile_convertIsEnabledAndCallsBack() {
        show(loaded)
        rule.onNodeWithText("Convert to pictures").performScrollTo().assertIsEnabled().performClick()
        assertNull(stateDescription("Convert to pictures"))
        assertEquals(listOf("convert"), calls)
    }

    @Test
    fun options_areSelectable() {
        show(loaded)
        rule.onNodeWithText("Selected pages").performScrollTo().performClick()
        rule.onNodeWithText("300 dpi, sharpest", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("PNG", substring = true).performScrollTo().performClick()
        assertEquals(listOf(PageScope.SELECTED), scopes)
        assertEquals(listOf(DpiChoice.HIGH), dpis)
        assertEquals(listOf(ImageFormat.PNG), formats)
    }

    @Test
    fun allPages_hidesRangeField_selectedShowsItWithError() {
        show(loaded)
        rule.onNodeWithText("Pages").assertDoesNotExist()
    }

    @Test
    fun selectedPages_showsRangeFieldWithSpecificError() {
        show(
            loaded.copy(
                scope = PageScope.SELECTED, rangeText = "9",
                rangeError = PdfError.InvalidRange(RangeErrorReason.OUT_OF_BOUNDS, "9"),
            ),
        )
        rule.onNode(hasText("Page \"9\" is outside the document", substring = true)).performScrollTo().assertExists()
    }

    @Test
    fun success_showsCountAndThreeLabelledButtons() {
        val files = List(3) { File("book_p${it + 1}.jpg") }
        show(loaded.copy(operation = OperationState.Success(PdfToImagesResult(files, File("d"), "book_images.zip"))))
        rule.onNodeWithText("Created 3 pictures").assertExists()
        for (label in listOf("Save to folder", "Save as ZIP", "Share")) {
            val node = rule.onNodeWithText(label).performScrollTo()
            node.assertIsEnabled()
            assertNotNull(node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
            node.performClick()
        }
        assertEquals(listOf("folder", "zip", "share"), calls)
    }

    @Test
    fun success_singleFile_usesSingular() {
        show(loaded.copy(operation = OperationState.Success(PdfToImagesResult(listOf(File("a_p1.jpg")), File("d"), "z.zip"))))
        rule.onNodeWithText("Created 1 picture").assertExists()
    }

    @Test
    fun saving_disablesResultButtons() {
        show(loaded.copy(saving = true, operation = OperationState.Success(PdfToImagesResult(listOf(File("a.jpg")), File("d"), "z.zip"))))
        for (label in listOf("Save to folder", "Save as ZIP", "Share")) {
            rule.onNodeWithText(label).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun running_disablesControls_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        rule.onNodeWithText("Convert to pictures").assertIsNotEnabled()
        rule.onNodeWithText("Choose another PDF").assertIsNotEnabled()
        rule.onNodeWithText("JPG", substring = true).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun fileCard_exposesRemoveCustomAction() {
        show(loaded)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        assertEquals(listOf("remove"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }
}
