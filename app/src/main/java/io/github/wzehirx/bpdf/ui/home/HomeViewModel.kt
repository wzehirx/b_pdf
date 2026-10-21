package io.github.wzehirx.bpdf.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.wzehirx.bpdf.BPdfApp
import io.github.wzehirx.bpdf.core.files.RecentFile
import io.github.wzehirx.bpdf.core.files.RecentFilesStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home screen state: the documents opened recently in the viewer. */
class HomeViewModel(
    private val recentFiles: RecentFilesStore,
    private val releaseAccess: (Uri) -> Unit,
) : ViewModel() {
    val recents: StateFlow<List<RecentFile>> =
        recentFiles.recents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Takes [file] off the list and gives back the access we kept to it. */
    fun remove(file: RecentFile) {
        viewModelScope.launch {
            recentFiles.remove(file.uri)
            releaseAccess(Uri.parse(file.uri))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BPdfApp).container
                HomeViewModel(container.recentFiles, container.fileRepository::releasePersistedPermission)
            }
        }
    }
}
