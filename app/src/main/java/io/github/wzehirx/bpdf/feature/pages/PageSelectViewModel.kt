package io.github.wzehirx.bpdf.feature.pages

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
import io.github.wzehirx.bpdf.core.pdf.PageRanges
import io.github.wzehirx.bpdf.core.pdf.PageSelectOperation
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.work.OperationRunner
import io.github.wzehirx.bpdf.core.work.OperationState
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

/** Which of the two page-selection tools a screen/ViewModel instance runs. */
enum class PageSelectMode { REMOVE, EXTRACT }

/**
 * A finished run: [selectedCount] pages were removed or extracted and the output has
 * [remainingCount] pages (for extraction that equals [selectedCount]).
 */
data class PageSelectResult(
    val file: File,
    val selectedCount: Int,
    val remainingCount: Int,
    val suggestedName: String,
)

data class PageSelectUiState(
    val mode: PageSelectMode = PageSelectMode.REMOVE,
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val rangeText: String = "",
    /** Live error for [rangeText]; a blank field only counts as an error after an attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    /** Removing: the range is valid but covers every page, which would leave an empty PDF. */
    val removesAllPages: Boolean = false,
    /** Number of times the action was pressed with invalid input; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    val operation: OperationState<PageSelectResult> = OperationState.Idle,
)

class PageSelectViewModel(
    private val mode: PageSelectMode,
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val rangeText: String = "",
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<PageSelectResult>(viewModelScope, ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<PageSelectUiState> = combine(local, runner.state) { l, op ->
        val selection = if (l.file != null) selectionOf(l.rangeText, l.pageCount, l.attempted) else null
        PageSelectUiState(
            mode = mode,
            file = l.file,
            pageCount = l.pageCount,
            rangeText = l.rangeText,
            rangeError = (selection as? Selection.Invalid)?.error,
            removesAllPages = selection is Selection.RemovesAll,
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PageSelectUiState(mode = mode))

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    private sealed interface Selection {
        data class Valid(val pages: List<Int>) : Selection
        data class Invalid(val error: PdfError.InvalidRange) : Selection
        data object RemovesAll : Selection
        data object Blank : Selection
    }

    /** A blank field is only an error once the user tried to run ([attempted]). */
    private fun selectionOf(text: String, pageCount: Int, attempted: Boolean): Selection? {
        val pages = try {
            PageRanges.parse(text, pageCount)
        } catch (e: PdfError.InvalidRange) {
            return if (text.isBlank() && !attempted) Selection.Blank else Selection.Invalid(e)
        }
        return if (mode == PageSelectMode.REMOVE && pages.size >= pageCount) Selection.RemovesAll else Selection.Valid(pages)
    }

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
                    withContext(Dispatchers.IO) { PdfLoader.pageCount(imported.file) }
                } catch (t: Throwable) {
                    imported.file.delete()
                    throw t
                }
                val old = local.value.file
                local.update { it.copy(file = imported, pageCount = pages, attempted = false) }
                old?.file?.delete()
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
        local.update { it.copy(file = null, pageCount = 0, attempted = false) }
        file.file.delete()
    }

    fun setRangeText(text: String) {
        if (busy) return
        resetResult()
        local.update { it.copy(rangeText = text, attempted = false) }
    }

    fun run() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val pages = (selectionOf(l.rangeText, l.pageCount, attempted = true) as? Selection.Valid)?.pages
            ?: return rejectInput()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, if (mode == PageSelectMode.REMOVE) "removed" else "extracted", "pdf")
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                val resultPages = when (mode) {
                    PageSelectMode.REMOVE ->
                        PageSelectOperation.remove(file.file, pages, out, progress = progress, isCancelled = { !context.isActive })
                    PageSelectMode.EXTRACT ->
                        PageSelectOperation.extract(file.file, pages, out, progress = progress, isCancelled = { !context.isActive })
                }
                PageSelectResult(out, pages.size, resultPages, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the operation, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else run()
    }

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

    override fun onCleared() {
        val output = successValue()?.file
        runner.cancel()
        output?.delete()
        local.value.file?.file?.delete()
    }

    companion object {
        fun factory(mode: PageSelectMode): ViewModelProvider.Factory = viewModelFactory {
            initializer { PageSelectViewModel(mode, (this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
