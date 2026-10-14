package org.icculus.chunky.morsecar.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/** Where you are in a book, as an offset into its raw text (so it survives speed changes). */
data class SavedPosition(val rawOffset: Int, val rawLength: Int) {
    val fraction: Float get() = if (rawLength <= 0) 0f else (rawOffset.toFloat() / rawLength).coerceIn(0f, 1f)
}

data class RecentBook(val uri: String, val title: String, val position: SavedPosition, val lastPlayedMs: Long)

/** Per-book positions, keyed by document URI, plus which book was playing last. */
class PositionStore(private val store: DataStore<Preferences>) {
    /** A read-only view, for looking up many books at once. */
    class Snapshot internal constructor(private val prefs: Preferences) {
        fun position(uri: String): SavedPosition? {
            val offset = prefs[offsetKey(uri)] ?: return null
            return SavedPosition(offset, prefs[lengthKey(uri)] ?: 0)
        }

        fun title(uri: String): String? = prefs[titleKey(uri)]

        val currentBook: String? get() = prefs[CURRENT_BOOK]

        fun recent(limit: Int): List<RecentBook> = prefs.asMap().keys
            .map { it.name }
            .filter { it.startsWith(PLAYED_PREFIX) }
            .map { it.removePrefix(PLAYED_PREFIX) }
            .mapNotNull { uri ->
                val position = position(uri) ?: return@mapNotNull null
                RecentBook(uri, title(uri) ?: "Unknown book", position, prefs[playedKey(uri)] ?: 0)
            }
            .sortedByDescending { it.lastPlayedMs }
            .take(limit)
    }

    suspend fun snapshot(): Snapshot = Snapshot(
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first(),
    )

    suspend fun save(uri: String, title: String, position: SavedPosition) {
        store.edit {
            it[offsetKey(uri)] = position.rawOffset
            it[lengthKey(uri)] = position.rawLength
            it[titleKey(uri)] = title
            it[playedKey(uri)] = System.currentTimeMillis()
            it[CURRENT_BOOK] = uri
        }
    }

    private companion object {
        const val PLAYED_PREFIX = "played|"
        val CURRENT_BOOK = stringPreferencesKey("current_book")

        fun offsetKey(uri: String) = intPreferencesKey("offset|$uri")
        fun lengthKey(uri: String) = intPreferencesKey("length|$uri")
        fun titleKey(uri: String) = stringPreferencesKey("title|$uri")
        fun playedKey(uri: String) = longPreferencesKey("$PLAYED_PREFIX$uri")
    }
}
