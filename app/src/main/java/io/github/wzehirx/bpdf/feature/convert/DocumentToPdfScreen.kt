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
import io.github.wzehirx.bpdf.core.pdf.DocumentKind
import io.github.wzehirx.bpdf.core.pdf.XlsxReader
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** The file types the picker offers: Word, Excel and plain text. */
internal val DOCUMENT_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/*", // .csv, .md, .log and other text files come with their own types
    "application/octet-stream", // providers that cannot tell the type; the file content is sniffed on import
)

/** Word, Excel or text to PDF. */
@Composable
fun DocumentToPdfScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: DocumentToPdfViewModel = viewModel(factory = DocumentToPdfViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val result = (state.operation as? OperationState.Success)?.value

    HandOffEffect(initialUri) { viewModel.pickFile(listOf(it)) }
    val launchSave = rememberSaveDocumentLauncher("application/pdf") { uri -> uri?.let(viewModel::save) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }

    DocumentToPdfScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onConvert = viewModel::convert,
        onCancel = viewModel::cancel,
        onSave = { launchSave(result?.suggestedName.orEmpty()) },
        onShare = {
            scope.launch {
                val uri = viewModel.prepareShare()
                val shared = uri != null &&
                    context.shareFile(uri, "application/pdf", resources.getString(R.string.convert_to_pdf_share_chooser_title))
                if (!shared) announce(resources.getString(R.string.error_generic))
            }
        },
        onRetry = viewModel::retry,
    )
}

/** Stateless screen body, driven entirely by [state]. */
@Composable
fun DocumentToPdfScreenContent(
    state: DocumentToPdfUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.convert_need_document)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    ToolScaffold(title = stringResource(R.string.tool_document_to_pdf_title), onBack = onBack) {
        Text(stringResource(R.string.convert_to_pdf_instructions))
        Text(stringResource(R.string.convert_to_pdf_limits))
        PickFilesButton(
            label = stringResource(if (file == null) R.string.convert_choose_document else R.string.convert_replace_document),
            mimeTypes = DOCUMENT_MIME_TYPES,
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
                file = file,
                pageCount = null,
                summary = state.kind?.let { resources.getString(R.string.convert_document_summary, file.displayName, resources.getString(it.labelRes())) }
                    ?: file.displayName,
                removeActionLabel = stringResource(R.string.single_file_remove_action),
                removeButtonDescription = stringResource(R.string.single_file_remove, file.displayName),
                enabled = !busy,
                onRemove = onRemoveFile,
            )
        }
        Button(
            onClick = onConvert,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.convert_to_pdf_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { r ->
                buildList {
                    add(resources.getQuantityString(R.plurals.convert_to_pdf_success, r.stats.pages, r.stats.pages))
                    if (r.stats.pictures > 0) add(resources.getQuantityString(R.plurals.convert_to_pdf_pictures, r.stats.pictures, r.stats.pictures))
                    if (r.stats.sheets > 1) add(resources.getQuantityString(R.plurals.convert_to_pdf_sheets, r.stats.sheets, r.stats.sheets))
                    if (r.stats.truncated) add(resources.getString(R.string.convert_to_pdf_truncated, XlsxReader.MAX_ROWS, XlsxReader.MAX_COLS))
                }.joinToString(" ")
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

private fun DocumentKind.labelRes() = when (this) {
    DocumentKind.DOCX -> R.string.convert_kind_word
    DocumentKind.XLSX -> R.string.convert_kind_excel
    DocumentKind.TEXT -> R.string.convert_kind_text
}
