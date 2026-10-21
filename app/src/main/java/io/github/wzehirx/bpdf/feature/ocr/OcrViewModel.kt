package io.github.wzehirx.bpdf.feature.ocr

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
import io.github.wzehirx.bpdf.core.ocr.OcrEngine
import io.github.wzehirx.bpdf.core.ocr.OcrLanguage
import io.github.wzehirx.bpdf.core.ocr.OcrOperation
import io.github.wzehirx.bpdf.core.ocr.SearchablePdfOperation
import io.github.wzehirx.bpdf.core.ocr.TessDataInstaller
import io.github.wzehirx.bpdf.core.ocr.TesseractOcrEngine
import io.github.wzehirx.bpdf.core.pdf.AndroidPageRasterizer
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.PageRanges
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PageScope
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.UnlockOperation
import io.github.wzehirx.bpdf.core.pdf.toPlainText
import io.github.wzehirx.bpdf.core.work.OperationRunner
import io.github.wzehirx.bpdf.core.work.OperationState
import io.github.wzehirx.bpdf.core.work.PasswordGate
import io.github.wzehirx.bpdf.core.work.PasswordPrompt
import io.github.wzehirx.bpdf.feature.pdftext.TextResult
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
import java.io.Closeable
import java.io.File
import java.io.InputStream

/** The page being read: [position] (1-based) of [total] pages chosen, and [pageNumber] in the document. */
data class OcrPageProgress(val position: Int, val total: Int, val pageNumber: Int)

data class OcrUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val language: OcrLanguage = OcrLanguage.TURKISH_ENGLISH,
    val scope: PageScope = PageScope.ALL,
    val rangeText: String = "",
    /** Live error for [rangeText] (only while [scope] is SELECTED); blank counts only after an attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    /** Number of times the button was pressed with an invalid range; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while a result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    val passwordPrompt: PasswordPrompt? = null,
    /** True when the input needed a password: the searchable PDF has none. */
    val copyUnprotected: Boolean = false,
    /** The page being read while [operation] is running; null before the first page and when idle. */
    val progress: OcrPageProgress? = null,
    val operation: OperationState<TextResult> = OperationState.Idle,
)

class OcrViewModel(
    private val fileRepository: FileRepository,
    /** Opens the bundled font; tests pass their own. */
    private val fontLoader: () -> InputStream,
    /** Creates the engine for one run (preparing its language data first); it is closed when the run ends. */
    private val engineFactory: (languages: String) -> OcrEngine,
    /** Creates the rasterizer for one run; it is closed (when [Closeable]) when the run ends. */
    private val rasterizerFactory: () -> PageRasterizer = { AndroidPageRasterizer() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Header line above each page in the saved `.txt` file; receives the 1-based page number. */
    private val pageHeader: (Int) -> String = { "Page $it" },
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val language: OcrLanguage = OcrLanguage.TURKISH_ENGLISH,
        val scope: PageScope = PageScope.ALL,
        val rangeText: String = "",
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val protectedInput: Boolean = false,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val progress = MutableStateFlow<OcrPageProgress?>(null)
    private val runner = OperationRunner<TextResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<OcrUiState> = combine(local, runner.state, passwordGate.prompt, progress) { l, op, prompt, page ->
        OcrUiState(
            file = l.file,
            pageCount = l.pageCount,
            language = l.language,
            scope = l.scope,
            rangeText = l.rangeText,
            rangeError = if (l.scope == PageScope.SELECTED && l.file != null &&
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
            copyUnprotected = l.protectedInput,
            progress = if (op is OperationState.Running) page else null,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OcrUiState())

    /** Emits once each time a save has written its file to the target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or reading. */
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
                    it.copy(file = imported, protectedInput = passwordGate.passwordOf(imported.file) != null, pageCount = pages, attempted = false)
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
        local.update { it.copy(file = null, protectedInput = false, pageCount = 0, attempted = false) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun setLanguage(language: OcrLanguage) = edit { it.copy(language = language) }

    fun setScope(scope: PageScope) = edit { it.copy(scope = scope, attempted = false) }

    fun setRangeText(text: String) = edit { it.copy(rangeText = text, attempted = false) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun recognize() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val pages: List<Int>? = when (l.scope) {
            PageScope.ALL -> null
            PageScope.SELECTED ->
                if (rangeErrorOf(l.rangeText, l.pageCount) != null) return rejectInput()
                else PageRanges.parse(l.rangeText, l.pageCount)
        }
        val languages = l.language.codes
        val name = suggestName(file.displayName, "text", "txt")
        val password = passwordGate.passwordOf(file.file)
        progress.value = null
        runner.start { runProgress ->
            val context = currentCoroutineContext()
            var engine: OcrEngine? = null
            var rasterizer: PageRasterizer? = null
            // PdfRenderer cannot open encrypted files: render a decrypted cache copy instead.
            var decrypted: File? = null
            var out: File? = null
            try {
                val source = if (password != null || PdfLoader.isEncrypted(file.file)) {
                    fileRepository.newOutputFile("pdf").also { decrypted = it }
                        .also { UnlockOperation.run(file.file, password.orEmpty(), it, isCancelled = { !context.isActive }) }
                } else {
                    file.file
                }
                engine = engineFactory(languages)
                rasterizer = rasterizerFactory()
                val recognized = OcrOperation.run(source, pages, languages, rasterizer, engine, progress = { finished, total, pageNumber ->
                    progress.value = if (finished < total) OcrPageProgress(finished + 1, total, pageNumber) else null
                    runProgress(finished.toFloat() / total)
                })
                out = if (recognized.all { it.isBlank }) null else fileRepository.newOutputFile("txt")
                try {
                    out?.writeText(recognized.toPlainText(pageHeader), Charsets.UTF_8)
                } catch (e: java.io.IOException) {
                    throw PdfError.Io(e)
                }
                TextResult(recognized, out, name)
            } catch (t: Throwable) {
                out?.delete()
                throw t
            } finally {
                runCatching { engine?.close() }
                (rasterizer as? Closeable)?.let { runCatching { it.close() } }
                decrypted?.delete()
                progress.value = null
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the recognition, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else recognize()
    }

    /** Answers the password prompt; a wrong password keeps the prompt open with `wrong` set. */
    fun submitPassword(password: String) = passwordGate.submit(password)

    /** The user gave up on the password prompt; the picked file is discarded. */
    fun dismissPassword() = passwordGate.dismiss()

    /** The password that opened [file] (for thumbnails), or null when it needs none. */
    fun passwordOf(file: File): String? = passwordGate.passwordOf(file)

    /** Stops after the page being read (the engine is asked to give up at once) and releases the engine. */
    fun cancel() = runner.cancel()

    /** Writes the recognized text as a `.txt` file to [target]. */
    fun saveText(target: Uri) {
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

    /** The name suggested for the searchable PDF of the current file. */
    fun searchablePdfName(): String = suggestName(local.value.file?.displayName.orEmpty(), "searchable", "pdf")

    /** Builds the searchable PDF (original pages plus an invisible text layer) and writes it to [target]. */
    fun saveSearchablePdf(target: Uri) {
        val result = successValue() ?: return
        val file = local.value.file ?: return
        if (busy || result.allBlank) return
        local.update { it.copy(saving = true) }
        val password = passwordGate.passwordOf(file.file)
        viewModelScope.launch {
            var built: File? = null
            try {
                val temp = fileRepository.newOutputFile("pdf").also { built = it }
                withContext(ioDispatcher) {
                    SearchablePdfOperation.run(file.file, result.pages, temp, fontLoader, password)
                }
                fileRepository.export(temp, target)
                saved.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                built?.delete()
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
                val installer = TessDataInstaller(app.filesDir, { app.assets.open(it) })
                OcrViewModel(
                    app.container.fileRepository,
                    fontLoader = { app.assets.open(Fonts.ASSET_PATH) },
                    engineFactory = { languages -> TesseractOcrEngine(installer.ensure(languages)) },
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
