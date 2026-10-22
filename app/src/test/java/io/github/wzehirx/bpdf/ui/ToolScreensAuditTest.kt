package io.github.wzehirx.bpdf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import io.github.wzehirx.bpdf.feature.compress.CompressScreenContent
import io.github.wzehirx.bpdf.feature.compress.CompressUiState
import io.github.wzehirx.bpdf.feature.images.ImagesToPdfScreenContent
import io.github.wzehirx.bpdf.feature.images.ImagesToPdfUiState
import io.github.wzehirx.bpdf.feature.metadata.MetadataScreenContent
import io.github.wzehirx.bpdf.feature.metadata.MetadataUiState
import io.github.wzehirx.bpdf.feature.ocr.OcrScreenContent
import io.github.wzehirx.bpdf.feature.ocr.OcrUiState
import io.github.wzehirx.bpdf.feature.pagenumbers.PageNumbersScreenContent
import io.github.wzehirx.bpdf.feature.pagenumbers.PageNumbersUiState
import io.github.wzehirx.bpdf.feature.pages.PageSelectScreenContent
import io.github.wzehirx.bpdf.feature.pages.PageSelectUiState
import io.github.wzehirx.bpdf.feature.pdfimages.PdfToImagesScreenContent
import io.github.wzehirx.bpdf.feature.pdfimages.PdfToImagesUiState
import io.github.wzehirx.bpdf.feature.pdftext.PdfToTextScreenContent
import io.github.wzehirx.bpdf.feature.pdftext.PdfToTextUiState
import io.github.wzehirx.bpdf.feature.protect.ProtectScreenContent
import io.github.wzehirx.bpdf.feature.protect.ProtectUiState
import io.github.wzehirx.bpdf.feature.repair.RepairScreenContent
import io.github.wzehirx.bpdf.feature.repair.RepairUiState
import io.github.wzehirx.bpdf.feature.rotate.RotateScreenContent
import io.github.wzehirx.bpdf.feature.rotate.RotateUiState
import io.github.wzehirx.bpdf.feature.sign.SignScreenContent
import io.github.wzehirx.bpdf.feature.sign.SignUiState
import io.github.wzehirx.bpdf.feature.split.SplitScreenContent
import io.github.wzehirx.bpdf.feature.split.SplitUiState
import io.github.wzehirx.bpdf.feature.unlock.UnlockScreenContent
import io.github.wzehirx.bpdf.feature.unlock.UnlockUiState
import io.github.wzehirx.bpdf.feature.watermark.WatermarkScreenContent
import io.github.wzehirx.bpdf.feature.watermark.WatermarkUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Spec 5.1 and 5.4 over the single-file and conversion tools (loaded-file state): every interactive element is named and at least 48dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class ToolScreensAuditTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { BPdfTheme { content() } }

    private fun file(name: String) = ImportedFile(File(name), name, 1000)

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
                label.isEmpty() && node.config.getOrNull(SemanticsProperties.EditableText) == null -> "unnamed element at ${node.positionInRoot}"
                b.height < min - 0.5f -> "'$label' is ${b.height / rule.density.density}dp high"
                b.width < min - 0.5f -> "'$label' is ${b.width / rule.density.density}dp wide"
                else -> null
            }
        }
        assertTrue("$screen: $problems", problems.isEmpty())
    }

    @Test
    fun split() {
        show {
            SplitScreenContent(
                state = SplitUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onModeChange = {},
                onRangeChange = {},
                onEveryNChange = {},
                onSplit = {},
                onCancel = {},
                onSaveToFolder = {},
                onSaveZip = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Split")
    }

    @Test
    fun pages() {
        show {
            PageSelectScreenContent(
                state = PageSelectUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onRangeChange = {},
                onRun = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("PageSelect")
    }

    @Test
    fun rotate() {
        show {
            RotateScreenContent(
                state = RotateUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onAngleChange = {},
                onScopeChange = {},
                onRangeChange = {},
                onRotate = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Rotate")
    }

    @Test
    fun images() {
        show {
            ImagesToPdfScreenContent(
                state = ImagesToPdfUiState(images = listOf(file("a.jpg"), file("b.jpg"))),
                onBack = {},
                onPickFromGallery = {},
                onTakePhoto = {},
                onMove = { _, _ -> },
                onRemove = {},
                onPageSizeChange = {},
                onOrientationChange = {},
                onMarginChange = {},
                onConvert = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("ImagesToPdf")
    }

    @Test
    fun pdfimages() {
        show {
            PdfToImagesScreenContent(
                state = PdfToImagesUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onScopeChange = {},
                onRangeChange = {},
                onDpiChange = {},
                onFormatChange = {},
                onConvert = {},
                onCancel = {},
                onSaveToFolder = {},
                onSaveZip = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("PdfToImages")
    }

    @Test
    fun pdftext() {
        show {
            PdfToTextScreenContent(
                state = PdfToTextUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onScopeChange = {},
                onRangeChange = {},
                onExtract = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onCopyAll = {},
                onRetry = {},
                ocrAvailable = true,
                onOpenOcr = {},
            )
        }
        audit("PdfToText")
    }

    @Test
    fun ocr() {
        show {
            OcrScreenContent(
                state = OcrUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onLanguageChange = {},
                onScopeChange = {},
                onRangeChange = {},
                onRecognize = {},
                onCancel = {},
                onSaveText = {},
                onShareText = {},
                onCopyAll = {},
                onSaveSearchablePdf = {},
                onCancelBuild = {},
                onRetry = {},
            )
        }
        audit("Ocr")
    }

    @Test
    fun protect() {
        show {
            ProtectScreenContent(
                state = ProtectUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onUserPasswordChange = {},
                onConfirmPasswordChange = {},
                onOwnerPasswordChange = {},
                onPermissionsChange = {},
                onProtect = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Protect")
    }

    @Test
    fun unlock() {
        show {
            UnlockScreenContent(
                state = UnlockUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onUnlock = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Unlock")
    }

    @Test
    fun sign() {
        show {
            SignScreenContent(
                state = SignUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onModeChange = {},
                onTypedTextChange = {},
                onStroke = {},
                onClearDrawing = {},
                onPageChange = {},
                onPositionChange = {},
                onAddDateChange = {},
                onSign = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Sign")
    }

    @Test
    fun compress() {
        show {
            CompressScreenContent(
                state = CompressUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onLevelChange = {},
                onRemoveMetadataChange = {},
                onCompress = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Compress")
    }

    @Test
    fun watermark() {
        show {
            WatermarkScreenContent(
                state = WatermarkUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onTextChange = {},
                onFontSizeChange = {},
                onOpacityChange = {},
                onRotationChange = {},
                onPositionChange = {},
                onColorChange = {},
                onScopeChange = {},
                onRangeChange = {},
                onApply = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Watermark")
    }

    @Test
    fun pagenumbers() {
        show {
            PageNumbersScreenContent(
                state = PageNumbersUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onPositionChange = {},
                onFormatChange = {},
                onCustomFormatChange = {},
                onStartChange = {},
                onFontSizeChange = {},
                onScopeChange = {},
                onRangeChange = {},
                onApply = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("PageNumbers")
    }

    @Test
    fun metadata() {
        show {
            MetadataScreenContent(
                state = MetadataUiState(file = file("a.pdf")),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onTitleChange = {},
                onAuthorChange = {},
                onSubjectChange = {},
                onKeywordsChange = {},
                onCreatorChange = {},
                onWrite = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Metadata")
    }

    @Test
    fun repair() {
        show {
            RepairScreenContent(
                state = RepairUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {},
                onPickFile = {},
                onRemoveFile = {},
                onRepair = {},
                onCancel = {},
                onSave = {},
                onShare = {},
                onRetry = {},
            )
        }
        audit("Repair")
    }

    @Test
    fun documentToPdf() {
        show {
            io.github.wzehirx.bpdf.feature.convert.DocumentToPdfScreenContent(
                state = io.github.wzehirx.bpdf.feature.convert.DocumentToPdfUiState(
                    file = file("rapor.docx"), kind = io.github.wzehirx.bpdf.core.pdf.DocumentKind.DOCX,
                ),
                onBack = {}, onPickFile = {}, onRemoveFile = {}, onConvert = {}, onCancel = {},
                onSave = {}, onShare = {}, onRetry = {},
            )
        }
        audit("Word, Excel and text to PDF")
    }

    @Test
    fun pdfToDocument() {
        show {
            io.github.wzehirx.bpdf.feature.convert.PdfToDocumentScreenContent(
                state = io.github.wzehirx.bpdf.feature.convert.PdfToDocumentUiState(file = file("a.pdf"), pageCount = 5),
                onBack = {}, onPickFile = {}, onRemoveFile = {}, onFormatChange = {}, onLayoutChange = {},
                onConvert = {}, onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
            )
        }
        audit("PDF to Word, Excel and HTML")
    }
}
