package io.github.wzehirx.bpdf.feature.unlock

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
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.UnlockOperation
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

/** A finished unlock: the copy in [file] opens without a password. */
data class UnlockResult(val file: File, val pageCount: Int, val suggestedName: String)

data class UnlockUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    /** True when the chosen PDF has neither a password nor restrictions, so there is nothing to remove. */
    val notProtected: Boolean = false,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    val operation: OperationState<UnlockResult> = OperationState.Idle,
)

class UnlockViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val notProtected: Boolean = false,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<UnlockResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<UnlockUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        UnlockUiState(
            file = l.file,
            pageCount = l.pageCount,
            notProtected = l.notProtected,
            importing = l.pendingImports > 0,
            saving = l.saving,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UnlockUiState())

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
                val (pages, encrypted) = try {
                    val pages = passwordGate.open(imported)
                    if (pages == null) {
                        // The user gave up on the password prompt: keep whatever was open before.
                        imported.file.delete()
                        return@launch
                    }
                    val password = passwordGate.passwordOf(imported.file)
                    pages to withContext(ioDispatcher) { PdfLoader.load(imported.file, password).use { it.isEncrypted } }
                } catch (t: Throwable) {
                    imported.file.delete()
                    throw t
                }
                val old = local.value.file
                local.update { it.copy(file = imported, pageCount = pages, notProtected = !encrypted) }
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
        local.update { it.copy(file = null, pageCount = 0, notProtected = false) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun unlock() {
        val l = local.value
        val file = l.file ?: return
        if (busy || l.notProtected) return
        resetResult()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "unlocked", "pdf")
        val password = passwordGate.passwordOf(file.file).orEmpty()
        val pages = l.pageCount
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                progress(0.1f)
                UnlockOperation.run(file.file, password, out, isCancelled = { !context.isActive })
                UnlockResult(out, pages, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the unlock, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else unlock()
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
            }
        }
    }

    /** A content Uri for sharing the unlocked file, or null when there is no result. */
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
        passwordGate.clear()
        val output = successValue()?.file
        runner.cancel()
        output?.delete()
        local.value.file?.file?.delete()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { UnlockViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
