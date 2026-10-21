package io.github.wzehirx.bpdf.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun dataStore(file: File) = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it },
        produceFile = { file },
    )

    private fun store(file: File = File(tmp.root, "settings.preferences_pb")) = SettingsStore(dataStore(file))

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun SettingsStore.now() = runBlocking { settings.first() }

    @Test
    fun defaults() {
        assertEquals(AppSettings(ThemeMode.SYSTEM, highContrast = false, saveBehavior = SaveBehavior.ASK), store().now())
    }

    @Test
    fun eachSettingIsStored_andSurvivesANewStoreOnTheSameFile() = runBlocking {
        val file = File(tmp.root, "s.preferences_pb")
        val s = store(file)
        s.setTheme(ThemeMode.DARK)
        s.setHighContrast(true)
        s.setSaveBehavior(SaveBehavior.SHARE)
        val expected = AppSettings(ThemeMode.DARK, true, SaveBehavior.SHARE)
        assertEquals(expected, s.now())
        s.setTheme(ThemeMode.LIGHT)
        assertEquals(expected.copy(theme = ThemeMode.LIGHT), s.now())
    }

    @Test
    fun unknownStoredValues_fallBackToDefaults() = runBlocking {
        val ds = dataStore(File(tmp.root, "bad.preferences_pb"))
        ds.edit {
            it[stringPreferencesKey("theme")] = "neon"
            it[stringPreferencesKey("save_behavior")] = "teleport"
        }
        assertEquals(AppSettings(), SettingsStore(ds).now())
    }
}
