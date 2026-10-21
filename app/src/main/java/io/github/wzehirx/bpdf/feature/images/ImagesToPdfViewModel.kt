package io.github.wzehirx.bpdf.feature.images

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
import io.github.wzehirx.bpdf.core.pdf.ImagesToPdfOperation
import io.github.wzehirx.bpdf.core.pdf.ImagesToPdfOptions
import io.github.wzehirx.bpdf.core.pdf.Orientation
import io.github.wzehirx.bpdf.core.pdf.PageSizeOption
import io.github.wzehirx.bpdf.core.pdf.PdfError
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
import java.io.File

/** Empty border around each image. */
enum class MarginChoice(val points: Float) { NONE(0f), SMALL(18f), LARGE(36f) }

/** A finished conversion: the output [file] plus what the UI needs to describe and save it. */
data class ImagesToPdfResult(val file: File, val pageCount: Int, val suggestedName: String)

data class ImagesToPdfUiState(
    val images: List<ImportedFile> = emptyList(),
    val pageSize: PageSizeOption = PageSizeOption.A4,
    val orientation: Orientation = Orientation.AUTO,
    val margin: MarginChoice = MarginChoice.NONE,
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    val operation: OperationState<ImagesToPdfResult> = OperationState.Idle,
)

class ImagesToPdfViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val images: List<ImportedFile> = emptyList(),
        val pageSize: PageSizeOption = PageSizeOption.A4,
        val orientation: Orientation = Orientation.AUTO,
        val margin: MarginChoice = MarginChoice.NONE,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<ImagesToPdfResult>(viewModelScope, ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)
    private val added = Channel<Int>(Channel.BUFFERED)

    /** The file the camera app is currently writing to; kept here so it survives rotation. */
    private var pendingCamera: File? = null
    private var photoCount = 0

    val state: StateFlow<ImagesToPdfUiState> = combine(local, runner.state) { l, op ->
        ImagesToPdfUiState(
            images = l.images,
            pageSize = l.pageSize,
            orientation = l.orientation,
            margin = l.margin,
            importing = l.pendingImports > 0,
            saving = l.saving,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ImagesToPdfUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    /** Emits how many pictures were just added (gallery import or camera), so the screen can announce it. */
    val addedEvents: Flow<Int> = added.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Ignored while importing or converting: the inputs must not change under a running operation. */
    fun addImages(uris: List<Uri>) {
        if (busy) return
        resetResult()
        local.update { it.copy(pendingImports = it.pendingImports + 1) }
        viewModelScope.launch {
            var count = 0
            try {
                for (uri in uris) {
                    val imported = fileRepository.importToCache(uri, "img")
                    local.update { it.copy(images = it.images + imported) }
                    count++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                local.update { it.copy(pendingImports = it.pendingImports - 1) }
                if (count > 0) added.trySend(count)
            }
        }
    }

    /** Creates the file the camera should write to and returns its Uri; null while busy. */
    fun prepareCamera(): Uri? {
        if (busy) return null
        pendingCamera?.delete()
        val file = fileRepository.newCameraFile()
        pendingCamera = file
        return fileRepository.cameraUri(file)
    }

    /** Result of the camera: a taken photo becomes the last image, anything else discards the file. */
    fun cameraResult(taken: Boolean) {
        val file = pendingCamera ?: return
        pendingCamera = null
        if (!taken || !file.isFile || file.length() == 0L) {
            file.delete()
            return
        }
        resetResult()
        val name = "photo_${++photoCount}.jpg"
        local.update { it.copy(images = it.images + ImportedFile(file, name, file.length())) }
        added.trySend(1)
    }

    fun move(from: Int, to: Int) {
        val images = local.value.images
        if (busy || from !in images.indices || to !in images.indices || from == to) return
        resetResult()
        local.update { it.copy(images = it.images.toMutableList().apply { add(to, removeAt(from)) }) }
    }

    fun remove(index: Int) {
        val images = local.value.images
        if (busy || index !in images.indices) return
        resetResult()
        local.update { it.copy(images = it.images.filterIndexed { i, _ -> i != index }) }
        images[index].file.delete()
    }

    fun setPageSize(value: PageSizeOption) = edit { it.copy(pageSize = value) }

    fun setOrientation(value: Orientation) = edit { it.copy(orientation = value) }

    fun setMargin(value: MarginChoice) = edit { it.copy(margin = value) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    fun convert() {
        val l = local.value
        if (busy || l.images.isEmpty()) return
        resetResult()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(l.images.first().displayName, "images", "pdf")
        val options = ImagesToPdfOptions(l.pageSize, l.orientation, l.margin.points)
        val files = l.images.map { it.file }
        val names = l.images.associate { it.file to it.displayName }
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                ImagesToPdfOperation.run(files, options, out, progress = progress, isCancelled = { !context.isActive })
                ImagesToPdfResult(out, files.size, name)
            } catch (e: PdfError.UnreadableImage) {
                out.delete()
                // The engine only knows the cache file name; the user knows the picked name.
                val known = files.firstOrNull { it.name == e.fileName }?.let(names::get)
                throw if (known != null) PdfError.UnreadableImage(known) else e
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the conversion, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else convert()
    }

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

    /** A content Uri for sharing the PDF, or null when there is no result. */
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
        val output = successValue()?.file
        runner.cancel()
        output?.delete()
        pendingCamera?.delete()
        local.value.images.forEach { it.file.delete() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ImagesToPdfViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
