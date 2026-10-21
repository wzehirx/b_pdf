package io.github.wzehirx.bpdf.feature.merge

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
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedFileList
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun MergeScreen(onBack: () -> Unit, viewModel: MergeViewModel = viewModel(factory = MergeViewModel.Factory)) {
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

    MergeScreenContent(
        state = state,
        onBack = onBack,
        onAddFiles = viewModel::addFiles,
        onMove = viewModel::move,
        onRemove = viewModel::remove,
        onMerge = viewModel::merge,
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
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun MergeScreenContent(
    state: MergeUiState,
    onBack: () -> Unit,
    onAddFiles: (List<Uri>) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onMerge: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needTwo = stringResource(R.string.merge_need_two_files)
    val tooFew = state.files.size < 2
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_merge_title), onBack = onBack) {
        Text(stringResource(R.string.merge_instructions))
        PickFilesButton(
            label = stringResource(R.string.merge_add_files),
            mimeTypes = arrayOf("application/pdf"),
            multiple = true,
            onPicked = onAddFiles,
            enabled = !busy,
        )
        if (state.importing) {
            Text(
                stringResource(R.string.merge_importing),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        SelectedFileList(files = state.files, onMove = onMove, onRemove = onRemove, enabled = !busy)
        Button(
            onClick = onMerge,
            enabled = !tooFew && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (tooFew) stateDescription = needTwo },
        ) {
            Text(stringResource(R.string.merge_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.merge_success, result.pageCount, result.fileCount, result.pageCount)
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
