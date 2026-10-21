package io.github.wzehirx.bpdf.feature.pdfimages

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
import io.github.wzehirx.bpdf.core.files.baseNameOf
import io.github.wzehirx.bpdf.core.files.exportToTree
import io.github.wzehirx.bpdf.core.files.suggestName
import io.github.wzehirx.bpdf.core.files.zipFiles
import io.github.wzehirx.bpdf.core.pdf.AndroidPageRasterizer
import io.github.wzehirx.bpdf.core.pdf.ImageFormat
import io.github.wzehirx.bpdf.core.pdf.PageRanges
import io.github.wzehirx.bpdf.core.pdf.PageRasterizer
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import io.github.wzehirx.bpdf.core.pdf.PdfToImagesOperation
import io.github.wzehirx.bpdf.core.work.OperationRunner
import io.github.wzehirx.bpdf.core.work.OperationState
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

/** Which pages become pictures. */
enum class PageScope { ALL, SELECTED }

/** Rendering resolution, in pixels per inch. */
enum class DpiChoice(val dpi: Int) { LOW(72), MEDIUM(150), HIGH(300) }

/** A finished conversion: the picture [files] inside [outDir] plus the suggested name for a zip of them. */
data class PdfToImagesResult(val files: List<File>, val outDir: File, val zipName: String)

data class PdfToImagesUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val scope: PageScope = PageScope.ALL,
    val rangeText: String = "",
    /** Live error for [rangeText] when [scope] is [PageScope.SELECTED]; blank only counts as an error after an attempt. */
    val rangeError: PdfError.InvalidRange? = null,
    val dpi: DpiChoice = DpiChoice.MEDIUM,
    val format: ImageFormat = ImageFormat.JPEG,
    /** Number of times Convert was pressed with an invalid selection; the screen announces each one. */
    val failedAttempts: Int = 0,
    val importing: Boolean = false,
    /** True while a save/export is writing the result; edits and further saves are ignored. */
    val saving: Boolean = false,
    val operation: OperationState<PdfToImagesResult> = OperationState.Idle,
)

class PdfToImagesViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Creates the rasterizer for one run; it is closed (when [Closeable]) when the run ends. */
    private val rasterizerFactory: () -> PageRasterizer = { AndroidPageRasterizer() },
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val scope: PageScope = PageScope.ALL,
        val rangeText: String = "",
        val dpi: DpiChoice = DpiChoice.MEDIUM,
        val format: ImageFormat = ImageFormat.JPEG,
        val attempted: Boolean = false,
        val failedAttempts: Int = 0,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<PdfToImagesResult>(viewModelScope, ioDispatcher)
    private val saved = Channel<Int>(Channel.BUFFERED)

    val state: StateFlow<PdfToImagesUiState> = combine(local, runner.state) { l, op ->
        PdfToImagesUiState(
            file = l.file,
            pageCount = l.pageCount,
            scope = l.scope,
            rangeText = l.rangeText,
            rangeError = if (l.scope == PageScope.SELECTED && l.file != null && (l.rangeText.isNotBlank() || l.attempted)) {
                rangeErrorOf(l.rangeText, l.pageCount)
            } else {
                null
            },
            dpi = l.dpi,
            format = l.format,
            failedAttempts = l.failedAttempts,
            importing = l.pendingImports > 0,
            saving = l.saving,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PdfToImagesUiState())

    /** Emits the number of pictures each time a save has finished writing them. */
    val savedEvents: Flow<Int> = saved.receiveAsFlow()

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
                    withContext(ioDispatcher) { PdfLoader.pageCount(imported.file) }
                } catch (t: Throwable) {
                    imported.file.delete()
                    throw t
                }
                val old = local.value.file
                local.update { it.copy(file = imported, pageCount = pages, attempted = false) }
                old?.file?.delete()
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
        file.file.delete()
    }

    fun setScope(scope: PageScope) = edit { it.copy(scope = scope, attempted = false) }

    fun setRangeText(text: String) = edit { it.copy(rangeText = text, attempted = false) }

    fun setDpi(dpi: DpiChoice) = edit { it.copy(dpi = dpi) }

    fun setFormat(format: ImageFormat) = edit { it.copy(format = format) }

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
        val pages = when (l.scope) {
            PageScope.ALL -> (0 until l.pageCount).toList()
            PageScope.SELECTED ->
                try {
                    PageRanges.parse(l.rangeText, l.pageCount).sorted()
                } catch (_: PdfError.InvalidRange) {
                    return rejectInput()
                }
        }
        val base = baseNameOf(file.displayName)
        val zipName = suggestName(file.displayName, "images", "zip")
        val format = l.format
        val dpi = l.dpi.dpi
        runner.start { progress ->
            val context = currentCoroutineContext()
            val outDir = fileRepository.newOutputDir()
            val rasterizer = rasterizerFactory()
            try {
                val files = PdfToImagesOperation.run(
                    file.file, pages, format, dpi, outDir, base, rasterizer,
                    progress = progress, isCancelled = { !context.isActive },
                )
                PdfToImagesResult(files, outDir, zipName)
            } catch (t: Throwable) {
                outDir.deleteRecursively()
                throw t
            } finally {
                (rasterizer as? Closeable)?.let { runCatching { it.close() } }
            }
        }
    }

    private fun rejectInput() = local.update { it.copy(attempted = true, failedAttempts = it.failedAttempts + 1) }

    /** Retries whatever failed: re-runs the conversion, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else convert()
    }

    fun cancel() = runner.cancel()

    /** Writes every picture into the folder chosen with the system folder picker. */
    fun saveToFolder(treeUri: Uri) {
        val result = successValue() ?: return
        if (busy) return
        launchSave { fileRepository.exportToTree(result.files, treeUri) }
    }

    /** Writes all pictures as one zip to [target]. */
    fun saveZip(target: Uri) {
        val result = successValue() ?: return
        if (busy) return
        launchSave {
            val zip = fileRepository.newOutputFile("zip")
            try {
                withContext(Dispatchers.IO) { zipFiles(result.files, zip) }
                fileRepository.export(zip, target)
            } finally {
                zip.delete()
            }
            result.files.size
        }
    }

    private fun launchSave(block: suspend () -> Int) {
        // Marked before launching so a second tap in the same frame is already ignored.
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                saved.send(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                local.update { it.copy(saving = false) }
            }
        }
    }

    /** Content Uris for sharing all pictures, empty when there is no result. */
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
        val output = successValue()?.outDir
        runner.cancel()
        output?.deleteRecursively()
        local.value.file?.file?.delete()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { PdfToImagesViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
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
