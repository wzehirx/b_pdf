package io.github.wzehirx.bpdf.feature.unlock

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
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UnlockScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val loaded = UnlockUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private fun show(state: UnlockUiState) = rule.setContent {
        UnlockScreenContent(
            state = state, onBack = {}, onPickFile = {}, onRemoveFile = { calls += "remove" },
            onUnlock = { calls += "unlock" }, onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
        )
    }

    // The tool title has the same words as the button, so match the button only.
    private val button = hasText("Remove password") and hasClickAction()

    private fun description() = rule.onNode(button).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_buttonDisabledWithReason() {
        show(UnlockUiState())
        rule.onNode(button).assertIsNotEnabled()
        assertEquals("Choose a PDF first", description())
    }

    @Test
    fun protectedFile_unlockCallsBack() {
        show(loaded)
        rule.onNode(button).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("unlock"), calls)
    }

    @Test
    fun unprotectedFile_explainsAndDisablesButton() {
        show(loaded.copy(notProtected = true))
        val message = "This PDF has no password and no restrictions, so there is nothing to remove."
        rule.onNodeWithText(message).assertExists()
        rule.onNode(button).assertIsNotEnabled()
        assertEquals(message, description())
    }

    @Test
    fun success_showsResult() {
        show(loaded.copy(operation = OperationState.Success(UnlockResult(File("x.pdf"), 5, "book_unlocked.pdf"))))
        rule.onNode(hasText("Password removed from a PDF with 5 pages")).assertExists()
    }
}
