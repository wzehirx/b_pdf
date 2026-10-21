package io.github.wzehirx.bpdf.core.files

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A document opened earlier: where to read it again ([uri], a content Uri we hold a persisted permission for) and its name. */
data class RecentFile(val uri: String, val name: String, val openedAt: Long)

/**
 * The last [maxEntries] documents opened in the viewer, newest first, kept in a DataStore so they
 * survive restarts. Only the Uri, the display name and the time are stored; never file contents.
 */
class RecentFilesStore(
    private val store: DataStore<Preferences>,
    private val maxEntries: Int = DEFAULT_MAX,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val recents: Flow<List<RecentFile>> = store.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { decode(it[KEY]) }

    /**
     * Puts [uri] at the top (replacing an older entry for the same Uri, so the name stays current).
     * Returns the Uris that fell off the end of the list, whose persisted permission can be released.
     */
    suspend fun record(uri: String, name: String): List<String> {
        var dropped = emptyList<String>()
        store.edit { prefs ->
            val all = listOf(RecentFile(uri, name, clock())) + decode(prefs[KEY]).filter { it.uri != uri }
            dropped = all.drop(maxEntries).map { it.uri }
            prefs[KEY] = encode(all.take(maxEntries))
        }
        return dropped
    }

    suspend fun remove(uri: String) {
        store.edit { prefs -> prefs[KEY] = encode(decode(prefs[KEY]).filter { it.uri != uri }) }
    }

    private fun encode(files: List<RecentFile>): String =
        JSONArray().apply {
            files.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name).put("at", it.openedAt)) }
        }.toString()

    private fun decode(json: String?): List<RecentFile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RecentFile(uri, o.optString("name", uri), o.optLong("at"))
            }
        } catch (_: JSONException) {
            emptyList() // unreadable data is treated as an empty list
        }
    }

    companion object {
        const val DEFAULT_MAX = 10
        private val KEY = stringPreferencesKey("recent_files")
    }
}
