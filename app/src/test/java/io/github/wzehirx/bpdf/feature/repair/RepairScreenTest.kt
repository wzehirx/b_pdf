package io.github.wzehirx.bpdf.feature.repair

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RepairScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val file = ImportedFile(File("book.pdf"), "book.pdf", 1000)

    private fun show(state: RepairUiState) = rule.setContent {
        RepairScreenContent(
            state = state, onBack = {}, onPickFile = {}, onRemoveFile = {}, onRepair = { calls += "repair" },
            onCancel = { calls += "cancel" }, onSave = {}, onShare = {}, onRetry = { calls += "retry" },
        )
    }

    private val button = hasText("Repair") and hasClickAction()

    @Test
    fun withoutFile_repairDisabledWithReason() {
        show(RepairUiState())
        rule.onNode(button).assertIsNotEnabled()
        assertEquals("Choose a PDF first", rule.onNode(button).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun readableFile_showsPageCount_andRepairCallsBack() {
        show(RepairUiState(file = file, pageCount = 4))
        rule.onNodeWithText("book.pdf, 4 pages").assertExists()
        rule.onNode(button).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("repair"), calls)
    }

    @Test
    fun unreadableFile_isDescribedAsDamaged_andStillRepairable() {
        show(RepairUiState(file = file, pageCount = null))
        rule.onNodeWithText("book.pdf, damaged and cannot be read as it is").assertExists()
        rule.onNode(button).performScrollTo().assertIsEnabled()
    }

    @Test
    fun success_showsRecoveredPages() {
        show(RepairUiState(file = file, operation = OperationState.Success(RepairOutcome(File("x.pdf"), 3, "n.pdf"))))
        rule.onNodeWithText("Repaired. Recovered 3 pages.").assertExists()
    }

    @Test
    fun failure_showsMessage_andRetryCallsBack() {
        show(RepairUiState(file = file, operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertExists()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }
}
