package io.github.wzehirx.bpdf.feature.pagenumbers

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.NumberPosition
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.LabelledSlider
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PageChooser
import io.github.wzehirx.bpdf.ui.components.PageRangeField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.SinglePdfSection
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import io.github.wzehirx.bpdf.ui.components.toMessage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun PageNumbersScreen(onBack: () -> Unit, viewModel: PageNumbersViewModel = viewModel(factory = PageNumbersViewModel.Factory)) {
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
            val message = when {
                NumberFieldError.FORMAT_EMPTY in state.fieldErrors -> resources.getString(R.string.page_numbers_format_error)
                NumberFieldError.START_INVALID in state.fieldErrors -> resources.getString(R.string.page_numbers_start_error)
                else -> state.rangeError?.toMessage(resources)
            }
            message?.let(announce)
        }
    }

    PageNumbersScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onPositionChange = viewModel::setPosition,
        onFormatChange = viewModel::setFormat,
        onCustomFormatChange = viewModel::setCustomFormat,
        onStartChange = viewModel::setStartText,
        onFontSizeChange = viewModel::setFontSize,
        onScopeChange = viewModel::setScope,
        onRangeChange = viewModel::setRangeText,
        onApply = viewModel::addPageNumbers,
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
fun PageNumbersScreenContent(
    state: PageNumbersUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onPositionChange: (NumberPosition) -> Unit,
    onFormatChange: (NumberFormat) -> Unit,
    onCustomFormatChange: (String) -> Unit,
    onStartChange: (String) -> Unit,
    onFontSizeChange: (Int) -> Unit,
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
    val needFile = stringResource(R.string.page_numbers_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    val sampleTotal = state.pageCount.coerceAtLeast(1)
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_page_numbers_title), onBack = onBack) {
        Text(stringResource(R.string.page_numbers_instructions))
        SinglePdfSection(file, state.pageCount, state.importing, busy, onPickFile, onRemoveFile)
        if (file != null) {
            ChoiceRow(
                label = stringResource(R.string.page_numbers_position_label),
                options = NumberPosition.entries,
                selected = state.position,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onPositionChange,
                enabled = !busy,
            )
            ChoiceRow(
                label = stringResource(R.string.page_numbers_format_label),
                options = NumberFormat.entries,
                selected = state.format,
                optionLabel = { resources.getString(it.labelRes(), sampleTotal) },
                optionDescription = { it.descriptionRes()?.let { res -> resources.getString(res, sampleTotal) } },
                onSelect = onFormatChange,
                enabled = !busy,
            )
            if (state.format == NumberFormat.CUSTOM) {
                val formatError = if (NumberFieldError.FORMAT_EMPTY in state.fieldErrors) {
                    stringResource(R.string.page_numbers_format_error)
                } else {
                    null
                }
                OutlinedTextField(
                    value = state.customFormat,
                    onValueChange = onCustomFormatChange,
                    label = { Text(stringResource(R.string.page_numbers_custom_label)) },
                    isError = formatError != null,
                    supportingText = { Text(formatError ?: stringResource(R.string.page_numbers_custom_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { if (formatError != null) error(formatError) },
                )
            }
            val startError = if (NumberFieldError.START_INVALID in state.fieldErrors) {
                stringResource(R.string.page_numbers_start_error)
            } else {
                null
            }
            OutlinedTextField(
                value = state.startText,
                onValueChange = onStartChange,
                label = { Text(stringResource(R.string.page_numbers_start_label)) },
                isError = startError != null,
                supportingText = startError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (startError != null) error(startError) },
            )
            LabelledSlider(
                label = stringResource(R.string.text_size_label),
                value = state.fontSize.toFloat(),
                onValueChange = { onFontSizeChange(it.roundToInt()) },
                valueRange = PageNumbersViewModel.MIN_FONT_SIZE.toFloat()..PageNumbersViewModel.MAX_FONT_SIZE.toFloat(),
                step = 1f,
                valueText = { resources.getQuantityString(R.plurals.text_size_value, it.roundToInt(), it.roundToInt()) },
                enabled = !busy,
            )
            ChoiceRow(
                label = stringResource(R.string.page_numbers_scope_label),
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
        Button(
            onClick = onApply,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.page_numbers_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.page_numbers_success, result.numberedCount, result.numberedCount)
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

private fun NumberPosition.labelRes() = when (this) {
    NumberPosition.TOP_LEFT -> R.string.page_numbers_pos_top_left
    NumberPosition.TOP_CENTER -> R.string.page_numbers_pos_top_center
    NumberPosition.TOP_RIGHT -> R.string.page_numbers_pos_top_right
    NumberPosition.BOTTOM_LEFT -> R.string.page_numbers_pos_bottom_left
    NumberPosition.BOTTOM_CENTER -> R.string.page_numbers_pos_bottom_center
    NumberPosition.BOTTOM_RIGHT -> R.string.page_numbers_pos_bottom_right
}

/** Visible label; takes the page count as its argument where it shows an example. */
private fun NumberFormat.labelRes() = when (this) {
    NumberFormat.NUMBER -> R.string.page_numbers_fmt_number
    NumberFormat.NUMBER_OF_TOTAL -> R.string.page_numbers_fmt_number_total
    NumberFormat.LOCALIZED -> R.string.page_numbers_fmt_localized
    NumberFormat.CUSTOM -> R.string.page_numbers_fmt_custom
}

/** What a screen reader says instead of the label where the label contains a slash. */
private fun NumberFormat.descriptionRes(): Int? = when (this) {
    NumberFormat.NUMBER_OF_TOTAL -> R.string.page_numbers_fmt_number_total_spoken
    NumberFormat.LOCALIZED -> R.string.page_numbers_fmt_localized_spoken
    else -> null
}

private fun PageScope.labelRes() = when (this) {
    PageScope.ALL -> R.string.pages_scope_all
    PageScope.SELECTED -> R.string.pages_scope_selected
}
