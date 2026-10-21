package io.github.wzehirx.bpdf.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PageEdit
import io.github.wzehirx.bpdf.core.settings.AppSettings
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.feature.merge.MergeResult
import io.github.wzehirx.bpdf.feature.merge.MergeScreenContent
import io.github.wzehirx.bpdf.feature.merge.MergeUiState
import io.github.wzehirx.bpdf.feature.organize.OrganizeScreenContent
import io.github.wzehirx.bpdf.feature.organize.OrganizeUiState
import io.github.wzehirx.bpdf.feature.settings.SettingsScreenContent
import io.github.wzehirx.bpdf.feature.settings.SettingsUiState
import io.github.wzehirx.bpdf.feature.viewer.ViewerMode
import io.github.wzehirx.bpdf.feature.viewer.ViewerScreenContent
import io.github.wzehirx.bpdf.feature.viewer.ViewerUiState
import io.github.wzehirx.bpdf.ui.home.HomeScreen
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Accessibility audit, spec 5.4: at 200% font scale on a small phone the content must scroll and
 * every key control must be reachable (scrollable into view and then displayed), with a 48dp target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class FontScale200Test {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
            BPdfTheme { content() }
        }
    }

    private fun reachable(text: String) {
        rule.onNode(hasText(text) and hasClickAction()).performScrollTo().assertIsDisplayed().assertTarget48dp()
    }

    private fun SemanticsNodeInteraction.assertTarget48dp(): SemanticsNodeInteraction {
        val b = fetchSemanticsNode().touchBoundsInRoot
        val min = 48 * rule.density.density
        assertTrue("${b.width}x${b.height}px is below 48dp", b.height >= min - 0.5f && b.width >= min - 0.5f)
        return this
    }

    private fun assertScrollable() {
        assertTrue("no scrollable container at 200% font", rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
    }

    private fun file(name: String) = ImportedFile(File(name), name, 1000)

    @Test
    fun home_scrollsToTheLastToolAndSettings() {
        show { HomeScreen(onOpenTool = {}, isAvailable = { true }, onOpenSettings = {}) }
        assertScrollable()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed().assertTarget48dp()
        val last = Tool.entries.last { it.category == ToolCategory.entries.last() }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // The tools are lazily composed: scroll the grid until the last card exists, then it must be on screen.
        val lastCard = androidx.compose.ui.test.hasContentDescription("${context.getString(last.title)}. ${context.getString(last.description)}")
        rule.onNode(hasScrollAction()).performScrollToNode(lastCard)
        rule.onNode(lastCard).assertIsDisplayed()
    }

    @Test
    fun merge_scrollsAndKeepsEveryButtonReachable() {
        val result = MergeResult(File("o.pdf"), pageCount = 12, fileCount = 3, suggestedName = "m.pdf")
        show {
            MergeScreenContent(
                state = MergeUiState(files = listOf(file("a.pdf"), file("b.pdf"), file("c.pdf")), operation = OperationState.Success(result)),
                onBack = {}, onAddFiles = {}, onMove = { _, _ -> }, onRemove = {}, onMerge = {}, onCancel = {},
                onSave = {}, onShare = {}, onRetry = {},
            )
        }
        assertScrollable()
        reachable("Merge")
        reachable("Save")
        reachable("Share")
    }

    @Test
    fun organize_scrollsAndKeepsKeyButtonsReachable() {
        show {
            OrganizeScreenContent(
                state = OrganizeUiState(
                    file = file("book.pdf"), pageCount = 3, edits = listOf(PageEdit(0), PageEdit(1), PageEdit(2)),
                ),
                onBack = {}, onPickFile = {}, onRemoveFile = {}, onMoveUp = {}, onMoveDown = {}, onMoveTo = { _, _ -> },
                onRotateLeft = {}, onRotateRight = {}, onDuplicate = {}, onDelete = {}, onUndo = {}, onOrganize = {},
                onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
            )
        }
        assertScrollable()
        reachable("Create organized PDF")
        rule.onNodeWithContentDescription("Actions for page 3").performScrollTo().assertIsDisplayed().assertTarget48dp()
    }

    @Test
    fun viewer_controlsStayReachable() {
        show {
            ViewerScreenContent(
                state = ViewerUiState(file = file("book.pdf"), pageCount = 5, currentPage = 1, mode = ViewerMode.PAGE),
                onBack = {}, onPickFile = {}, onGoTo = {}, onSetMode = { _, _ -> }, onZoomIn = {}, onZoomOut = {}, onSetZoom = {},
                pageImage = { _, w -> Bitmap.createBitmap(w.coerceAtLeast(1), w.coerceAtLeast(1) * 2, Bitmap.Config.ARGB_8888) },
                pageText = { "text" }, openWithTools = emptyList(), onOpenWithTool = {}, announce = {},
            )
        }
        assertScrollable()
        listOf("Previous page", "Next page", "Go to page…", "Switch to text mode", "Zoom in", "Zoom out").forEach { reachable(it) }
    }

    @Test
    fun settings_scrollsAndKeepsEveryControlReachable() {
        show {
            SettingsScreenContent(
                state = SettingsUiState(AppSettings()), language = "", languageSupported = true, onBack = {},
                onTheme = {}, onHighContrast = {}, onLanguage = {}, onOpenSystemLanguageSettings = {},
                onSaveBehavior = {}, onClearCache = {}, onOpenAbout = {}, announce = {},
            )
        }
        assertScrollable()
        reachable("Clear cache")
        reachable("About b PDF")
        rule.onNodeWithText("Share first").performScrollTo().assertIsDisplayed()
    }
}
