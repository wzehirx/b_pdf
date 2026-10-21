package io.github.wzehirx.bpdf.feature.protect

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
import io.github.wzehirx.bpdf.core.pdf.PdfPermissions
import io.github.wzehirx.bpdf.core.pdf.ProtectOperation
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

/** A finished protection: [file] asks for the password before it opens. */
data class ProtectResult(val file: File, val pageCount: Int, val suggestedName: String)

/** What is wrong with the passwords typed so far; shown only after the user pressed Protect. */
enum class ProtectError { EMPTY, MISMATCH }

data class ProtectUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val userPassword: String = "",
    val confirmPassword: String = "",
    val ownerPassword: String = "",
    val permissions: PdfPermissions = PdfPermissions(),
    val passwordError: ProtectError? = null,
    /** Number of times Protect was pressed with unusable passwords; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the password of the chosen (already protected) file. */
    val passwordPrompt: PasswordPrompt? = null,
    val operation: OperationState<ProtectResult> = OperationState.Idle,
) {
    // Never print the passwords the user typed.
    override fun toString() = "ProtectUiState(file=$file, pageCount=$pageCount, passwordError=$passwordError, " +
        "importing=$importing, saving=$saving, operation=$operation)"
}

class ProtectViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val userPassword: String = "",
        val confirmPassword: String = "",
        val ownerPassword: String = "",
        val permissions: PdfPermissions = PdfPermissions(),
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<ProtectResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<ProtectUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        ProtectUiState(
            file = l.file,
            pageCount = l.pageCount,
            userPassword = l.userPassword,
            confirmPassword = l.confirmPassword,
            ownerPassword = l.ownerPassword,
            permissions = l.permissions,
            passwordError = if (l.file != null && l.attempted) passwordErrorOf(l) else null,
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProtectUiState())

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
                local.update { it.copy(file = imported, pageCount = pages, attempted = false) }
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
        local.update { it.copy(file = null, pageCount = 0, attempted = false) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun setUserPassword(text: String) = edit { it.copy(userPassword = text, attempted = false) }

    fun setConfirmPassword(text: String) = edit { it.copy(confirmPassword = text, attempted = false) }

    fun setOwnerPassword(text: String) = edit { it.copy(ownerPassword = text) }

    fun setPermissions(permissions: PdfPermissions) = edit { it.copy(permissions = permissions) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun protect() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        if (passwordErrorOf(l) != null) {
            local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }
            return
        }
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "protected", "pdf")
        val inputPassword = passwordGate.passwordOf(file.file)
        val pages = l.pageCount
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                progress(0.1f)
                ProtectOperation.run(
                    file.file, l.userPassword, l.ownerPassword.ifEmpty { null }, l.permissions, out,
                    inputPassword = inputPassword, isCancelled = { !context.isActive },
                )
                ProtectResult(out, pages, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the protection, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else protect()
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

    /** A content Uri for sharing the protected file, or null when there is no result. */
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
        val input = local.value.file?.file
        // Typed passwords never outlive the screen.
        local.value = Local()
        val output = successValue()?.file
        runner.cancel()
        output?.delete()
        input?.delete()
    }

    companion object {
        private fun passwordErrorOf(l: Local): ProtectError? = when {
            l.userPassword.isEmpty() -> ProtectError.EMPTY
            l.userPassword != l.confirmPassword -> ProtectError.MISMATCH
            else -> null
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ProtectViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
