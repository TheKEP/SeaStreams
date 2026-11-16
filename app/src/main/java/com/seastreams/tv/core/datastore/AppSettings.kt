package com.seastreams.tv.core.datastore

/** Launch target for streams when the built-in ExoPlayer is not used (PRD §9). */
enum class ExternalPlayer(val packageName: String?) {
    NONE(null),
    VLC("org.videolan.vlc"),
    KODI("org.xbmc.kodi"),
    MX_PLAYER("com.mxtech.videoplayer.ad"),
    JUST_PLAYER("com.brouken.player"),
}

/** Center-channel dialogue gain steps (PRD §9). */
enum class DialogueBoost(val gainDb: Int) { OFF(0), PLUS_3(3), PLUS_6(6), PLUS_9(9), PLUS_12(12) }

enum class SubtitleMode { ALWAYS_ON, ALWAYS_OFF }

enum class SubtitleEdgeType { NONE, OUTLINE, DROP_SHADOW }

data class PlayerSettings(
    val externalPlayer: ExternalPlayer = ExternalPlayer.NONE,
    val autoPlayTopStream: Boolean = false,
    /** Addon whose top stream wins auto-play; `null` means the first enabled addon. */
    val preferredAutoPlayAddonUrl: String? = null,
    val fallbackTimeoutSeconds: Int = DEFAULT_FALLBACK_TIMEOUT_SECONDS,
    val autoPlayFromContinueWatching: Boolean = false,
    val blurUnwatchedThumbnails: Boolean = false,
    val hdrWhiteDimming: Boolean = false,
) {
    companion object {
        const val DEFAULT_FALLBACK_TIMEOUT_SECONDS = 5
        val FALLBACK_TIMEOUT_RANGE: IntRange = 1..10
    }
}

data class AudioSettings(
    /** When true, audio is bitstreamed over HDMI and on-device DSP is bypassed. */
    val passthroughEnabled: Boolean = true,
    val dialogueBoost: DialogueBoost = DialogueBoost.OFF,
    val nightMode: Boolean = false,
    val audioDelayMs: Int = 0,
    val preferredAudioLanguage: String = DEFAULT_LANGUAGE,
) {
    companion object {
        const val AUDIO_DELAY_LIMIT_MS = 500
        const val AUDIO_DELAY_STEP_MS = 25
    }
}

data class SubtitleStyle(
    val fontScale: Float = 1.0f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** ARGB colour int. */
    val textColor: Int = WHITE_ARGB,
    val edgeType: SubtitleEdgeType = SubtitleEdgeType.OUTLINE,
    /** 0.0 (transparent) – 1.0 (opaque) background box. */
    val backgroundOpacity: Float = 0f,
) {
    companion object {
        const val WHITE_ARGB: Int = -0x1 // 0xFFFFFFFF
        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 2.0f
    }
}

data class SubtitleSettings(
    val mode: SubtitleMode = SubtitleMode.ALWAYS_OFF,
    val preferredLanguage: String = DEFAULT_LANGUAGE,
    val hideNonPreferredLanguages: Boolean = false,
    val cleanSdh: Boolean = false,
    val style: SubtitleStyle = SubtitleStyle(),
)

/** Snapshot of every persisted, non-profile-specific setting. */
data class AppSettings(
    val player: PlayerSettings = PlayerSettings(),
    val audio: AudioSettings = AudioSettings(),
    val subtitles: SubtitleSettings = SubtitleSettings(),
)

const val DEFAULT_LANGUAGE = "en"
