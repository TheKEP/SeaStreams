package com.seastreams.tv.domain.playback

/**
 * The result extras external players return with `return_result = true`. MX Player and Just Player
 * send `position`/`duration` (int ms) and `end_by`; VLC sends `extra_position`/`extra_duration` (long ms).
 */
data class ExternalPlayerExtras(val positionMs: Long?, val durationMs: Long?, val endBy: String?) {

    val endedByCompletion: Boolean get() = endBy == END_BY_COMPLETION

    companion object {
        const val END_BY_COMPLETION = "playback_completion"

        private val POSITION_KEYS = listOf("position", "extra_position")
        private val DURATION_KEYS = listOf("duration", "extra_duration")
        private const val END_BY_KEY = "end_by"

        /** Reads a result `Intent`'s extras (as a plain map so it stays testable off-device). */
        fun from(extras: Map<String, Any?>): ExternalPlayerExtras = ExternalPlayerExtras(
            positionMs = POSITION_KEYS.firstNotNullOfOrNull { (extras[it] as? Number)?.toLong() },
            durationMs = DURATION_KEYS.firstNotNullOfOrNull { (extras[it] as? Number)?.toLong() },
            endBy = extras[END_BY_KEY] as? String,
        )
    }
}

/** What SeaStreams knew when it handed a stream to an external player. */
data class ExternalPlaybackLaunch(
    /** Resume position passed to the player (`0` for a fresh start). */
    val startPositionMs: Long,
    /** Runtime from TMDB in ms; `0` when unknown. Used when the player reports no duration. */
    val knownDurationMs: Long,
    val launchedAtMillis: Long,
)

enum class ProgressSource {
    /** The player returned a position. */
    REPORTED,

    /** No position came back: estimated from the time spent in the player. */
    ESTIMATED,

    /** Nothing trustworthy came back: the saved progress is left as it was. */
    PRESERVED,
}

/**
 * How one playback ended. [positionMs]/[durationMs] are what to save (both `null` for
 * [ProgressSource.PRESERVED]); [completed] means watched (`>= 90%`, the checkmark rule). Only
 * [endedNaturally] — the player reached the end on its own, never a `Back` exit — lets Auto-Play
 * Next Episode run (PRD §9).
 */
data class PlaybackOutcome(
    val positionMs: Long?,
    val durationMs: Long?,
    val completed: Boolean,
    val source: ProgressSource,
    val endedNaturally: Boolean = false,
)

/** Turns an external player's result into watch progress (PRD §9 External Player Progress Preservation). */
object ExternalPlaybackProgress {

    /** Less time than this in the player (and no position reported) is treated as "didn't play". */
    const val MIN_ESTIMATE_ELAPSED_MS = 15_000L

    /**
     * A player that sends no `end_by` (VLC) has no completion signal: only a reported position within
     * this much of its reported duration counts as having played to the end.
     */
    const val END_TOLERANCE_MS = 1_000L

    fun outcome(extras: ExternalPlayerExtras, launch: ExternalPlaybackLaunch, returnedAtMillis: Long): PlaybackOutcome {
        val duration = extras.durationMs?.takeIf { it > 0 } ?: launch.knownDurationMs.takeIf { it > 0 }
        val reported = extras.positionMs?.takeIf { it > 0 }

        if (duration != null && (reported != null || extras.endedByCompletion)) {
            val position = if (extras.endedByCompletion) duration else minOf(requireNotNull(reported), duration)
            return PlaybackOutcome(
                positionMs = position,
                durationMs = duration,
                completed = extras.endedByCompletion || isCompleted(position, duration),
                source = ProgressSource.REPORTED,
                endedNaturally = extras.endedByCompletion || reachedEndWithoutEndBy(extras),
            )
        }

        // The player sent no usable position: estimate from wall-clock time spent in it, but only when
        // the length is known and the user stayed long enough for the estimate to mean something.
        val elapsed = returnedAtMillis - launch.launchedAtMillis
        if (duration == null || elapsed < MIN_ESTIMATE_ELAPSED_MS) {
            return PlaybackOutcome(
                null,
                null,
                completed = extras.endedByCompletion,
                source = ProgressSource.PRESERVED,
                endedNaturally = extras.endedByCompletion,
            )
        }
        val estimated = minOf(launch.startPositionMs.coerceAtLeast(0) + elapsed, duration)
        return PlaybackOutcome(
            positionMs = estimated,
            durationMs = duration,
            completed = extras.endedByCompletion || isCompleted(estimated, duration),
            source = ProgressSource.ESTIMATED,
            // Time spent in the player says nothing about how it was left: only an explicit completion advances.
            endedNaturally = extras.endedByCompletion,
        )
    }

    /** No `end_by` at all (VLC) and the player's own position/duration put it at the very end. */
    private fun reachedEndWithoutEndBy(extras: ExternalPlayerExtras): Boolean {
        if (extras.endBy != null) return false
        val position = extras.positionMs ?: return false
        val duration = extras.durationMs?.takeIf { it > 0 } ?: return false
        return position >= duration - END_TOLERANCE_MS
    }
}
