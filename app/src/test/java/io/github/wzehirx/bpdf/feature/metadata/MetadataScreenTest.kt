package io.github.wzehirx.bpdf.feature.metadata

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfMetadata
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MetadataScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val info = PdfMetadata("T", "A", null, null, null, "SomeTool 2", 5, false, 1.7f)
    private val loaded = MetadataUiState(
        file = ImportedFile(File("book.pdf"), "book.pdf", 1000), info = info,
        title = "Şükrü Öğüt — İçerik", author = "A",
    )

    private fun show(state: MetadataUiState) = rule.setContent {
        MetadataScreenContent(
            state = state, onBack = {}, onPickFile = {}, onRemoveFile = {},
            onTitleChange = { calls += "title:$it" }, onAuthorChange = { calls += "author:$it" },
            onSubjectChange = { calls += "subject:$it" }, onKeywordsChange = { calls += "keywords:$it" },
            onCreatorChange = { calls += "creator:$it" }, onWrite = { calls += "write" },
            onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
        )
    }

    @Test
    fun withoutFile_applyDisabledWithReason() {
        show(MetadataUiState())
        rule.onNodeWithText("Apply changes").assertIsNotEnabled()
        assertEquals("Choose a PDF first", rule.onNodeWithText("Apply changes").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun info_isShownAsReadOnlyLines() {
        show(loaded)
        rule.onNodeWithText("5 pages").performScrollTo().assertExists()
        rule.onNodeWithText("Not protected with a password").performScrollTo().assertExists()
        rule.onNodeWithText("PDF version 1.7").performScrollTo().assertExists()
        rule.onNodeWithText("Made with: SomeTool 2").performScrollTo().assertExists()
    }

    @Test
    fun protectedFile_showsInfo() {
        show(loaded.copy(info = info.copy(encrypted = true, producer = null)))
        rule.onNodeWithText("Protected with a password").performScrollTo().assertExists()
        rule.onNodeWithText("The program that made it is not recorded").performScrollTo().assertExists()
    }

    @Test
    fun fields_showCurrentValues_includingTurkishText_andReportEdits() {
        show(loaded)
        rule.onNodeWithText("Şükrü Öğüt — İçerik").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Title")).performScrollTo().performTextReplacement("Yeni")
        assertEquals(listOf("title:Yeni"), calls.filterNot { it.endsWith(":") })
        rule.onNodeWithText("Separate keywords with commas.").assertExists()
    }

    @Test
    fun apply_callsBack() {
        show(loaded)
        rule.onNode(hasText("Apply changes") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("write"), calls)
    }

    @Test
    fun success_showsResult() {
        show(loaded.copy(operation = OperationState.Success(MetadataResult(File("x.pdf"), "book_info.pdf"))))
        rule.onNodeWithText("Details updated. Save or share the new copy.").assertExists()
    }
}
