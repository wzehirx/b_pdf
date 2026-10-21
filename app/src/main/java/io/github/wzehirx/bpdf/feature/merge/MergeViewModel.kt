package io.github.wzehirx.bpdf.feature.merge

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
import io.github.wzehirx.bpdf.core.pdf.MergeOperation
import io.github.wzehirx.bpdf.core.work.OperationRunner
import io.github.wzehirx.bpdf.core.work.OperationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A finished merge: the output [file] plus what the UI needs to describe and save it. */
data class MergeResult(
    val file: File,
    val pageCount: Int,
    val fileCount: Int,
    val suggestedName: String,
)

data class MergeUiState(
    val files: List<ImportedFile> = emptyList(),
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    val operation: OperationState<MergeResult> = OperationState.Idle,
)

class MergeViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val files: List<ImportedFile> = emptyList(),
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<MergeResult>(viewModelScope, ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<MergeUiState> = combine(local, runner.state) { l, op ->
        MergeUiState(
            files = l.files,
            importing = l.pendingImports > 0,
            saving = l.saving,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MergeUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Ignored while importing or merging: the inputs must not change under a running operation. */
    fun addFiles(uris: List<Uri>) {
        if (busy) return
        resetResult()
        local.update { it.copy(pendingImports = it.pendingImports + 1) }
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    val imported = fileRepository.importToCache(uri)
                    local.update { it.copy(files = it.files + imported) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                local.update { it.copy(pendingImports = it.pendingImports - 1) }
            }
        }
    }

    fun move(from: Int, to: Int) {
        val files = local.value.files
        if (busy || from !in files.indices || to !in files.indices || from == to) return
        resetResult()
        local.update { it.copy(files = it.files.toMutableList().apply { add(to, removeAt(from)) }) }
    }

    fun remove(index: Int) {
        val files = local.value.files
        if (busy || index !in files.indices) return
        resetResult()
        local.update { it.copy(files = it.files.filterIndexed { i, _ -> i != index }) }
        files[index].file.delete()
    }

    fun merge() {
        val files = local.value.files
        if (busy || files.size < 2) return
        resetResult()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(files.first().displayName, "merged", "pdf")
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                val pages = MergeOperation.run(
                    files.map { it.file }, out, progress = progress, isCancelled = { !context.isActive },
                )
                MergeResult(out, pages, files.size, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the merge, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else merge()
    }

    fun cancel() = runner.cancel()

    fun save(target: Uri) {
        val result = (runner.state.value as? OperationState.Success)?.value ?: return
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

    /** A content Uri for sharing the merged file, or null when there is no result. */
    suspend fun prepareShare(): Uri? {
        val result = (runner.state.value as? OperationState.Success)?.value ?: return null
        return withContext(ioDispatcher) { fileRepository.shareUri(result.file, result.suggestedName) }
    }

    fun clearResult() {
        if (!local.value.saving) resetResult()
    }

    private fun resetResult() {
        // Drop the result (and its output file) the inputs no longer match.
        (runner.state.value as? OperationState.Success)?.value?.file?.delete()
        runner.reset()
        local.update { it.copy(error = null) }
    }

    override fun onCleared() {
        val output = (runner.state.value as? OperationState.Success)?.value?.file
        runner.cancel()
        output?.delete()
        local.value.files.forEach { it.file.delete() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MergeViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
