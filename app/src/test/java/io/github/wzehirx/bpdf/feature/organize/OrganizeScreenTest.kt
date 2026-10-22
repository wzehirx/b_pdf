package io.github.wzehirx.bpdf.feature.organize

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PageEdit
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OrganizeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val threePages = OrganizeUiState(
        file = ImportedFile(File("book.pdf"), "book.pdf", 1000),
        pageCount = 3,
        edits = listOf(PageEdit(0), PageEdit(1), PageEdit(2)),
    )

    private fun show(state: OrganizeUiState) = rule.setContent {
        OrganizeScreenContent(
            state = state,
            onBack = {},
            onPickFile = {},
            onRemoveFile = { calls += "remove" },
            onMoveUp = { calls += "up$it" },
            onMoveDown = { calls += "down$it" },
            onMoveTo = { from, to -> calls += "moveTo$from->$to" },
            onRotateLeft = { calls += "left$it" },
            onRotateRight = { calls += "right$it" },
            onDuplicate = { calls += "dup$it" },
            onDelete = { calls += "del$it" },
            onUndo = { calls += "undo" },
            onOrganize = { calls += "organize" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun cell(label: String) = rule.onNodeWithContentDescription(label)

    private fun SemanticsNodeInteraction.actions() =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun SemanticsNodeInteraction.invoke(label: String) {
        val action = actions().first { it.label == label }
        rule.runOnUiThread { action.action() }
    }

    private val allLabels = listOf(
        "Move up", "Move down", "Move to position…", "Rotate left", "Rotate right", "Duplicate", "Delete",
    )

    @Test
    fun withoutFile_buttonIsDisabledAndSaysWhy_andThereIsNoGrid() {
        show(OrganizeUiState())
        val button = rule.onNode(hasText("Create organized PDF") and hasClickAction())
        button.assertIsNotEnabled()
        assertEquals("Choose a PDF first", button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithContentDescription("Position 1 of 3, original page 1").assertDoesNotExist()
        rule.onNodeWithText("Undo").assertDoesNotExist()
    }

    @Test
    fun pageCell_hasLabel_andExposesEveryAction() {
        show(threePages)
        assertEquals(allLabels, cell("Position 2 of 3, original page 2").actions().map { it.label })
        // The first page cannot move up and the last cannot move down.
        assertEquals(allLabels - "Move up", cell("Position 1 of 3, original page 1").actions().map { it.label })
        assertEquals(allLabels - "Move down", cell("Position 3 of 3, original page 3").actions().map { it.label })
    }

    @Test
    fun customActions_callBackWithThePageIndex() {
        show(threePages)
        val page = cell("Position 2 of 3, original page 2")
        for (label in listOf("Move up", "Move down", "Rotate left", "Rotate right", "Duplicate", "Delete")) page.invoke(label)
        assertEquals(listOf("up1", "down1", "left1", "right1", "dup1", "del1"), calls)
    }

    @Test
    fun rotatedPage_isInTheLabel() {
        show(threePages.copy(edits = listOf(PageEdit(0), PageEdit(1, 90), PageEdit(2, 270))))
        cell("Position 2 of 3, original page 2, rotated 90°").assertExists()
        cell("Position 3 of 3, original page 3, rotated 270°").assertExists()
    }

    @Test
    fun overflowMenu_offersTheSameLabelledActions() {
        show(threePages)
        rule.onNode(hasContentDescription("Actions for page 2")).performScrollTo().performClick()
        for (label in allLabels) rule.onNodeWithText(label).assertExists()
        rule.onNodeWithText("Rotate right").performClick()
        assertEquals(listOf("right1"), calls)
        rule.onNodeWithText("Rotate left").assertDoesNotExist() // the menu closed
    }

    @Test
    fun overflowMenu_hasNoUnavailableMoves() {
        show(threePages)
        rule.onNode(hasContentDescription("Actions for page 1")).performScrollTo().performClick()
        rule.onNodeWithText("Move up").assertDoesNotExist()
        rule.onNodeWithText("Move down").assertExists()
    }

    @Test
    fun overflowButton_isAtLeast48dpAndLabelled() {
        show(threePages)
        // The visible button is 40dp; Material extends its touch target to 48dp.
        val bounds = rule.onNode(hasContentDescription("Actions for page 1")).fetchSemanticsNode().touchBoundsInRoot
        val min = 48 * ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertTrue("${bounds.width}x${bounds.height} < $min", bounds.width >= min && bounds.height >= min)
    }

    @Test
    fun moveToPosition_opensDialog_validatesTheNumber_andMoves() {
        show(threePages)
        cell("Position 1 of 3, original page 1").invoke("Move to position…")
        rule.onNodeWithText("Move page 1").assertExists()
        val field = rule.onNode(hasSetTextAction())
        field.performTextClearance()
        rule.onNodeWithText("Move").performClick()
        rule.onNodeWithText("Enter the position you want, from 1 to 3.").assertExists()
        field.performTextInput("9")
        rule.onNodeWithText("\"9\" is not a position. Enter a number from 1 to 3.").assertExists()
        rule.onNodeWithText("Move").performClick()
        field.performTextClearance()
        field.performTextInput("0")
        rule.onNodeWithText("Move").performClick()
        assertTrue(calls.isEmpty())
        field.performTextClearance()
        field.performTextInput("3")
        rule.onNodeWithText("Move").performClick()
        assertEquals(listOf("moveTo0->2"), calls)
        rule.onNodeWithText("Move page 1").assertDoesNotExist()
    }

    @Test
    fun moveToPosition_error_isExposedAsFieldError_andCancelClosesWithoutMoving() {
        show(threePages)
        cell("Position 2 of 3, original page 2").invoke("Move to position…")
        val field = rule.onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("x")
        val message = "\"x\" is not a position. Enter a number from 1 to 3."
        rule.onNodeWithText(message).assertExists()
        assertEquals(message, field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Move page 2").assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun moveToPosition_startsAtTheCurrentPosition_withAHint() {
        show(threePages)
        cell("Position 2 of 3, original page 2").invoke("Move to position…")
        rule.onNodeWithText("Enter a number from 1 to 3.").assertExists()
        rule.onNode(hasSetTextAction() and hasText("2")).assertExists()
    }

    @Test
    fun undo_isDisabledUntilThereIsSomethingToUndo() {
        show(threePages)
        rule.onNode(hasText("Undo") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun undo_whenAvailable_callsBack() {
        show(threePages.copy(undoDepth = 2))
        rule.onNode(hasText("Undo") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("undo"), calls)
    }

    @Test
    fun summary_saysHowManyPagesTheNewPdfHas() {
        show(threePages.copy(edits = listOf(PageEdit(0), PageEdit(0))))
        rule.onNodeWithText("The new PDF will have 2 pages").assertExists()
    }

    @Test
    fun organize_callsBack() {
        show(threePages)
        rule.onNode(hasText("Create organized PDF") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("organize"), calls)
    }

    @Test
    fun running_removesActions_andDisablesControls() {
        show(threePages.copy(undoDepth = 1, operation = OperationState.Running(0.3f)))
        assertTrue(cell("Position 2 of 3, original page 2").actions().isEmpty())
        rule.onNode(hasContentDescription("Actions for page 2")).assertDoesNotExist()
        rule.onNode(hasText("Undo") and hasClickAction()).assertIsNotEnabled()
        rule.onNode(hasText("Create organized PDF") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun success_showsCount_saveAndShare() {
        show(threePages.copy(operation = OperationState.Success(OrganizeResult(File("o.pdf"), 3, "n.pdf"))))
        rule.onNodeWithText("Created a PDF with 3 pages").assertExists()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun failed_showsFocusedErrorAndRetry() {
        show(threePages.copy(operation = OperationState.Failed(PdfError.Corrupt(null))))
        rule.onNodeWithText("This PDF is damaged or cannot be read.").assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun fileCard_exposesRemoveAction() {
        show(threePages)
        val actions = rule.onNode(hasText("book.pdf, 3 pages")).actions()
        rule.runOnUiThread { actions.first { it.label == "Remove file" }.action() }
        assertEquals(listOf("remove"), calls)
    }

    @Test
    fun cellsSayWhichOriginalPageTheyHold_afterMovesAndCopies() {
        // Original page 4 now sits first, and page 2 appears twice.
        show(
            OrganizeUiState(
                file = threePages.file, pageCount = 4,
                edits = listOf(PageEdit(3), PageEdit(1), PageEdit(1, 90), PageEdit(0)),
            ),
        )
        cell("Position 1 of 4, original page 4").assertExists()
        cell("Position 2 of 4, original page 2").assertExists()
        cell("Position 3 of 4, copy of original page 2, rotated 90°").assertExists()
        cell("Position 4 of 4, original page 1").assertExists()
    }

    @Test
    fun focusIndex_movesFocusToThatCell_thenReportsHandled() {
        val handled = mutableListOf<String>()
        var focus by androidx.compose.runtime.mutableStateOf<Int?>(null)
        rule.setContent {
            OrganizeScreenContent(
                state = threePages.copy(focusIndex = focus),
                onBack = {}, onPickFile = {}, onRemoveFile = {}, onMoveUp = {}, onMoveDown = {}, onMoveTo = { _, _ -> },
                onRotateLeft = {}, onRotateRight = {}, onDuplicate = {}, onDelete = {}, onUndo = {}, onOrganize = {},
                onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
                onFocusHandled = { handled += "handled"; focus = null },
            )
        }
        rule.runOnIdle { focus = 1 }
        rule.waitForIdle()
        rule.waitUntil(5_000) { handled.isNotEmpty() }
        cell("Position 2 of 3, original page 2").assertIsFocused()
        // a second action on the same slot is possible: the cell still offers its actions
        assertTrue(cell("Position 2 of 3, original page 2").actions().isNotEmpty())
    }

    @Test
    fun announcements_areSpecificAndLocalized() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertEquals("Page 3 moved to position 1", OrganizeEvent.Moved(3, 1).message(resources))
        assertEquals("Page 2 rotated right", OrganizeEvent.RotatedRight(2).message(resources))
        assertEquals("Page 2 rotated left", OrganizeEvent.RotatedLeft(2).message(resources))
        assertEquals("Page 2 duplicated", OrganizeEvent.Duplicated(2).message(resources))
        assertEquals("Page 4 deleted. Undo available", OrganizeEvent.Deleted(4).message(resources))
        assertEquals("Last change undone", OrganizeEvent.Undone.message(resources))
        assertEquals(
            "The last page cannot be deleted. A PDF needs at least one page.",
            OrganizeEvent.LastPageBlocked.message(resources),
        )
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkish_labelsActionsAndAnnouncements() {
        show(threePages)
        assertEquals(
            listOf("Yukarı taşı", "Aşağı taşı", "Konuma taşı…", "Sola döndür", "Sağa döndür", "Çoğalt", "Sil"),
            cell("Konum 2, toplam 3, özgün sayfa 2").actions().map { it.label },
        )
        rule.onNodeWithText("Geri al").assertExists()
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        assertEquals("4. sayfa silindi. Geri alma kullanılabilir", OrganizeEvent.Deleted(4).message(resources))
    }
}
