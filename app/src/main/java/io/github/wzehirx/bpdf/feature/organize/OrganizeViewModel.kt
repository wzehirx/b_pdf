package io.github.wzehirx.bpdf.feature.organize

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.wzehirx.bpdf.BPdfApp
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.files.suggestName
import io.github.wzehirx.bpdf.core.pdf.OrganizeOperation
import io.github.wzehirx.bpdf.core.pdf.PageEdit
import io.github.wzehirx.bpdf.core.work.OperationRunner
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.core.work.PasswordGate
import io.github.wzehirx.bpdf.core.work.PasswordPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A finished run: the new document has [pageCount] pages. */
data class OrganizeResult(
    val file: File,
    val pageCount: Int,
    val suggestedName: String,
)

/**
 * What just happened to the page list, for the screen to announce. Positions are 1-based, as the
 * user sees them; a [page] is the page's number in the original document, which is also what the
 * page's cell says ("original page N"), so what is announced and what is read agree.
 */
sealed interface OrganizeEvent {
    data class Moved(val page: Int, val position: Int) : OrganizeEvent
    data class RotatedRight(val page: Int) : OrganizeEvent
    data class RotatedLeft(val page: Int) : OrganizeEvent
    data class Duplicated(val page: Int) : OrganizeEvent
    data class Deleted(val page: Int) : OrganizeEvent
    data object Undone : OrganizeEvent

    /** The only remaining page cannot be deleted. */
    data object LastPageBlocked : OrganizeEvent
}

data class OrganizeUiState(
    val file: ImportedFile? = null,
    /** Pages of the imported file. */
    val pageCount: Int = 0,
    /** The document being built: one entry per output page, in output order. */
    val edits: List<PageEdit> = emptyList(),
    /** How many changes [OrganizeViewModel.undo] can take back. */
    val undoDepth: Int = 0,
    val importing: Boolean = false,
    /** The cell (0-based) that should take screen-reader focus after a change; see [OrganizeViewModel.focusHandled]. */
    val focusIndex: Int? = null,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    /** True when an input needed a password: the saved copy has none. */
    val copyUnprotected: Boolean = false,
    val operation: OperationState<OrganizeResult> = OperationState.Idle,
) {
    val canUndo: Boolean get() = undoDepth > 0
}

class OrganizeViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val edits: List<PageEdit> = emptyList(),
        /** Earlier versions of [edits], oldest first. */
        val undo: List<List<PageEdit>> = emptyList(),
        /** An input needed a password, so the saved copy has none. */
        val protectedInput: Boolean = false,
        val pendingImports: Int = 0,
        val focusIndex: Int? = null,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<OrganizeResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)
    private val eventChannel = Channel<OrganizeEvent>(Channel.BUFFERED)

    val state: StateFlow<OrganizeUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        OrganizeUiState(
            file = l.file,
            pageCount = l.pageCount,
            edits = l.edits,
            undoDepth = l.undo.size,
            importing = l.pendingImports > 0,
            focusIndex = l.focusIndex,
            saving = l.saving,
            copyUnprotected = l.protectedInput,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OrganizeUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    /** One event per change to the page list (and per refused delete), for announcements. */
    val events: Flow<OrganizeEvent> = eventChannel.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or running. */
    fun pickFile(uris: List<Uri>) {
        val uri = uris.firstOrNull() ?: return
        if (busy) return
        resetResult()
        local.update { it.copy(pendingImports = it.pendingImports + 1) }
        viewModelScope.launch {
            try {
                val imported = fileRepository.importToCache(uri)
                val pages = try {
                    passwordGate.open(imported)
                } catch (t: Throwable) {
                    imported.file.delete()
                    throw t
                } ?: run {
                    // The user gave up on the password prompt: keep whatever was open before.
                    imported.file.delete()
                    return@launch
                }
                val old = local.value.file
                local.update {
                    it.copy(file = imported, protectedInput = passwordGate.wasEncrypted(imported.file), pageCount = pages, edits = List(pages) { i -> PageEdit(i) }, undo = emptyList(), focusIndex = null)
                }
                old?.let { passwordGate.forget(it.file); it.file.delete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                local.update { it.copy(pendingImports = it.pendingImports - 1) }
            }
        }
    }

    fun remove() {
        val file = local.value.file ?: return
        if (busy) return
        resetResult()
        local.update { it.copy(file = null, protectedInput = false, pageCount = 0, edits = emptyList(), undo = emptyList(), focusIndex = null) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    /** Swaps page [index] (0-based) with the one above it. */
    fun moveUp(index: Int) = moveTo(index, index - 1)

    /** Swaps page [index] (0-based) with the one below it. */
    fun moveDown(index: Int) = moveTo(index, index + 1)

    /** Moves page [from] so it ends up at index [to] (both 0-based); out-of-range or equal indices do nothing. */
    fun moveTo(from: Int, to: Int) = change { edits ->
        if (from !in edits.indices || to !in edits.indices || from == to) return@change null
        val next = edits.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        Change(next, OrganizeEvent.Moved(page = moved.sourceIndex + 1, position = to + 1), focus = to)
    }

    fun rotateRight(index: Int) = change { edits ->
        turned(edits, index, 90)?.let { Change(it, OrganizeEvent.RotatedRight(edits[index].sourceIndex + 1), focus = index) }
    }

    fun rotateLeft(index: Int) = change { edits ->
        turned(edits, index, 270)?.let { Change(it, OrganizeEvent.RotatedLeft(edits[index].sourceIndex + 1), focus = index) }
    }

    /** Inserts a copy of page [index] (with its rotation) right after it; focus goes to the copy. */
    fun duplicate(index: Int) = change { edits ->
        if (index !in edits.indices) return@change null
        Change(
            edits.take(index + 1) + edits[index] + edits.drop(index + 1),
            OrganizeEvent.Duplicated(edits[index].sourceIndex + 1),
            focus = index + 1,
        )
    }

    /** Takes page [index] out; the only remaining page is kept and [OrganizeEvent.LastPageBlocked] is sent. */
    fun delete(index: Int) {
        if (busy || local.value.file == null) return
        val edits = local.value.edits
        if (index !in edits.indices) return
        if (edits.size == 1) {
            eventChannel.trySend(OrganizeEvent.LastPageBlocked)
            return
        }
        // The deleted cell is gone: focus goes to the page that took its place (or the new last one).
        change { current ->
            Change(
                current.take(index) + current.drop(index + 1),
                OrganizeEvent.Deleted(current[index].sourceIndex + 1),
                focus = minOf(index, current.size - 2),
            )
        }
    }

    /** Takes back the latest change. */
    fun undo() {
        if (busy) return
        val previous = local.value.undo.lastOrNull() ?: return
        resetResult()
        local.update { it.copy(edits = previous, undo = it.undo.dropLast(1)) }
        eventChannel.trySend(OrganizeEvent.Undone)
    }

    private fun turned(edits: List<PageEdit>, index: Int, degrees: Int): List<PageEdit>? {
        val edit = edits.getOrNull(index) ?: return null
        return edits.toMutableList().also { it[index] = edit.copy(rotation = (edit.rotation + degrees) % 360) }
    }

    /** A new page list, what to announce about it and the cell that takes focus. */
    private class Change(val edits: List<PageEdit>, val event: OrganizeEvent, val focus: Int)

    /** Applies [transform] to the page list (null = nothing to do), remembering the old list for [undo]. */
    private fun change(transform: (List<PageEdit>) -> Change?) {
        if (busy) return
        val l = local.value
        if (l.file == null) return
        val change = transform(l.edits) ?: return
        resetResult()
        local.update {
            it.copy(edits = change.edits, undo = (it.undo + listOf(l.edits)).takeLast(MAX_UNDO), focusIndex = change.focus)
        }
        eventChannel.trySend(change.event)
    }

    /** The screen has moved focus to [OrganizeUiState.focusIndex]. */
    fun focusHandled() = local.update { it.copy(focusIndex = null) }

    fun organize() {
        val l = local.value
        val file = l.file ?: return
        if (busy || l.edits.isEmpty()) return
        resetResult()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "organized", "pdf")
        val edits = l.edits
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                val count = OrganizeOperation.run(file.file, edits, out, password, progress = progress, isCancelled = { !context.isActive })
                OrganizeResult(out, count, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the operation, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else organize()
    }

    /** Answers the password prompt; a wrong password keeps the prompt open with `wrong` set. */
    fun submitPassword(password: String) = passwordGate.submit(password)

    /** The user gave up on the password prompt; the picked file is discarded. */
    fun dismissPassword() = passwordGate.dismiss()

    /** The password that opened [file] (for thumbnails), or null when it needs none. */
    fun passwordOf(file: File): String? = passwordGate.passwordOf(file)

    fun cancel() = runner.cancel()

    fun save(target: Uri) {
        val result = successValue() ?: return
        if (busy) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                fileRepository.export(result.file, target)
                saved.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                local.update { it.copy(saving = false) }
                // The screen was left mid-save: the result could not be deleted then, so do it now.
                if (cleared) result.file.delete()
            }
        }
    }

    /** A content Uri for sharing the result, or null when there is none. */
    suspend fun prepareShare(): Uri? {
        val result = successValue() ?: return null
        return withContext(ioDispatcher) { fileRepository.shareUri(result.file, result.suggestedName) }
    }

    fun clearResult() {
        if (!local.value.saving) resetResult()
    }

    private fun successValue() = (runner.state.value as? OperationState.Success)?.value

    private fun resetResult() {
        // Drop the result (and its output file) the inputs no longer match.
        successValue()?.file?.delete()
        runner.reset()
        local.update { it.copy(error = null) }
    }

    /** Set in [onCleared]; a save still running then deletes its own source when it ends. */
    @Volatile private var cleared = false

    override fun onCleared() {
        cleared = true
        passwordGate.clear()
        val output = successValue()?.file
        runner.cancel()
        if (!local.value.saving) output?.delete()
        local.value.file?.file?.delete()
    }

    companion object {
        /** How many changes [undo] can step back through. */
        const val MAX_UNDO = 20

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { OrganizeViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
