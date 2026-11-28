package com.seastreams.tv.data.backup

import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.AppearanceSettings
import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.DialogueBoost
import com.seastreams.tv.core.datastore.DolbyVisionPlayback
import com.seastreams.tv.core.datastore.PlaybackBufferProfile
import com.seastreams.tv.core.datastore.PlaybackSpeed
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SubtitleEdgeType
import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.core.datastore.SubtitleSettings
import com.seastreams.tv.core.datastore.SubtitleStyle
import com.seastreams.tv.core.datastore.VolumeBoost
import com.seastreams.tv.core.util.enumByNameOrNull
import kotlinx.serialization.Serializable

/**
 * One profile's General Settings (PRD §11: UI, Playback, Audio, Subtitles, Appearance) as a backup stores them. Enums
 * travel as names; a missing field or an unknown name reads as the documented default, so files from older or newer
 * builds still restore. Range clamping happens when the restore writes them (`ProfileSettingsWriter`).
 */
@Serializable
data class GeneralSettingsBackup(
    val player: PlayerBackup = PlayerBackup(),
    val audio: AudioBackup = AudioBackup(),
    val subtitles: SubtitlesBackup = SubtitlesBackup(),
    val backgroundArgb: Int = AppearanceSettings().backgroundArgb,
) {
    fun toAppSettings(): AppSettings = AppSettings(
        player = player.toSettings(),
        audio = audio.toSettings(),
        subtitles = subtitles.toSettings(),
        appearance = AppearanceSettings(backgroundArgb),
    )

    companion object {
        fun of(settings: AppSettings) = GeneralSettingsBackup(
            player = PlayerBackup.of(settings.player),
            audio = AudioBackup.of(settings.audio),
            subtitles = SubtitlesBackup.of(settings.subtitles),
            backgroundArgb = settings.appearance.backgroundArgb,
        )
    }
}

private val PLAYER = PlayerSettings()
private val AUDIO = AudioSettings()
private val SUBS = SubtitleSettings()
private val STYLE = SubtitleStyle()

@Serializable
data class PlayerBackup(
    /** `PlayerChoice.storageValue`: `builtin`, `chooser` or an app's package. */
    val player: String = PLAYER.playerChoice.storageValue,
    val autoPlayTopStream: Boolean = PLAYER.autoPlayTopStream,
    val preferredAutoPlayAddonUrl: String? = null,
    val autoPlayNextEpisode: Boolean = PLAYER.autoPlayNextEpisode,
    val autoStreamFailover: Boolean = PLAYER.autoStreamFailover,
    val fallbackTimeoutSeconds: Int = PLAYER.fallbackTimeoutSeconds,
    val autoPlayFromContinueWatching: Boolean = PLAYER.autoPlayFromContinueWatching,
    val defaultPlaybackSpeed: String = PLAYER.defaultPlaybackSpeed.name,
    val blurUnwatchedThumbnails: Boolean = PLAYER.blurUnwatchedThumbnails,
    val convertDvProfile7ToHdr: Boolean = PLAYER.convertDvProfile7ToHdr,
    val dolbyVisionPlayback: String = PLAYER.dolbyVisionPlayback.name,
    val hdrWhiteDimming: Boolean = PLAYER.hdrWhiteDimming,
    val playbackBufferProfile: String = PLAYER.playbackBufferProfile.name,
    val parallelStreamConnections: Int = PLAYER.parallelStreamConnections,
    val autoPlayTrailers: Boolean = PLAYER.autoPlayTrailers,
    val muteAutoPlayTrailers: Boolean = PLAYER.muteAutoPlayTrailers,
    val trailerAutoPlayDelaySeconds: Int = PLAYER.trailerAutoPlayDelaySeconds,
    /** Absent in files from before Next Up (Milestone 13): restores the default (on). */
    val showNextUpInContinueWatching: Boolean = PLAYER.showNextUpInContinueWatching,
) {
    fun toSettings() = PlayerSettings(
        playerChoice = PlayerChoice.fromStorage(player),
        autoPlayTopStream = autoPlayTopStream,
        preferredAutoPlayAddonUrl = preferredAutoPlayAddonUrl?.takeIf { it.isNotBlank() },
        autoPlayNextEpisode = autoPlayNextEpisode,
        autoStreamFailover = autoStreamFailover,
        fallbackTimeoutSeconds = fallbackTimeoutSeconds,
        autoPlayFromContinueWatching = autoPlayFromContinueWatching,
        defaultPlaybackSpeed = enumByNameOrNull<PlaybackSpeed>(defaultPlaybackSpeed) ?: PLAYER.defaultPlaybackSpeed,
        blurUnwatchedThumbnails = blurUnwatchedThumbnails,
        convertDvProfile7ToHdr = convertDvProfile7ToHdr,
        dolbyVisionPlayback = enumByNameOrNull<DolbyVisionPlayback>(dolbyVisionPlayback) ?: PLAYER.dolbyVisionPlayback,
        hdrWhiteDimming = hdrWhiteDimming,
        playbackBufferProfile = enumByNameOrNull<PlaybackBufferProfile>(playbackBufferProfile) ?: PLAYER.playbackBufferProfile,
        parallelStreamConnections = parallelStreamConnections,
        autoPlayTrailers = autoPlayTrailers,
        muteAutoPlayTrailers = muteAutoPlayTrailers,
        trailerAutoPlayDelaySeconds = trailerAutoPlayDelaySeconds,
        showNextUpInContinueWatching = showNextUpInContinueWatching,
    )

    companion object {
        fun of(s: PlayerSettings) = PlayerBackup(
            s.playerChoice.storageValue, s.autoPlayTopStream, s.preferredAutoPlayAddonUrl, s.autoPlayNextEpisode,
            s.autoStreamFailover, s.fallbackTimeoutSeconds, s.autoPlayFromContinueWatching, s.defaultPlaybackSpeed.name,
            s.blurUnwatchedThumbnails, s.convertDvProfile7ToHdr, s.dolbyVisionPlayback.name, s.hdrWhiteDimming,
            s.playbackBufferProfile.name, s.parallelStreamConnections, s.autoPlayTrailers, s.muteAutoPlayTrailers,
            s.trailerAutoPlayDelaySeconds, s.showNextUpInContinueWatching,
        )
    }
}

@Serializable
data class AudioBackup(
    val passthroughEnabled: Boolean = AUDIO.passthroughEnabled,
    val dialogueBoost: String = AUDIO.dialogueBoost.name,
    val nightMode: Boolean = AUDIO.nightMode,
    val audioDelayMs: Int = AUDIO.audioDelayMs,
    val volumeBoost: String = AUDIO.volumeBoost.name,
    val preferredAudioLanguage: String = AUDIO.preferredAudioLanguage,
) {
    fun toSettings() = AudioSettings(
        passthroughEnabled = passthroughEnabled,
        dialogueBoost = enumByNameOrNull<DialogueBoost>(dialogueBoost) ?: AUDIO.dialogueBoost,
        nightMode = nightMode,
        audioDelayMs = audioDelayMs,
        volumeBoost = enumByNameOrNull<VolumeBoost>(volumeBoost) ?: AUDIO.volumeBoost,
        preferredAudioLanguage = preferredAudioLanguage.take(MAX_LANGUAGE_CHARS),
    )

    companion object {
        fun of(s: AudioSettings) =
            AudioBackup(s.passthroughEnabled, s.dialogueBoost.name, s.nightMode, s.audioDelayMs, s.volumeBoost.name, s.preferredAudioLanguage)
    }
}

@Serializable
data class SubtitlesBackup(
    val mode: String = SUBS.mode.name,
    val preferredLanguage: String = SUBS.preferredLanguage,
    val hideNonPreferredLanguages: Boolean = SUBS.hideNonPreferredLanguages,
    val cleanSdh: Boolean = SUBS.cleanSdh,
    val fontScale: Float = STYLE.fontScale,
    val bold: Boolean = STYLE.bold,
    val italic: Boolean = STYLE.italic,
    val textColor: Int = STYLE.textColor,
    val edgeType: String = STYLE.edgeType.name,
    val backgroundOpacity: Float = STYLE.backgroundOpacity,
    val autoSyncExternal: Boolean = SUBS.autoSyncExternal,
) {
    fun toSettings() = SubtitleSettings(
        mode = enumByNameOrNull<SubtitleMode>(mode) ?: SUBS.mode,
        preferredLanguage = preferredLanguage.take(MAX_LANGUAGE_CHARS),
        hideNonPreferredLanguages = hideNonPreferredLanguages,
        cleanSdh = cleanSdh,
        style = SubtitleStyle(
            fontScale = fontScale.takeIf { it.isFinite() } ?: STYLE.fontScale,
            bold = bold,
            italic = italic,
            textColor = textColor,
            edgeType = enumByNameOrNull<SubtitleEdgeType>(edgeType) ?: STYLE.edgeType,
            backgroundOpacity = backgroundOpacity.takeIf { it.isFinite() } ?: STYLE.backgroundOpacity,
        ),
        autoSyncExternal = autoSyncExternal,
    )

    companion object {
        fun of(s: SubtitleSettings) = SubtitlesBackup(
            s.mode.name, s.preferredLanguage, s.hideNonPreferredLanguages, s.cleanSdh, s.style.fontScale, s.style.bold,
            s.style.italic, s.style.textColor, s.style.edgeType.name, s.style.backgroundOpacity, s.autoSyncExternal,
        )
    }
}

/** Language codes are short (`en`, `pt-BR`); anything longer in a hand-edited file is cut. */
private const val MAX_LANGUAGE_CHARS = 16
