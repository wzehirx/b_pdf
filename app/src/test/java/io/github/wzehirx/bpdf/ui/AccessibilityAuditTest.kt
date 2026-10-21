package io.github.wzehirx.bpdf.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.files.RecentFile
import io.github.wzehirx.bpdf.core.pdf.PageEdit
import io.github.wzehirx.bpdf.core.settings.AppSettings
import io.github.wzehirx.bpdf.feature.about.AboutScreenContent
import io.github.wzehirx.bpdf.feature.about.LicenseEntry
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
 * Generic audit of spec 5.1 and 5.4 over the screens: every interactive element (click, toggle,
 * select, text field) has an accessible name and a touch target of at least 48dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class AccessibilityAuditTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { BPdfTheme { content() } }

    private fun name(node: SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()).joinToString("").trim()

    private fun audit(screen: String) {
        rule.waitForIdle()
        val min = 48 * rule.density.density
        val interactive = rule.onAllNodes(hasClickAction() or isToggleable() or isSelectable() or hasSetTextAction())
            .fetchSemanticsNodes()
        assertTrue("$screen has no interactive nodes", interactive.isNotEmpty())
        assertTrue("$screen has no heading", rule.onAllNodes(isHeading()).fetchSemanticsNodes().isNotEmpty())
        val problems = interactive.mapNotNull { node ->
            val b = node.touchBoundsInRoot
            val label = name(node)
            when {
                label.isEmpty() && node.config.getOrNull(SemanticsProperties.EditableText) == null &&
                    node.config.getOrNull(SemanticsProperties.IsTraversalGroup) == null -> "unnamed element at ${node.positionInRoot}"
                b.height < min - 0.5f -> "'$label' is ${b.height / rule.density.density}dp high"
                b.width < min - 0.5f -> "'$label' is ${b.width / rule.density.density}dp wide"
                else -> null
            }
        }
        assertTrue("$screen: $problems", problems.isEmpty())
    }

    private fun file(name: String) = ImportedFile(File(name), name, 1000)

    @Test
    fun home() {
        show {
            HomeScreen(
                onOpenTool = {}, isAvailable = { true },
                recents = listOf(RecentFile("content://a/1", "annual report.pdf", 2)),
            )
        }
        audit("Home")
    }

    @Test
    fun settings() {
        show {
            SettingsScreenContent(
                SettingsUiState(AppSettings()), language = "", languageSupported = true, onBack = {}, onTheme = {},
                onHighContrast = {}, onLanguage = {}, onOpenSystemLanguageSettings = {}, onSaveBehavior = {},
                onClearCache = {}, onOpenAbout = {}, announce = {},
            )
        }
        audit("Settings")
    }

    @Test
    fun settingsWithoutPerAppLanguage() {
        show {
            SettingsScreenContent(
                SettingsUiState(AppSettings()), language = "", languageSupported = false, onBack = {}, onTheme = {},
                onHighContrast = {}, onLanguage = {}, onOpenSystemLanguageSettings = {}, onSaveBehavior = {},
                onClearCache = {}, onOpenAbout = {}, announce = {},
            )
        }
        audit("Settings (no per-app language)")
    }

    @Test
    fun about() {
        show {
            AboutScreenContent("1.0.0", 10, listOf(LicenseEntry("Lib", "Apache-2.0", "https://x.y")), onBack = {}, onOpenUrl = {})
        }
        audit("About")
    }

    @Test
    fun merge() {
        show {
            MergeScreenContent(
                MergeUiState(files = listOf(file("a.pdf"), file("b.pdf"))), onBack = {}, onAddFiles = {}, onMove = { _, _ -> },
                onRemove = {}, onMerge = {}, onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
            )
        }
        audit("Merge")
    }

    @Test
    fun organize() {
        show {
            OrganizeScreenContent(
                OrganizeUiState(file = file("book.pdf"), pageCount = 3, edits = listOf(PageEdit(0), PageEdit(1), PageEdit(2))),
                onBack = {}, onPickFile = {}, onRemoveFile = {}, onMoveUp = {}, onMoveDown = {}, onMoveTo = { _, _ -> },
                onRotateLeft = {}, onRotateRight = {}, onDuplicate = {}, onDelete = {}, onUndo = {}, onOrganize = {},
                onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
            )
        }
        audit("Organize")
    }

    @Test
    fun viewer() {
        show {
            ViewerScreenContent(
                ViewerUiState(file = file("book.pdf"), pageCount = 5, currentPage = 1, mode = ViewerMode.PAGE),
                onBack = {}, onPickFile = {}, onGoTo = {}, onSetMode = { _, _ -> }, onZoomIn = {}, onZoomOut = {}, onSetZoom = {},
                pageImage = { _, w -> Bitmap.createBitmap(w.coerceAtLeast(1), w.coerceAtLeast(1) * 2, Bitmap.Config.ARGB_8888) },
                pageText = { "text" }, openWithTools = emptyList(), onOpenWithTool = {}, announce = {},
            )
        }
        audit("Viewer")
    }
}
