package com.seastreams.tv.core.datastore

/**
 * Playback speed steps (PRD §9/§11). [multiplier] is ExoPlayer's `PlaybackParameters.speed`
 * (`1 + percent / 100`); pitch always stays at 1.0, so voices keep their natural pitch.
 */
enum class PlaybackSpeed(val percent: Int, val multiplier: Float) {
    MINUS_25(-25, 0.75f),
    STANDARD(0, 1.0f),
    PLUS_25(25, 1.25f),
    PLUS_50(50, 1.5f),
    PLUS_75(75, 1.75f),
    PLUS_100(100, 2.0f),
    ;

    /** `-25% (0.75x)`, `Standard (1.0x)`, `+50% (1.5x)`. */
    val label: String
        get() = when {
            this == STANDARD -> "Standard (1.0x)"
            percent > 0 -> "+$percent% (${multiplier}x)"
            else -> "$percent% (${multiplier}x)"
        }

    companion object {
        val DEFAULT = STANDARD
    }
}

/** Overall volume boost steps (PRD §9/§11): linear PCM gain, soft-limited so boosted peaks never wrap. */
enum class VolumeBoost(val percent: Int, val gain: Float) {
    STANDARD(0, 1.0f),
    PLUS_25(25, 1.25f),
    PLUS_50(50, 1.5f),
    PLUS_75(75, 1.75f),
    PLUS_100(100, 2.0f),
    ;

    /** `Standard`, `+25%`. */
    val label: String get() = if (this == STANDARD) "Standard" else "+$percent%"

    companion object {
        val DEFAULT = STANDARD
    }
}

/** The DSP effect levels that decide whether a change engages an effect ([engagesDsp]). */
data class DspLevels(
    val dialogueBoost: DialogueBoost,
    val nightMode: Boolean,
    val delayMs: Int,
    val volumeBoost: VolumeBoost,
)

/**
 * PRD §9: whether going from [before] to [after] turns an audio effect **on**: Dialogue Boost to a
 * level other than Off, Night Mode off → on, Audio Delay to a new non-zero value, or Volume Boost to a
 * new level other than Standard. Keeping an effect on, turning one off or resetting it doesn't count.
 */
fun engagesDsp(before: DspLevels, after: DspLevels): Boolean =
    (after.dialogueBoost != before.dialogueBoost && after.dialogueBoost != DialogueBoost.OFF) ||
        (after.nightMode && !before.nightMode) ||
        (after.delayMs != before.delayMs && after.delayMs != 0) ||
        (after.volumeBoost != before.volumeBoost && after.volumeBoost != VolumeBoost.STANDARD)
