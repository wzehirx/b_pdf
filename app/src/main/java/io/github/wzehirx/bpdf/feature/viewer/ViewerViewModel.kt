package io.github.wzehirx.bpdf.feature.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.wzehirx.bpdf.BPdfApp
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.files.RecentFilesStore
import io.github.wzehirx.bpdf.core.files.SourceUnavailable
import io.github.wzehirx.bpdf.core.pdf.PdfTextReader
import io.github.wzehirx.bpdf.core.pdf.ThumbnailLoader
import io.github.wzehirx.bpdf.core.work.PasswordGate
import io.github.wzehirx.bpdf.core.work.PasswordPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

enum class ViewerMode { PAGE, TEXT }

/** Persisted-permission calls the viewer needs, so tests can stand in for the system. */
class UriAccess(
    val persist: (Uri) -> Boolean,
    val isPersisted: (Uri) -> Boolean,
    val release: (Uri) -> Unit,
) {
    companion object {
        fun of(repo: FileRepository) = UriAccess(
            persist = { repo.tryPersistReadPermission(it) },
            isPersisted = repo::hasPersistedReadPermission,
            release = repo::releasePersistedPermission,
        )
    }
}

data class ViewerUiState(
    val file: ImportedFile? = null,
    val pageCount: Int = 0,
    val mode: ViewerMode = ViewerMode.PAGE,
    /** The page shown (page mode) or last navigated to (text mode), 0-based. */
    val currentPage: Int = 0,
    val zoom: Float = ViewerViewModel.MIN_ZOOM,
    val importing: Boolean = false,
    val passwordPrompt: PasswordPrompt? = null,
    /** Why the last open failed; cleared by the next open or [ViewerViewModel.dismissError]. */
    val error: Throwable? = null,
    /** The failed Uri was also dropped from the recent files list. */
    val removedFromRecents: Boolean = false,
)

/**
 * Reads one PDF page by page: page images ([pageImage], drawn by Android's PdfRenderer) and page
 * text ([pageText], extracted on demand for the pages that are shown, never the whole document).
 * State lives here so rotation does not reopen the file. Opening a Uri imports it to the cache,
 * asks for the password when needed and, when we may reopen it later, adds it to the recent files.
 */
class ViewerViewModel(
    private val fileRepository: FileRepository,
    private val recentFiles: RecentFilesStore,
    private val loader: ThumbnailLoader,
    initialUri: Uri? = null,
    private val access: UriAccess = UriAccess.of(fileRepository),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val file: ImportedFile? = null,
        val pageCount: Int = 0,
        val mode: ViewerMode = ViewerMode.PAGE,
        val currentPage: Int = 0,
        val zoom: Float = MIN_ZOOM,
        val pendingImports: Int = 0,
        val error: Throwable? = null,
        val removedFromRecents: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val passwordGate = PasswordGate(ioDispatcher)

    val state: StateFlow<ViewerUiState> = combine(local, passwordGate.prompt) { l, prompt ->
        ViewerUiState(
            file = l.file,
            pageCount = l.pageCount,
            mode = l.mode,
            currentPage = l.currentPage,
            zoom = l.zoom,
            importing = l.pendingImports > 0,
            passwordPrompt = prompt,
            error = l.error,
            removedFromRecents = l.removedFromRecents,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ViewerUiState())

    init {
        initialUri?.let { open(it) }
    }

    /** Opens a document the user just chose in the picker: also asks the system to keep access to it for the recent files. */
    fun openPicked(uri: Uri) = open(uri, keepAccess = true)

    /** Opens [uri] (an "open with" request or a recent file). Ignored while another document is opening. */
    fun open(uri: Uri, keepAccess: Boolean = false) {
        if (local.value.pendingImports > 0) return
        local.update { it.copy(pendingImports = it.pendingImports + 1, error = null, removedFromRecents = false) }
        viewModelScope.launch {
            try {
                val imported = try {
                    fileRepository.importToCache(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    throw unavailable(uri, e)
                } catch (e: FileNotFoundException) {
                    throw unavailable(uri, e)
                } catch (e: IllegalArgumentException) {
                    throw unavailable(uri, e)
                }
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
                    it.copy(file = imported, pageCount = pages, mode = ViewerMode.PAGE, currentPage = 0, zoom = MIN_ZOOM)
                }
                old?.let {
                    closeReader()
                    loader.release(it.file)
                    passwordGate.forget(it.file)
                    it.file.delete()
                }
                remember(uri, imported.displayName, keepAccess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                local.update { it.copy(error = e) }
            } finally {
                local.update { it.copy(pendingImports = it.pendingImports - 1) }
            }
        }
    }

    private suspend fun unavailable(uri: Uri, cause: Throwable): SourceUnavailable {
        // A document that cannot be read any more is of no use in the recent files list.
        runCatching { recentFiles.remove(uri.toString()) }
        access.release(uri)
        local.update { it.copy(removedFromRecents = true) }
        return SourceUnavailable(cause)
    }

    private suspend fun remember(uri: Uri, name: String, keepAccess: Boolean) {
        try {
            if (keepAccess) withContext(ioDispatcher) { access.persist(uri) }
            if (withContext(ioDispatcher) { access.isPersisted(uri) }) {
                recentFiles.record(uri.toString(), name).forEach { access.release(Uri.parse(it)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The recent list is a convenience; reading the document must not fail because of it.
        }
    }

    fun dismissError() = local.update { it.copy(error = null, removedFromRecents = false) }

    /** Answers the password prompt; a wrong password keeps the prompt open with `wrong` set. */
    fun submitPassword(password: String) = passwordGate.submit(password)

    /** The user gave up on the password prompt; the picked file is discarded. */
    fun dismissPassword() = passwordGate.dismiss()

    fun setMode(mode: ViewerMode, page: Int? = null) {
        local.update {
            val p = page ?: it.currentPage
            it.copy(mode = mode, currentPage = p.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0)))
        }
    }

    fun goTo(page: Int) = local.update {
        if (it.file == null || it.pageCount == 0) it else it.copy(currentPage = page.coerceIn(0, it.pageCount - 1))
    }

    fun nextPage() = goTo(local.value.currentPage + 1)

    fun previousPage() = goTo(local.value.currentPage - 1)

    fun setZoom(zoom: Float) = local.update { it.copy(zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }

    fun zoomIn() = setZoom(zoomInTarget(local.value.zoom))

    fun zoomOut() = setZoom(zoomOutTarget(local.value.zoom))

    /** Page [pageIndex] drawn [widthPx] wide, or null when it cannot be drawn. */
    suspend fun pageImage(pageIndex: Int, widthPx: Int): Bitmap? {
        val file = local.value.file ?: return null
        return loader.renderOrNull(file.file, pageIndex, widthPx, passwordGate.passwordOf(file.file))
    }

    // Text of single pages, extracted when asked for and kept for the most recent ones.
    private val textLock = Mutex()
    private var reader: PdfTextReader? = null
    private var readerFile: File? = null
    private val textCache = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > TEXT_CACHE_PAGES
    }

    /** Closes the open text reader (and forgets its pages) on the IO dispatcher, behind any extraction in progress. */
    private fun closeReader() {
        viewModelScope.launch(ioDispatcher) {
            textLock.withLock {
                runCatching { reader?.close() }
                reader = null
                readerFile = null
                textCache.clear()
            }
        }
    }

    /** Text of page [pageIndex] (empty for a page without text), or null when it cannot be read. */
    suspend fun pageText(pageIndex: Int): String? {
        val file = local.value.file ?: return null
        return withContext(ioDispatcher) {
            textLock.withLock {
                try {
                    if (readerFile != file.file) {
                        reader?.let { runCatching { it.close() } }
                        reader = null
                        textCache.clear()
                        readerFile = file.file
                    }
                    textCache[pageIndex] ?: run {
                        val r = reader ?: PdfTextReader(file.file, passwordGate.passwordOf(file.file)).also { reader = it }
                        r.text(pageIndex).trim().also { textCache[pageIndex] = it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    /** A content Uri of the open document for another tool of this app, or null when nothing is open. */
    suspend fun prepareHandOff(): Uri? {
        val file = local.value.file ?: return null
        return withContext(ioDispatcher) { fileRepository.shareUri(file.file, file.displayName) }
    }

    override fun onCleared() {
        passwordGate.clear()
        loader.close()
        // Not on the main thread: closing waits for an extraction that may still be running.
        val open = reader
        if (open != null) Thread { runCatching { open.close() } }.start()
        local.value.file?.file?.delete()
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val ZOOM_STEP = 0.5f
        private const val TEXT_CACHE_PAGES = 30

        /** Rounds a pinch zoom to the nearest button step, so the buttons continue from there. */
        private fun snapZoom(zoom: Float) = Math.round(zoom / ZOOM_STEP) * ZOOM_STEP

        /** The zoom the "Zoom in" button leads to from [zoom]. */
        fun zoomInTarget(zoom: Float) = (snapZoom(zoom) + ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** The zoom the "Zoom out" button leads to from [zoom]. */
        fun zoomOutTarget(zoom: Float) = (snapZoom(zoom) - ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** Route argument carrying the Uri to open. */
        const val URI_ARG = "uri"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BPdfApp
                val handle: SavedStateHandle = createSavedStateHandle()
                ViewerViewModel(
                    fileRepository = app.container.fileRepository,
                    recentFiles = app.container.recentFiles,
                    loader = ThumbnailLoader(File(app.cacheDir, "viewer")),
                    initialUri = handle.get<String>(URI_ARG)?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                )
            }
        }
    }
}
