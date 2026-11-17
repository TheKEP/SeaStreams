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

enum class SubtitleEdgeType { NONE, OUTLINE, DROP_SHADOW }

data class PlayerSettings(
    val playerChoice: PlayerChoice = PlayerChoice.BuiltIn,
    val autoPlayTopStream: Boolean = false,
    /** Addon whose top stream wins auto-play; `null` means the first enabled addon. */
    val preferredAutoPlayAddonUrl: String? = null,
    /** Plays the next episode's top stream when an episode finishes (PRD §9: on by default). */
    val autoPlayNextEpisode: Boolean = true,
    val fallbackTimeoutSeconds: Int = DEFAULT_FALLBACK_TIMEOUT_SECONDS,
    val autoPlayFromContinueWatching: Boolean = false,
    val blurUnwatchedThumbnails: Boolean = false,
    /** Plays Dolby Vision Profile 7 tracks as their HDR10 base layer (PRD §9: on by default). */
    val convertDvProfile7ToHdr: Boolean = true,
    val hdrWhiteDimming: Boolean = false,
    val playbackBufferProfile: PlaybackBufferProfile = PlaybackBufferProfile.DEFAULT,
    /** Concurrent HTTP byte-range connections per stream; `1` turns the parallel reader off. */
    val parallelStreamConnections: Int = DEFAULT_PARALLEL_CONNECTIONS,
    /** Inline 16:9 catalog-card trailer previews (PRD §4). */
    val autoPlayTrailers: Boolean = true,
    val muteAutoPlayTrailers: Boolean = true,
    val trailerAutoPlayDelaySeconds: Int = DEFAULT_TRAILER_DELAY_SECONDS,
) {
    companion object {
        const val DEFAULT_FALLBACK_TIMEOUT_SECONDS = 5
        val FALLBACK_TIMEOUT_RANGE: IntRange = 1..10
        const val DEFAULT_PARALLEL_CONNECTIONS = 3
        val PARALLEL_CONNECTIONS_RANGE: IntRange = 1..4
        const val DEFAULT_TRAILER_DELAY_SECONDS = 2
        val TRAILER_DELAY_RANGE: IntRange = 1..7
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

/**
 * How fast catalog rows (Home, Movies, TV Shows) glide sideways as focus moves, and how fast the
 * focused card widens from a 2:3 poster to a 16:9 backdrop. Slow is the default; Fast is the
 * original snappy feel. Each card finishes widening just before its row's glide settles.
 */
enum class CatalogRowScrollSpeed(val label: String, val scrollDurationMs: Int, val expandDurationMs: Int) {
    SLOW("Slow", scrollDurationMs = 480, expandDurationMs = 450),
    MEDIUM("Medium", scrollDurationMs = 280, expandDurationMs = 260),
    FAST("Fast", scrollDurationMs = 130, expandDurationMs = 120),
    ;

    companion object {
        val DEFAULT = SLOW
    }
}

/** App-wide look (PRD §1, §10). */
data class AppearanceSettings(
    /** Opaque ARGB page background; one of [BackgroundSwatches.ALL]. */
    val backgroundArgb: Int = BackgroundSwatches.DEFAULT,
    val catalogRowScrollSpeed: CatalogRowScrollSpeed = CatalogRowScrollSpeed.DEFAULT,
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
        0xFF151F35.toInt(),
    )
}

/** Snapshot of every persisted, non-profile-specific setting. */
data class AppSettings(
    val player: PlayerSettings = PlayerSettings(),
    val audio: AudioSettings = AudioSettings(),
    val subtitles: SubtitleSettings = SubtitleSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
)

const val DEFAULT_LANGUAGE = "en"
