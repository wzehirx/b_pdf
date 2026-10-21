package io.github.wzehirx.bpdf.feature.metadata

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
import io.github.wzehirx.bpdf.core.pdf.MetadataOperation
import io.github.wzehirx.bpdf.core.pdf.PdfMetadata
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

/** A finished edit: the copy in [file] carries the new document information. */
data class MetadataResult(val file: File, val suggestedName: String)

data class MetadataUiState(
    val file: ImportedFile? = null,
    /** What the file says about itself (pages, encryption, version, producer); read-only. */
    val info: PdfMetadata? = null,
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val importing: Boolean = false,
    /** True while the result is being written to its target; edits and further saves are ignored. */
    val saving: Boolean = false,
    /** Set while the user is asked for the file's password. */
    val passwordPrompt: PasswordPrompt? = null,
    val operation: OperationState<MetadataResult> = OperationState.Idle,
)

class MetadataViewModel(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val info: PdfMetadata? = null,
        val title: String = "",
        val author: String = "",
        val subject: String = "",
        val keywords: String = "",
        val creator: String = "",
        val pendingImports: Int = 0,
        val saving: Boolean = false,
        /** Import or save failure; takes precedence over the runner's state until cleared. */
        val error: Throwable? = null,
    )

    private val local = MutableStateFlow(Local())
    private val runner = OperationRunner<MetadataResult>(viewModelScope, ioDispatcher)
    private val passwordGate = PasswordGate(ioDispatcher)
    private val saved = Channel<Unit>(Channel.BUFFERED)

    val state: StateFlow<MetadataUiState> = combine(local, runner.state, passwordGate.prompt) { l, op, prompt ->
        MetadataUiState(
            file = l.file,
            info = l.info,
            title = l.title,
            author = l.author,
            subject = l.subject,
            keywords = l.keywords,
            creator = l.creator,
            importing = l.pendingImports > 0,
            saving = l.saving,
            passwordPrompt = prompt,
            operation = l.error?.let { OperationState.Failed(it) } ?: op,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MetadataUiState())

    /** Emits once each time [save] has written the result to its target. */
    val savedEvents: Flow<Unit> = saved.receiveAsFlow()

    private val busy: Boolean
        get() = local.value.pendingImports > 0 || local.value.saving || runner.state.value is OperationState.Running

    /** Takes the first of [uris]; a protected file asks for its password first. Ignored while busy. */
    fun pickFile(uris: List<Uri>) {
        val uri = uris.firstOrNull() ?: return
        if (busy) return
        resetResult()
        local.update { it.copy(pendingImports = it.pendingImports + 1) }
        viewModelScope.launch {
            try {
                val imported = fileRepository.importToCache(uri)
                val info = try {
                    passwordGate.open(imported) ?: run {
                        // The user gave up on the password prompt: keep whatever was open before.
                        imported.file.delete()
                        return@launch
                    }
                    val password = passwordGate.passwordOf(imported.file)
                    withContext(ioDispatcher) { MetadataOperation.read(imported.file, password) }
                } catch (t: Throwable) {
                    imported.file.delete()
                    throw t
                }
                val old = local.value.file
                local.update { it.copy(
                        file = imported,
                        info = info,
                        title = info.title.orEmpty(),
                        author = info.author.orEmpty(),
                        subject = info.subject.orEmpty(),
                        keywords = info.keywords.orEmpty(),
                        creator = info.creator.orEmpty(),
                    ) }
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
        local.update { it.copy(file = null, info = null, title = "", author = "", subject = "", keywords = "", creator = "") }
        passwordGate.forget(file.file)
        file.file.delete()
    }

    fun setTitle(text: String) = edit { it.copy(title = text) }

    fun setAuthor(text: String) = edit { it.copy(author = text) }

    fun setSubject(text: String) = edit { it.copy(subject = text) }

    fun setKeywords(text: String) = edit { it.copy(keywords = text) }

    fun setCreator(text: String) = edit { it.copy(creator = text) }

    private fun edit(change: (Local) -> Local) {
        if (busy) return
        resetResult()
        local.update(change)
    }

    /** Writes a copy of the file with the edited fields; save or share it afterwards. */
    fun write() {
        val l = local.value
        val file = l.file ?: return
        val info = l.info ?: return
        if (busy) return
        resetResult()
        val out = fileRepository.newOutputFile("pdf")
        val name = suggestName(file.displayName, "info", "pdf")
        val password = passwordGate.passwordOf(file.file)
        val edited = info.copy(
            title = l.title, author = l.author, subject = l.subject, keywords = l.keywords, creator = l.creator,
        )
        runner.start { progress ->
            val context = currentCoroutineContext()
            try {
                progress(0.1f)
                MetadataOperation.write(file.file, edited, out, password, isCancelled = { !context.isActive })
                MetadataResult(out, name)
            } catch (t: Throwable) {
                out.delete()
                throw t
            }
        }
    }

    /** Retries whatever failed: re-runs the write, or just dismisses an import/save error. */
    fun retry() {
        if (local.value.error != null) local.update { it.copy(error = null) } else write()
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
            }
        }
    }

    /** A content Uri for sharing the edited file, or null when there is no result. */
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
            initializer { MetadataViewModel((this[APPLICATION_KEY] as BPdfApp).container.fileRepository) }
        }
    }
}
