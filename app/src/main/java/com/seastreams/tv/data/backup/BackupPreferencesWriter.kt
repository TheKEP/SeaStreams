package com.seastreams.tv.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.ProfileSettingsWriter
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import javax.inject.Inject

/**
 * The preference half of a restore, as one atomic DataStore edit: every store shares the app's single
 * `DataStore<Preferences>`, so either all of it lands or none does. Values are clamped by the stores' own writers.
 */
internal class BackupPreferencesWriter @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val streams: StreamProcessingStore,
    private val usenetSettings: UsenetSettingsStore,
    private val debridSettings: DebridSettingsStore,
) {

    /**
     * Writes each of [restored]'s (profile id → entry) General Settings and stream processing, the Usenet / Debrid
     * settings, a non-blank TMDB key, and makes the first restored profile active when the active one isn't among
     * [profileIds] (every profile on the TV after the restore).
     */
    suspend fun write(backup: AppBackup, restored: List<Pair<Long, ProfileBackupEntry>>, profileIds: Set<Long>) {
        dataStore.edit { prefs ->
            restored.forEach { (id, entry) ->
                ProfileSettingsWriter(prefs, id).all(entry.settings.toAppSettings())
                streams.writeBackup(prefs, id, entry.streams)
            }
            val usenet = backup.usenet
            usenetSettings.writeBackup(
                prefs,
                enabled = usenet.enabled,
                performance = usenet.performance,
                search = enumByNameOrNull<HeaderPreset>(usenet.searchHeaders) ?: HeaderPreset.DEFAULT_SEARCH,
                download = enumByNameOrNull<HeaderPreset>(usenet.downloadHeaders) ?: HeaderPreset.DEFAULT_DOWNLOAD,
            )
            debridSettings.writeBackup(prefs, backup.debrid.enabled)
            SettingsDataStore.restoreAppKeys(prefs, backup.tmdbApiKey, profileIds, fallback = restored.firstOrNull()?.first)
        }
    }
}
