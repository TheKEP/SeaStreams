package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.TitleDetails
import java.time.Clock
import javax.inject.Inject
import kotlin.random.Random

/** Which episodes of one season the Episodes screen's long-press actions apply to. */
object SeasonEpisodeSelection {

    /** Every episode of [season] in [episodes] (`Mark Season Watched` / `Unwatched`). */
    fun season(episodes: List<Episode>, season: Int): List<Episode> = episodes.filter { it.ref.season == season }

    /** Episode 1 of [selected]'s season up to and including [selected] (`Mark previous in season …`). */
    fun throughEpisode(episodes: List<Episode>, selected: EpisodeRef): List<Episode> =
        episodes.filter { it.ref.season == selected.season && it.ref.episode <= selected.episode }

    /**
     * A uniformly random aired episode of [season] only (`Shuffle Season`); `null` when the season has
     * none. [lastAired] excludes episodes that haven't aired yet.
     */
    fun shuffle(episodes: List<Episode>, season: Int, lastAired: EpisodeRef?, random: Random = Random.Default): Episode? {
        val pool = season(episodes, season).filter { lastAired == null || it.ref <= lastAired }
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }
}

/**
 * Writes manual watched / unwatched marks for a show's episodes to `WatchProgressDao`. Watched means a
 * finished record (position = duration), so the episode counts as completed everywhere (PRD §9);
 * unwatched deletes the record, as if the episode was never started.
 */
class EpisodeWatchMarker @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val clock: Clock,
) {
    /**
     * Marks every one of [episodes] (all from one season) watched, whatever its current state. The highest
     * episode gets the newest timestamp, so next-up moves on from the last one marked.
     */
    suspend fun markWatched(profileId: Long, details: TitleDetails, episodes: List<Episode>) {
        val ordered = episodes.sortedBy { it.ref }
        if (ordered.isEmpty()) return
        val media = details.media
        val saved = ordered.map { it.ref.season }.distinct().flatMap { season ->
            watchProgressDao.getSeason(profileId, media.tmdbId, media.mediaType, season)
        }.associateBy { EpisodeRef(it.seasonNumber, it.episodeNumber) }
        val now = clock.millis()
        val rows = ordered.mapIndexed { index, episode ->
            val duration = saved[episode.ref]?.durationMs?.takeIf { it > 0 }
                ?: episode.runtimeMinutes?.takeIf { it > 0 }?.let { it * MILLIS_PER_MINUTE }
                ?: UNKNOWN_DURATION_MS
            WatchProgressEntity(
                profileId = profileId,
                tmdbId = media.tmdbId,
                mediaType = media.mediaType,
                seasonNumber = episode.ref.season,
                episodeNumber = episode.ref.episode,
                title = media.title,
                episodeTitle = episode.title,
                posterPath = details.posterPath,
                backdropPath = details.backdropPath,
                positionMs = duration,
                durationMs = duration,
                updatedAtMillis = now - (ordered.lastIndex - index),
            )
        }
        watchProgressDao.upsertAll(rows)
    }

    /** Clears the saved progress of every one of [episodes], watched, part-watched or not. */
    suspend fun markUnwatched(profileId: Long, details: TitleDetails, episodes: List<EpisodeRef>) {
        val media = details.media
        episodes.groupBy { it.season }.forEach { (season, refs) ->
            watchProgressDao.deleteEpisodes(profileId, media.tmdbId, media.mediaType, season, refs.map { it.episode })
        }
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L

        /** Duration stored when TMDB has no runtime: any positive value makes position = duration "finished". */
        const val UNKNOWN_DURATION_MS = 1L
    }
}
