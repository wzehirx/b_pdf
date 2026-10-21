package io.github.wzehirx.bpdf.feature.protect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfPermissions
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ProtectScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val permissions = mutableListOf<PdfPermissions>()
    private val typed = mutableListOf<String>()
    private val loaded = ProtectUiState(file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = 5)

    private var current by mutableStateOf(ProtectUiState())

    private fun show(state: ProtectUiState) {
        current = state
        rule.setContent { Content() }
    }

    @Composable
    private fun Content() {
        ProtectScreenContent(
            state = current,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onUserPasswordChange = { typed += "user:$it" },
            onConfirmPasswordChange = { typed += "confirm:$it" },
            onOwnerPasswordChange = { typed += "owner:$it" },
            onPermissionsChange = { permissions += it },
            onProtect = { calls += "protect" },
            onCancel = {},
            onSave = {},
            onShare = {},
            onRetry = {},
        )
    }

    private fun stateOf(text: String) = rule.onNodeWithText(text).performScrollTo().fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun withoutFile_protectIsDisabledWithReason() {
        show(ProtectUiState())
        rule.onNodeWithText("Protect").assertIsNotEnabled()
        assertEquals("Choose a PDF first", rule.onNodeWithText("Protect").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun withFile_protectCallsBack() {
        show(loaded)
        rule.onNodeWithText("Protect").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("protect"), calls)
    }

    @Test
    fun passwordFields_areLabelled_andReportTyping() {
        show(loaded)
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("abc")
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("abd")
        rule.onAllNodes(hasSetTextAction())[2].performTextReplacement("own")
        assertEquals(listOf("user:abc", "confirm:abd", "owner:own"), typed.filterNot { it.endsWith(":") })
        rule.onNodeWithText("Confirm password").assertExists()
        rule.onNodeWithText("Owner password (optional)").assertExists()
    }

    @Test
    fun permissionSwitches_haveStateDescriptionAndToggle() {
        show(loaded.copy(permissions = PdfPermissions(print = true, copy = false, modify = true)))
        assertEquals("Allowed", stateOf("Printing"))
        assertEquals("Not allowed", stateOf("Copying text and pictures"))
        rule.onNodeWithText("Copying text and pictures").performScrollTo().performClick()
        rule.onNodeWithText("Printing").performScrollTo().performClick()
        assertEquals(
            listOf(PdfPermissions(print = true, copy = true, modify = true), PdfPermissions(print = false, copy = false, modify = true)),
            permissions,
        )
    }

    @Test
    fun showPasswordsSwitch_hasStateDescription() {
        show(loaded)
        assertEquals("Hidden", stateOf("Show passwords"))
        rule.onNodeWithText("Show passwords").performScrollTo().performClick()
        assertEquals("Visible", stateOf("Show passwords"))
    }

    @Test
    fun mismatch_showsErrorOnConfirmField_andFocusesIt() {
        show(loaded)
        rule.runOnUiThread { current = loaded.copy(passwordError = ProtectError.MISMATCH, failedAttempts = 1) }
        rule.onNodeWithText("The two passwords do not match. Type the same password in both fields.").assertExists()
        rule.onAllNodes(hasSetTextAction())[1].assertIsFocused()
    }

    @Test
    fun emptyPassword_showsErrorOnFirstField_andFocusesIt() {
        show(loaded)
        rule.runOnUiThread { current = loaded.copy(passwordError = ProtectError.EMPTY, failedAttempts = 1) }
        rule.onNodeWithText("Enter a password.").assertExists()
        rule.onAllNodes(hasSetTextAction())[0].assertIsFocused()
    }

    @Test
    fun busy_disablesEverything() {
        show(loaded.copy(operation = OperationState.Running(0.5f)))
        rule.onNodeWithText("Protect").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun success_showsResultText() {
        show(loaded.copy(operation = OperationState.Success(ProtectResult(File("x.pdf"), 5, "book_protected.pdf"))))
        rule.onNode(hasText("Protected a PDF with 5 pages", substring = true)).assertExists()
    }
}
