package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide Debrid settings (PRD §11 `DEBRID`, §14) under `debrid/` keys, never scoped to a profile. Strictly separate
 * from `UsenetSettingsStore`: nothing here reads or writes a `usenet/` key.
 */
@Singleton
class DebridSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    /** `Enable Debrid` (default off): while off Debrid is not searched, but every provider is kept. */
    val enabled: Flow<Boolean> = preferences.map { it[ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    /** `Clear Debrid settings`: every `debrid/` key, so Debrid reads as fresh (`Enable Debrid` off). */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(KEY_PREFIX) }.forEach { prefs.remove(it) }
        }
    }

    private companion object {
        const val KEY_PREFIX = "debrid/"
        val ENABLED = booleanPreferencesKey("debrid/enabled")
    }
}
