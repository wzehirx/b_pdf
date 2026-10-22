package io.github.wzehirx.bpdf.feature.repair

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
import io.github.wzehirx.bpdf.ui.components.PrimaryButton
import io.github.wzehirx.bpdf.ui.components.ToolSection
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun RepairScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: RepairViewModel = viewModel(factory = RepairViewModel.Factory),
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

    RepairScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onRepair = viewModel::repair,
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
fun RepairScreenContent(
    state: RepairUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onRepair: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.repair_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_repair_title), onBack = onBack) {
        ToolSection {
            Text(stringResource(R.string.repair_instructions))
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
                    file = file,
                    pageCount = state.pageCount,
                    summary = state.pageCount?.let { pluralStringResource(R.plurals.single_file_summary, it, file.displayName, it) }
                        ?: stringResource(R.string.repair_file_unreadable, file.displayName),
                    removeActionLabel = stringResource(R.string.single_file_remove_action),
                    removeButtonDescription = stringResource(R.string.single_file_remove, file.displayName),
                    enabled = !busy,
                    onRemove = onRemoveFile,
                )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.repair_button),
            onClick = onRepair,
            enabled = file != null && !busy,
            disabledReason = if (file == null) needFile else null,
        )
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.repair_success, result.pagesRecovered, result.pagesRecovered)
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
