package io.github.wzehirx.bpdf.feature.protect

import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.PdfPermissions
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.ui.components.OperationStatusPanel
import io.github.wzehirx.bpdf.ui.components.PasswordField
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** Stateful entry point: wires the ViewModel to system pickers, saving and sharing. */
@Composable
fun ProtectScreen(onBack: () -> Unit, viewModel: ProtectViewModel = viewModel(factory = ProtectViewModel.Factory)) {
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
    // Pressing Protect with unusable passwords starts no operation, so say what is wrong.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) state.passwordError?.let { announce(resources.getString(it.messageRes())) }
    }

    ProtectScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onUserPasswordChange = viewModel::setUserPassword,
        onConfirmPasswordChange = viewModel::setConfirmPassword,
        onOwnerPasswordChange = viewModel::setOwnerPassword,
        onPermissionsChange = viewModel::setPermissions,
        onProtect = viewModel::protect,
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
fun ProtectScreenContent(
    state: ProtectUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onUserPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onOwnerPasswordChange: (String) -> Unit,
    onPermissionsChange: (PdfPermissions) -> Unit,
    onProtect: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.protect_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    // Deliberately not rememberSaveable: passwords never go into saved instance state.
    var showPasswords by remember { mutableStateOf(false) }
    val userFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    // A refused Protect puts focus on the field that needs fixing.
    LaunchedEffect(state.failedAttempts) {
        if (state.failedAttempts > 0) {
            when (state.passwordError) {
                ProtectError.EMPTY -> runCatching { userFocus.requestFocus() }
                ProtectError.MISMATCH -> runCatching { confirmFocus.requestFocus() }
                null -> Unit
            }
        }
    }
    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(title = stringResource(R.string.tool_protect_title), onBack = onBack) {
        Text(stringResource(R.string.protect_instructions))
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
            PasswordField(
                value = state.userPassword,
                onValueChange = onUserPasswordChange,
                label = stringResource(R.string.protect_password_label),
                visible = showPasswords,
                modifier = Modifier.focusRequester(userFocus),
                enabled = !busy,
                errorMessage = if (state.passwordError == ProtectError.EMPTY) resources.getString(R.string.protect_error_empty) else null,
            )
            PasswordField(
                value = state.confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = stringResource(R.string.protect_confirm_label),
                visible = showPasswords,
                modifier = Modifier.focusRequester(confirmFocus),
                enabled = !busy,
                errorMessage = if (state.passwordError == ProtectError.MISMATCH) resources.getString(R.string.protect_error_mismatch) else null,
            )
            PasswordField(
                value = state.ownerPassword,
                onValueChange = onOwnerPasswordChange,
                label = stringResource(R.string.protect_owner_label),
                visible = showPasswords,
                enabled = !busy,
                hint = stringResource(R.string.protect_owner_hint),
                imeAction = ImeAction.Done,
            )
            SwitchRow(
                label = stringResource(R.string.protect_show_passwords),
                checked = showPasswords,
                stateText = stringResource(if (showPasswords) R.string.password_state_visible else R.string.password_state_hidden),
                enabled = true,
                onChange = { showPasswords = it },
            )
            Text(
                stringResource(R.string.protect_permissions_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            PermissionSwitch(R.string.protect_perm_print, state.permissions.print, !busy) {
                onPermissionsChange(state.permissions.copy(print = it))
            }
            PermissionSwitch(R.string.protect_perm_copy, state.permissions.copy, !busy) {
                onPermissionsChange(state.permissions.copy(copy = it))
            }
            PermissionSwitch(R.string.protect_perm_modify, state.permissions.modify, !busy) {
                onPermissionsChange(state.permissions.copy(modify = it))
            }
        }
        Button(
            onClick = onProtect,
            enabled = file != null && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.protect_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.protect_success, result.pageCount, result.pageCount)
            },
            onSave = onSave,
            onShare = onShare,
            actionsEnabled = !state.saving,
            onCancel = onCancel,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun PermissionSwitch(labelRes: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(
        label = stringResource(labelRes),
        checked = checked,
        stateText = stringResource(if (checked) R.string.protect_perm_allowed else R.string.protect_perm_blocked),
        enabled = enabled,
        onChange = onChange,
    )
}

/** One labelled switch: the whole row toggles, and the state is spoken as text rather than "on/off". */
@Composable
private fun SwitchRow(label: String, checked: Boolean, stateText: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = stateText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun ProtectError.messageRes() = when (this) {
    ProtectError.EMPTY -> R.string.protect_error_empty
    ProtectError.MISMATCH -> R.string.protect_error_mismatch
}
