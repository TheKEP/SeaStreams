package com.seastreams.tv.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.seastreams.tv.core.datastore.SettingsDataStore.Companion.clampFallbackTimeout
import com.seastreams.tv.core.datastore.SettingsDataStore.Companion.clampParallelConnections
import com.seastreams.tv.core.datastore.SettingsDataStore.Companion.clampTrailerDelay
import com.seastreams.tv.core.datastore.SettingsDataStore.Companion.snapAudioDelay
import com.seastreams.tv.core.util.enumByNameOrNull

/** Storage-name prefix for every General Settings key owned by [profileId] (`p{id}/…`). */
internal fun profileKeyPrefix(profileId: Long): String = "p$profileId/"

/**
 * A General Settings key stored once per profile. [legacy] is the pre-per-profile global key with
 * the same name; it is only read as a fallback until `migrateLegacySettings` has copied it out.
 */
internal class ScopedKey<T>(val name: String, private val factory: (String) -> Preferences.Key<T>) {
    val legacy: Preferences.Key<T> = factory(name)

    fun forProfile(profileId: Long): Preferences.Key<T> = factory(profileKeyPrefix(profileId) + name)

    /** Copies the legacy value into each profile that has none yet, then drops the legacy key. */
    fun migrateLegacy(prefs: MutablePreferences, profileIds: List<Long>) {
        val value = prefs[legacy] ?: return
        profileIds.map(::forProfile).filter { prefs[it] == null }.forEach { prefs[it] = value }
        prefs.remove(legacy)
    }
}

/** Every per-profile General Settings key (PRD §10), keeping the original storage names. */
internal object ProfileSettingsKeys {
    val EXTERNAL_PLAYER = ScopedKey("player_external", ::stringPreferencesKey)
    val AUTO_PLAY_TOP_STREAM = ScopedKey("player_autoplay_top_stream", ::booleanPreferencesKey)
    val PREFERRED_AUTOPLAY_ADDON = ScopedKey("player_autoplay_addon", ::stringPreferencesKey)
    val FALLBACK_TIMEOUT_SECONDS = ScopedKey("player_fallback_timeout_s", ::intPreferencesKey)
    val AUTO_PLAY_FROM_CONTINUE_WATCHING = ScopedKey("player_autoplay_continue_watching", ::booleanPreferencesKey)
    val BLUR_UNWATCHED_THUMBNAILS = ScopedKey("player_blur_unwatched", ::booleanPreferencesKey)
    val HDR_WHITE_DIMMING = ScopedKey("player_hdr_white_dimming", ::booleanPreferencesKey)
    val AUTO_PLAY_NEXT_EPISODE = ScopedKey("player_autoplay_next_episode", ::booleanPreferencesKey)
    val CONVERT_DV_PROFILE_7 = ScopedKey("player_convert_dv_profile7", ::booleanPreferencesKey)
    val BUFFER_PROFILE = ScopedKey("player_buffer_profile", ::stringPreferencesKey)
    val PARALLEL_CONNECTIONS = ScopedKey("player_parallel_connections", ::intPreferencesKey)
    val AUTO_PLAY_TRAILERS = ScopedKey("player_autoplay_trailers", ::booleanPreferencesKey)
    val MUTE_AUTO_PLAY_TRAILERS = ScopedKey("player_mute_autoplay_trailers", ::booleanPreferencesKey)
    val TRAILER_DELAY_SECONDS = ScopedKey("player_trailer_delay_s", ::intPreferencesKey)
    val BACKGROUND_COLOR = ScopedKey("ui_background_color", ::intPreferencesKey)
    val CATALOG_ROW_SCROLL_SPEED = ScopedKey("ui_catalog_row_scroll_speed", ::stringPreferencesKey)

    val PASSTHROUGH_ENABLED = ScopedKey("audio_passthrough", ::booleanPreferencesKey)
    val DIALOGUE_BOOST = ScopedKey("audio_dialogue_boost", ::stringPreferencesKey)
    val NIGHT_MODE = ScopedKey("audio_night_mode", ::booleanPreferencesKey)
    val AUDIO_DELAY_MS = ScopedKey("audio_delay_ms", ::intPreferencesKey)
    val PREFERRED_AUDIO_LANGUAGE = ScopedKey("audio_language", ::stringPreferencesKey)

    val SUBTITLE_MODE = ScopedKey("subs_mode", ::stringPreferencesKey)
    val SUBTITLE_LANGUAGE = ScopedKey("subs_language", ::stringPreferencesKey)
    val HIDE_NON_PREFERRED_SUBTITLES = ScopedKey("subs_hide_non_preferred", ::booleanPreferencesKey)
    val CLEAN_SDH = ScopedKey("subs_clean_sdh", ::booleanPreferencesKey)
    val SUBTITLE_FONT_SCALE = ScopedKey("subs_font_scale", ::floatPreferencesKey)
    val SUBTITLE_BOLD = ScopedKey("subs_bold", ::booleanPreferencesKey)
    val SUBTITLE_ITALIC = ScopedKey("subs_italic", ::booleanPreferencesKey)
    val SUBTITLE_TEXT_COLOR = ScopedKey("subs_text_color", ::intPreferencesKey)
    val SUBTITLE_EDGE_TYPE = ScopedKey("subs_edge_type", ::stringPreferencesKey)
    val SUBTITLE_BACKGROUND_OPACITY = ScopedKey("subs_background_opacity", ::floatPreferencesKey)

    val ALL: List<ScopedKey<*>> = listOf(
        EXTERNAL_PLAYER, AUTO_PLAY_TOP_STREAM, PREFERRED_AUTOPLAY_ADDON, FALLBACK_TIMEOUT_SECONDS,
        AUTO_PLAY_FROM_CONTINUE_WATCHING, BLUR_UNWATCHED_THUMBNAILS, HDR_WHITE_DIMMING, AUTO_PLAY_NEXT_EPISODE,
        CONVERT_DV_PROFILE_7, BUFFER_PROFILE, PARALLEL_CONNECTIONS, AUTO_PLAY_TRAILERS, MUTE_AUTO_PLAY_TRAILERS,
        TRAILER_DELAY_SECONDS, BACKGROUND_COLOR, CATALOG_ROW_SCROLL_SPEED,
        PASSTHROUGH_ENABLED, DIALOGUE_BOOST, NIGHT_MODE, AUDIO_DELAY_MS, PREFERRED_AUDIO_LANGUAGE,
        SUBTITLE_MODE, SUBTITLE_LANGUAGE, HIDE_NON_PREFERRED_SUBTITLES, CLEAN_SDH, SUBTITLE_FONT_SCALE,
        SUBTITLE_BOLD, SUBTITLE_ITALIC, SUBTITLE_TEXT_COLOR, SUBTITLE_EDGE_TYPE, SUBTITLE_BACKGROUND_OPACITY,
    )
}

/**
 * Reads one profile's settings: its own value, else the legacy global value (present only until
 * migration), else the documented default. A `null` profile (none chosen yet) sees legacy/defaults.
 */
internal class ProfileSettingsReader(private val prefs: Preferences, private val profileId: Long?) {

    operator fun <T> get(key: ScopedKey<T>): T? =
        profileId?.let { prefs[key.forProfile(it)] } ?: prefs[key.legacy]

    fun appSettings() = AppSettings(
        player = player(),
        audio = audio(),
        subtitles = subtitles(),
        appearance = AppearanceSettings(
            backgroundArgb = this[ProfileSettingsKeys.BACKGROUND_COLOR]?.takeIf { it in BackgroundSwatches.ALL }
                ?: BackgroundSwatches.DEFAULT,
            catalogRowScrollSpeed =
                enumOrDefault(this[ProfileSettingsKeys.CATALOG_ROW_SCROLL_SPEED], CatalogRowScrollSpeed.DEFAULT),
        ),
    )

    fun player(): PlayerSettings = with(ProfileSettingsKeys) {
        val defaults = PlayerSettings()
        PlayerSettings(
            playerChoice = PlayerChoice.fromStorage(get(EXTERNAL_PLAYER)),
            autoPlayTopStream = get(AUTO_PLAY_TOP_STREAM) ?: defaults.autoPlayTopStream,
            autoPlayNextEpisode = get(AUTO_PLAY_NEXT_EPISODE) ?: defaults.autoPlayNextEpisode,
            convertDvProfile7ToHdr = get(CONVERT_DV_PROFILE_7) ?: defaults.convertDvProfile7ToHdr,
            preferredAutoPlayAddonUrl = get(PREFERRED_AUTOPLAY_ADDON),
            fallbackTimeoutSeconds = clampFallbackTimeout(get(FALLBACK_TIMEOUT_SECONDS) ?: defaults.fallbackTimeoutSeconds),
            autoPlayFromContinueWatching =
                get(AUTO_PLAY_FROM_CONTINUE_WATCHING) ?: defaults.autoPlayFromContinueWatching,
            blurUnwatchedThumbnails = get(BLUR_UNWATCHED_THUMBNAILS) ?: defaults.blurUnwatchedThumbnails,
            hdrWhiteDimming = get(HDR_WHITE_DIMMING) ?: defaults.hdrWhiteDimming,
            playbackBufferProfile = enumOrDefault(get(BUFFER_PROFILE), defaults.playbackBufferProfile),
            parallelStreamConnections =
                clampParallelConnections(get(PARALLEL_CONNECTIONS) ?: defaults.parallelStreamConnections),
            autoPlayTrailers = get(AUTO_PLAY_TRAILERS) ?: defaults.autoPlayTrailers,
            muteAutoPlayTrailers = get(MUTE_AUTO_PLAY_TRAILERS) ?: defaults.muteAutoPlayTrailers,
            trailerAutoPlayDelaySeconds =
                clampTrailerDelay(get(TRAILER_DELAY_SECONDS) ?: defaults.trailerAutoPlayDelaySeconds),
        )
    }

    fun audio(): AudioSettings = with(ProfileSettingsKeys) {
        val defaults = AudioSettings()
        AudioSettings(
            passthroughEnabled = get(PASSTHROUGH_ENABLED) ?: defaults.passthroughEnabled,
            dialogueBoost = enumOrDefault(get(DIALOGUE_BOOST), defaults.dialogueBoost),
            nightMode = get(NIGHT_MODE) ?: defaults.nightMode,
            audioDelayMs = snapAudioDelay(get(AUDIO_DELAY_MS) ?: defaults.audioDelayMs),
            preferredAudioLanguage = get(PREFERRED_AUDIO_LANGUAGE) ?: defaults.preferredAudioLanguage,
        )
    }

    fun subtitles(): SubtitleSettings = with(ProfileSettingsKeys) {
        val defaults = SubtitleSettings()
        val defaultStyle = defaults.style
        SubtitleSettings(
            mode = enumOrDefault(get(SUBTITLE_MODE), defaults.mode),
            preferredLanguage = get(SUBTITLE_LANGUAGE) ?: defaults.preferredLanguage,
            hideNonPreferredLanguages = get(HIDE_NON_PREFERRED_SUBTITLES) ?: defaults.hideNonPreferredLanguages,
            cleanSdh = get(CLEAN_SDH) ?: defaults.cleanSdh,
            style = SubtitleStyle(
                fontScale = get(SUBTITLE_FONT_SCALE) ?: defaultStyle.fontScale,
                bold = get(SUBTITLE_BOLD) ?: defaultStyle.bold,
                italic = get(SUBTITLE_ITALIC) ?: defaultStyle.italic,
                textColor = get(SUBTITLE_TEXT_COLOR) ?: defaultStyle.textColor,
                edgeType = enumOrDefault(get(SUBTITLE_EDGE_TYPE), defaultStyle.edgeType),
                backgroundOpacity = get(SUBTITLE_BACKGROUND_OPACITY) ?: defaultStyle.backgroundOpacity,
            ),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumByNameOrNull<E>(name) ?: default
}

/** Writes one profile's settings, clamping every value to its legal range. */
internal class ProfileSettingsWriter(private val prefs: MutablePreferences, private val profileId: Long) {

    operator fun <T> set(key: ScopedKey<T>, value: T) {
        prefs[key.forProfile(profileId)] = value
    }

    fun player(updated: PlayerSettings) = with(ProfileSettingsKeys) {
        set(EXTERNAL_PLAYER, updated.playerChoice.storageValue)
        set(AUTO_PLAY_TOP_STREAM, updated.autoPlayTopStream)
        set(AUTO_PLAY_NEXT_EPISODE, updated.autoPlayNextEpisode)
        set(CONVERT_DV_PROFILE_7, updated.convertDvProfile7ToHdr)
        val addonUrl = updated.preferredAutoPlayAddonUrl
        if (addonUrl == null) prefs.remove(PREFERRED_AUTOPLAY_ADDON.forProfile(profileId)) else set(PREFERRED_AUTOPLAY_ADDON, addonUrl)
        set(FALLBACK_TIMEOUT_SECONDS, clampFallbackTimeout(updated.fallbackTimeoutSeconds))
        set(AUTO_PLAY_FROM_CONTINUE_WATCHING, updated.autoPlayFromContinueWatching)
        set(BLUR_UNWATCHED_THUMBNAILS, updated.blurUnwatchedThumbnails)
        set(HDR_WHITE_DIMMING, updated.hdrWhiteDimming)
        set(BUFFER_PROFILE, updated.playbackBufferProfile.name)
        set(PARALLEL_CONNECTIONS, clampParallelConnections(updated.parallelStreamConnections))
        set(AUTO_PLAY_TRAILERS, updated.autoPlayTrailers)
        set(MUTE_AUTO_PLAY_TRAILERS, updated.muteAutoPlayTrailers)
        set(TRAILER_DELAY_SECONDS, clampTrailerDelay(updated.trailerAutoPlayDelaySeconds))
    }

    fun audio(updated: AudioSettings) = with(ProfileSettingsKeys) {
        set(PASSTHROUGH_ENABLED, updated.passthroughEnabled)
        set(DIALOGUE_BOOST, updated.dialogueBoost.name)
        set(NIGHT_MODE, updated.nightMode)
        set(AUDIO_DELAY_MS, snapAudioDelay(updated.audioDelayMs))
        set(PREFERRED_AUDIO_LANGUAGE, updated.preferredAudioLanguage.ifBlank { DEFAULT_LANGUAGE })
    }

    fun subtitles(updated: SubtitleSettings) = with(ProfileSettingsKeys) {
        set(SUBTITLE_MODE, updated.mode.name)
        set(SUBTITLE_LANGUAGE, updated.preferredLanguage.ifBlank { DEFAULT_LANGUAGE })
        set(HIDE_NON_PREFERRED_SUBTITLES, updated.hideNonPreferredLanguages)
        set(CLEAN_SDH, updated.cleanSdh)
        val style = updated.style
        set(SUBTITLE_FONT_SCALE, style.fontScale.coerceIn(SubtitleStyle.MIN_FONT_SCALE, SubtitleStyle.MAX_FONT_SCALE))
        set(SUBTITLE_BOLD, style.bold)
        set(SUBTITLE_ITALIC, style.italic)
        set(SUBTITLE_TEXT_COLOR, style.textColor)
        set(SUBTITLE_EDGE_TYPE, style.edgeType.name)
        set(SUBTITLE_BACKGROUND_OPACITY, style.backgroundOpacity.coerceIn(0f, 1f))
    }
}
