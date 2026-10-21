package io.github.wzehirx.bpdf.feature.pdftext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.toPlainText
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.Tool
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.ComposeWhenNear
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageChooser
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SaveResultActions
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving, sharing and the clipboard. */
@Composable
fun PdfToTextScreen(
    onBack: () -> Unit,
    onOpenOcr: (Uri?) -> Unit = {},
    initialUri: Uri? = null,
    viewModel: PdfToTextViewModel = viewModel(factory = PdfToTextViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val result = (state.operation as? OperationState.Success)?.value

    HandOffEffect(initialUri) { viewModel.pickFile(listOf(it)) }
    val launchSave = rememberSaveDocumentLauncher("text/plain") { uri -> uri?.let(viewModel::save) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }
    // Pressing Extract with an invalid range starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) state.rangeError?.let { announce(it.toMessage(resources)) }
    }

    PdfToTextScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onScopeChange = viewModel::setScope,
        onRangeChange = viewModel::setRangeText,
        onExtract = viewModel::extract,
        onCancel = viewModel::cancel,
        onSave = { launchSave(result?.suggestedName.orEmpty()) },
        onShare = {
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
                clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.tool_pdf_to_text_title), text))
            }.isSuccess
            announce(resources.getString(if (copied) R.string.text_copied else R.string.text_copy_failed))
        },
        onRetry = viewModel::retry,
        ocrAvailable = Tool.OCR.available,
        onOpenOcr = { onOpenOcr(state.sourceUri) },
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        thumbnail = rememberPageThumbnails(state.file?.file, state.file?.let { viewModel.passwordOf(it.file) }),
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun PdfToTextScreenContent(
    state: PdfToTextUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onScopeChange: (TextScope) -> Unit,
    onRangeChange: (String) -> Unit,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopyAll: () -> Unit,
    onRetry: () -> Unit,
    ocrAvailable: Boolean,
    onOpenOcr: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
    thumbnail: suspend (Int) -> Bitmap? = { null },
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.text_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    val result = (state.operation as? OperationState.Success)?.value
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_pdf_to_text_title), onBack = onBack) {
        Text(stringResource(R.string.text_instructions))
        PickFilesButton(
                label = stringResource(if (file == null) R.string.single_file_choose else R.string.single_file_replace),
                mimeTypes = arrayOf("application/pdf"),
                multiple = false,
                onPicked = onPickFile,
                enabled = !busy,
            )
        if (state.importing) {
            Text(
                    stringResource(R.string.single_file_importing),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
        }
        if (file != null) {
            SelectedPdfCard(
                    summary = pluralStringResource(R.plurals.single_file_summary, state.pageCount, file.displayName, state.pageCount),
                    removeActionLabel = stringResource(R.string.single_file_remove_action),
                    removeButtonDescription = stringResource(R.string.single_file_remove, file.displayName),
                    enabled = !busy,
                    onRemove = onRemoveFile,
                )
            ChoiceRow(
                    label = stringResource(R.string.text_scope_label),
                    options = TextScope.entries,
                    selected = state.scope,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onScopeChange,
                    enabled = !busy,
                )
            if (state.scope == TextScope.SELECTED) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        Button(
                onClick = onExtract,
                enabled = file != null && !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { if (file == null) stateDescription = needFile },
            ) {
                Text(stringResource(R.string.text_button))
            }
        OperationStatusPanel(
                state = state.operation,
                successText = { r ->
                    if (r.allBlank) {
                        resources.getString(R.string.text_empty_message)
                    } else {
                        val summary = resources.getQuantityString(R.plurals.text_success, r.pages.size, r.pages.size)
                        if (r.blankCount == 0) {
                            summary
                        } else {
                            summary + " " + resources.getQuantityString(R.plurals.text_blank_pages, r.blankCount, r.blankCount)
                        }
                    }
                },
                onSave = onSave,
                onShare = onShare,
                actionsEnabled = !state.saving,
                onCancel = onCancel,
                onRetry = onRetry,
                successActions = {
                    if (result?.allBlank == true) {
                        OcrSuggestion(ocrAvailable, onOpenOcr)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SaveResultActions(onSave = onSave, onShare = onShare, enabled = !state.saving)
                            OutlinedButton(onClick = onCopyAll, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.text_copy_all))
                            }
                        }
                    }
                },
            )
        if (result != null && !result.allBlank) {
            PageTextList(result.pages)
        }
    }
}

/** "Try OCR": opens the OCR tool when this version has it, otherwise a disabled button that says why. */
@Composable
private fun OcrSuggestion(available: Boolean, onOpenOcr: () -> Unit) {
    val unavailable = stringResource(R.string.text_ocr_unavailable)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onOpenOcr,
            enabled = available,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (!available) stateDescription = unavailable },
        ) {
            Text(stringResource(R.string.text_try_ocr))
        }
        if (!available) Text(unavailable, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun TextScope.labelRes() = when (this) {
    TextScope.ALL -> R.string.text_scope_all
    TextScope.SELECTED -> R.string.text_scope_selected
}
