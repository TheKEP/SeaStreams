package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.SeasonInfo
import kotlin.random.Random

/** Saved playback of one movie (`episode == null`) or episode. */
data class ProgressPoint(val episode: EpisodeRef?, val positionMs: Long, val durationMs: Long, val updatedAtMillis: Long) {
    /** Watched to the end (PRD §9: `>= 90%`). */
    val completed: Boolean get() = isCompleted(positionMs, durationMs)

    /** Started and not finished: Play resumes it. */
    val inProgress: Boolean get() = positionMs > 0 && durationMs > 0 && !completed
}

/** `>= 90%` of a known duration counts as watched (PRD §9). */
fun isCompleted(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs * WatchProgressDao.COMPLETION_RATIO

/** Episode order maths for shows: what Play, auto-next and Shuffle pick. */
object EpisodeNavigator {

    /**
     * The episode after [current]: the next one in its season, else episode 1 of the following season.
     * `null` at the end of the show, or when the next episode hasn't aired yet ([lastAired]).
     */
    fun next(current: EpisodeRef, seasons: List<SeasonInfo>, lastAired: EpisodeRef?): EpisodeRef? {
        val ordered = seasons.filter { it.episodeCount > 0 }.sortedBy { it.number }
        val season = ordered.firstOrNull { it.number == current.season }
        val candidate = when {
            season != null && current.episode < season.episodeCount -> EpisodeRef(current.season, current.episode + 1)
            else -> ordered.firstOrNull { it.number > current.season }?.let { EpisodeRef(it.number, 1) }
        }
        return candidate?.takeIf { lastAired == null || it <= lastAired }
    }

    /**
     * What `Play` on a show starts: the most recently watched episode if it is unfinished, else the
     * one after it, else the first episode. [progress] holds the show's per-episode records.
     */
    fun nextUp(progress: List<ProgressPoint>, seasons: List<SeasonInfo>, lastAired: EpisodeRef?): EpisodeRef? {
        val first = seasons.filter { it.episodeCount > 0 }.minByOrNull { it.number }?.let { EpisodeRef(it.number, 1) }
        val latest = progress.filter { it.episode != null }.maxByOrNull { it.updatedAtMillis } ?: return first
        val episode = latest.episode ?: return first
        if (!latest.completed) return episode
        return next(episode, seasons, lastAired) ?: episode
    }

    /** A uniformly random aired episode across every regular season (`Shuffle`); `null` when there are none. */
    fun shuffle(seasons: List<SeasonInfo>, lastAired: EpisodeRef?, random: Random = Random.Default): EpisodeRef? {
        val pool = seasons.filter { it.episodeCount > 0 }.sortedBy { it.number }.flatMap { season ->
            (1..season.episodeCount).map { EpisodeRef(season.number, it) }
        }.filter { lastAired == null || it <= lastAired }
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }
}
