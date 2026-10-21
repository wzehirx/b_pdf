package io.github.wzehirx.bpdf.feature.compress

import android.text.format.Formatter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.CompressLevel
import io.github.wzehirx.bpdf.core.pdf.CompressResult
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CompressScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val levels = mutableListOf<CompressLevel>()
    private val metadata = mutableListOf<Boolean>()

    private val loaded = CompressUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: CompressUiState) = rule.setContent {
        CompressScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onLevelChange = { levels += it },
            onRemoveMetadataChange = { metadata += it },
            onCompress = { calls += "compress" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun compressButton() = rule.onNode(hasText("Compress") and hasClickAction())

    private fun result(before: Long, after: Long) =
        OperationState.Success(CompressUiResult(File("o.pdf"), CompressResult(before, after), "n.pdf"))

    @Test
    fun withoutFile_compressIsDisabledAndSaysWhy_andOptionsAreHidden() {
        show(CompressUiState())
        compressButton().assertIsNotEnabled()
        assertEquals("Choose a PDF first", compressButton().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithText("Balanced").assertDoesNotExist()
    }

    @Test
    fun levelOptions_areRadiosWithSelection_andCallBack() {
        show(loaded.copy(level = CompressLevel.MEDIUM))
        rule.onNodeWithText("Balanced").assertIsSelected()
        rule.onNodeWithText("Light, best picture quality").assertIsNotSelected()
        rule.onNodeWithText("Strong, smallest file").assertIsNotSelected()
        assertEquals(Role.RadioButton, rule.onNodeWithText("Strong, smallest file").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        rule.onNodeWithText("Strong, smallest file").performScrollTo().performClick()
        assertEquals(listOf(CompressLevel.HIGH), levels)
    }

    @Test
    fun removeMetadata_isASwitchWithSpokenState_andCallsBack() {
        show(loaded)
        val row = rule.onNode(hasText("Also remove document details (title, author, keywords)"))
        assertEquals("Kept", row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(Role.Switch, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        row.performScrollTo().performClick()
        assertEquals(listOf(true), metadata)
    }

    @Test
    fun removeMetadata_whenOn_saysWillBeRemoved() {
        show(loaded.copy(removeMetadata = true))
        val row = rule.onNode(hasText("Also remove document details (title, author, keywords)"))
        assertEquals("Will be removed", row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun compress_callsBack() {
        show(loaded)
        compressButton().performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("compress"), calls)
    }

    @Test
    fun success_announcesReducedFromXToYWithPercent_andOffersSaveAndShare() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val before = Formatter.formatShortFileSize(context, 2_000_000)
        val after = Formatter.formatShortFileSize(context, 500_000)
        show(loaded.copy(operation = result(2_000_000, 500_000)))
        rule.onNodeWithText("Reduced from $before to $after (75%)").assertExists().assertIsFocused()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun noSaving_saysSoClearly_insteadOfAZeroPercentReduction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val size = Formatter.formatShortFileSize(context, 40_000)
        show(loaded.copy(operation = result(40_000, 40_000)))
        rule.onNodeWithText("This PDF could not be made smaller. It stays at $size, and the saved copy is the same size.").assertExists()
        rule.onNode(hasText("Reduced from", substring = true)).assertDoesNotExist()
    }

    @Test
    fun unprotectedCopy_isSaidInTheResult() {
        show(loaded.copy(copyUnprotected = true, operation = result(2_000_000, 500_000)))
        rule.onNodeWithText("The saved copy has no password.", substring = true).assertExists()
    }

    @Test
    fun running_disablesControls_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        compressButton().assertIsNotEnabled()
        rule.onNodeWithText("Balanced").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun fileCard_exposesRemoveCustomAction_andButton() {
        show(loaded)
        val actions = rule.onNode(hasText("book.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        rule.onNode(hasContentDescription("Remove book.pdf")).performClick()
        assertEquals(listOf("remove", "remove"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(loaded.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun saving_disablesResultButtons() {
        show(loaded.copy(saving = true, operation = result(100, 50)))
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
    }
}
