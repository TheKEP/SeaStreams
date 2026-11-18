package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.seastreams.tv.core.network.TmdbApiKeyProvider
import com.seastreams.tv.domain.model.StreamingProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Typed facade over Preferences DataStore.
 *
 * Every General Settings value (appearance, playback, trailers, audio, subtitles; PRD §10) is
 * stored **per profile** under `p{profileId}/…` keys: [settings] always reflects the active
 * profile and every setter writes only to the active profile. The active profile id, the TMDB
 * credential, the first-launch flag and the Movies/TV provider-row toggles stay app-wide.
 * All setters clamp values to their legal ranges.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : TmdbApiKeyProvider {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        // A corrupt/unreadable file must not crash the app; fall back to defaults.
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    /** The active profile's General Settings; documented defaults when no profile is active. */
    val settings: Flow<AppSettings> = preferences
        .map { prefs -> ProfileSettingsReader(prefs, prefs[Keys.ACTIVE_PROFILE_ID]).appSettings() }
        .distinctUntilChanged()

    val tmdbApiKey: Flow<String?> = preferences.map { it[Keys.TMDB_API_KEY] }.distinctUntilChanged()

    val activeProfileId: Flow<Long?> = preferences.map { it[Keys.ACTIVE_PROFILE_ID] }.distinctUntilChanged()

    override suspend fun currentApiKey(): String? = tmdbApiKey.first()

    /**
     * Streaming-service rows shown on the Movies/TV Shows tabs, in [StreamingProvider] order. The
     * *disabled* set is what is stored, so every provider (including ones added later) starts on.
     */
    val enabledProviders: Flow<List<StreamingProvider>> = preferences
        .map { prefs ->
            val disabled = prefs[Keys.DISABLED_PROVIDERS].orEmpty()
            StreamingProvider.entries.filterNot { it.name in disabled }
        }
        .distinctUntilChanged()

    suspend fun setProviderEnabled(provider: StreamingProvider, enabled: Boolean) = edit { prefs ->
        val disabled = prefs[Keys.DISABLED_PROVIDERS].orEmpty()
        prefs[Keys.DISABLED_PROVIDERS] = if (enabled) disabled - provider.name else disabled + provider.name
    }

    suspend fun setTmdbApiKey(apiKey: String?) = edit { prefs ->
        val trimmed = apiKey?.trim()
        if (trimmed.isNullOrEmpty()) prefs.remove(Keys.TMDB_API_KEY) else prefs[Keys.TMDB_API_KEY] = trimmed
    }

    /** Whether first-launch defaults (built-in addons) have been written; see `FirstLaunchSeeder`. */
    suspend fun isDefaultsSeeded(): Boolean = preferences.first()[Keys.DEFAULTS_SEEDED] ?: false

    suspend fun markDefaultsSeeded() = edit { prefs -> prefs[Keys.DEFAULTS_SEEDED] = true }

    suspend fun setActiveProfileId(profileId: Long?) = edit { prefs ->
        if (profileId == null) prefs.remove(Keys.ACTIVE_PROFILE_ID) else prefs[Keys.ACTIVE_PROFILE_ID] = profileId
    }

    suspend fun updatePlayer(transform: (PlayerSettings) -> PlayerSettings) = editActiveProfile { prefs, id ->
        ProfileSettingsWriter(prefs, id).player(transform(ProfileSettingsReader(prefs, id).player()))
    }

    /** Persists the page background; anything other than a [BackgroundSwatches] colour is ignored. */
    suspend fun setBackgroundColor(argb: Int) {
        if (argb !in BackgroundSwatches.ALL) return
        editActiveProfile { prefs, id -> ProfileSettingsWriter(prefs, id)[ProfileSettingsKeys.BACKGROUND_COLOR] = argb }
    }

    suspend fun setCatalogRowScrollSpeed(speed: CatalogRowScrollSpeed) = editActiveProfile { prefs, id ->
        ProfileSettingsWriter(prefs, id)[ProfileSettingsKeys.CATALOG_ROW_SCROLL_SPEED] = speed.name
    }

    suspend fun updateAudio(transform: (AudioSettings) -> AudioSettings) = editActiveProfile { prefs, id ->
        ProfileSettingsWriter(prefs, id).audio(transform(ProfileSettingsReader(prefs, id).audio()))
    }

    suspend fun updateSubtitles(transform: (SubtitleSettings) -> SubtitleSettings) = editActiveProfile { prefs, id ->
        ProfileSettingsWriter(prefs, id).subtitles(transform(ProfileSettingsReader(prefs, id).subtitles()))
    }

    /**
     * Removes every General Settings value stored for [profileId] (called when the profile is
     * deleted) and, if it was the active profile, clears the active selection in the same write.
     */
    suspend fun clearProfile(profileId: Long) = edit { prefs ->
        val prefix = profileKeyPrefix(profileId)
        prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { prefs.remove(it) }
        if (prefs[Keys.ACTIVE_PROFILE_ID] == profileId) prefs.remove(Keys.ACTIVE_PROFILE_ID)
    }

    /**
     * One-time upgrade from app-wide settings: copies each legacy global value into every profile in
     * [profileIds] that has no value of its own yet, then deletes the legacy keys. Runs at most once;
     * the done-flag is written even when [profileIds] is empty (legacy values are then dropped).
     */
    suspend fun migrateLegacySettings(profileIds: List<Long>) = edit { prefs ->
        if (prefs[Keys.LEGACY_SETTINGS_MIGRATED] == true) return@edit
        ProfileSettingsKeys.ALL.forEach { it.migrateLegacy(prefs, profileIds) }
        prefs[Keys.LEGACY_SETTINGS_MIGRATED] = true
    }

    /** Edits the active profile's settings atomically; a no-op while no profile is active. */
    private suspend fun editActiveProfile(block: (MutablePreferences, Long) -> Unit) = edit { prefs ->
        prefs[Keys.ACTIVE_PROFILE_ID]?.let { id -> block(prefs, id) }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private object Keys {
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val DEFAULTS_SEEDED = booleanPreferencesKey("defaults_seeded_v1")
        val DISABLED_PROVIDERS = stringSetPreferencesKey("catalog_disabled_providers")
        val LEGACY_SETTINGS_MIGRATED = booleanPreferencesKey("legacy_settings_migrated_v1")
    }

    companion object {
        fun clampFallbackTimeout(seconds: Int): Int =
            seconds.coerceIn(PlayerSettings.FALLBACK_TIMEOUT_RANGE)

        fun clampParallelConnections(count: Int): Int = count.coerceIn(PlayerSettings.PARALLEL_CONNECTIONS_RANGE)

        fun clampTrailerDelay(seconds: Int): Int = seconds.coerceIn(PlayerSettings.TRAILER_DELAY_RANGE)

        /** Snaps to the nearest 25 ms step and clamps to ±500 ms. */
        fun snapAudioDelay(delayMs: Int): Int {
            val step = AudioSettings.AUDIO_DELAY_STEP_MS
            val snapped = (delayMs.toFloat() / step).roundToInt() * step
            return snapped.coerceIn(-AudioSettings.AUDIO_DELAY_LIMIT_MS, AudioSettings.AUDIO_DELAY_LIMIT_MS)
        }
    }
}
