package io.github.wzehirx.bpdf.feature.unlock

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
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun UnlockScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: UnlockViewModel = viewModel(factory = UnlockViewModel.Factory),
) {
    HandOffEffect(initialUri) { viewModel.pickFile(listOf(it)) }
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
    // Choosing an unprotected file is not an error, so say why Remove password stays off.
    LaunchedEffect(state.notProtected) {
        if (state.notProtected) announce(resources.getString(R.string.unlock_not_protected))
    }

    UnlockScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onUnlock = viewModel::unlock,
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
fun UnlockScreenContent(
    state: UnlockUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onUnlock: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.unlock_need_file)
    val notProtected = stringResource(R.string.unlock_not_protected)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_unlock_title), onBack = onBack) {
        Text(stringResource(R.string.unlock_instructions))
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
            if (state.notProtected) Text(notProtected)
        }
        Button(
            onClick = onUnlock,
            enabled = file != null && !state.notProtected && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    if (file == null) stateDescription = needFile else if (state.notProtected) stateDescription = notProtected
                },
        ) {
            Text(stringResource(R.string.unlock_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.unlock_success, result.pageCount, result.pageCount)
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}
