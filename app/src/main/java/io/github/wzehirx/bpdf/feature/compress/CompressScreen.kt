package io.github.wzehirx.bpdf.feature.compress

import android.net.Uri
import android.text.format.Formatter
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.CompressLevel
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.SinglePdfSection
import io.github.wzehirx.bpdf.ui.components.SwitchRow
import io.github.wzehirx.bpdf.ui.components.PrimaryButton
import io.github.wzehirx.bpdf.ui.components.ToolSection
import io.github.wzehirx.bpdf.ui.components.OptionsCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun CompressScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: CompressViewModel = viewModel(factory = CompressViewModel.Factory),
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

    CompressScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onLevelChange = viewModel::setLevel,
        onRemoveMetadataChange = viewModel::setRemoveMetadata,
        onCompress = viewModel::compress,
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
fun CompressScreenContent(
    state: CompressUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onLevelChange: (CompressLevel) -> Unit,
    onRemoveMetadataChange: (Boolean) -> Unit,
    onCompress: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val needFile = stringResource(R.string.compress_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_compress_title), onBack = onBack) {
        ToolSection {
            Text(stringResource(R.string.compress_instructions))
            SinglePdfSection(file, state.pageCount, state.importing, busy, onPickFile, onRemoveFile)
        }
        if (file != null) {
            OptionsCard {
        ChoiceRow(
            label = stringResource(R.string.compress_level_label),
            options = CompressLevel.entries,
            selected = state.level,
            optionLabel = { resources.getString(it.labelRes()) },
            onSelect = onLevelChange,
            enabled = !busy,
        )
        SwitchRow(
            label = stringResource(R.string.compress_remove_metadata),
            checked = state.removeMetadata,
            stateText = stringResource(if (state.removeMetadata) R.string.compress_metadata_removed else R.string.compress_metadata_kept),
            enabled = !busy,
            onChange = onRemoveMetadataChange,
        )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.compress_button),
            onClick = onCompress,
            enabled = file != null && !busy,
            disabledReason = if (file == null) needFile else null,
        )
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                val before = Formatter.formatShortFileSize(context, result.sizes.beforeBytes)
                val after = Formatter.formatShortFileSize(context, result.sizes.afterBytes)
                if (result.sizes.afterBytes < result.sizes.beforeBytes) {
                    resources.getString(R.string.compress_success, before, after, result.sizes.savedPercent)
                } else {
                    resources.getString(R.string.compress_no_saving, before)
                }
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

private fun CompressLevel.labelRes() = when (this) {
    CompressLevel.LOW -> R.string.compress_level_low
    CompressLevel.MEDIUM -> R.string.compress_level_medium
    CompressLevel.HIGH -> R.string.compress_level_high
}
