package io.github.wzehirx.bpdf.feature.sign

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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.SignPosition
import io.github.wzehirx.bpdf.core.pdf.SignaturePoint
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SignScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val texts = mutableListOf<String>()
    private val pages = mutableListOf<String>()
    private val modes = mutableListOf<SignMode>()
    private val positions = mutableListOf<SignPosition>()
    private val dates = mutableListOf<Boolean>()

    private val loaded = SignUiState(file = ImportedFile(File("contract.pdf"), "contract.pdf", 1000), pageCount = 5, pageText = "5")

    private fun show(state: SignUiState) = rule.setContent {
        SignScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onModeChange = { modes += it },
            onTypedTextChange = { texts += it },
            onStroke = { calls += "stroke" },
            onClearDrawing = { calls += "clear" },
            onPageChange = { pages += it },
            onPositionChange = { positions += it },
            onAddDateChange = { dates += it },
            onSign = { calls += "sign" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun signButton() = rule.onNode(hasText("Add signature") and hasClickAction())

    private fun stateOf(node: androidx.compose.ui.test.SemanticsNodeInteraction) =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun instructions_sayItIsAVisualSignatureNotADigitalOne() {
        show(SignUiState())
        rule.onNode(hasText("visual signature", substring = true) and hasText("not a cryptographic (digital) signature", substring = true)).assertExists()
    }

    @Test
    fun withoutFile_buttonIsDisabledAndSaysWhy_andOptionsAreHidden() {
        show(SignUiState())
        signButton().assertIsNotEnabled()
        assertEquals("Choose a PDF first", stateOf(signButton()))
        rule.onNodeWithText("Page to sign").assertDoesNotExist()
    }

    @Test
    fun typedIsTheDefault_andTheTextFieldCallsBack() {
        show(loaded.copy(typedText = "Ali"))
        rule.onNodeWithText("Typed (recommended, works with a screen reader)").assertIsSelected()
        rule.onNode(hasText("Ali") and hasSetTextAction()).performTextReplacement("Ayşe Çağlı")
        assertEquals(listOf("Ayşe Çağlı"), texts)
    }

    @Test
    fun missingText_exposesErrorSemantics() {
        show(loaded.copy(fieldErrors = setOf(SignFieldError.TEXT_MISSING)))
        rule.onNodeWithText("Type the text of your signature.").assertExists()
        val node = rule.onNode(hasSetTextAction() and hasText("Your name or signature text")).fetchSemanticsNode()
        assertEquals("Type the text of your signature.", node.config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun modeChoice_isAnAnnouncedRadioGroup() {
        show(loaded)
        val drawn = rule.onNodeWithText("Drawn with your finger")
        assertEquals(Role.RadioButton, drawn.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        drawn.performScrollTo().performClick()
        assertEquals(listOf(SignMode.DRAWN), modes)
    }

    @Test
    fun drawnMode_padIsDescribedAsEmptyOrNotEmpty_andClearIsDisabledWhenEmpty() {
        show(loaded.copy(mode = SignMode.DRAWN))
        rule.onNode(hasContentDescription("Drawn signature, empty")).assertExists()
        rule.onNodeWithText("Clear drawing").performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Draw your signature in the box").assertExists()
    }

    @Test
    fun drawnMode_withInk_saysNotEmpty_andClearCallsBack() {
        show(loaded.copy(mode = SignMode.DRAWN, strokes = listOf(listOf(SignaturePoint(0.1f, 0.1f), SignaturePoint(0.8f, 0.5f)))))
        rule.onNode(hasContentDescription("Drawn signature, not empty")).assertExists()
        rule.onNodeWithText("Clear drawing").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun drawnMode_emptyAfterAttempt_showsError() {
        show(loaded.copy(mode = SignMode.DRAWN, fieldErrors = setOf(SignFieldError.DRAWING_EMPTY)))
        rule.onNodeWithText("Draw your signature first.").assertExists()
    }

    @Test
    fun pageField_hasHint_errorSemantics_andCallsBack() {
        show(loaded)
        rule.onNodeWithText("A page number from 1 to 5").assertExists()
        rule.onNode(hasText("5") and hasSetTextAction()).performTextReplacement("2")
        assertEquals(listOf("2"), pages)
    }

    @Test
    fun invalidPage_exposesError() {
        show(loaded.copy(pageText = "9", fieldErrors = setOf(SignFieldError.PAGE_INVALID)))
        val message = "Enter a page number from 1 to 5."
        rule.onNodeWithText(message).assertExists()
        assertEquals(message, rule.onNode(hasText("9") and hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test
    fun positionChoices_areNamedRadios_bottomRightSelected_andCallBack() {
        show(loaded)
        rule.onNodeWithText("Bottom right").assertIsSelected()
        for (label in listOf("Top left", "Top right", "Center", "Bottom left", "Bottom center", "Bottom right")) {
            assertEquals(Role.RadioButton, rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithText("Top left").performScrollTo().performClick()
        assertEquals(listOf(SignPosition.TOP_LEFT), positions)
    }

    @Test
    fun dateSwitch_speaksItsState_andToggles() {
        show(loaded)
        val row = rule.onNode(hasText("Add today's date under the signature"))
        assertEquals("No date", stateOf(row))
        row.performScrollTo().performClick()
        assertEquals(listOf(true), dates)
    }

    @Test
    fun sign_callsBack() {
        show(loaded)
        signButton().performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("sign"), calls)
    }

    @Test
    fun success_showsPageAndNotDigitalNote_saveAndShare() {
        show(loaded.copy(operation = OperationState.Success(SignResult(File("o.pdf"), 5, "n.pdf"))))
        rule.onNodeWithText("Added a visual signature to page 5. It is not a digital signature.").assertExists().assertIsFocused()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun success_ofAnUnlockedInput_saysTheCopyHasNoPassword() {
        show(loaded.copy(copyUnprotected = true, operation = OperationState.Success(SignResult(File("o.pdf"), 1, "n.pdf"))))
        rule.onNode(hasText("password", substring = true, ignoreCase = true) and hasText("Added a visual signature", substring = true)).assertExists()
    }

    @Test
    fun running_disablesEveryControl_andCancelWorks() {
        show(loaded.copy(operation = OperationState.Running(0.4f)))
        signButton().assertIsNotEnabled()
        rule.onNodeWithText("Top left").assertIsNotEnabled()
        rule.onNodeWithText("Drawn with your finger").assertIsNotEnabled()
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
        val actions = rule.onNode(hasText("contract.pdf, 5 pages")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue(actions.any { it.label == "Remove file" })
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        assertEquals(listOf("remove"), calls)
    }
}
