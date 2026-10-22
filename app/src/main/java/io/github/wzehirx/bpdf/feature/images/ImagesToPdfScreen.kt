package io.github.wzehirx.bpdf.feature.images

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.wzehirx.bpdf.core.pdf.Orientation
import io.github.wzehirx.bpdf.core.pdf.PageSizeOption
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.SelectedFileList
import io.github.wzehirx.bpdf.ui.components.PrimaryButton
import io.github.wzehirx.bpdf.ui.components.ToolSection
import io.github.wzehirx.bpdf.ui.components.OptionsCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to the gallery, camera, saving and sharing. */
@Composable
fun ImagesToPdfScreen(onBack: () -> Unit, viewModel: ImagesToPdfViewModel = viewModel(factory = ImagesToPdfViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val suggestedName = (state.operation as? OperationState.Success)?.value?.suggestedName.orEmpty()

    val currentAdd by rememberUpdatedState(viewModel::addImages)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) currentAdd(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        viewModel.cameraResult(taken)
    }
    val launchSave = rememberSaveDocumentLauncher("application/pdf") { uri -> uri?.let(viewModel::save) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }

    LaunchedEffect(viewModel) {
        viewModel.addedEvents.collect { n ->
            announce(resources.getQuantityString(R.plurals.images_added_announcement, n, n))
        }
    }

    ImagesToPdfScreenContent(
        state = state,
        onBack = onBack,
        onPickFromGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onTakePhoto = {
            val uri = viewModel.prepareCamera()
            if (uri != null) {
                try {
                    camera.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    viewModel.cameraResult(false)
                    announce(resources.getString(R.string.images_no_camera))
                }
            }
        },
        onMove = viewModel::move,
        onRemove = viewModel::remove,
        onPageSizeChange = viewModel::setPageSize,
        onOrientationChange = viewModel::setOrientation,
        onMarginChange = viewModel::setMargin,
        onConvert = viewModel::convert,
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
fun ImagesToPdfScreenContent(
    state: ImagesToPdfUiState,
    onBack: () -> Unit,
    onPickFromGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onPageSizeChange: (PageSizeOption) -> Unit,
    onOrientationChange: (Orientation) -> Unit,
    onMarginChange: (MarginChoice) -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    val resources = LocalResources.current
    val needImage = stringResource(R.string.images_need_images)
    val empty = state.images.isEmpty()
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    ToolScaffold(title = stringResource(R.string.tool_images_to_pdf_title), onBack = onBack) {
        ToolSection {
            Text(stringResource(R.string.images_instructions))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickFromGallery, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.images_choose))
                }
                OutlinedButton(onClick = onTakePhoto, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.images_take_photo))
                }
            }
            if (state.importing) {
                Text(
                    stringResource(R.string.images_importing),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SelectedFileList(files = state.images, onMove = onMove, onRemove = onRemove, enabled = !busy, announceAdded = false)
        }
        OptionsCard {
            ChoiceRow(
                label = stringResource(R.string.images_page_size_label),
                options = PageSizeOption.entries,
                selected = state.pageSize,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onPageSizeChange,
                enabled = !busy,
            )
            if (state.pageSize != PageSizeOption.FIT_IMAGE) {
                ChoiceRow(
                    label = stringResource(R.string.images_orientation_label),
                    options = Orientation.entries,
                    selected = state.orientation,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onOrientationChange,
                    enabled = !busy,
                )
            }
            ChoiceRow(
                label = stringResource(R.string.images_margin_label),
                options = MarginChoice.entries,
                selected = state.margin,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onMarginChange,
                enabled = !busy,
            )
        }
        PrimaryButton(
            text = stringResource(R.string.images_convert_button),
            onClick = onConvert,
            enabled = !empty && !busy,
            disabledReason = if (empty) needImage else null,
        )
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.images_success, result.pageCount, result.pageCount)
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

private fun PageSizeOption.labelRes() = when (this) {
    PageSizeOption.A4 -> R.string.images_size_a4
    PageSizeOption.LETTER -> R.string.images_size_letter
    PageSizeOption.FIT_IMAGE -> R.string.images_size_fit
}

private fun Orientation.labelRes() = when (this) {
    Orientation.AUTO -> R.string.images_orientation_auto
    Orientation.PORTRAIT -> R.string.images_orientation_portrait
    Orientation.LANDSCAPE -> R.string.images_orientation_landscape
}

private fun MarginChoice.labelRes() = when (this) {
    MarginChoice.NONE -> R.string.images_margin_none
    MarginChoice.SMALL -> R.string.images_margin_small
    MarginChoice.LARGE -> R.string.images_margin_large
}
