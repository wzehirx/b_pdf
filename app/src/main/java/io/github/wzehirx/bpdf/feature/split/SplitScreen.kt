package io.github.wzehirx.bpdf.feature.split

import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SaveFolderZipActions
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFiles
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun SplitScreen(onBack: () -> Unit, viewModel: SplitViewModel = viewModel(factory = SplitViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val zipName = (state.operation as? OperationState.Success)?.value?.zipName.orEmpty()

    val currentSaveFolder by rememberUpdatedState(viewModel::saveToFolder)
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) currentSaveFolder(uri)
    }
    val launchZip = rememberSaveDocumentLauncher("application/zip") { uri -> uri?.let(viewModel::saveZip) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { n ->
            announce(resources.getQuantityString(R.plurals.split_saved_announcement, n, n))
        }
    }
    // Pressing Split with invalid input starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) {
            val message = state.rangeError?.toMessage(resources)
                ?: everyNMessage(resources, state.everyNError, state.everyNText, state.pageCount)
            if (message != null) announce(message)
        }
    }

    SplitScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onModeChange = viewModel::setMode,
        onRangeChange = viewModel::setRangeText,
        onEveryNChange = viewModel::setEveryNText,
        onSplit = viewModel::split,
        onCancel = viewModel::cancel,
        onSaveToFolder = { folderLauncher.launch(null) },
        onSaveZip = { launchZip(zipName) },
        onShare = {
            scope.launch {
                val uris = viewModel.prepareShare()
                val shared = context.shareFiles(
                    uris, "application/pdf", resources.getString(R.string.split_share_chooser_title),
                )
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
fun SplitScreenContent(
    state: SplitUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onModeChange: (SplitModeChoice) -> Unit,
    onRangeChange: (String) -> Unit,
    onEveryNChange: (String) -> Unit,
    onSplit: () -> Unit,
    onCancel: () -> Unit,
    onSaveToFolder: () -> Unit,
    onSaveZip: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.split_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_split_title), onBack = onBack) {
        Text(stringResource(R.string.split_instructions))
        PickFilesButton(
            label = stringResource(if (file == null) R.string.split_choose_file else R.string.split_replace_file),
            mimeTypes = arrayOf("application/pdf"),
            multiple = false,
            onPicked = onPickFile,
            enabled = !busy,
        )
        if (state.importing) {
            Text(
                stringResource(R.string.split_importing),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (file != null) {
            SelectedPdfCard(
                summary = pluralStringResource(R.plurals.split_file_summary, state.pageCount, file.displayName, state.pageCount),
                removeActionLabel = stringResource(R.string.split_remove_action),
                removeButtonDescription = stringResource(R.string.split_remove_file, file.displayName),
                enabled = !busy,
                onRemove = onRemoveFile,
            )
            ChoiceRow(
                label = stringResource(R.string.split_mode_label),
                options = SplitModeChoice.entries,
                selected = state.mode,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onModeChange,
                enabled = !busy,
            )
            when (state.mode) {
                SplitModeChoice.RANGES -> {
                    Text(stringResource(R.string.split_ranges_hint), style = MaterialTheme.typography.bodyMedium)
                    PageRangeField(
                        value = state.rangeText,
                        onValueChange = onRangeChange,
                        pageCount = state.pageCount,
                        error = state.rangeError,
                        enabled = !busy,
                    )
                }
                SplitModeChoice.EVERY_N -> EveryNField(state, onEveryNChange, enabled = !busy)
                SplitModeChoice.EACH_PAGE -> Unit
            }
        }
        Button(
            onClick = onSplit,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.split_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.split_success, result.files.size, result.files.size)
            },
            unprotectedOutput = state.copyUnprotected,
            onCancel = onCancel,
            onRetry = onRetry,
            successActions = {
                SaveFolderZipActions(
                    onSaveToFolder = onSaveToFolder,
                    onSaveZip = onSaveZip,
                    onShare = onShare,
                    enabled = !state.saving,
                )
            },
        )
    }
}

private fun SplitModeChoice.labelRes() = when (this) {
    SplitModeChoice.RANGES -> R.string.split_mode_ranges
    SplitModeChoice.EVERY_N -> R.string.split_mode_every_n
    SplitModeChoice.EACH_PAGE -> R.string.split_mode_each_page
}

private fun everyNMessage(resources: Resources, error: EveryNError?, text: String, pageCount: Int): String? =
    when (error) {
        null -> null
        EveryNError.EMPTY -> resources.getString(R.string.split_every_n_empty)
        EveryNError.INVALID -> resources.getString(R.string.split_every_n_invalid, text.trim(), pageCount)
    }

@Composable
private fun EveryNField(state: SplitUiState, onChange: (String) -> Unit, enabled: Boolean) {
    val resources = LocalResources.current
    val message = everyNMessage(resources, state.everyNError, state.everyNText, state.pageCount)
    OutlinedTextField(
        value = state.everyNText,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.split_every_n_label)) },
        isError = message != null,
        supportingText = {
            Text(message ?: pluralStringResource(R.plurals.split_every_n_hint, state.pageCount, state.pageCount))
        },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (message != null) error(message) },
    )
}
