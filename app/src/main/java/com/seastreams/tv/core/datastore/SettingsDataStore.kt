package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.seastreams.tv.core.network.TmdbApiKeyProvider
import com.seastreams.tv.core.util.enumByNameOrNull
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
 * Typed facade over Preferences DataStore for global settings (audio, subtitles, player),
 * the active profile and the TMDB credential. All setters clamp values to their legal ranges.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : TmdbApiKeyProvider {

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        // A corrupt/unreadable file must not crash the app; fall back to defaults.
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val settings: Flow<AppSettings> = preferences.map(::toAppSettings).distinctUntilChanged()

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

    suspend fun updatePlayer(transform: (PlayerSettings) -> PlayerSettings) = edit { prefs ->
        val updated = transform(readPlayer(prefs))
        prefs[Keys.EXTERNAL_PLAYER] = updated.externalPlayer.name
        prefs[Keys.AUTO_PLAY_TOP_STREAM] = updated.autoPlayTopStream
        val addonUrl = updated.preferredAutoPlayAddonUrl
        if (addonUrl == null) prefs.remove(Keys.PREFERRED_AUTOPLAY_ADDON) else prefs[Keys.PREFERRED_AUTOPLAY_ADDON] = addonUrl
        prefs[Keys.FALLBACK_TIMEOUT_SECONDS] = clampFallbackTimeout(updated.fallbackTimeoutSeconds)
        prefs[Keys.AUTO_PLAY_FROM_CONTINUE_WATCHING] = updated.autoPlayFromContinueWatching
        prefs[Keys.BLUR_UNWATCHED_THUMBNAILS] = updated.blurUnwatchedThumbnails
        prefs[Keys.HDR_WHITE_DIMMING] = updated.hdrWhiteDimming
    }

    suspend fun updateAudio(transform: (AudioSettings) -> AudioSettings) = edit { prefs ->
        val updated = transform(readAudio(prefs))
        prefs[Keys.PASSTHROUGH_ENABLED] = updated.passthroughEnabled
        prefs[Keys.DIALOGUE_BOOST] = updated.dialogueBoost.name
        prefs[Keys.NIGHT_MODE] = updated.nightMode
        prefs[Keys.AUDIO_DELAY_MS] = snapAudioDelay(updated.audioDelayMs)
        prefs[Keys.PREFERRED_AUDIO_LANGUAGE] = updated.preferredAudioLanguage.ifBlank { DEFAULT_LANGUAGE }
    }

    suspend fun updateSubtitles(transform: (SubtitleSettings) -> SubtitleSettings) = edit { prefs ->
        val updated = transform(readSubtitles(prefs))
        prefs[Keys.SUBTITLE_MODE] = updated.mode.name
        prefs[Keys.SUBTITLE_LANGUAGE] = updated.preferredLanguage.ifBlank { DEFAULT_LANGUAGE }
        prefs[Keys.HIDE_NON_PREFERRED_SUBTITLES] = updated.hideNonPreferredLanguages
        prefs[Keys.CLEAN_SDH] = updated.cleanSdh
        val style = updated.style
        prefs[Keys.SUBTITLE_FONT_SCALE] =
            style.fontScale.coerceIn(SubtitleStyle.MIN_FONT_SCALE, SubtitleStyle.MAX_FONT_SCALE)
        prefs[Keys.SUBTITLE_BOLD] = style.bold
        prefs[Keys.SUBTITLE_ITALIC] = style.italic
        prefs[Keys.SUBTITLE_TEXT_COLOR] = style.textColor
        prefs[Keys.SUBTITLE_EDGE_TYPE] = style.edgeType.name
        prefs[Keys.SUBTITLE_BACKGROUND_OPACITY] = style.backgroundOpacity.coerceIn(0f, 1f)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private fun toAppSettings(prefs: Preferences) = AppSettings(
        player = readPlayer(prefs),
        audio = readAudio(prefs),
        subtitles = readSubtitles(prefs),
    )

    private fun readPlayer(prefs: Preferences): PlayerSettings {
        val defaults = PlayerSettings()
        return PlayerSettings(
            externalPlayer = enumOrDefault(prefs[Keys.EXTERNAL_PLAYER], defaults.externalPlayer),
            autoPlayTopStream = prefs[Keys.AUTO_PLAY_TOP_STREAM] ?: defaults.autoPlayTopStream,
            preferredAutoPlayAddonUrl = prefs[Keys.PREFERRED_AUTOPLAY_ADDON],
            fallbackTimeoutSeconds = clampFallbackTimeout(
                prefs[Keys.FALLBACK_TIMEOUT_SECONDS] ?: defaults.fallbackTimeoutSeconds,
            ),
            autoPlayFromContinueWatching =
                prefs[Keys.AUTO_PLAY_FROM_CONTINUE_WATCHING] ?: defaults.autoPlayFromContinueWatching,
            blurUnwatchedThumbnails = prefs[Keys.BLUR_UNWATCHED_THUMBNAILS] ?: defaults.blurUnwatchedThumbnails,
            hdrWhiteDimming = prefs[Keys.HDR_WHITE_DIMMING] ?: defaults.hdrWhiteDimming,
        )
    }

    private fun readAudio(prefs: Preferences): AudioSettings {
        val defaults = AudioSettings()
        return AudioSettings(
            passthroughEnabled = prefs[Keys.PASSTHROUGH_ENABLED] ?: defaults.passthroughEnabled,
            dialogueBoost = enumOrDefault(prefs[Keys.DIALOGUE_BOOST], defaults.dialogueBoost),
            nightMode = prefs[Keys.NIGHT_MODE] ?: defaults.nightMode,
            audioDelayMs = snapAudioDelay(prefs[Keys.AUDIO_DELAY_MS] ?: defaults.audioDelayMs),
            preferredAudioLanguage = prefs[Keys.PREFERRED_AUDIO_LANGUAGE] ?: defaults.preferredAudioLanguage,
        )
    }

    private fun readSubtitles(prefs: Preferences): SubtitleSettings {
        val defaults = SubtitleSettings()
        val defaultStyle = defaults.style
        return SubtitleSettings(
            mode = enumOrDefault(prefs[Keys.SUBTITLE_MODE], defaults.mode),
            preferredLanguage = prefs[Keys.SUBTITLE_LANGUAGE] ?: defaults.preferredLanguage,
            hideNonPreferredLanguages = prefs[Keys.HIDE_NON_PREFERRED_SUBTITLES] ?: defaults.hideNonPreferredLanguages,
            cleanSdh = prefs[Keys.CLEAN_SDH] ?: defaults.cleanSdh,
            style = SubtitleStyle(
                fontScale = prefs[Keys.SUBTITLE_FONT_SCALE] ?: defaultStyle.fontScale,
                bold = prefs[Keys.SUBTITLE_BOLD] ?: defaultStyle.bold,
                italic = prefs[Keys.SUBTITLE_ITALIC] ?: defaultStyle.italic,
                textColor = prefs[Keys.SUBTITLE_TEXT_COLOR] ?: defaultStyle.textColor,
                edgeType = enumOrDefault(prefs[Keys.SUBTITLE_EDGE_TYPE], defaultStyle.edgeType),
                backgroundOpacity = prefs[Keys.SUBTITLE_BACKGROUND_OPACITY] ?: defaultStyle.backgroundOpacity,
            ),
        )
    }

    private object Keys {
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val DEFAULTS_SEEDED = booleanPreferencesKey("defaults_seeded_v1")
        val DISABLED_PROVIDERS = stringSetPreferencesKey("catalog_disabled_providers")

        val EXTERNAL_PLAYER = stringPreferencesKey("player_external")
        val AUTO_PLAY_TOP_STREAM = booleanPreferencesKey("player_autoplay_top_stream")
        val PREFERRED_AUTOPLAY_ADDON = stringPreferencesKey("player_autoplay_addon")
        val FALLBACK_TIMEOUT_SECONDS = intPreferencesKey("player_fallback_timeout_s")
        val AUTO_PLAY_FROM_CONTINUE_WATCHING = booleanPreferencesKey("player_autoplay_continue_watching")
        val BLUR_UNWATCHED_THUMBNAILS = booleanPreferencesKey("player_blur_unwatched")
        val HDR_WHITE_DIMMING = booleanPreferencesKey("player_hdr_white_dimming")

        val PASSTHROUGH_ENABLED = booleanPreferencesKey("audio_passthrough")
        val DIALOGUE_BOOST = stringPreferencesKey("audio_dialogue_boost")
        val NIGHT_MODE = booleanPreferencesKey("audio_night_mode")
        val AUDIO_DELAY_MS = intPreferencesKey("audio_delay_ms")
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("audio_language")

        val SUBTITLE_MODE = stringPreferencesKey("subs_mode")
        val SUBTITLE_LANGUAGE = stringPreferencesKey("subs_language")
        val HIDE_NON_PREFERRED_SUBTITLES = booleanPreferencesKey("subs_hide_non_preferred")
        val CLEAN_SDH = booleanPreferencesKey("subs_clean_sdh")
        val SUBTITLE_FONT_SCALE = floatPreferencesKey("subs_font_scale")
        val SUBTITLE_BOLD = booleanPreferencesKey("subs_bold")
        val SUBTITLE_ITALIC = booleanPreferencesKey("subs_italic")
        val SUBTITLE_TEXT_COLOR = intPreferencesKey("subs_text_color")
        val SUBTITLE_EDGE_TYPE = stringPreferencesKey("subs_edge_type")
        val SUBTITLE_BACKGROUND_OPACITY = floatPreferencesKey("subs_background_opacity")
    }

    companion object {
        fun clampFallbackTimeout(seconds: Int): Int =
            seconds.coerceIn(PlayerSettings.FALLBACK_TIMEOUT_RANGE)

        /** Snaps to the nearest 25 ms step and clamps to ±500 ms. */
        fun snapAudioDelay(delayMs: Int): Int {
            val step = AudioSettings.AUDIO_DELAY_STEP_MS
            val snapped = (delayMs.toFloat() / step).roundToInt() * step
            return snapped.coerceIn(-AudioSettings.AUDIO_DELAY_LIMIT_MS, AudioSettings.AUDIO_DELAY_LIMIT_MS)
        }

        private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
            enumByNameOrNull<E>(name) ?: default
    }
}
