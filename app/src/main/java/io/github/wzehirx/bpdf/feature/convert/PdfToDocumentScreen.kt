package io.github.wzehirx.bpdf.feature.convert

import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.PdfLayout
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** PDF to Word, Excel or HTML. */
@Composable
fun PdfToDocumentScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: PdfToDocumentViewModel = viewModel(factory = PdfToDocumentViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val result = (state.operation as? OperationState.Success)?.value

    HandOffEffect(initialUri) { viewModel.pickFile(listOf(it)) }
    // The save dialog's file type is fixed when its launcher is made, so there is one launcher per type.
    val saveWord = rememberSaveDocumentLauncher(DocumentFormat.WORD.mimeType) { uri -> uri?.let(viewModel::save) }
    val saveExcel = rememberSaveDocumentLauncher(DocumentFormat.EXCEL.mimeType) { uri -> uri?.let(viewModel::save) }
    val saveHtml = rememberSaveDocumentLauncher(DocumentFormat.HTML.mimeType) { uri -> uri?.let(viewModel::save) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }

    PdfToDocumentScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onFormatChange = viewModel::setFormat,
        onLayoutChange = viewModel::setLayout,
        onConvert = viewModel::convert,
        onCancel = viewModel::cancel,
        onSave = {
            val name = result?.suggestedName.orEmpty()
            when (result?.format) {
                DocumentFormat.EXCEL -> saveExcel(name)
                DocumentFormat.HTML -> saveHtml(name)
                else -> saveWord(name)
            }
        },
        onShare = {
            scope.launch {
                val uri = viewModel.prepareShare()
                val shared = uri != null && result != null &&
                    context.shareFile(uri, result.format.mimeType, resources.getString(R.string.convert_share_chooser_title))
                if (!shared) announce(resources.getString(R.string.error_generic))
            }
        },
        onRetry = viewModel::retry,
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
    )
}

/** Stateless screen body, driven entirely by [state]. */
@Composable
fun PdfToDocumentScreenContent(
    state: PdfToDocumentUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onFormatChange: (DocumentFormat) -> Unit,
    onLayoutChange: (PdfLayout) -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.convert_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_pdf_to_document_title), onBack = onBack) {
        Text(stringResource(R.string.convert_from_pdf_instructions))
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
        }
        ChoiceRow(
            label = stringResource(R.string.convert_format_label),
            options = DocumentFormat.entries,
            selected = state.format,
            optionLabel = { resources.getString(it.labelRes()) },
            onSelect = onFormatChange,
            enabled = !busy,
        )
        if (state.format == DocumentFormat.EXCEL) {
            Text(stringResource(R.string.convert_excel_note))
        } else {
            ChoiceRow(
                label = stringResource(R.string.convert_layout_label),
                options = listOf(PdfLayout.TEXT_AND_PICTURES, PdfLayout.EXACT_LOOK),
                selected = state.layout,
                optionLabel = { resources.getString(if (it == PdfLayout.EXACT_LOOK) R.string.convert_layout_exact else R.string.convert_layout_text_pictures) },
                onSelect = onLayoutChange,
                enabled = !busy,
            )
        }
        Button(
            onClick = onConvert,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.convert_pdf_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { r ->
                val summary = resources.getQuantityString(r.format.successPlural(), r.stats.pages, r.stats.pages)
                buildList {
                    add(summary)
                    if (r.stats.pictures > 0) add(resources.getQuantityString(R.plurals.convert_pictures_kept, r.stats.pictures, r.stats.pictures))
                    if (r.stats.blankPages > 0) add(resources.getQuantityString(R.plurals.convert_blank_pages, r.stats.blankPages, r.stats.blankPages))
                }.joinToString(" ")
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            unprotectedOutput = state.copyUnprotected,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

private fun DocumentFormat.labelRes() = when (this) {
    DocumentFormat.WORD -> R.string.convert_format_word
    DocumentFormat.EXCEL -> R.string.convert_format_excel
    DocumentFormat.HTML -> R.string.convert_format_html
}

private fun DocumentFormat.successPlural() = when (this) {
    DocumentFormat.WORD -> R.plurals.convert_success_word
    DocumentFormat.EXCEL -> R.plurals.convert_success_excel
    DocumentFormat.HTML -> R.plurals.convert_success_html
}
