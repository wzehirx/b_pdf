package io.github.wzehirx.bpdf.feature.sign

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
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.SignOperation
import io.github.wzehirx.bpdf.core.pdf.SignOptions
import io.github.wzehirx.bpdf.core.pdf.SignPosition
import io.github.wzehirx.bpdf.core.pdf.SignatureImage
import io.github.wzehirx.bpdf.core.pdf.SignaturePoint
import io.github.wzehirx.bpdf.core.pdf.SignatureSource
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
import java.io.InputStream

/** How the signature is made: typed (the accessible default) or drawn with the finger. */
enum class SignMode { TYPED, DRAWN }

/** What is wrong with the fields, shown after an attempt. */
enum class SignFieldError { TEXT_MISSING, DRAWING_EMPTY, PAGE_INVALID }

/** A signed copy: [page] is the 1-based page that carries the signature. */
data class SignResult(val file: File, val page: Int, val suggestedName: String)

data class SignUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val mode: SignMode = SignMode.TYPED,
    val typedText: String = "",
    /** The drawn signature: strokes of points relative to the drawing area. */
    val strokes: List<List<SignaturePoint>> = emptyList(),
    /** The 1-based page to sign as typed; validated when the button is pressed (and live once it was wrong). */
    val pageText: String = "",
    val position: SignPosition = SignPosition.BOTTOM_RIGHT,
    val addDate: Boolean = false,
    val fieldErrors: Set<SignFieldError> = emptySet(),
    /** Number of times the button was pressed with invalid input; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    val passwordPrompt: PasswordPrompt? = null,
    /** True when the input needed a password: the saved copy has none. */
    val copyUnprotected: Boolean = false,
    val operation: OperationState<SignResult> = OperationState.Idle,
) {
    val hasDrawing: Boolean get() = SignatureImage.hasInk(strokes)
}

class SignViewModel(
    private val fileRepository: FileRepository,
    /** Opens the bundled font; tests pass their own. */
    private val fontLoader: () -> InputStream,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val mode: SignMode = SignMode.TYPED,
        val typedText: String = "",
        val strokes: List<List<SignaturePoint>> = emptyList(),
        val pageText: String = "",
        val position: SignPosition = SignPosition.BOTTOM_RIGHT,
        val addDate: Boolean = false,
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val protectedInput: Boolean = false,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<SignResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<SignUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        SignUiState(
            file = l.file,
            pageCount = l.pageCount,
            mode = l.mode,
            typedText = l.typedText,
            strokes = l.strokes,
            pageText = l.pageText,
            position = l.position,
            addDate = l.addDate,
            // Text and drawing errors only count after an attempt; a wrong page number is flagged as it is typed.
            fieldErrors = fieldErrorsOf(l).filterTo(HashSet()) {
                l.attempted || (it == SignFieldError.PAGE_INVALID && l.file != null && l.pageText.isNotBlank())
            },
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            copyUnprotected = l.protectedInput,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SignUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or signing. The page to sign starts at the last page. */
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
                    it.copy(
                        file = imported,
                        protectedInput = passwordGate.passwordOf(imported.file) != null,
                        pageCount = pages,
                        pageText = pages.toString(),
                        attempted = false,
                    )
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
        local.update { it.copy(file = null, protectedInput = false, pageCount = 0, pageText = "", attempted = false) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun setMode(mode: SignMode) = edit { it.copy(mode = mode, attempted = false) }

    fun setTypedText(text: String) = edit { it.copy(typedText = text.take(MAX_TEXT_LENGTH), attempted = false) }

    /** Adds one finished stroke (points relative to the drawing area). */
    fun addStroke(stroke: List<SignaturePoint>) {
        if (stroke.isEmpty()) return
        edit { it.copy(strokes = (it.strokes + listOf(stroke)).takeLast(MAX_STROKES), attempted = false) }
    }

    fun clearDrawing() = edit { it.copy(strokes = emptyList(), attempted = false) }

    fun setPageText(text: String) = edit { it.copy(pageText = text.filter(Char::isDigit).take(5), attempted = false) }

    fun setPosition(position: SignPosition) = edit { it.copy(position = position) }

    fun setAddDate(add: Boolean) = edit { it.copy(addDate = add) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun sign() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        if (fieldErrorsOf(l).isNotEmpty()) return rejectInput()
        val options = SignOptions(page = l.pageText.toInt() - 1, position = l.position, addDate = l.addDate)
        val typed = l.typedText.trim()
        val strokes = l.strokes
        val drawn = l.mode == SignMode.DRAWN
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "signed", "pdf")
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            var picture: File? = null
            try {
                val source = if (drawn) {
                    SignatureSource.Image(fileRepository.newOutputFile("png").also { picture = it; SignatureImage.render(strokes, it) })
                } else {
                    SignatureSource.Typed(typed)
                }
                SignOperation.run(file.file, source, options, out, fontLoader, password, progress = progress, isCancelled = { !context.isActive })
                SignResult(out, options.page + 1, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            } finally {
                picture?.delete()
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the signing, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else sign()
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

    /** A content Uri for sharing the signed file, or null when there is no result. */
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
        const val MAX_TEXT_LENGTH = 80
        const val MAX_STROKES = 200

        private fun fieldErrorsOf(l: Local): Set<SignFieldError> = buildSet {
            when (l.mode) {
                SignMode.TYPED -> if (l.typedText.isBlank()) add(SignFieldError.TEXT_MISSING)
                SignMode.DRAWN -> if (!SignatureImage.hasInk(l.strokes)) add(SignFieldError.DRAWING_EMPTY)
            }
            val page = l.pageText.toIntOrNull()
            if (page == null || page !in 1..l.pageCount) add(SignFieldError.PAGE_INVALID)
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BPdfApp
                SignViewModel(app.container.fileRepository, { app.assets.open(Fonts.ASSET_PATH) })
            }
        }
    }
}
