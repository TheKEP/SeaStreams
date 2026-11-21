package com.seastreams.tv.core.datastore

/**
 * Which player streams open in (PRD §9). ExoPlayer is the default; any installed video app can be
 * targeted by package name, or Android's app chooser can be shown every time.
 */
sealed interface PlayerChoice {
    /** Stable value written to DataStore. */
    val storageValue: String

    data object BuiltIn : PlayerChoice {
        override val storageValue = "builtin"
    }

    data object SystemChooser : PlayerChoice {
        override val storageValue = "chooser"
    }

    data class App(val packageName: String) : PlayerChoice {
        override val storageValue: String get() = APP_PREFIX + packageName
    }

    companion object {
        private const val APP_PREFIX = "app:"

        /** Package names behind the enum values Milestone 1 stored, so older saves keep working. */
        private val LEGACY_PACKAGES = mapOf(
            "VLC" to "org.videolan.vlc",
            "KODI" to "org.xbmc.kodi",
            "MX_PLAYER" to "com.mxtech.videoplayer.ad",
            "JUST_PLAYER" to "com.brouken.player",
        )

        /** Unknown or missing values fall back to the built-in player. */
        fun fromStorage(value: String?): PlayerChoice = when {
            value == null -> BuiltIn
            value == SystemChooser.storageValue -> SystemChooser
            value.startsWith(APP_PREFIX) && value.length > APP_PREFIX.length -> App(value.removePrefix(APP_PREFIX))
            else -> LEGACY_PACKAGES[value]?.let(::App) ?: BuiltIn
        }
    }
}

/** Center-channel dialogue gain steps (PRD §9). */
enum class DialogueBoost(val gainDb: Int) { OFF(0), PLUS_3(3), PLUS_6(6), PLUS_9(9), PLUS_12(12) }

enum class SubtitleMode { ALWAYS_ON, ALWAYS_OFF }

/** ExoPlayer read-ahead buffer size (PRD §9 Playback Buffer Profile); `LARGE` is the default. */
enum class PlaybackBufferProfile(val label: String) {
    STANDARD("Standard"),
    LARGE("Large (2 min)"),
    MAX("Max (5 min)"),
    ;

    companion object {
        val DEFAULT = LARGE
    }
}

/** How Dolby Vision Profile 8 plays (PRD §10). Profile 5 is always native; Profile 7 also follows `convertDvProfile7ToHdr`. */
enum class DolbyVisionPlayback(val label: String) {
    /** Native Dolby Vision when the display reports DV support, else the HDR10/HLG base layer. */
    AUTO("Auto"),
    NATIVE("Always Dolby Vision"),
    HDR10_BASE_LAYER("HDR10/HLG base layer"),
}

enum class SubtitleEdgeType { NONE, OUTLINE, DROP_SHADOW }

data class PlayerSettings(
    val playerChoice: PlayerChoice = PlayerChoice.BuiltIn,
    /** Plays the top stream on `Play`/`Resume` instead of opening the stream list (PRD §11: on by default). */
    val autoPlayTopStream: Boolean = true,
    /**
     * Addon whose top stream wins auto-play; `null` (the default) means the active profile's first enabled
     * stream addon in addon (`sortOrder`) order that returns streams.
     */
    val preferredAutoPlayAddonUrl: String? = null,
    /** Plays the next episode's top stream when an episode finishes (PRD §9: on by default). */
    val autoPlayNextEpisode: Boolean = true,
    /** Advances to the next stream on a start timeout or playback error (PRD §9: on by default). */
    val autoStreamFailover: Boolean = true,
    val fallbackTimeoutSeconds: Int = DEFAULT_FALLBACK_TIMEOUT_SECONDS,
    /** `Continue Watching` cards play directly from Home, bypassing Details (PRD §3/§11: on by default). */
    val autoPlayFromContinueWatching: Boolean = true,
    val blurUnwatchedThumbnails: Boolean = false,
    /** Plays Dolby Vision Profile 7 tracks as their HDR10 base layer (PRD §9: on by default). */
    val convertDvProfile7ToHdr: Boolean = true,
    val dolbyVisionPlayback: DolbyVisionPlayback = DolbyVisionPlayback.AUTO,
    /** Dims the player UI (never the video) during HDR10/HDR10+/HLG/Dolby Vision playback (PRD §10: on by default). */
    val hdrWhiteDimming: Boolean = true,
    val playbackBufferProfile: PlaybackBufferProfile = PlaybackBufferProfile.DEFAULT,
    /** Concurrent HTTP byte-range connections per stream; `1` turns the parallel reader off. */
    val parallelStreamConnections: Int = DEFAULT_PARALLEL_CONNECTIONS,
    /** Inline 16:9 catalog-card trailer previews (PRD §4). */
    val autoPlayTrailers: Boolean = true,
    val muteAutoPlayTrailers: Boolean = true,
    val trailerAutoPlayDelaySeconds: Int = DEFAULT_TRAILER_DELAY_SECONDS,
) {
    companion object {
        const val DEFAULT_FALLBACK_TIMEOUT_SECONDS = 10
        /** PRD §9/§11: the only selectable Stream Fallback Timeout values, ascending. */
        val FALLBACK_TIMEOUT_OPTIONS: List<Int> = listOf(10, 15, 20)
        const val DEFAULT_PARALLEL_CONNECTIONS = 4
        val PARALLEL_CONNECTIONS_RANGE: IntRange = 1..4
        const val DEFAULT_TRAILER_DELAY_SECONDS = 2
        val TRAILER_DELAY_RANGE: IntRange = 1..7
    }
}

/**
 * PRD §9: whether this change turns an audio effect **on** — Dialogue Boost to a level other than Off,
 * Night Mode off → on, or Audio Delay to a new non-zero value. Keeping an effect on, turning one off or
 * resetting the delay to 0 doesn't count.
 */
fun engagesDsp(
    boostBefore: DialogueBoost,
    boostAfter: DialogueBoost,
    nightBefore: Boolean,
    nightAfter: Boolean,
    delayBeforeMs: Int,
    delayAfterMs: Int,
): Boolean =
    (boostAfter != boostBefore && boostAfter != DialogueBoost.OFF) ||
        (nightAfter && !nightBefore) ||
        (delayAfterMs != delayBeforeMs && delayAfterMs != 0)

/**
 * Turning an effect on switches passthrough off so it takes effect at once (effects need PCM). Only the
 * change that engages an effect does this, so passthrough can be switched back on afterwards and stays on.
 */
fun AudioSettings.withPassthroughAutoOff(previous: AudioSettings): AudioSettings {
    val delay = SettingsDataStore.snapAudioDelay(audioDelayMs)
    val engaged = engagesDsp(previous.dialogueBoost, dialogueBoost, previous.nightMode, nightMode, previous.audioDelayMs, delay)
    return if (passthroughEnabled && engaged) copy(passthroughEnabled = false) else this
}

data class AudioSettings(
    /**
     * When true, codecs the receiver supports are bitstreamed over HDMI untouched. Turning an effect on
     * switches it off ([withPassthroughAutoOff]).
     */
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

/** Global subtitle styling; defaults (PRD §9/§11): 100% size, bold, upright, white, outline, no background box. */
data class SubtitleStyle(
    val fontScale: Float = 1.0f,
    val bold: Boolean = true,
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
    /** Lists only tracks in [preferredLanguage] (PRD §9/§11: on by default). */
    val hideNonPreferredLanguages: Boolean = true,
    /** Strips SDH annotations such as `[DOOR CREAKS]` from cues (PRD §9/§11: on by default). */
    val cleanSdh: Boolean = true,
    val style: SubtitleStyle = SubtitleStyle(),
    /** Detect and apply the sync offset of external (addon) subtitles during playback (PRD §9). */
    val autoSyncExternal: Boolean = true,
)

/** Per-profile look (PRD §1, §11). */
data class AppearanceSettings(
    /** Opaque ARGB page background; one of [BackgroundSwatches.ALL]. */
    val backgroundArgb: Int = BackgroundSwatches.DEFAULT,
)

/** The selectable page backgrounds, in picker order (PRD §1). */
object BackgroundSwatches {
    const val DEFAULT: Int = 0xFF000000.toInt()
    val ALL: List<Int> = listOf(
        DEFAULT,
        0xFF171535.toInt(),
        0xFF351529.toInt(),
        0xFF351515.toInt(),
        0xFF153535.toInt(),
        0xFF090B34.toInt(),
        0xFF472A11.toInt(),
    )
}

/** Snapshot of the active profile's General Settings (persisted per profile; PRD §11). */
data class AppSettings(
    val player: PlayerSettings = PlayerSettings(),
    val audio: AudioSettings = AudioSettings(),
    val subtitles: SubtitleSettings = SubtitleSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
)

const val DEFAULT_LANGUAGE = "en"
