package io.github.wzehirx.bpdf.feature.convert

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
import io.github.wzehirx.bpdf.core.files.baseNameOf
import io.github.wzehirx.bpdf.core.files.suggestName
import io.github.wzehirx.bpdf.core.pdf.AndroidPageRasterizer
import io.github.wzehirx.bpdf.core.pdf.ConversionLabels
import io.github.wzehirx.bpdf.core.pdf.ConversionStats
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PdfLayout
import io.github.wzehirx.bpdf.core.pdf.PdfToExcelOperation
import io.github.wzehirx.bpdf.core.pdf.PdfToHtmlOperation
import io.github.wzehirx.bpdf.core.pdf.PdfToWordOperation
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
import java.io.Closeable
import java.io.File
import java.util.Locale

enum class DocumentFormat(val extension: String, val mimeType: String, val suffix: String) {
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "word"),
    EXCEL("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "excel"),
    HTML("html", "text/html", "html"),
}

/** The converted file: [file] is saved and shared under [suggestedName]. */
data class PdfToDocumentResult(
    val file: File,
    val suggestedName: String,
    val format: DocumentFormat,
    val stats: ConversionStats,
)

data class PdfToDocumentUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val format: DocumentFormat = DocumentFormat.WORD,
    /** What Word and HTML keep of the pages; Excel only has text. */
    val layout: PdfLayout = PdfLayout.TEXT_AND_PICTURES,
    val importing: Boolean = false,
    val saving: Boolean = false,
    val passwordPrompt: PasswordPrompt? = null,
    /** True when the PDF needed a password: the converted file has none. */
    val copyUnprotected: Boolean = false,
    val operation: OperationState<PdfToDocumentResult> = OperationState.Idle,
)

class PdfToDocumentViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val labels: ConversionLabels = ConversionLabels(),
    /** BCP-47 tag written as the language of the document, e.g. "tr-TR". */
    private val languageTag: () -> String = { Locale.getDefault().toLanguageTag() },
    /** Creates the rasterizer of one run (exact look); closed when the run ends if it is [Closeable]. */
    private val rasterizerFactory: () -> PageRasterizer = { AndroidPageRasterizer() },
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val protectedInput: Boolean = false,
        val format: DocumentFormat = DocumentFormat.WORD,
        val layout: PdfLayout = PdfLayout.TEXT_AND_PICTURES,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<PdfToDocumentResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<PdfToDocumentUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        PdfToDocumentUiState(
            file = l.file,
            pageCount = l.pageCount,
            format = l.format,
            layout = l.layout,
            importing = l.pendingImports > 0,
            saving = l.saving,
            passwordPrompt = prompt,
            copyUnprotected = l.protectedInput,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PdfToDocumentUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]. Ignored while importing or converting. */
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
                    imported.file.delete()
                    return@launch
                }
                val old = local.value.file
                val unprotected = passwordGate.wasEncrypted(imported.file)
                local.update { it.copy(file = imported, pageCount = pages, protectedInput = unprotected) }
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
        local.update { it.copy(file = null, pageCount = 0, protectedInput = false) }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun setFormat(format: DocumentFormat) = edit { it.copy(format = format) }

    fun setLayout(layout: PdfLayout) = edit { it.copy(layout = layout) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun convert() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val format = l.format
        val layout = l.layout
        val password = passwordGate.passwordOf(file.file)
        val title = baseNameOf(file.displayName)
        val name = suggestName(file.displayName, format.suffix, format.extension)
        runner.start { progress ->
            val context = currentCoroutineContext()
            val isCancelled = { !context.isActive }
            val out = fileRepository.newOutputFile(format.extension)
            var rasterizer: PageRasterizer? = null
            try {
                val stats = when (format) {
                    DocumentFormat.WORD -> {
                        if (layout == PdfLayout.EXACT_LOOK) rasterizer = rasterizerFactory()
                        PdfToWordOperation.run(file.file, out, title, languageTag(), labels, layout, rasterizer, password, progress, isCancelled)
                    }
                    DocumentFormat.HTML -> {
                        if (layout == PdfLayout.EXACT_LOOK) rasterizer = rasterizerFactory()
                        PdfToHtmlOperation.run(file.file, out, title, languageTag(), labels, layout, rasterizer, password, progress, isCancelled)
                    }
                    DocumentFormat.EXCEL ->
                        PdfToExcelOperation.run(file.file, out, title, labels.pageHeader, password, progress, isCancelled)
                }
                PdfToDocumentResult(out, name, format, stats)
            } catch (t: Throwable) {
                out.delete()
                throw t
            } finally {
                (rasterizer as? Closeable)?.let { runCatching { it.close() } }
            }
        }
    }

    /** Retries whatever failed: converts again, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else convert()
    }

    fun submitPassword(password: String) = passwordGate.submit(password)

    fun dismissPassword() = passwordGate.dismiss()

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
                // The screen was left mid-save: the result could not be deleted then, so do it now.
                if (cleared) out.delete()
            }
        }
    }

    /** A content Uri for sharing the converted file, or null when there is none. */
    suspend fun prepareShare(): Uri? {
        val result = successValue() ?: return null
        return withContext(ioDispatcher) { fileRepository.shareUri(result.file, result.suggestedName) }
    }

    fun clearResult() {
        if (!local.value.saving) resetResult()
    }

    private fun successValue() = (runner.state.value as? OperationState.Success)?.value

    private fun resetResult() {
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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BPdfApp
                PdfToDocumentViewModel(
                    app.container.fileRepository,
                    labels = ConversionLabels(
                        pageHeader = { app.getString(R.string.convert_page_header, it) },
                        pictureAlt = { n, page -> app.getString(R.string.convert_picture_alt, n, page) },
                        pageImageAlt = { app.getString(R.string.convert_page_image_alt, it) },
                    ),
                )
            }
        }
    }
}
