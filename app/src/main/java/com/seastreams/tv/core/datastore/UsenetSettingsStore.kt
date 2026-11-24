package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.data.usenet.headers.RequestHeaderSettings
import com.seastreams.tv.data.usenet.headers.releaseVersion
import com.seastreams.tv.domain.usenet.UsenetPerformance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val usenetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * App-wide Usenet Sources settings (PRD §12): the performance profile and values, and the request
 * header choices plus the latest versions fetched by `HeaderVersionUpdater`. Unlike General
 * Settings these are never scoped to a profile (`usenet/` keys, not `p{id}/`), because provider
 * connection limits belong to the account and the device.
 */
@Singleton
class UsenetSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    /**
     * `Enable Usenet` (PRD §11, default off): while off the built-in `Usenet` entry is hidden and nothing is searched,
     * but every provider, indexer and setting is kept.
     */
    val enabled: Flow<Boolean> = preferences.map { it[Keys.ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    /** Whether the one-time upgrade check (`SourceTogglesMigrator`) has run. */
    suspend fun toggleMigrated(): Boolean = preferences.first()[Keys.ENABLED_MIGRATED] == true

    /** Records the upgrade check; [enable] turns `Enable Usenet` on for an install that was already set up. */
    suspend fun finishToggleMigration(enable: Boolean) {
        dataStore.edit { prefs ->
            if (prefs[Keys.ENABLED_MIGRATED] == true) return@edit
            if (enable) prefs[Keys.ENABLED] = true
            prefs[Keys.ENABLED_MIGRATED] = true
        }
    }

    /**
     * `Clear Usenet settings`: every `usenet/` key (the toggle, performance, header presets and checked versions), so
     * Usenet reads as fresh. Only the upgrade marker stays, so the one-time migration never runs again.
     */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(KEY_PREFIX) && it != Keys.ENABLED_MIGRATED }
                .forEach { prefs.remove(it) }
        }
    }

    /** Clamped performance values; the Conservative defaults when none are stored or the JSON is unreadable. */
    val performance: Flow<UsenetPerformance> = preferences
        .map { decodePerformance(it[Keys.PERFORMANCE]) }
        .distinctUntilChanged()

    val headers: Flow<RequestHeaderSettings> = preferences
        .map { prefs ->
            RequestHeaderSettings(
                search = enumByNameOrNull<HeaderPreset>(prefs[Keys.HEADERS_SEARCH].orEmpty()) ?: HeaderPreset.DEFAULT_SEARCH,
                download = enumByNameOrNull<HeaderPreset>(prefs[Keys.HEADERS_DOWNLOAD].orEmpty()) ?: HeaderPreset.DEFAULT_DOWNLOAD,
                versions = decodeVersions(prefs[Keys.HEADER_VERSIONS]),
                checkedAtMillis = prefs[Keys.HEADER_CHECKED_AT],
            )
        }
        .distinctUntilChanged()

    suspend fun updatePerformance(transform: (UsenetPerformance) -> UsenetPerformance) {
        dataStore.edit { prefs ->
            val next = transform(decodePerformance(prefs[Keys.PERFORMANCE])).clamped()
            prefs[Keys.PERFORMANCE] = usenetJson.encodeToString(UsenetPerformance.serializer(), next)
        }
    }

    suspend fun setHeaderPreset(use: HeaderUse, preset: HeaderPreset) {
        dataStore.edit { prefs ->
            prefs[if (use == HeaderUse.SEARCH) Keys.HEADERS_SEARCH else Keys.HEADERS_DOWNLOAD] = preset.name
        }
    }

    /** Merges [fetched] into the stored versions and records a successful check at [checkedAtMillis]. */
    suspend fun saveHeaderVersions(fetched: Map<HeaderPreset, String>, checkedAtMillis: Long) {
        dataStore.edit { prefs ->
            val merged = decodeVersions(prefs[Keys.HEADER_VERSIONS]) + fetched
            prefs[Keys.HEADER_VERSIONS] = usenetJson.encodeToString(
                VersionMapSerializer,
                merged.mapKeys { (preset, _) -> preset.name },
            )
            prefs[Keys.HEADER_CHECKED_AT] = checkedAtMillis
        }
    }

    /** Backup restore, inside its single edit: the toggle, clamped performance values and both header presets. */
    internal fun writeBackup(prefs: MutablePreferences, enabled: Boolean, performance: UsenetPerformance, search: HeaderPreset, download: HeaderPreset) {
        prefs[Keys.ENABLED] = enabled
        prefs[Keys.PERFORMANCE] = usenetJson.encodeToString(UsenetPerformance.serializer(), performance.clamped())
        prefs[Keys.HEADERS_SEARCH] = search.name
        prefs[Keys.HEADERS_DOWNLOAD] = download.name
    }

    private fun decodePerformance(raw: String?): UsenetPerformance {
        if (raw.isNullOrBlank()) return UsenetPerformance()
        return try {
            usenetJson.decodeFromString(UsenetPerformance.serializer(), raw).clamped()
        } catch (e: SerializationException) {
            UsenetPerformance()
        } catch (e: IllegalArgumentException) {
            UsenetPerformance()
        }
    }

    /** Stored versions, keeping only known presets with a plain version (a hand-edited file can't inject header text). */
    private fun decodeVersions(raw: String?): Map<HeaderPreset, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val decoded = try {
            usenetJson.decodeFromString(VersionMapSerializer, raw)
        } catch (e: SerializationException) {
            return emptyMap()
        } catch (e: IllegalArgumentException) {
            return emptyMap()
        }
        return decoded.mapNotNull { (name, version) ->
            val preset = enumByNameOrNull<HeaderPreset>(name) ?: return@mapNotNull null
            releaseVersion(version)?.let { preset to it }
        }.toMap()
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("usenet/enabled")
        val ENABLED_MIGRATED = booleanPreferencesKey("usenet/enabled_migrated")
        val PERFORMANCE = stringPreferencesKey("usenet/performance")
        val HEADERS_SEARCH = stringPreferencesKey("usenet/headers_search")
        val HEADERS_DOWNLOAD = stringPreferencesKey("usenet/headers_download")
        val HEADER_VERSIONS = stringPreferencesKey("usenet/header_versions")
        val HEADER_CHECKED_AT = longPreferencesKey("usenet/header_checked_at")
    }
}

private const val KEY_PREFIX = "usenet/"

private val VersionMapSerializer = MapSerializer(String.serializer(), String.serializer())
