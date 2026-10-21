package io.github.wzehirx.bpdf

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.files.RecentFilesStore
import io.github.wzehirx.bpdf.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; ViewModels obtain dependencies from it via `viewModelFactory`. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val fileRepository: FileRepository = FileRepository(context)

    /** Documents opened in the viewer, for the home screen. The DataStore lives as long as the process. */
    val recentFiles: RecentFilesStore by lazy {
        RecentFilesStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { appContext.preferencesDataStoreFile("recent_files") },
            ),
        )
    }

    /** Theme, high contrast and default save behavior. */
    val settings: SettingsStore by lazy {
        SettingsStore(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { appContext.preferencesDataStoreFile("settings") },
            ),
        )
    }
}
