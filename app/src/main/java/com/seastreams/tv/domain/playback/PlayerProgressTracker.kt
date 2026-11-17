package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.PlaybackRequest
import javax.inject.Inject

/**
 * Saves built-in playback to `WatchProgressDao` (PRD §9): periodically while playing, on pause, when a
 * stream or episode is switched, and on exit. A finished playback (`STATE_ENDED`) is saved as watched
 * (position = duration), so it satisfies the `>= 90%` rule everywhere.
 *
 * Trailers ([PlaybackRequest.Trailer]) never write anything. A fresh start that stops within the
 * first [MIN_FRESH_POSITION_MS] isn't saved either, so an accidental start doesn't land in Continue
 * Watching.
 */
class PlayerProgressTracker @Inject constructor(private val recorder: PlaybackProgressRecorder) {

    /** Saves [positionMs] of [durationMs] (`<= 0` when ExoPlayer doesn't know it yet). */
    suspend fun save(request: PlaybackRequest, positionMs: Long, durationMs: Long) {
        val media = request as? PlaybackRequest.Media ?: return
        val duration = durationMs.takeIf { it > 0 } ?: media.target.knownDurationMs.takeIf { it > 0 } ?: return
        if (media.startPositionMs == 0L && positionMs < MIN_FRESH_POSITION_MS) return
        val position = positionMs.coerceIn(0, duration)
        recorder.record(
            media.target,
            PlaybackOutcome(position, duration, completed = isCompleted(position, duration), source = ProgressSource.REPORTED),
        )
    }

    /** `STATE_ENDED`: the movie/episode is watched. */
    suspend fun saveFinished(request: PlaybackRequest, durationMs: Long) {
        val media = request as? PlaybackRequest.Media ?: return
        val duration = durationMs.takeIf { it > 0 } ?: media.target.knownDurationMs.takeIf { it > 0 } ?: return
        recorder.record(media.target, PlaybackOutcome(duration, duration, completed = true, source = ProgressSource.REPORTED))
    }

    companion object {
        const val MIN_FRESH_POSITION_MS = 10_000L

        /** How often progress is saved while playing. */
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
