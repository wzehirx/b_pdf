package io.github.wzehirx.bpdf.feature.watermark

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.WatermarkPosition
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.LabelledSlider
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageChooser
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.SinglePdfSection
import io.github.wzehirx.bpdf.ui.components.PrimaryButton
import io.github.wzehirx.bpdf.ui.components.ToolSection
import io.github.wzehirx.bpdf.ui.components.OptionsCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun WatermarkScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: WatermarkViewModel = viewModel(factory = WatermarkViewModel.Factory),
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
    // Pressing the button with invalid input starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) {
            if (state.textMissing) {
                announce(resources.getString(R.string.watermark_text_error))
            } else {
                state.rangeError?.let { announce(it.toMessage(resources)) }
            }
        }
    }

    WatermarkScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onTextChange = viewModel::setText,
        onFontSizeChange = viewModel::setFontSize,
        onOpacityChange = viewModel::setOpacityPercent,
        onRotationChange = viewModel::setRotation,
        onPositionChange = viewModel::setPosition,
        onColorChange = viewModel::setColor,
        onScopeChange = viewModel::setScope,
        onRangeChange = viewModel::setRangeText,
        onApply = viewModel::addWatermark,
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
        thumbnail = rememberPageThumbnails(state.file?.file, state.file?.let { viewModel.passwordOf(it.file) }),
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun WatermarkScreenContent(
    state: WatermarkUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onTextChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onRotationChange: (Int) -> Unit,
    onPositionChange: (WatermarkPosition) -> Unit,
    onColorChange: (WatermarkColor) -> Unit,
    onScopeChange: (PageScope) -> Unit,
    onRangeChange: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
    thumbnail: suspend (Int) -> Bitmap? = { null },
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.watermark_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_watermark_title), onBack = onBack) {
        ToolSection {
            Text(stringResource(R.string.watermark_instructions))
            SinglePdfSection(file, state.pageCount, state.importing, busy, onPickFile, onRemoveFile)
        }
        if (file != null) {
            OptionsCard {
                val textError = if (state.textMissing) stringResource(R.string.watermark_text_error) else null
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    label = { Text(stringResource(R.string.watermark_text_label)) },
                    isError = textError != null,
                    supportingText = textError?.let { { Text(it) } },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { if (textError != null) error(textError) },
                )
                LabelledSlider(
                    label = stringResource(R.string.text_size_label),
                    value = state.fontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.roundToInt()) },
                    valueRange = WatermarkViewModel.MIN_FONT_SIZE.toFloat()..WatermarkViewModel.MAX_FONT_SIZE.toFloat(),
                    step = WatermarkViewModel.FONT_STEP.toFloat(),
                    valueText = { resources.getQuantityString(R.plurals.text_size_value, it.roundToInt(), it.roundToInt()) },
                    enabled = !busy,
                )
                LabelledSlider(
                    label = stringResource(R.string.watermark_opacity_label),
                    value = state.opacityPercent.toFloat(),
                    onValueChange = { onOpacityChange(it.roundToInt()) },
                    valueRange = WatermarkViewModel.MIN_OPACITY.toFloat()..WatermarkViewModel.MAX_OPACITY.toFloat(),
                    step = WatermarkViewModel.OPACITY_STEP.toFloat(),
                    valueText = { resources.getString(R.string.watermark_opacity_value, it.roundToInt()) },
                    enabled = !busy,
                )
                LabelledSlider(
                    label = stringResource(R.string.watermark_rotation_label),
                    value = state.rotation.toFloat(),
                    onValueChange = { onRotationChange(it.roundToInt()) },
                    valueRange = -WatermarkViewModel.MAX_ROTATION.toFloat()..WatermarkViewModel.MAX_ROTATION.toFloat(),
                    step = WatermarkViewModel.ROTATION_STEP.toFloat(),
                    valueText = {
                        val degrees = it.roundToInt()
                        when {
                            degrees > 0 -> resources.getString(R.string.watermark_rotation_ccw, degrees)
                            degrees < 0 -> resources.getString(R.string.watermark_rotation_cw, abs(degrees))
                            else -> resources.getString(R.string.watermark_rotation_none)
                        }
                    },
                    enabled = !busy,
                )
                ChoiceRow(
                    label = stringResource(R.string.watermark_position_label),
                    options = WatermarkPosition.entries,
                    selected = state.position,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onPositionChange,
                    enabled = !busy,
                )
                ChoiceRow(
                    label = stringResource(R.string.watermark_color_label),
                    options = WatermarkColor.entries,
                    selected = state.color,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onColorChange,
                    enabled = !busy,
                )
                ChoiceRow(
                    label = stringResource(R.string.watermark_scope_label),
                    options = PageScope.entries,
                    selected = state.scope,
                    optionLabel = { resources.getString(it.labelRes()) },
                    onSelect = onScopeChange,
                    enabled = !busy,
                )
                if (state.scope == PageScope.SELECTED) {
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
        }
        PrimaryButton(
            text = stringResource(R.string.watermark_button),
            onClick = onApply,
            enabled = file != null && !busy,
            disabledReason = if (file == null) needFile else null,
        )
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.watermark_success, result.markedCount, result.markedCount)
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

private fun WatermarkPosition.labelRes() = when (this) {
    WatermarkPosition.CENTER -> R.string.watermark_position_center
    WatermarkPosition.TOP -> R.string.watermark_position_top
    WatermarkPosition.BOTTOM -> R.string.watermark_position_bottom
    WatermarkPosition.TILED -> R.string.watermark_position_tiled
}

private fun WatermarkColor.labelRes() = when (this) {
    WatermarkColor.GRAY -> R.string.color_gray
    WatermarkColor.RED -> R.string.color_red
    WatermarkColor.BLUE -> R.string.color_blue
    WatermarkColor.BLACK -> R.string.color_black
}

private fun PageScope.labelRes() = when (this) {
    PageScope.ALL -> R.string.pages_scope_all
    PageScope.SELECTED -> R.string.pages_scope_selected
}
