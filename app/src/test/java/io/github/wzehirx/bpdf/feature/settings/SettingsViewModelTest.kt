package io.github.wzehirx.bpdf.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.wzehirx.bpdf.core.files.FileRepository
import io.github.wzehirx.bpdf.core.settings.SaveBehavior
import io.github.wzehirx.bpdf.core.settings.SettingsStore
import io.github.wzehirx.bpdf.core.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: FileRepository
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FileRepository(context)
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        vm = SettingsViewModel(store, repo, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private fun awaitState(cond: (SettingsUiState) -> Boolean) =
        runBlocking { withTimeout(10_000) { vm.state.first(cond) } }

    @Test
    fun setters_updateTheState() {
        vm.setTheme(ThemeMode.DARK)
        vm.setHighContrast(true)
        vm.setSaveBehavior(SaveBehavior.SHARE)
        val s = awaitState { it.settings.theme == ThemeMode.DARK && it.settings.highContrast && it.settings.saveBehavior == SaveBehavior.SHARE }
        assertEquals(null, s.cacheFreedBytes)
    }

    @Test
    fun clearCache_reportsFreedBytes() {
        repo.newOutputFile("pdf").writeBytes(ByteArray(4096))
        vm.clearCache()
        val s = awaitState { it.cacheFreedBytes != null && !it.clearingCache }
        assertEquals(4096L, s.cacheFreedBytes)
        assertEquals(0L, repo.cacheSize())
    }
}
