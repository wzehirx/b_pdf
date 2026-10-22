package io.github.wzehirx.bpdf.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
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
import java.io.File

/**
 * Every main screen of the app, built from hand-made state, for the screen audits and the screenshots.
 * [loaded] false is the initial state (no file chosen); true is the state with a file (or files) chosen.
 * Each entry is composed inside the caller's `BPdfTheme`.
 */
object ScreenFixtures {
    class Entry(val name: String, val content: @Composable () -> Unit)

    private fun file(name: String) = ImportedFile(File(name), name, 1000)

    fun all(loaded: Boolean): List<Entry> {
        val f: ImportedFile? = if (loaded) file("a.pdf") else null
        val pages = if (loaded) 5 else 0
        return listOf(
            Entry("Home") {
                HomeScreen(
                    onOpenTool = {}, isAvailable = { true },
                    recents = if (loaded) listOf(RecentFile("content://a/1", "annual report.pdf", 2)) else emptyList(),
                )
            },
            Entry("Home (coming soon)") { HomeScreen(onOpenTool = {}, isAvailable = { false }) },
            Entry("Settings") {
                SettingsScreenContent(
                    SettingsUiState(AppSettings()), language = "", languageSupported = !loaded, onBack = {}, onTheme = {},
                    onHighContrast = {}, onLanguage = {}, onOpenSystemLanguageSettings = {}, onSaveBehavior = {},
                    onClearCache = {}, onOpenAbout = {}, announce = {},
                )
            },
            Entry("About") {
                AboutScreenContent("1.0.1", 11, listOf(LicenseEntry("Lib", "Apache-2.0", "https://x.y")), onBack = {}, onOpenUrl = {})
            },
            Entry("Viewer") {
                ViewerScreenContent(
                    if (loaded) ViewerUiState(file = file("book.pdf"), pageCount = 5, currentPage = 1, mode = ViewerMode.PAGE) else ViewerUiState(),
                    onBack = {}, onPickFile = {}, onGoTo = {}, onSetMode = { _, _ -> }, onZoomIn = {}, onZoomOut = {}, onSetZoom = {},
                    pageImage = { _, w -> Bitmap.createBitmap(w.coerceAtLeast(1), w.coerceAtLeast(1) * 2, Bitmap.Config.ARGB_8888) },
                    pageText = { "text" }, openWithTools = emptyList(), onOpenWithTool = {}, announce = {},
                )
            },
            Entry("Merge") {
                MergeScreenContent(
                    MergeUiState(files = if (loaded) listOf(file("a.pdf"), file("b.pdf")) else emptyList()),
                    onBack = {}, onAddFiles = {}, onMove = { _, _ -> },
                    onRemove = {}, onMerge = {}, onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
                )
            },
            Entry("Organize") {
                OrganizeScreenContent(
                    if (loaded) {
                        OrganizeUiState(file = file("book.pdf"), pageCount = 3, edits = listOf(PageEdit(0), PageEdit(1), PageEdit(2)))
                    } else {
                        OrganizeUiState()
                    },
                    onBack = {}, onPickFile = {}, onRemoveFile = {}, onMoveUp = {}, onMoveDown = {}, onMoveTo = { _, _ -> },
                    onRotateLeft = {}, onRotateRight = {}, onDuplicate = {}, onDelete = {}, onUndo = {}, onOrganize = {},
                    onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
                )
            },
            Entry("Split") {
                SplitScreenContent(
                    state = SplitUiState(file = f, pageCount = pages),
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
            },
            Entry("PageSelect") {
                PageSelectScreenContent(
                    state = PageSelectUiState(file = f, pageCount = pages),
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
            },
            Entry("Rotate") {
                RotateScreenContent(
                    state = RotateUiState(file = f, pageCount = pages),
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
            },
            Entry("ImagesToPdf") {
                ImagesToPdfScreenContent(
                    state = ImagesToPdfUiState(images = if (f != null) listOf(file("a.jpg"), file("b.jpg")) else emptyList()),
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
            },
            Entry("PdfToImages") {
                PdfToImagesScreenContent(
                    state = PdfToImagesUiState(file = f, pageCount = pages),
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
            },
            Entry("PdfToText") {
                PdfToTextScreenContent(
                    state = PdfToTextUiState(file = f, pageCount = pages),
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
            },
            Entry("Ocr") {
                OcrScreenContent(
                    state = OcrUiState(file = f, pageCount = pages),
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
            },
            Entry("Protect") {
                ProtectScreenContent(
                    state = ProtectUiState(file = f, pageCount = pages),
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
            },
            Entry("Unlock") {
                UnlockScreenContent(
                    state = UnlockUiState(file = f, pageCount = pages),
                    onBack = {},
                    onPickFile = {},
                    onRemoveFile = {},
                    onUnlock = {},
                    onCancel = {},
                    onSave = {},
                    onShare = {},
                    onRetry = {},
                )
            },
            Entry("Sign") {
                SignScreenContent(
                    state = SignUiState(file = f, pageCount = pages),
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
            },
            Entry("Compress") {
                CompressScreenContent(
                    state = CompressUiState(file = f, pageCount = pages),
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
            },
            Entry("Watermark") {
                WatermarkScreenContent(
                    state = WatermarkUiState(file = f, pageCount = pages),
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
            },
            Entry("PageNumbers") {
                PageNumbersScreenContent(
                    state = PageNumbersUiState(file = f, pageCount = pages),
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
            },
            Entry("Metadata") {
                MetadataScreenContent(
                    state = MetadataUiState(file = f),
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
            },
            Entry("Repair") {
                RepairScreenContent(
                    state = RepairUiState(file = f, pageCount = pages),
                    onBack = {},
                    onPickFile = {},
                    onRemoveFile = {},
                    onRepair = {},
                    onCancel = {},
                    onSave = {},
                    onShare = {},
                    onRetry = {},
                )
            },
            Entry("Word, Excel and text to PDF") {
                io.github.wzehirx.bpdf.feature.convert.DocumentToPdfScreenContent(
                    state = io.github.wzehirx.bpdf.feature.convert.DocumentToPdfUiState(
                        file = f, kind = if (f != null) io.github.wzehirx.bpdf.core.pdf.DocumentKind.DOCX else null,
                    ),
                    onBack = {}, onPickFile = {}, onRemoveFile = {}, onConvert = {}, onCancel = {},
                    onSave = {}, onShare = {}, onRetry = {},
                )
            },
            Entry("PDF to Word, Excel and HTML") {
                io.github.wzehirx.bpdf.feature.convert.PdfToDocumentScreenContent(
                    state = io.github.wzehirx.bpdf.feature.convert.PdfToDocumentUiState(file = f, pageCount = pages),
                    onBack = {}, onPickFile = {}, onRemoveFile = {}, onFormatChange = {}, onLayoutChange = {},
                    onConvert = {}, onCancel = {}, onSave = {}, onShare = {}, onRetry = {},
                )
            },
        )
    }
}
