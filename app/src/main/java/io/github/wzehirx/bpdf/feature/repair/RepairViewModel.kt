package io.github.wzehirx.bpdf.feature.repair

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
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.RepairOperation
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

/** A finished repair: the rebuilt copy in [file] holds [pagesRecovered] pages. */
data class RepairOutcome(val file: File, val pagesRecovered: Int, val suggestedName: String)

data class RepairUiState(
    val file: ImportedFile? = null,
    /** Pages found in the chosen file as it is, or null when it is too damaged to read (the usual reason to repair). */
    val pageCount: Int? = null,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    /** True when an input needed a password: the saved copy has none. */
    val copyUnprotected: Boolean = false,
    val operation: OperationState<RepairOutcome> = OperationState.Idle,
)

class RepairViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int? = null,
        /** An input needed a password, so the saved copy has none. */
        val protectedInput: Boolean = false,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<RepairOutcome>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher) { file, password ->
        // A damaged file is exactly what this tool is for, so it is not turned away here.
        try {
            PdfLoader.pageCount(file, password)
        } catch (_: PdfError.Corrupt) {
            UNREADABLE
        }
    }
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<RepairUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        RepairUiState(
            file = l.file,
            pageCount = l.pageCount,
            importing = l.pendingImports > 0,
            saving = l.saving,
            copyUnprotected = l.protectedInput,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RepairUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]; a protected file asks for its password first. Ignored while busy. */
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
                local.update { it.copy(file = imported, protectedInput = passwordGate.passwordOf(imported.file) != null, pageCount = pages.takeIf { it != UNREADABLE }) }
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
        local.update { it.copy(file = null, protectedInput = false, pageCount = null) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun repair() {
        val file = local.value.file ?: return
        if (busy) return
        resetResult()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "repaired", "pdf")
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                progress(0.1f)
                val result = RepairOperation.run(file.file, out, password, isCancelled = { !context.isActive })
                RepairOutcome(out, result.pagesRecovered, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the repair, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else repair()
    }

    /** Answers the password prompt; a wrong password keeps the prompt open with `wrong` set. */
    fun submitPassword(password: String) = passwordGate.submit(password)

    /** The user gave up on the password prompt; the picked file is discarded. */
    fun dismissPassword() = passwordGate.dismiss()

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

    /** A content Uri for sharing the repaired file, or null when there is no result. */
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
        /** Page count the password check reports for a file that cannot be parsed. */
        private const val UNREADABLE = -1

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RepairViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
