package io.github.wzehirx.bpdf.feature.organize

import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import io.github.wzehirx.bpdf.ui.components.PageGrid
import io.github.wzehirx.bpdf.ui.components.PasswordPromptDialog
import io.github.wzehirx.bpdf.ui.components.PickFilesButton
import io.github.wzehirx.bpdf.ui.components.SelectedPdfCard
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.rememberPageThumbnails
import io.github.wzehirx.bpdf.ui.components.rememberSaveDocumentLauncher
import io.github.wzehirx.bpdf.ui.components.shareFile
import kotlinx.coroutines.launch

/** What a screen reader says after [this] change; pages and positions are 1-based. */
internal fun OrganizeEvent.message(resources: Resources): String = when (this) {
    is OrganizeEvent.Moved -> resources.getString(R.string.organize_moved, page, position)
    is OrganizeEvent.RotatedRight -> resources.getString(R.string.organize_rotated_right, page)
    is OrganizeEvent.RotatedLeft -> resources.getString(R.string.organize_rotated_left, page)
    is OrganizeEvent.Duplicated -> resources.getString(R.string.organize_duplicated, page)
    is OrganizeEvent.Deleted -> resources.getString(R.string.organize_deleted, page)
    OrganizeEvent.Undone -> resources.getString(R.string.organize_undone)
    OrganizeEvent.LastPageBlocked -> resources.getString(R.string.organize_last_page_blocked)
}

/** Stateful entry point: wires the ViewModel to system pickers, saving, sharing and announcements. */
@Composable
fun OrganizeScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    viewModel: OrganizeViewModel = viewModel(factory = OrganizeViewModel.Factory),
) {
    HandOffEffect(initialUri) { viewModel.pickFile(listOf(it)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.organize_undo)
    val suggestedName = (state.operation as? OperationState.Success)?.value?.suggestedName.orEmpty()

    val launchSave = rememberSaveDocumentLauncher("application/pdf") { uri -> uri?.let(viewModel::save) }
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { announce(resources.getString(R.string.saved_announcement)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            if (event is OrganizeEvent.Deleted) {
                // The snackbar is a live region, so it speaks the message itself; it also carries Undo.
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message(resources),
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undo()
                }
            } else {
                announce(event.message(resources))
            }
        }
    }

    OrganizeScreenContent(
        state = state,
        onBack = onBack,
        onPickFile = viewModel::pickFile,
        onRemoveFile = viewModel::remove,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onMoveTo = viewModel::moveTo,
        onRotateLeft = viewModel::rotateLeft,
        onRotateRight = viewModel::rotateRight,
        onDuplicate = viewModel::duplicate,
        onDelete = viewModel::delete,
        onUndo = viewModel::undo,
        onOrganize = viewModel::organize,
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
        onFocusHandled = viewModel::focusHandled,
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        snackbarHostState = snackbarHostState,
        thumbnail = rememberPageThumbnails(state.file?.file, state.file?.let { viewModel.passwordOf(it.file) }),
    )
}

/** Stateless screen body, driven entirely by [state]; testable without pickers. */
@Composable
fun OrganizeScreenContent(
    state: OrganizeUiState,
    onBack: () -> Unit,
    onPickFile: (List<Uri>) -> Unit,
    onRemoveFile: () -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveTo: (from: Int, to: Int) -> Unit,
    onRotateLeft: (Int) -> Unit,
    onRotateRight: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onUndo: () -> Unit,
    onOrganize: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onFocusHandled: () -> Unit = {},
    onSubmitPassword: (String) -> Unit = {},
    onDismissPassword: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    thumbnail: suspend (Int) -> Bitmap? = { null },
) {
    val resources = LocalResources.current
    val needFile = stringResource(R.string.organize_need_file)
    val busy = state.importing || state.saving || state.operation is OperationState.Running
    val file = state.file
    val edits = state.edits
    var moveFrom by rememberSaveable { mutableStateOf<Int?>(null) }

    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)
    val moveTo = stringResource(R.string.action_move_to_position)
    val rotateLeft = stringResource(R.string.action_rotate_left)
    val rotateRight = stringResource(R.string.action_rotate_right)
    val duplicate = stringResource(R.string.action_duplicate)
    val delete = stringResource(R.string.action_delete)
    // The same list backs the screen-reader actions and the per-page menu.
    val pageActions: (Int) -> List<CustomAccessibilityAction> = { i ->
        buildList {
            if (i > 0) add(CustomAccessibilityAction(moveUp) { onMoveUp(i); true })
            if (i < edits.size - 1) add(CustomAccessibilityAction(moveDown) { onMoveDown(i); true })
            add(CustomAccessibilityAction(moveTo) { moveFrom = i; true })
            add(CustomAccessibilityAction(rotateLeft) { onRotateLeft(i); true })
            add(CustomAccessibilityAction(rotateRight) { onRotateRight(i); true })
            add(CustomAccessibilityAction(duplicate) { onDuplicate(i); true })
            add(CustomAccessibilityAction(delete) { onDelete(i); true })
        }
    }

    moveFrom?.takeIf { it in edits.indices && !busy }?.let { from ->
        MoveToPositionDialog(
            current = from + 1,
            count = edits.size,
            onConfirm = { position ->
                moveFrom = null
                onMoveTo(from, position - 1)
            },
            onDismiss = { moveFrom = null },
        )
    }

    PasswordPromptDialog(state.passwordPrompt, onSubmitPassword, onDismissPassword)
    ToolScaffold(
        title = stringResource(R.string.tool_organize_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        Text(stringResource(R.string.organize_instructions))
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
                summary = pluralStringResource(R.plurals.single_file_summary, state.pageCount, file.displayName, state.pageCount),
                removeActionLabel = stringResource(R.string.single_file_remove_action),
                removeButtonDescription = stringResource(R.string.single_file_remove, file.displayName),
                enabled = !busy,
                onRemove = onRemoveFile,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onUndo,
                    enabled = state.canUndo && !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.organize_undo)) }
                Text(
                    pluralStringResource(R.plurals.organize_summary, edits.size, edits.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PageGrid(
                pageCount = edits.size,
                thumbnail = { i -> thumbnail(edits[i].sourceIndex) },
                selected = emptySet(),
                onToggle = null,
                rotations = edits.withIndex().associate { (i, edit) -> i to edit.rotation },
                pageActions = pageActions,
                enabled = !busy,
                overflowMenu = true,
                // A cell keeps its picture when pages are reordered around it.
                thumbnailKey = { i -> edits.getOrNull(i)?.sourceIndex ?: i },
                gridHeight = 520.dp,
                // Cells say which original page they hold, so the order can be checked after any edit.
                cellLabel = { i ->
                    val edit = edits[i]
                    val isCopy = edits.take(i).any { it.sourceIndex == edit.sourceIndex }
                    resources.getString(
                        if (isCopy) R.string.organize_cell_copy else R.string.organize_cell,
                        i + 1, edits.size, edit.sourceIndex + 1,
                    )
                },
                focusIndex = state.focusIndex,
                onFocusHandled = onFocusHandled,
            )
        }
        Button(
            onClick = onOrganize,
            enabled = file != null && edits.isNotEmpty() && !busy,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { if (file == null) stateDescription = needFile },
        ) {
            Text(stringResource(R.string.organize_button))
        }
        OperationStatusPanel(
            state = state.operation,
            successText = { result ->
                resources.getQuantityString(R.plurals.organize_success, result.pageCount, result.pageCount)
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
