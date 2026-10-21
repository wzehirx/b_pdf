package io.github.wzehirx.bpdf.feature.pages

import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch

/** Stateful entry point shared by "Remove pages" and "Extract pages". */
@Composable
fun PageSelectScreen(
    mode: PageSelectMode,
    onBack: () -> Unit,
    viewModel: PageSelectViewModel = viewModel(key = mode.name, factory = PageSelectViewModel.factory(mode)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val suggestedName = (state.operation as? OperationState.Success)?.value?.suggestedName.orEmpty()

    val launchSave = rememberSaveDocumentLauncher("application/pdf") { uri -> uri?.let(viewModel::save) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }
    // Pressing the button with an invalid selection starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) {
            val message = state.rangeError?.toMessage(resources)
                ?: if (state.removesAllPages) resources.getString(R.string.pages_remove_all_error) else null
            if (message != null) announce(message)
        }
    }

    PageSelectScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onRangeChange = viewModel::setRangeText,
        onRun = viewModel::run,
        onCancel = viewModel::cancel,
        onSave = { launchSave(suggestedName) },
        onShare = {
            scope.launch {
                val uri = viewModel.prepareShare()
                val shared = uri != null &&
                    context.shareFile(uri, "application/pdf", resources.getString(R.string.share_chooser_title))
                if (!shared) announce(resources.getString(R.string.error_generic))
            }
        },
        onRetry = viewModel::retry,
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun PageSelectScreenContent(
    state: PageSelectUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onRangeChange: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    val resources = LocalResources.current
    val removing = state.mode == PageSelectMode.REMOVE
    val needFile = stringResource(R.string.pages_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    ToolScaffold(
        title = stringResource(if (removing) R.string.tool_remove_pages_title else R.string.tool_extract_pages_title),
        onBack = onBack,
    ) {
        Text(stringResource(if (removing) R.string.pages_remove_instructions else R.string.pages_extract_instructions))
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
            Text(
                stringResource(if (removing) R.string.pages_remove_hint else R.string.pages_extract_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            PageRangeField(
                value = state.rangeText,
                onValueChange = onRangeChange,
                pageCount = state.pageCount,
                error = state.rangeError,
                enabled = !busy,
            )
            if (state.removesAllPages) {
                Text(
                    stringResource(R.string.pages_remove_all_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Button(
            onClick = onRun,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(if (removing) R.string.pages_remove_button else R.string.pages_extract_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                if (removing) {
                    resources.getQuantityString(
                        R.plurals.pages_removed_success, result.selectedCount, result.selectedCount, result.remainingCount,
                    )
                } else {
                    resources.getQuantityString(R.plurals.pages_extracted_success, result.selectedCount, result.selectedCount)
                }
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}
