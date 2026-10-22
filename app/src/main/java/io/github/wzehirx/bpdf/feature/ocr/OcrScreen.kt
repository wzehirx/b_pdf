package io.github.wzehirx.bpdf.feature.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.ocr.OcrLanguage
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.toPlainText
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.feature.pdftext.PageTextList
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageChooser
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.SinglePdfSection
import io.github.wzehirx.bpdf.ui.components.PrimaryButton
import io.github.wzehirx.bpdf.ui.components.ToolSection
import io.github.wzehirx.bpdf.ui.components.OptionsCard
import io.github.wzehirx.bpdf.ui.components.SecondaryButton
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving, sharing and the clipboard. */
@Composable
fun OcrScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: OcrViewModel = viewModel(factory = OcrViewModel.Factory),
) {
    HandOffEffect(initialUri) { viewModel.pickFile(listOf(it)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val result = (state.operation as? OperationState.Success)?.value

    val launchSaveText = rememberSaveDocumentLauncher("text/plain") { uri -> uri?.let(viewModel::saveText) }
    val launchSavePdf = rememberSaveDocumentLauncher("application/pdf") { uri -> uri?.let(viewModel::saveSearchablePdf) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }
    // The searchable PDF is built after the save dialog closes: say so once, since nothing else changes audibly.
    val buildingMessage = stringResource(R.string.ocr_building_pdf)
    LaunchedEffect(state.buildingPdf) { if (state.buildingPdf) announce(buildingMessage) }
    // Pressing the button with an invalid range starts nothing, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) state.rangeError?.let { announce(it.toMessage(resources)) }
    }

    OcrScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onLanguageChange = viewModel::setLanguage,
        onScopeChange = viewModel::setScope,
        onRangeChange = viewModel::setRangeText,
        onRecognize = viewModel::recognize,
        onCancel = viewModel::cancel,
        onSaveText = { launchSaveText(result?.suggestedName.orEmpty()) },
        onShareText = {
            scope.launch {
                val uri = viewModel.prepareShare()
                val shared = uri != null &&
                    context.shareFile(uri, "text/plain", resources.getString(R.string.text_share_chooser_title))
                if (!shared) announce(resources.getString(R.string.error_generic))
            }
        },
        onCopyAll = {
            val text = result?.pages?.toPlainText { resources.getString(R.string.text_page_header, it) }.orEmpty()
            val copied = runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.tool_ocr_title), text))
            }.isSuccess
            announce(resources.getString(if (copied) R.string.text_copied else R.string.text_copy_failed))
        },
        onSaveSearchablePdf = { launchSavePdf(viewModel.searchablePdfName()) },
        onCancelBuild = viewModel::cancelBuild,
        onRetry = viewModel::retry,
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        thumbnail = rememberPageThumbnails(state.file?.file, state.file?.let { viewModel.passwordOf(it.file) }),
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun OcrScreenContent(
    state: OcrUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onLanguageChange: (OcrLanguage) -> Unit,
    onScopeChange: (PageScope) -> Unit,
    onRangeChange: (String) -> Unit,
    onRecognize: () -> Unit,
    onCancel: () -> Unit,
    onSaveText: () -> Unit,
    onShareText: () -> Unit,
    onCopyAll: () -> Unit,
    onSaveSearchablePdf: () -> Unit,
    onCancelBuild: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
    thumbnail: suspend (Int) -> Bitmap? = { null },
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.ocr_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    val result = (state.operation as? OperationState.Success)?.value
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_ocr_title), onBack = onBack) {
        ToolSection {
            Text(stringResource(R.string.ocr_instructions))
            SinglePdfSection(file, state.pageCount, state.importing, busy, onPickFile, onRemoveFile)
        }
        if (file != null) {
            OptionsCard {
                ChoiceRow(
                    label = stringResource(R.string.ocr_language_label),
                    options = OcrLanguage.entries,
                    selected = state.language,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onLanguageChange,
                    enabled = !busy,
                )
                ChoiceRow(
                    label = stringResource(R.string.text_scope_label),
                    options = PageScope.entries,
                    selected = state.scope,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onScopeChange,
                    enabled = !busy,
                )
                if (state.scope == PageScope.SELECTED) {
                    PageRangeField(
                        value = state.rangeText,
                        onValueChange = onRangeChange,
                        pageCount = state.pageCount,
                        error = state.rangeError,
                        enabled = !busy,
                    )
                    PageChooser(
                        rangeText = state.rangeText,
                        pageCount = state.pageCount,
                        onRangeChange = onRangeChange,
                        thumbnail = thumbnail,
                        enabled = !busy,
                    )
                }
            }
        }
        PrimaryButton(
            text = stringResource(R.string.ocr_button),
            onClick = onRecognize,
            enabled = file != null && !busy,
            disabledReason = if (file == null) needFile else null,
        )
        OperationStatusPanel(
            state = state.operation,
            runningLabel = state.progress?.let { resources.getString(R.string.ocr_progress, it.position, it.total) },
            successText = { r ->
                if (r.allBlank) {
                    resources.getString(R.string.ocr_empty_message)
                } else {
                    val summary = resources.getQuantityString(R.plurals.ocr_success, r.pages.size, r.pages.size)
                    if (r.blankCount == 0) {
                        summary
                    } else {
                        summary + " " + resources.getQuantityString(R.plurals.text_blank_pages, r.blankCount, r.blankCount)
                    }
                }
            },
            onCancel = onCancel,
            onRetry = onRetry,
            successActions = {
                if (result != null && !result.allBlank) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.buildingPdf) {
                            Text(
                                stringResource(R.string.ocr_building_pdf),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            LinearProgressIndicator(progress = { state.buildProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            SecondaryButton(text = stringResource(R.string.cancel), onClick = onCancelBuild)
                        }
                        PrimaryButton(text = stringResource(R.string.ocr_save_text), onClick = onSaveText, enabled = !state.saving)
                        SecondaryButton(text = stringResource(R.string.ocr_share_text), onClick = onShareText, enabled = !state.saving)
                        SecondaryButton(text = stringResource(R.string.text_copy_all), onClick = onCopyAll, enabled = !state.saving)
                        SecondaryButton(text = stringResource(R.string.ocr_save_pdf), onClick = onSaveSearchablePdf, enabled = !state.saving)
                        Text(stringResource(R.string.ocr_pdf_note), style = MaterialTheme.typography.bodyMedium)
                        if (state.copyUnprotected) {
                            Text(stringResource(R.string.unprotected_output_note), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
        )
        if (result != null && !result.allBlank) {
            PageTextList(result.pages, emptyPageText = R.string.ocr_page_empty)
        }
    }
}

private fun OcrLanguage.labelRes() = when (this) {
    OcrLanguage.TURKISH_ENGLISH -> R.string.ocr_lang_tur_eng
    OcrLanguage.TURKISH -> R.string.ocr_lang_tur
    OcrLanguage.ENGLISH -> R.string.ocr_lang_eng
}

private fun PageScope.labelRes() = when (this) {
    PageScope.ALL -> R.string.text_scope_all
    PageScope.SELECTED -> R.string.text_scope_selected
}
