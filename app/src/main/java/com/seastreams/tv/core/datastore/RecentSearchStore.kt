package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each profile's recent Search queries (the chips under the search bar), newest first, capped at
 * [MAX_RECENT]. Stored as newline-separated text in the app's Preferences DataStore.
 */
@Singleton
class RecentSearchStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    fun observe(profileId: Long): Flow<List<String>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> decode(prefs[key(profileId)]) }
        .distinctUntilChanged()

    /** Moves [query] to the front (case-insensitive de-duplication); blank queries are ignored. */
    suspend fun add(profileId: Long, query: String) {
        val cleaned = normalize(query)
        if (cleaned.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[key(profileId)] = encode(withRecent(decode(prefs[key(profileId)]), cleaned))
        }
    }

    suspend fun clear(profileId: Long) {
        dataStore.edit { prefs -> prefs.remove(key(profileId)) }
    }

    companion object {
        const val MAX_RECENT = 8
        private const val SEPARATOR = "\n"
        private val WHITESPACE = Regex("""\s+""")

        /** Single-line, trimmed, with runs of whitespace collapsed. */
        fun normalize(query: String): String = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

        /** [query] first, then the older entries minus any case-insensitive duplicate, capped. */
        fun withRecent(existing: List<String>, query: String): List<String> =
            (listOf(query) + existing.filterNot { it.equals(query, ignoreCase = true) }).take(MAX_RECENT)

        private fun key(profileId: Long) = stringPreferencesKey("recent_searches_$profileId")

        private fun encode(queries: List<String>): String = queries.joinToString(SEPARATOR)

        private fun decode(raw: String?): List<String> = raw?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    }
}
