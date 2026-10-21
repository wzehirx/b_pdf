package io.github.wzehirx.bpdf.feature.pdfimages

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.ImageFormat
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageChooser
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SaveFolderZipActions
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFiles
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun PdfToImagesScreen(
    onBack: () -> Unit,
    viewModel: PdfToImagesViewModel = viewModel(factory = PdfToImagesViewModel.Factory),
) {
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
            announce(resources.getQuantityString(R.plurals.images_saved_announcement, n, n))
        }
    }
    // Pressing Convert with an invalid selection starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) state.rangeError?.let { announce(it.toMessage(resources)) }
    }

    PdfToImagesScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onScopeChange = viewModel::setScope,
        onRangeChange = viewModel::setRangeText,
        onDpiChange = viewModel::setDpi,
        onFormatChange = viewModel::setFormat,
        onConvert = viewModel::convert,
        onCancel = viewModel::cancel,
        onSaveToFolder = { folderLauncher.launch(null) },
        onSaveZip = { launchZip(zipName) },
        onShare = {
            scope.launch {
                val uris = viewModel.prepareShare()
                val mime = state.format.mimeType
                val shared = context.shareFiles(uris, mime, resources.getString(R.string.images_share_chooser_title))
                if (!shared) announce(resources.getString(R.string.error_generic))
            }
        },
        onRetry = viewModel::retry,
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        thumbnail = rememberPageThumbnails(state.file?.file, state.file?.let { viewModel.passwordOf(it.file) }),
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun PdfToImagesScreenContent(
    state: PdfToImagesUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onScopeChange: (PageScope) -> Unit,
    onRangeChange: (String) -> Unit,
    onDpiChange: (DpiChoice) -> Unit,
    onFormatChange: (ImageFormat) -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onSaveToFolder: () -> Unit,
    onSaveZip: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
    thumbnail: suspend (Int) -> Bitmap? = { null },
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.images_out_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_pdf_to_images_title), onBack = onBack) {
        Text(stringResource(R.string.images_out_instructions))
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
                label = stringResource(R.string.images_out_pages_label),
                options = PageScope.entries,
                selected = state.scope,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onScopeChange,
                enabled = !busy,
            )
            if (state.scope == PageScope.SELECTED) {
                Text(stringResource(R.string.images_out_range_hint), style = MaterialTheme.typography.bodyMedium)
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
            ChoiceRow(
                label = stringResource(R.string.images_out_dpi_label),
                options = DpiChoice.entries,
                selected = state.dpi,
                optionLabel = { resources.getString(R.string.images_out_dpi_option, it.dpi, resources.getString(it.hintRes())) },
                onSelect = onDpiChange,
                enabled = !busy,
            )
            ChoiceRow(
                label = stringResource(R.string.images_out_format_label),
                options = ImageFormat.entries,
                selected = state.format,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onFormatChange,
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
            Text(stringResource(R.string.images_out_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.images_out_success, result.files.size, result.files.size)
            },
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

private fun PageScope.labelRes() = when (this) {
    PageScope.ALL -> R.string.images_out_pages_all
    PageScope.SELECTED -> R.string.images_out_pages_selected
}

private fun DpiChoice.hintRes() = when (this) {
    DpiChoice.LOW -> R.string.images_out_dpi_low
    DpiChoice.MEDIUM -> R.string.images_out_dpi_medium
    DpiChoice.HIGH -> R.string.images_out_dpi_high
}

private fun ImageFormat.labelRes() = when (this) {
    ImageFormat.JPEG -> R.string.images_out_format_jpeg
    ImageFormat.PNG -> R.string.images_out_format_png
}
