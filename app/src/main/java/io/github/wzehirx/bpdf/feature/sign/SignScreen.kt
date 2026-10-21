package io.github.wzehirx.bpdf.feature.sign

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.SignPosition
import io.github.wzehirx.bpdf.core.pdf.SignaturePoint
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.HandOffEffect
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.SinglePdfSection
import io.github.wzehirx.bpdf.ui.components.SwitchRow
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun SignScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: SignViewModel = viewModel(factory = SignViewModel.Factory),
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
            val message = when {
                SignFieldError.TEXT_MISSING in state.fieldErrors -> resources.getString(R.string.sign_text_error)
                SignFieldError.DRAWING_EMPTY in state.fieldErrors -> resources.getString(R.string.sign_pad_error)
                SignFieldError.PAGE_INVALID in state.fieldErrors -> resources.getString(R.string.sign_page_error, state.pageCount)
                else -> null
            }
            message?.let(announce)
        }
    }

    SignScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onModeChange = viewModel::setMode,
        onTypedTextChange = viewModel::setTypedText,
        onStroke = viewModel::addStroke,
        onClearDrawing = {
            viewModel.clearDrawing()
            announce(resources.getString(R.string.sign_pad_cleared))
        },
        onPageChange = viewModel::setPageText,
        onPositionChange = viewModel::setPosition,
        onAddDateChange = viewModel::setAddDate,
        onSign = viewModel::sign,
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
fun SignScreenContent(
    state: SignUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onModeChange: (SignMode) -> Unit,
    onTypedTextChange: (String) -> Unit,
    onStroke: (List<SignaturePoint>) -> Unit,
    onClearDrawing: () -> Unit,
    onPageChange: (String) -> Unit,
    onPositionChange: (SignPosition) -> Unit,
    onAddDateChange: (Boolean) -> Unit,
    onSign: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.sign_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_sign_title), onBack = onBack) {
        Text(stringResource(R.string.sign_instructions))
        SinglePdfSection(file, state.pageCount, state.importing, busy, onPickFile, onRemoveFile)
        if (file != null) {
            ChoiceRow(
                label = stringResource(R.string.sign_mode_label),
                options = SignMode.entries,
                selected = state.mode,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onModeChange,
                enabled = !busy,
            )
            when (state.mode) {
                SignMode.TYPED -> {
                    val textError = if (SignFieldError.TEXT_MISSING in state.fieldErrors) stringResource(R.string.sign_text_error) else null
                    OutlinedTextField(
                        value = state.typedText,
                        onValueChange = onTypedTextChange,
                        label = { Text(stringResource(R.string.sign_text_label)) },
                        isError = textError != null,
                        supportingText = textError?.let { { Text(it) } },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { if (textError != null) error(textError) },
                    )
                }
                SignMode.DRAWN -> DrawnSignature(state, busy, onStroke, onClearDrawing)
            }
            val pageError = if (SignFieldError.PAGE_INVALID in state.fieldErrors) {
                stringResource(R.string.sign_page_error, state.pageCount)
            } else {
                null
            }
            OutlinedTextField(
                value = state.pageText,
                onValueChange = onPageChange,
                label = { Text(stringResource(R.string.sign_page_label)) },
                isError = pageError != null,
                supportingText = { Text(pageError ?: stringResource(R.string.sign_page_hint, state.pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (pageError != null) error(pageError) },
            )
            ChoiceRow(
                label = stringResource(R.string.sign_position_label),
                options = SignPosition.entries.sortedBy { it.order() },
                selected = state.position,
                optionLabel = { resources.getString(it.labelRes()) },
                onSelect = onPositionChange,
                enabled = !busy,
            )
            SwitchRow(
                label = stringResource(R.string.sign_date_label),
                checked = state.addDate,
                stateText = stringResource(if (state.addDate) R.string.sign_date_on else R.string.sign_date_off),
                enabled = !busy,
                onChange = onAddDateChange,
            )
        }
        Button(
            onClick = onSign,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.sign_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result -> resources.getString(R.string.sign_success, result.page) },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            unprotectedOutput = state.copyUnprotected,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun DrawnSignature(state: SignUiState, busy: Boolean, onStroke: (List<SignaturePoint>) -> Unit, onClear: () -> Unit) {
    val drawingError = SignFieldError.DRAWING_EMPTY in state.fieldErrors
    val errorText = stringResource(R.string.sign_pad_error)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.sign_pad_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.sign_pad_hint), style = MaterialTheme.typography.bodyMedium)
        SignaturePad(
            strokes = state.strokes,
            enabled = !busy,
            onStroke = onStroke,
            modifier = Modifier.semantics { if (drawingError) error(errorText) },
        )
        if (drawingError) Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = onClear,
            enabled = state.hasDrawing && !busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.sign_pad_clear))
        }
    }
}

private fun SignMode.labelRes() = when (this) {
    SignMode.TYPED -> R.string.sign_mode_typed
    SignMode.DRAWN -> R.string.sign_mode_drawn
}

/** Reading order of the position choices: top row, middle, bottom row. */
private fun SignPosition.order() = when (this) {
    SignPosition.TOP_LEFT -> 0
    SignPosition.TOP_RIGHT -> 1
    SignPosition.CENTER -> 2
    SignPosition.BOTTOM_LEFT -> 3
    SignPosition.BOTTOM_CENTER -> 4
    SignPosition.BOTTOM_RIGHT -> 5
}

private fun SignPosition.labelRes() = when (this) {
    SignPosition.TOP_LEFT -> R.string.sign_pos_top_left
    SignPosition.TOP_RIGHT -> R.string.sign_pos_top_right
    SignPosition.CENTER -> R.string.sign_pos_center
    SignPosition.BOTTOM_LEFT -> R.string.sign_pos_bottom_left
    SignPosition.BOTTOM_CENTER -> R.string.sign_pos_bottom_center
    SignPosition.BOTTOM_RIGHT -> R.string.sign_pos_bottom_right
}
