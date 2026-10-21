package io.github.wzehirx.bpdf.feature.images

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.Orientation
import io.github.wzehirx.bpdf.core.pdf.PageSizeOption
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.work.OperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ImagesToPdfScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val moves = mutableListOf<Pair<Int, Int>>()
    private val removes = mutableListOf<Int>()
    private val sizes = mutableListOf<PageSizeOption>()
    private val orientations = mutableListOf<Orientation>()
    private val margins = mutableListOf<MarginChoice>()

    private fun picture(name: String) = ImportedFile(File(name), name, 1000)

    private val three = listOf(picture("a.jpg"), picture("b.jpg"), picture("c.jpg"))

    private fun actions(name: String) = rule.onNode(hasText(name, substring = true)).fetchSemanticsNode()
        .config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun invoke(name: String, label: String) {
        val action = actions(name).first { it.label == label }
        rule.runOnUiThread { action.action() }
    }

    private fun show(state: ImagesToPdfUiState) = rule.setContent {
        ImagesToPdfScreenContent(
            state = state,
            onBack = {},
            onPickFromGallery = { calls += "gallery" },
            onTakePhoto = { calls += "camera" },
            onMove = { f, t -> moves += f to t },
            onRemove = { removes += it },
            onPageSizeChange = { sizes += it },
            onOrientationChange = { orientations += it },
            onMarginChange = { margins += it },
            onConvert = { calls += "convert" },
            onCancel = { calls += "cancel" },
            onSave = { calls += "save" },
            onShare = { calls += "share" },
            onRetry = { calls += "retry" },
        )
    }

    private fun stateDescription(text: String) = rule.onNodeWithText(text).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun galleryAndCameraButtons_callBack() {
        show(ImagesToPdfUiState())
        rule.onNodeWithText("Choose pictures").performClick()
        rule.onNodeWithText("Take photo").performClick()
        assertEquals(listOf("gallery", "camera"), calls)
    }

    @Test
    fun imageRows_exposeAllMoveAndRemoveCustomActions() {
        show(ImagesToPdfUiState(images = three))
        val labels = actions("b.jpg").map { it.label }
        assertTrue(labels.toString(), listOf("Move up", "Move down", "Move to top", "Move to bottom", "Remove").all { it in labels })
        invoke("b.jpg", "Move up")
        invoke("b.jpg", "Move down")
        invoke("b.jpg", "Move to top")
        invoke("b.jpg", "Move to bottom")
        invoke("b.jpg", "Remove")
        assertEquals(listOf(1 to 0, 1 to 2, 1 to 0, 1 to 2), moves)
        assertEquals(listOf(1), removes)
    }

    @Test
    fun edgeRows_omitImpossibleActions() {
        show(ImagesToPdfUiState(images = three))
        assertTrue(actions("a.jpg").none { it.label == "Move up" || it.label == "Move to top" })
        assertTrue(actions("c.jpg").none { it.label == "Move down" || it.label == "Move to bottom" })
    }

    @Test
    fun createButton_withoutImages_isDisabledAndSaysWhy() {
        show(ImagesToPdfUiState())
        rule.onNodeWithText("Create PDF").assertIsNotEnabled()
        assertEquals("Add at least 1 picture", stateDescription("Create PDF"))
    }

    @Test
    fun createButton_withImages_isEnabledAndCallsBack() {
        show(ImagesToPdfUiState(images = three))
        rule.onNodeWithText("Create PDF").performScrollTo().assertIsEnabled().performClick()
        assertNull(stateDescription("Create PDF"))
        assertEquals(listOf("convert"), calls)
    }

    @Test
    fun options_areSelectableAndOrientationHiddenForFitToPicture() {
        show(ImagesToPdfUiState(images = three))
        rule.onNodeWithText("US Letter").performScrollTo().performClick()
        rule.onNodeWithText("Landscape").performScrollTo().performClick()
        rule.onNodeWithText("Large").performScrollTo().performClick()
        assertEquals(listOf(PageSizeOption.LETTER), sizes)
        assertEquals(listOf(Orientation.LANDSCAPE), orientations)
        assertEquals(listOf(MarginChoice.LARGE), margins)
    }

    @Test
    fun fitToPicture_hidesOrientation() {
        show(ImagesToPdfUiState(images = three, pageSize = PageSizeOption.FIT_IMAGE))
        rule.onNodeWithText("Page orientation").assertDoesNotExist()
    }

    @Test
    fun success_showsSummaryAndSaveShare() {
        show(
            ImagesToPdfUiState(
                images = three,
                operation = OperationState.Success(ImagesToPdfResult(File("o.pdf"), 3, "a_images.pdf")),
            ),
        )
        rule.onNodeWithText("Created a PDF with 3 pages").assertExists()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(listOf("save", "share"), calls)
    }

    @Test
    fun failed_showsFocusedUnreadableImageErrorAndRetry() {
        show(
            ImagesToPdfUiState(images = three, operation = OperationState.Failed(PdfError.UnreadableImage("b.jpg"))),
        )
        rule.onNode(hasText("\"b.jpg\" is not a picture", substring = true)).assertIsFocused()
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun running_disablesControls_andCancelWorks() {
        show(ImagesToPdfUiState(images = three, operation = OperationState.Running(0.5f)))
        rule.onNodeWithText("Create PDF").assertIsNotEnabled()
        rule.onNodeWithText("Choose pictures").assertIsNotEnabled()
        rule.onNodeWithText("Take photo").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun busy_disablesRowControls_andHidesActions() {
        show(ImagesToPdfUiState(images = three, importing = true))
        rule.onNodeWithContentDescription("Remove b.jpg").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move b.jpg down").assertIsNotEnabled()
        assertTrue(actions("b.jpg").isEmpty())
    }
}
