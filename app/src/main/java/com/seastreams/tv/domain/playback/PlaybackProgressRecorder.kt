package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.util.MILLIS_PER_MINUTE
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.TitleDetails
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One movie, or one episode of a show, that the user asked to play. */
data class PlaybackTarget(
    val profileId: Long,
    val details: TitleDetails,
    val imdbId: String?,
    /** `null` for movies. */
    val episode: EpisodeRef?,
    val episodeTitle: String? = null,
    val episodeRuntimeMinutes: Int? = null,
) {
    /** Runtime in ms from TMDB (episode runtime for shows); `0` when unknown. */
    val knownDurationMs: Long
        get() = ((episodeRuntimeMinutes ?: details.media.runtimeMinutes) ?: 0) * MILLIS_PER_MINUTE
}

/** Reads and writes watch progress for [PlaybackTarget]s (`WatchProgressDao`). */
class PlaybackProgressRecorder @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val clock: Clock,
) {
    /** Live progress of every episode (or the movie) of [ref] for [profileId]. */
    fun observe(profileId: Long, ref: MediaRef): Flow<List<ProgressPoint>> =
        watchProgressDao.observeForTitle(profileId, ref.tmdbId, ref.mediaType).map { rows ->
            rows.map { row ->
                val episode = if (ref.mediaType == MediaType.TV) EpisodeRef(row.seasonNumber, row.episodeNumber) else null
                ProgressPoint(episode, row.positionMs, row.durationMs, row.updatedAtMillis)
            }
        }

    /** Where playback of [target] should resume: its saved position if unfinished, else `0`. */
    suspend fun resumePositionMs(target: PlaybackTarget): Long {
        val saved = saved(target) ?: return 0
        val point = ProgressPoint(target.episode, saved.positionMs, saved.durationMs, saved.updatedAtMillis)
        return if (point.inProgress) saved.positionMs else 0
    }

    /**
     * Saves [outcome]. A [ProgressSource.PRESERVED] outcome writes nothing, so a player that returned no
     * usable extras never wipes or corrupts what was saved before. Any write un-dismisses the item, so a
     * movie/episode removed from Continue Watching returns once it is played again.
     */
    suspend fun record(target: PlaybackTarget, outcome: PlaybackOutcome) {
        val position = outcome.positionMs ?: return
        val duration = outcome.durationMs?.takeIf { it > 0 } ?: return
        val media = target.details.media
        watchProgressDao.upsert(
            WatchProgressEntity(
                profileId = target.profileId,
                tmdbId = media.tmdbId,
                mediaType = media.mediaType,
                seasonNumber = target.episode?.season ?: 0,
                episodeNumber = target.episode?.episode ?: 0,
                title = media.title,
                episodeTitle = target.episodeTitle,
                posterPath = target.details.posterPath,
                backdropPath = target.details.backdropPath,
                positionMs = position.coerceIn(0, duration),
                durationMs = duration,
                updatedAtMillis = clock.millis(),
                dismissedFromContinueWatching = false,
            ),
        )
    }

    private suspend fun saved(target: PlaybackTarget): WatchProgressEntity? {
        val media = target.details.media
        return watchProgressDao.get(
            target.profileId,
            media.tmdbId,
            media.mediaType,
            target.episode?.season ?: 0,
            target.episode?.episode ?: 0,
        )
    }
}
