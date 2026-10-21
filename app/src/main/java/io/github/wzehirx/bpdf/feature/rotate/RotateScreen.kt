package io.github.wzehirx.bpdf.feature.rotate

import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import android.graphics.Bitmap
import io.github.wzehirx.bpdf.ui.components.PageChooser
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
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
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun RotateScreen(onBack: () -> Unit, viewModel: RotateViewModel = viewModel(factory = RotateViewModel.Factory)) {
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
    // Pressing Rotate with an invalid range starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) state.rangeError?.let { announce(it.toMessage(resources)) }
    }

    RotateScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onAngleChange = viewModel::setAngle,
        onScopeChange = viewModel::setScope,
        onRangeChange = viewModel::setRangeText,
        onRotate = viewModel::rotate,
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
        thumbnail = rememberPageThumbnails(state.file?.file),
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun RotateScreenContent(
    state: RotateUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onAngleChange: (RotateAngle) -> Unit,
    onScopeChange: (RotateScope) -> Unit,
    onRangeChange: (String) -> Unit,
    onRotate: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    thumbnail: suspend (Int) -> Bitmap? = { null },
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.rotate_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    ToolScaffold(title = stringResource(R.string.tool_rotate_title), onBack = onBack) {
        Text(stringResource(R.string.rotate_instructions))
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
                label = stringResource(R.string.rotate_angle_label),
                options = RotateAngle.entries,
                selected = state.angle,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onAngleChange,
                enabled = !busy,
            )
            ChoiceRow(
                label = stringResource(R.string.rotate_scope_label),
                options = RotateScope.entries,
                selected = state.scope,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onScopeChange,
                enabled = !busy,
            )
            if (state.scope == RotateScope.SELECTED) {
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
        Button(
            onClick = onRotate,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.rotate_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.rotate_success, result.rotatedCount, result.rotatedCount)
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

private fun RotateAngle.labelRes() = when (this) {
    RotateAngle.CLOCKWISE_90 -> R.string.rotate_angle_cw_90
    RotateAngle.HALF_TURN -> R.string.rotate_angle_180
    RotateAngle.COUNTER_CLOCKWISE_90 -> R.string.rotate_angle_ccw_90
}

private fun RotateScope.labelRes() = when (this) {
    RotateScope.ALL -> R.string.rotate_scope_all
    RotateScope.SELECTED -> R.string.rotate_scope_selected
}
