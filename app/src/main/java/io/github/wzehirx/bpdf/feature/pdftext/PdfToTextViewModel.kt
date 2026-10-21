package io.github.wzehirx.bpdf.feature.pdftext

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.wzehirx.bpdf.BPdfApp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.files.suggestName
import io.github.wzehirx.bpdf.core.pdf.PageRanges
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.TextExtractOperation
import io.github.wzehirx.bpdf.core.pdf.toPlainText
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

enum class TextScope { ALL, SELECTED }

/**
 * The extracted text. [file] is the `.txt` written for saving and sharing, or null when no page
 * has any text (nothing worth saving; the screen suggests OCR instead).
 */
data class TextResult(
    val pages: List<PageText>,
    val file: File?,
    val suggestedName: String,
) {
    val blankCount: Int get() = pages.count { it.isBlank }
    val allBlank: Boolean get() = pages.all { it.isBlank }
}

data class PdfToTextUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val scope: TextScope = TextScope.ALL,
    val rangeText: String = "",
    /** Live error for [rangeText] (only while [scope] is SELECTED); blank counts only after an attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    /** Number of times Extract was pressed with an invalid range; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    val operation: OperationState<TextResult> = OperationState.Idle,
)

class PdfToTextViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Header line above each page in the saved `.txt` file; receives the 1-based page number. */
    private val pageHeader: (Int) -> String = { "Page $it" },
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val scope: TextScope = TextScope.ALL,
        val rangeText: String = "",
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<TextResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<PdfToTextUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        PdfToTextUiState(
            file = l.file,
            pageCount = l.pageCount,
            scope = l.scope,
            rangeText = l.rangeText,
            rangeError = if (l.scope == TextScope.SELECTED && l.file != null &&
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PdfToTextUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or extracting. */
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

    fun setScope(scope: TextScope) = edit { it.copy(scope = scope, attempted = false) }

    fun setRangeText(text: String) = edit { it.copy(rangeText = text, attempted = false) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun extract() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val pages: List<Int>? = when (l.scope) {
            TextScope.ALL -> null
            TextScope.SELECTED ->
                if (rangeErrorOf(l.rangeText, l.pageCount) != null) return rejectInput()
                else PageRanges.parse(l.rangeText, l.pageCount)
        }
        val name = suggestName(file.displayName, "text", "txt")
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            val extracted = TextExtractOperation.run(
                file.file, pages, password, progress = progress, isCancelled = { !context.isActive },
            )
            val out = if (extracted.all { it.isBlank }) null else fileRepository.newOutputFile("txt")
            try {
                out?.writeText(extracted.toPlainText(pageHeader), Charsets.UTF_8)
            } catch (e: java.io.IOException) {
                out?.delete()
                throw PdfError.Io(e)
            }
            TextResult(extracted, out, name)
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the extraction, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else extract()
    }

    /** Answers the password prompt; a wrong password keeps the prompt open with `wrong` set. */
    fun submitPassword(password: String) = passwordGate.submit(password)

    /** The user gave up on the password prompt; the picked file is discarded. */
    fun dismissPassword() = passwordGate.dismiss()

    /** The password that opened [file] (for thumbnails), or null when it needs none. */
    fun passwordOf(file: File): String? = passwordGate.passwordOf(file)

    fun cancel() = runner.cancel()

    fun save(target: Uri) {
        val out = successValue()?.file ?: return
        if (busy) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                fileRepository.export(out, target)
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

    /** A content Uri for sharing the `.txt`, or null when there is no text to share. */
    suspend fun prepareShare(): Uri? {
        val result = successValue() ?: return null
        val out = result.file ?: return null
        return withContext(ioDispatcher) { fileRepository.shareUri(out, result.suggestedName) }
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
            initializer {
                val app = this[APPLICATION_KEY] as BPdfApp
                PdfToTextViewModel(
                    app.container.fileRepository,
                    pageHeader = { app.getString(R.string.text_page_header, it) },
                )
            }
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
