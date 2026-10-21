package io.github.wzehirx.bpdf.feature.split

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
import io.github.wzehirx.bpdf.core.files.MultiFileSaver
import io.github.wzehirx.bpdf.core.files.baseNameOf
import io.github.wzehirx.bpdf.core.files.suggestName
import io.github.wzehirx.bpdf.core.pdf.PageRanges
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.SplitMode
import io.github.wzehirx.bpdf.core.pdf.SplitOperation
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

enum class SplitModeChoice { RANGES, EVERY_N, EACH_PAGE }

enum class EveryNError { EMPTY, INVALID }

/** A finished split: the [files] inside [outDir] plus the suggested name for a zip of them. */
data class SplitResult(val files: List<File>, val outDir: File, val zipName: String)

data class SplitUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val mode: SplitModeChoice = SplitModeChoice.RANGES,
    val rangeText: String = "",
    val everyNText: String = "2",
    /** Live error for [rangeText]; a blank field only counts as an error after a split attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    val everyNError: EveryNError? = null,
    /** Number of times Split was pressed with invalid input; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while a save/export is writing the result; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    /** True when an input needed a password: the saved copy has none. */
    val copyUnprotected: Boolean = false,
    val operation: OperationState<SplitResult> = OperationState.Idle,
)

class SplitViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val mode: SplitModeChoice = SplitModeChoice.RANGES,
        val rangeText: String = "",
        val everyNText: String = "2",
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        /** An input needed a password, so the saved copy has none. */
        val protectedInput: Boolean = false,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<SplitResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Int>(Channel.BUFFERED)
    private val saver = MultiFileSaver(fileRepository, ioDispatcher)

    val state: StateFlow<SplitUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        SplitUiState(
            file = l.file,
            pageCount = l.pageCount,
            mode = l.mode,
            rangeText = l.rangeText,
            everyNText = l.everyNText,
            rangeError = if (l.mode == SplitModeChoice.RANGES && l.file != null &&
                (l.rangeText.isNotBlank() || l.attempted)
            ) {
                rangeErrorOf(l.rangeText, l.pageCount)
            } else {
                null
            },
            everyNError = if (l.mode == SplitModeChoice.EVERY_N && l.file != null) {
                everyNErrorOf(l.everyNText, l.pageCount, l.attempted)
            } else {
                null
            },
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            copyUnprotected = l.protectedInput,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SplitUiState())

    /** Emits the number of files each time a save has finished writing them. */
    val savedEvents: Flow<Int> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or splitting. */
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
                local.update { it.copy(file = imported, protectedInput = passwordGate.passwordOf(imported.file) != null, pageCount = pages, attempted = false) }
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
        local.update { it.copy(file = null, protectedInput = false, pageCount = 0, attempted = false) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun setMode(mode: SplitModeChoice) = edit { it.copy(mode = mode, attempted = false) }

    fun setRangeText(text: String) = edit { it.copy(rangeText = text, attempted = false) }

    fun setEveryNText(text: String) = edit { it.copy(everyNText = text, attempted = false) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun split() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val mode: SplitMode = when (l.mode) {
            SplitModeChoice.RANGES ->
                if (rangeErrorOf(l.rangeText, l.pageCount) != null) return rejectInput()
                else SplitMode.Ranges(l.rangeText)
            SplitModeChoice.EVERY_N ->
                if (everyNErrorOf(l.everyNText, l.pageCount, attempted = true) != null) return rejectInput()
                else SplitMode.EveryN(l.everyNText.trim().toInt())
            SplitModeChoice.EACH_PAGE -> SplitMode.EachPage
        }
        val zipName = suggestName(file.displayName, "split", "zip")
        val base = baseNameOf(file.displayName)
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            val outDir = fileRepository.newOutputDir()
            try {
                val files = SplitOperation.run(
                    file.file, mode, outDir, base, password, progress = progress, isCancelled = { !context.isActive },
                )
                SplitResult(files, outDir, zipName)
            } catch (t: Throwable) {
                outDir.deleteRecursively()
                throw t
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the split, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else split()
    }

    /** Answers the password prompt; a wrong password keeps the prompt open with `wrong` set. */
    fun submitPassword(password: String) = passwordGate.submit(password)

    /** The user gave up on the password prompt; the picked file is discarded. */
    fun dismissPassword() = passwordGate.dismiss()

    /** The password that opened [file] (for thumbnails), or null when it needs none. */
    fun passwordOf(file: File): String? = passwordGate.passwordOf(file)

    fun cancel() = runner.cancel()

    /** Writes every part into the folder chosen with the system folder picker. */
    fun saveToFolder(treeUri: Uri) {
        val result = successValue() ?: return
        if (busy) return
        local.update { it.copy(saving = true) } // marked before launching so a second tap is already ignored
        saver.toFolder(viewModelScope, result.files, treeUri, saveCallbacks)
    }

    /** Writes all parts as one zip to [target]. */
    fun saveZip(target: Uri) {
        val result = successValue() ?: return
        if (busy) return
        local.update { it.copy(saving = true) }
        saver.toZip(viewModelScope, result.files, target, saveCallbacks)
    }

    private val saveCallbacks = MultiFileSaver.Callbacks(
        onSaved = { saved.send(it) },
        onError = { e -> local.update { it.copy(error = e) } },
        onFinished = { local.update { it.copy(saving = false) } },
    )

    /** Content Uris for sharing all parts, empty when there is no result. */
    suspend fun prepareShare(): List<Uri> {
        val result = successValue() ?: return emptyList()
        return withContext(ioDispatcher) { result.files.map { fileRepository.shareUri(it) } }
    }

    fun clearResult() {
        if (!local.value.saving) resetResult()
    }

    private fun successValue() = (runner.state.value as? OperationState.Success)?.value

    private fun resetResult() {
        // Drop the result (and its output files) that the inputs no longer match.
        successValue()?.outDir?.deleteRecursively()
        runner.reset()
        local.update { it.copy(error = null) }
    }

    override fun onCleared() {
        passwordGate.clear()
        val output = successValue()?.outDir
        runner.cancel()
        output?.deleteRecursively()
        local.value.file?.file?.delete()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SplitViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }

        private fun rangeErrorOf(text: String, pageCount: Int): PdfError.InvalidRange? =
            try {
                PageRanges.parseGroups(text, pageCount)
                null
            } catch (e: PdfError.InvalidRange) {
                e
            }

        /** A blank value is only an error once the user tried to split ([attempted]). */
        private fun everyNErrorOf(text: String, pageCount: Int, attempted: Boolean): EveryNError? {
            val t = text.trim()
            if (t.isEmpty()) return if (attempted) EveryNError.EMPTY else null
            val n = if (t.all { it in '0'..'9' }) t.toIntOrNull() else null
            return if (n == null || n < 1 || n > pageCount) EveryNError.INVALID else null
        }
    }
}
