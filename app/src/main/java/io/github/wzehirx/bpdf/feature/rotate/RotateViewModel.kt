package io.github.wzehirx.bpdf.feature.rotate

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
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.RotateOperation
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

/** How far to turn, always described clockwise as PDF rotation is. */
enum class RotateAngle(val degrees: Int) { CLOCKWISE_90(90), HALF_TURN(180), COUNTER_CLOCKWISE_90(270) }

enum class RotateScope { ALL, SELECTED }

/** A finished rotation: [rotatedCount] of [pageCount] pages were turned. */
data class RotateResult(
    val file: File,
    val rotatedCount: Int,
    val pageCount: Int,
    val suggestedName: String,
)

data class RotateUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val angle: RotateAngle = RotateAngle.CLOCKWISE_90,
    val scope: RotateScope = RotateScope.ALL,
    val rangeText: String = "",
    /** Live error for [rangeText] (only while [scope] is SELECTED); blank counts only after an attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    /** Number of times Rotate was pressed with an invalid range; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    val operation: OperationState<RotateResult> = OperationState.Idle,
)

class RotateViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val angle: RotateAngle = RotateAngle.CLOCKWISE_90,
        val scope: RotateScope = RotateScope.ALL,
        val rangeText: String = "",
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<RotateResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<RotateUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        RotateUiState(
            file = l.file,
            pageCount = l.pageCount,
            angle = l.angle,
            scope = l.scope,
            rangeText = l.rangeText,
            rangeError = if (l.scope == RotateScope.SELECTED && l.file != null &&
                (l.rangeText.isNotBlank() || l.attempted)
            ) {
                rangeErrorOf(l.rangeText, l.pageCount)
            } else {
                null
            },
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RotateUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or rotating. */
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

    fun setAngle(angle: RotateAngle) = edit { it.copy(angle = angle) }

    fun setScope(scope: RotateScope) = edit { it.copy(scope = scope, attempted = false) }

    fun setRangeText(text: String) = edit { it.copy(rangeText = text, attempted = false) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun rotate() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val pages: List<Int>? = when (l.scope) {
            RotateScope.ALL -> null
            RotateScope.SELECTED ->
                if (rangeErrorOf(l.rangeText, l.pageCount) != null) return rejectInput()
                else PageRanges.parse(l.rangeText, l.pageCount)
        }
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "rotated", "pdf")
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                val total = RotateOperation.run(
                    file.file, pages, l.angle.degrees, out, password, progress = progress, isCancelled = { !context.isActive },
                )
                RotateResult(out, pages?.distinct()?.size ?: total, total, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the rotation, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else rotate()
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
            }
        }
    }

    /** A content Uri for sharing the rotated file, or null when there is no result. */
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
            initializer { RotateViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }

        private fun rangeErrorOf(text: String, pageCount: Int): PdfError.InvalidRange? =
            try {
                PageRanges.parse(text, pageCount)
                null
            } catch (e: PdfError.InvalidRange) {
                e
            }
    }
}
