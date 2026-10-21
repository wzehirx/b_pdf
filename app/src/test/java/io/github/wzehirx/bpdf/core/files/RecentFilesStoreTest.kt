package io.github.wzehirx.bpdf.core.files

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
class RecentFilesStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private var now = 1000L

    private fun dataStore(file: File) =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it },
            produceFile = { file },
        )

    private fun store(file: File = File(tmp.root, "recent.preferences_pb"), max: Int = 10) =
        RecentFilesStore(dataStore(file), max) { now++ }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun RecentFilesStore.list() = runBlocking { recents.first() }

    @Test
    fun startsEmpty() {
        assertEquals(emptyList<RecentFile>(), store().list())
    }

    @Test
    fun record_putsNewestFirst() = runBlocking {
        val s = store()
        s.record("content://a", "a.pdf")
        s.record("content://b", "b.pdf")
        assertEquals(listOf("b.pdf", "a.pdf"), s.list().map { it.name })
        assertEquals(listOf(1001L, 1000L), s.list().map { it.openedAt })
    }

    @Test
    fun record_sameUriAgain_movesToTopAndRefreshesName() = runBlocking {
        val s = store()
        s.record("content://a", "a.pdf")
        s.record("content://b", "b.pdf")
        s.record("content://a", "renamed.pdf")
        assertEquals(listOf("renamed.pdf", "b.pdf"), s.list().map { it.name })
    }

    @Test
    fun record_beyondTheLimit_dropsOldest_andReportsIt() = runBlocking {
        val s = store(max = 3)
        listOf("1", "2", "3").forEach { assertEquals(emptyList<String>(), s.record("content://$it", "$it.pdf")) }
        assertEquals(listOf("content://1"), s.record("content://4", "4.pdf"))
        assertEquals(listOf("4.pdf", "3.pdf", "2.pdf"), s.list().map { it.name })
    }

    @Test
    fun remove_dropsOnlyThatEntry_andUnknownUriIsIgnored() = runBlocking {
        val s = store()
        s.record("content://a", "a.pdf")
        s.record("content://b", "b.pdf")
        s.remove("content://a")
        s.remove("content://zzz")
        assertEquals(listOf("b.pdf"), s.list().map { it.name })
    }

    @Test
    fun entriesSurviveANewStoreOnTheSameFile() = runBlocking {
        val file = File(tmp.root, "persist.preferences_pb")
        store(file).record("content://a", "Ünlü ğüş.pdf")
        scopes.forEach { it.cancel() }
        assertEquals(listOf("Ünlü ğüş.pdf"), store(file).list().map { it.name })
    }

    @Test
    fun unreadableStoredData_isTreatedAsEmpty_andRecordRecovers() = runBlocking {
        val ds = dataStore(File(tmp.root, "bad.preferences_pb"))
        ds.edit { it[stringPreferencesKey("recent_files")] = "not json {" }
        val s = RecentFilesStore(ds)
        assertEquals(emptyList<RecentFile>(), s.list())
        s.record("content://a", "a.pdf")
        assertEquals(listOf("a.pdf"), s.list().map { it.name })
    }
}
