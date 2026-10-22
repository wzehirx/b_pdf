package io.github.wzehirx.bpdf.feature.convert

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
import io.github.wzehirx.bpdf.core.files.suggestName
import io.github.wzehirx.bpdf.core.pdf.DocumentKind
import io.github.wzehirx.bpdf.core.pdf.DocumentToPdfOperation
import io.github.wzehirx.bpdf.core.pdf.Fonts
import io.github.wzehirx.bpdf.core.pdf.ToPdfStats
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
import java.io.InputStream
import java.util.Locale

/** The converted PDF: [file] is saved and shared under [suggestedName]. */
data class DocumentToPdfResult(
    val file: File,
    val suggestedName: String,
    val kind: DocumentKind,
    val stats: ToPdfStats,
)

data class DocumentToPdfUiState(
    val file: ImportedFile? = null,
    /** What the picked file was recognised as (Word, Excel or text). */
    val kind: DocumentKind? = null,
    val importing: Boolean = false,
    val saving: Boolean = false,
    val operation: OperationState<DocumentToPdfResult> = OperationState.Idle,
)

class DocumentToPdfViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fontLoader: () -> InputStream,
    private val languageTag: () -> String = { Locale.getDefault().toLanguageTag() },
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val kind: DocumentKind? = null,
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<DocumentToPdfResult>(viewModelScope, ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<DocumentToPdfUiState> = combine(local, runner.state) { l, op ->
        DocumentToPdfUiState(
            file = l.file,
            kind = l.kind,
            importing = l.pendingImports > 0,
            saving = l.saving,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DocumentToPdfUiState())

    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]; a format that cannot be read (.doc, .xls...) becomes a failed state. */
    fun pickFile(uris: List<Uri>) {
        val uri = uris.firstOrNull() ?: return
        if (busy) return
        resetResult()
        local.update { it.copy(pendingImports = it.pendingImports + 1) }
        viewModelScope.launch {
            try {
                val name = withContext(ioDispatcher) { fileRepository.displayName(uri) }
                val extension = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "bin"
                val imported = fileRepository.importToCache(uri, extension)
                val kind = try {
                    withContext(ioDispatcher) { DocumentKind.detect(imported.file, imported.displayName) }
                } catch (t: Throwable) {
                    imported.file.delete()
                    throw t
                }
                val old = local.value.file
                local.update { it.copy(file = imported, kind = kind) }
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
        local.update { it.copy(file = null, kind = null) }
        file.file.delete()
    }

    fun convert() {
        val l = local.value
        val file = l.file ?: return
        if (busy) return
        resetResult()
        val title = baseNameOf(file.displayName)
        val name = suggestName(file.displayName, "converted", "pdf")
        runner.start { progress ->
            val context = currentCoroutineContext()
            val out = fileRepository.newOutputFile("pdf")
            try {
                val stats = DocumentToPdfOperation.run(
                    file.file, file.displayName, out, title, languageTag(), fontLoader, progress, isCancelled = { !context.isActive },
                )
                DocumentToPdfResult(out, name, DocumentKind.detect(file.file, file.displayName), stats)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else convert()
    }

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
        val output = successValue()?.file
        runner.cancel()
        if (!local.value.saving) output?.delete()
        local.value.file?.file?.delete()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BPdfApp
                DocumentToPdfViewModel(app.container.fileRepository, fontLoader = { app.assets.open(Fonts.ASSET_PATH) })
            }
        }
    }
}
