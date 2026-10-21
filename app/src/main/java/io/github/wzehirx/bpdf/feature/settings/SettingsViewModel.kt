package io.github.wzehirx.bpdf.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.wzehirx.bpdf.BPdfApp
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.settings.AppSettings
import io.github.wzehirx.bpdf.core.settings.SaveBehavior
import io.github.wzehirx.bpdf.core.settings.SettingsStore
import io.github.wzehirx.bpdf.core.settings.ThemeMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val clearingCache: Boolean = false,
    /** Bytes freed by the last "Clear cache"; null until the user has cleared it. */
    val cacheFreedBytes: Long? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(store.settings, local) { settings, l ->
        l.copy(settings = settings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { store.setTheme(mode) } }

    fun setHighContrast(enabled: Boolean) { viewModelScope.launch { store.setHighContrast(enabled) } }

    fun setSaveBehavior(behavior: SaveBehavior) { viewModelScope.launch { store.setSaveBehavior(behavior) } }

    fun clearCache() {
        if (local.value.clearingCache) return
        local.update { it.copy(clearingCache = true) }
        viewModelScope.launch {
            val freed = try {
                withContext(ioDispatcher) { fileRepository.clearCache() }
            } catch (_: Exception) {
                0L
            }
            local.update { it.copy(clearingCache = false, cacheFreedBytes = freed) }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as BPdfApp).container
                SettingsViewModel(container.settings, container.fileRepository)
            }
        }
    }
}
