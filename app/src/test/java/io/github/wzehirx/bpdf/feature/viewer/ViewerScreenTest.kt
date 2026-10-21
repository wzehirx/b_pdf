package io.github.wzehirx.bpdf.feature.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.files.SourceUnavailable
import io.github.wzehirx.bpdf.core.work.PasswordPrompt
import io.github.wzehirx.bpdf.ui.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ViewerScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val spoken = mutableListOf<String>()
    private val modes = mutableListOf<Pair<ViewerMode, Int?>>()
    private val gotos = mutableListOf<Int>()
    private val tools = mutableListOf<Tool>()

    private fun loaded(pages: Int = 5, page: Int = 0, mode: ViewerMode = ViewerMode.PAGE, zoom: Float = 1f) = ViewerUiState(
        file = ImportedFile(File("book.pdf"), "book.pdf", 1000), pageCount = pages, currentPage = page, mode = mode, zoom = zoom,
    )

    /** Like the real screen: moves and mode switches update the state the content is drawn from. */
    private fun showStateful(initial: ViewerUiState, text: suspend (Int) -> String? = { "text of page ${it + 1}" }) =
        rule.setContent {
            var state by androidx.compose.runtime.remember { mutableStateOf(initial) }
            ViewerScreenContent(
                state = state,
                onBack = {},
                onPickFile = {},
                onGoTo = { gotos += it; state = state.copy(currentPage = it) },
                onSetMode = { m, p -> modes += m to p; state = state.copy(mode = m, currentPage = p ?: state.currentPage) },
                onZoomIn = {},
                onZoomOut = {},
                onSetZoom = {},
                pageImage = { _, w -> Bitmap.createBitmap(w.coerceAtLeast(1), w.coerceAtLeast(1) * 2, Bitmap.Config.ARGB_8888) },
                pageText = text,
                openWithTools = emptyList(),
                onOpenWithTool = {},
                announce = { spoken += it },
            )
        }

    private fun show(
        state: ViewerUiState,
        text: suspend (Int) -> String? = { "text of page ${it + 1}" },
    ) = rule.setContent { Content(state, text) }

    @androidx.compose.runtime.Composable
    private fun Content(state: ViewerUiState, text: suspend (Int) -> String? = { "text of page ${it + 1}" }) {
        ViewerScreenContent(
            state = state,
            onBack = { calls += "back" },
            onPickFile = { calls += "pick" },
            onGoTo = { gotos += it },
            onSetMode = { m, p -> modes += m to p },
            onZoomIn = { calls += "zoomIn" },
            onZoomOut = { calls += "zoomOut" },
            onSetZoom = {},
            pageImage = { _, w -> Bitmap.createBitmap(w.coerceAtLeast(1), w.coerceAtLeast(1) * 2, Bitmap.Config.ARGB_8888) },
            pageText = text,
            openWithTools = listOf(Tool.ROTATE, Tool.PDF_TO_TEXT),
            onOpenWithTool = { tools += it },
            announce = { spoken += it },
        )
    }

    private fun actionsOf(description: String) =
        rule.onNodeWithContentDescription(description).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun List<androidx.compose.ui.semantics.CustomAccessibilityAction>.run(label: String) =
        rule.runOnUiThread { first { it.label == label }.action() }

    private fun stateDescriptionOf(text: String) =
        rule.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun pageMode_pageImageIsLabelledPageNOfM() {
        show(loaded(page = 1))
        rule.onNodeWithContentDescription("Page 2 of 5").assertExists()
        assertEquals(
            androidx.compose.ui.semantics.Role.Image,
            rule.onNodeWithContentDescription("Page 2 of 5").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role),
        )
    }

    @Test
    fun pageMode_pagerOffersNextPreviousGoToAndTextModeActions() {
        show(loaded(page = 2))
        val actions = actionsOf("Page 3 of 5")
        assertEquals(listOf("Next page", "Previous page", "Go to page…", "Switch to text mode"), actions.map { it.label })
        actions.run("Next page")
        actions.run("Previous page")
        assertEquals(listOf(3, 1), gotos)
        actions.run("Switch to text mode")
        assertEquals(listOf(ViewerMode.TEXT to 2), modes)
    }

    @Test
    fun firstPageHasNoPrevious_lastPageHasNoNext() {
        show(loaded(pages = 3, page = 0))
        assertFalse(actionsOf("Page 1 of 3").any { it.label == "Previous page" })
        assertTrue(actionsOf("Page 1 of 3").any { it.label == "Next page" })
    }

    @Test
    fun lastPage_hasNoNextAction() {
        show(loaded(pages = 3, page = 2))
        assertFalse(actionsOf("Page 3 of 3").any { it.label == "Next page" })
        rule.onNode(hasText("Next page") and hasClickAction()).assertIsNotEnabled()
        rule.onNode(hasText("Previous page") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun goToPageAction_opensTheDialog_andAValidNumberMoves() {
        show(loaded(page = 0))
        actionsOf("Page 1 of 5").run("Go to page…")
        rule.onNodeWithText("Go to page").assertExists()
        rule.onNode(hasText("Go to page") and isHeading()).assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("4")
        rule.onNodeWithText("Go").performClick()
        assertEquals(listOf(3), gotos)
        rule.onNodeWithText("Go to page").assertDoesNotExist()
    }

    @Test
    fun goToPage_invalidNumber_showsSpecificErrorAndStaysOpen() {
        show(loaded(page = 0))
        rule.onNode(hasText("Go to page…") and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("9")
        rule.onNodeWithText("Go").performClick()
        val message = "\"9\" is not a page of this document. Enter a whole number from 1 to 5."
        rule.onNodeWithText(message).assertExists()
        assertEquals(message, rule.onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
        assertTrue(gotos.isEmpty())
    }

    @Test
    fun controlButtons_move_andSwitchMode() {
        show(loaded(page = 2))
        rule.onNode(hasText("Next page") and hasClickAction()).performClick()
        rule.onNode(hasText("Previous page") and hasClickAction()).performClick()
        assertEquals(listOf(3, 1), gotos)
        rule.onNode(hasText("Switch to text mode") and hasClickAction()).performClick()
        assertEquals(listOf(ViewerMode.TEXT to 2), modes)
    }

    @Test
    fun opening_announcesNameAndPageCount_once_andFocusesTheFirstPage() {
        showStateful(loaded(page = 0))
        rule.waitForIdle()
        assertEquals(listOf("Opened book.pdf, 5 pages"), spoken)
        rule.onNodeWithContentDescription("Page 1 of 5").assertIsFocused()
    }

    @Test
    fun opening_aOnePageDocument_usesTheSingularForm() {
        showStateful(loaded(pages = 1))
        rule.waitForIdle()
        assertEquals(listOf("Opened book.pdf, 1 page"), spoken)
    }

    @Test
    fun pageMode_nextAction_announcesOnce_andMovesFocusToTheNewPage() {
        showStateful(loaded(page = 2))
        rule.waitForIdle()
        spoken.clear()
        actionsOf("Page 3 of 5").run("Next page")
        rule.waitForIdle()
        assertEquals(listOf("Page 4 of 5"), spoken)
        rule.onNodeWithContentDescription("Page 4 of 5").assertIsFocused()
    }

    @Test
    fun pageMode_goToDialog_announcesOnce_andFocusesTheDestination() {
        showStateful(loaded(page = 0))
        rule.waitForIdle()
        spoken.clear()
        actionsOf("Page 1 of 5").run("Go to page…")
        rule.onNode(hasSetTextAction()).performTextReplacement("4")
        rule.onNodeWithText("Go").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Page 4 of 5"), spoken)
        rule.onNodeWithContentDescription("Page 4 of 5").assertIsFocused()
    }

    @Test
    fun pageMode_nextButton_announcesOnce_andKeepsFocusOnTheButton() {
        showStateful(loaded(page = 1))
        rule.waitForIdle()
        spoken.clear()
        rule.onNode(hasText("Next page") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Page 3 of 5"), spoken)
        rule.onNodeWithContentDescription("Page 3 of 5").assertIsNotFocused()
    }

    @Test
    @Config(qualifiers = "w400dp-h2000dp")
    fun textMode_nextAction_announcesOnce_andFocusesTheNextHeading() {
        showStateful(loaded(pages = 4, mode = ViewerMode.TEXT))
        rule.waitForIdle()
        spoken.clear()
        val actions = rule.onNode(isHeading() and hasText("Page 1")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        actions.run("Next page")
        rule.waitForIdle()
        assertEquals(listOf("Page 2 of 4"), spoken)
        rule.onNode(isHeading() and hasText("Page 2")).assertIsFocused()
    }

    @Test
    fun textMode_goToDialog_announcesAndFocusesTheHeading() {
        showStateful(loaded(pages = 4, mode = ViewerMode.TEXT))
        rule.waitForIdle()
        spoken.clear()
        rule.onNode(hasText("Go to page…") and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("3")
        rule.onNodeWithText("Go").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Page 3 of 4"), spoken)
        rule.onNode(isHeading() and hasText("Page 3")).assertIsFocused()
    }

    @Test
    fun swipingToAnotherPage_isAnnouncedOnce() {
        showStateful(loaded(page = 0))
        rule.waitForIdle()
        spoken.clear()
        rule.onNodeWithContentDescription("Page 1 of 5").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(listOf("Page 2 of 5"), spoken)
    }

    @Test
    fun externalStateChange_isNotAnnounced() {
        var state by mutableStateOf(loaded(page = 0))
        rule.setContent { Content(state) }
        rule.waitForIdle()
        spoken.clear()
        rule.runOnUiThread { state = state.copy(currentPage = 2) }
        rule.waitForIdle()
        assertTrue(spoken.toString(), spoken.isEmpty())
    }

    @Test
    fun textMode_all30HeadingsExistWithoutScrolling_andFarTextIsNotBuilt() {
        val asked = mutableListOf<Int>()
        show(loaded(pages = 30, mode = ViewerMode.TEXT), text = { asked += it; "body ${it + 1}" })
        rule.onAllNodes(isHeading() and hasText("Page ", substring = true)).assertCountEquals(30)
        rule.onNode(isHeading() and hasText("Page 30")).assertExists()
        rule.waitForIdle()
        assertTrue("asked for ${asked.size} pages", asked.size < 30 && asked.isNotEmpty())
        rule.onNodeWithText("body 30").assertDoesNotExist()
    }

    @Test
    @Config(fontScale = 2.0f)
    fun largeFont_pageModeStillShowsThePage_andControlsScroll() {
        show(loaded())
        rule.onNodeWithContentDescription("Page 1 of 5").assertIsDisplayed()
        val page = rule.onNodeWithContentDescription("Page 1 of 5").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("page ${page.height} of ${root.height}", page.height >= root.height * 0.3f)
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp")
    fun landscape_pageModeStillShowsThePage() {
        show(loaded())
        rule.onNodeWithContentDescription("Page 1 of 5").assertIsDisplayed()
        val page = rule.onNodeWithContentDescription("Page 1 of 5").fetchSemanticsNode().boundsInRoot
        assertTrue("page height ${page.height}", page.height > 100f)
    }

    @Test
    @Config(fontScale = 2.0f, qualifiers = "w400dp-h2000dp")
    fun largeFont_textMode_controlsAreInTheScrollingContent() {
        show(loaded(pages = 3, mode = ViewerMode.TEXT))
        rule.onNode(isHeading() and hasText("Page 1")).assertExists()
        rule.onNode(hasText("Next page") and hasClickAction()).assertExists()
    }

    @Test
    fun pageLabelIsNotALiveRegion_soItIsNeverSpokenTwice() {
        show(loaded(page = 1))
        val live = rule.onNodeWithContentDescription("Page 2 of 5").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(null, live)
        val indicator = rule.onNode(hasText("Page 2 of 5")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(null, indicator)
    }

    @Test
    fun zoomButtons_callBack_announceTheNewZoom_andRespectLimits() {
        show(loaded(zoom = 1f))
        rule.onNode(hasText("Zoom out") and hasClickAction()).assertIsNotEnabled()
        assertEquals("Minimum zoom", stateDescriptionOf("Zoom out"))
        rule.onNode(hasText("Zoom in") and hasClickAction()).performClick()
        assertEquals(listOf("zoomIn"), calls)
        assertEquals(listOf("Zoom 150%"), spoken.drop(1))
        rule.onNodeWithText("Zoom 100%").assertExists()
    }

    @Test
    fun maxZoom_disablesZoomIn() {
        show(loaded(zoom = 4f))
        rule.onNode(hasText("Zoom in") and hasClickAction()).assertIsNotEnabled()
        assertEquals("Maximum zoom", stateDescriptionOf("Zoom in"))
        rule.onNode(hasText("Zoom out") and hasClickAction()).performClick()
        assertEquals(listOf("zoomOut"), calls)
        assertEquals(listOf("Zoom 350%"), spoken.drop(1))
    }

    @Test
    @Config(qualifiers = "w400dp-h2000dp")
    fun textMode_hasOneHeadingPerPage() {
        show(loaded(pages = 4, mode = ViewerMode.TEXT))
        rule.onAllNodes(isHeading() and hasText("Page ", substring = true)).assertCountEquals(4)
        for (n in 1..4) rule.onNode(isHeading() and hasText("Page $n")).assertExists()
        rule.onNodeWithText("text of page 3").assertExists()
    }

    @Test
    @Config(qualifiers = "w400dp-h2000dp")
    fun textMode_blankAndUnreadablePages_sayWhy() {
        show(loaded(pages = 2, mode = ViewerMode.TEXT), text = { if (it == 0) "" else null })
        rule.onNodeWithText("No text on this page. It may be a picture of text.").assertExists()
        rule.onNodeWithText("The text of this page could not be read.").assertExists()
    }

    @Test
    @Config(qualifiers = "w400dp-h2000dp")
    fun textMode_headingsCarryTheNavigationActions_andNoZoomControls() {
        show(loaded(pages = 3, mode = ViewerMode.TEXT))
        val actions = rule.onNode(isHeading() and hasText("Page 2")).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Next page", "Previous page", "Go to page…", "Switch to page mode"), actions.map { it.label })
        actions.run("Next page")
        assertEquals(listOf(2), gotos)
        actions.run("Switch to page mode")
        assertEquals(listOf(ViewerMode.PAGE to 0), modes)
        rule.onNodeWithText("Zoom in").assertDoesNotExist()
    }

    @Test
    fun readPageText_isAButtonThatTogglesTheTextOfThePage() {
        show(loaded(page = 1))
        rule.onNodeWithText("text of page 2").assertDoesNotExist()
        rule.onNode(hasText("Read page text") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("text of page 2").assertExists()
        rule.onNode(hasText("Hide page text") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("text of page 2").assertDoesNotExist()
    }

    @Test
    fun openWithTool_menuListsTools_andPicksOne() {
        show(loaded())
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Open with tool…").performClick()
        rule.onNodeWithText("Rotate pages").assertExists()
        rule.onNodeWithText("PDF to text").assertExists()
        rule.onNodeWithText("Rotate pages").performClick()
        assertEquals(listOf(Tool.ROTATE), tools)
    }

    @Test
    fun menu_chooseAnotherPdf_isOffered() {
        show(loaded())
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Choose another PDF").assertExists()
    }

    @Test
    fun noFile_showsInstructionsAndChooseButton_withoutMenuOrControls() {
        show(ViewerUiState())
        rule.onNodeWithText("Choose a PDF to read it page by page. Text mode lists the text of every page under its own heading.").assertExists()
        rule.onNode(hasText("Choose PDF") and hasClickAction()).assertIsEnabled()
        rule.onNodeWithContentDescription("More options").assertDoesNotExist()
        rule.onNodeWithText("Next page").assertDoesNotExist()
    }

    @Test
    fun titleIsTheFileName_asAHeading() {
        show(loaded())
        rule.onNode(isHeading() and hasText("book.pdf")).assertExists()
    }

    @Test
    fun failedOpen_showsFocusedMessage_includingRecentsNote() {
        show(ViewerUiState(error = SourceUnavailable(RuntimeException()), removedFromRecents = true))
        rule.onNodeWithText(
            "This document could not be opened. It may have been moved or deleted, or this app no longer has access to it. " +
                "It was also removed from the recent files.",
        ).assertIsFocused()
    }

    @Test
    fun importing_isAnnouncedAsLiveText_andBlocksTheMenu() {
        show(loaded().copy(importing = true))
        val node = rule.onNodeWithText("Opening file…").fetchSemanticsNode()
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun passwordPrompt_isShown() {
        show(ViewerUiState(importing = true, passwordPrompt = PasswordPrompt("locked.pdf")))
        rule.onNodeWithText("Enter the password for \"locked.pdf\".").assertExists()
    }

    @Test
    fun everyInteractiveControlIsAtLeast48dpHigh() {
        show(loaded())
        val density = rule.density.density
        for (label in listOf("Next page", "Previous page", "Go to page…", "Switch to text mode", "Zoom in", "Zoom out")) {
            val h = rule.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().size.height / density
            assertTrue("$label is ${h}dp", h >= 47.5f)
        }
    }

    @Suppress("unused")
    private val unusedUri: Uri? = null
    @Suppress("unused")
    private val unusedDesc = hasContentDescription("")
}
