package io.github.wzehirx.bpdf.feature.watermark

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
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.WatermarkOperation
import io.github.wzehirx.bpdf.core.pdf.WatermarkOptions
import io.github.wzehirx.bpdf.core.pdf.WatermarkPosition
import io.github.wzehirx.bpdf.core.pdf.Fonts
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

/** Named colour presets; the name is always shown, so colour is never the only cue. */
enum class WatermarkColor(val argb: Int) {
    GRAY(0xFF808080.toInt()),
    RED(0xFFC62828.toInt()),
    BLUE(0xFF1565C0.toInt()),
    BLACK(0xFF000000.toInt()),
}

/** A finished watermark: [markedCount] of [pageCount] pages were stamped. */
data class WatermarkResult(
    val file: File,
    val markedCount: Int,
    val pageCount: Int,
    val suggestedName: String,
)

data class WatermarkUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val text: String = "",
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val opacityPercent: Int = DEFAULT_OPACITY,
    /** Counter-clockwise degrees; negative turns clockwise. */
    val rotation: Int = DEFAULT_ROTATION,
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    val color: WatermarkColor = WatermarkColor.GRAY,
    val scope: PageScope = PageScope.ALL,
    val rangeText: String = "",
    /** Live error for [rangeText] (only while [scope] is SELECTED); blank counts only after an attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    /** True after Add watermark was pressed with no text. */
    val textMissing: Boolean = false,
    /** Number of times the button was pressed with invalid input; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    /** True when an input needed a password: the saved copy has none. */
    val copyUnprotected: Boolean = false,
    val operation: OperationState<WatermarkResult> = OperationState.Idle,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 48
        const val DEFAULT_OPACITY = 30
        const val DEFAULT_ROTATION = 45
        const val MAX_TEXT_LENGTH = 200
    }
}

class WatermarkViewModel(
    private val fileRepository: FileRepository,
    /** Opens the bundled font; tests pass their own. */
    private val fontLoader: () -> InputStream,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val text: String = "",
        val fontSize: Int = WatermarkUiState.DEFAULT_FONT_SIZE,
        val opacityPercent: Int = WatermarkUiState.DEFAULT_OPACITY,
        val rotation: Int = WatermarkUiState.DEFAULT_ROTATION,
        val position: WatermarkPosition = WatermarkPosition.CENTER,
        val color: WatermarkColor = WatermarkColor.GRAY,
        val scope: PageScope = PageScope.ALL,
        val rangeText: String = "",
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
    private val runner = OperationRunner<WatermarkResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<WatermarkUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        WatermarkUiState(
            file = l.file,
            pageCount = l.pageCount,
            text = l.text,
            fontSize = l.fontSize,
            opacityPercent = l.opacityPercent,
            rotation = l.rotation,
            position = l.position,
            color = l.color,
            scope = l.scope,
            rangeText = l.rangeText,
            rangeError = if (l.scope == PageScope.SELECTED && l.file != null &&
                (l.rangeText.isNotBlank() || l.attempted)
            ) {
                rangeErrorOf(l.rangeText, l.pageCount)
            } else {
                null
            },
            textMissing = l.attempted && l.text.isBlank(),
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            copyUnprotected = l.protectedInput,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WatermarkUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or stamping. */
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
                local.update { it.copy(file = imported, protectedInput = passwordGate.wasEncrypted(imported.file), pageCount = pages, attempted = false) }
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

    fun setText(text: String) = edit { it.copy(text = text.take(WatermarkUiState.MAX_TEXT_LENGTH), attempted = false) }

    fun setFontSize(size: Int) = edit { it.copy(fontSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)) }

    fun setOpacityPercent(percent: Int) = edit { it.copy(opacityPercent = percent.coerceIn(MIN_OPACITY, MAX_OPACITY)) }

    fun setRotation(degrees: Int) = edit { it.copy(rotation = degrees.coerceIn(-MAX_ROTATION, MAX_ROTATION)) }

    fun setPosition(position: WatermarkPosition) = edit { it.copy(position = position) }

    fun setColor(color: WatermarkColor) = edit { it.copy(color = color) }

    fun setScope(scope: PageScope) = edit { it.copy(scope = scope, attempted = false) }

    fun setRangeText(text: String) = edit { it.copy(rangeText = text, attempted = false) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun addWatermark() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        if (l.text.isBlank()) return rejectInput()
        val pages: List<Int>? = when (l.scope) {
            PageScope.ALL -> null
            PageScope.SELECTED ->
                if (rangeErrorOf(l.rangeText, l.pageCount) != null) return rejectInput()
                else PageRanges.parse(l.rangeText, l.pageCount)
        }
        val options = WatermarkOptions(
            text = l.text,
            fontSize = l.fontSize.toFloat(),
            opacity = l.opacityPercent / 100f,
            rotation = l.rotation.toFloat(),
            position = l.position,
            colorArgb = l.color.argb,
            pages = pages,
        )
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "watermarked", "pdf")
        val password = passwordGate.passwordOf(file.file)
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                val marked = WatermarkOperation.run(
                    file.file, options, out, fontLoader, password, progress = progress, isCancelled = { !context.isActive },
                )
                WatermarkResult(out, marked, l.pageCount, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the watermark, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else addWatermark()
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

    /** A content Uri for sharing the watermarked file, or null when there is no result. */
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
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 200
        const val FONT_STEP = 4
        const val MIN_OPACITY = 10
        const val MAX_OPACITY = 100
        const val OPACITY_STEP = 5
        const val MAX_ROTATION = 90
        const val ROTATION_STEP = 5

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BPdfApp
                WatermarkViewModel(app.container.fileRepository, { app.assets.open(Fonts.ASSET_PATH) })
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
