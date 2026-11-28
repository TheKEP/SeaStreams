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
        val latest = latest(progress) ?: return first
        val episode = latest.episode ?: return first
        if (!latest.completed) return episode
        return next(episode, seasons, lastAired) ?: episode
    }

    /**
     * The show's most recently watched episode record. Records saved together (`Mark watched` on a season) share a
     * timestamp, so a tie goes to the later episode. Home's Next Up cards and Details' `Play` both start from it.
     */
    fun latest(progress: List<ProgressPoint>): ProgressPoint? =
        progress.filter { it.episode != null }
            .maxWithOrNull(compareBy<ProgressPoint>({ it.updatedAtMillis }, { it.episode }))

    /** A uniformly random aired episode across every regular season (`Shuffle`); `null` when there are none. */
    fun shuffle(seasons: List<SeasonInfo>, lastAired: EpisodeRef?, random: Random = Random.Default): EpisodeRef? =
        airedPool(seasons, lastAired).randomOrNull(random)

    /**
     * What `Next Episode` and Auto-Play Next Episode play after [current] (built-in and external players
     * alike). Sequential sessions use [next]. Shuffle sessions pick a random aired episode from the show
     * ([ShuffleMode.Show]) or from that one season only ([ShuffleMode.Season]). The pick is never
     * [current], and it avoids episodes already played in the session until the whole pool has played,
     * then a new cycle starts. `null` when nothing is left (end of show, or [current] is the only
     * aired episode in the pool).
     */
    fun next(
        current: EpisodeRef,
        seasons: List<SeasonInfo>,
        lastAired: EpisodeRef?,
        session: ShuffleSession,
        random: Random = Random.Default,
    ): NextPick? {
        val mode = session.mode
        val pool = when (mode) {
            ShuffleMode.None -> return next(current, seasons, lastAired)?.let { NextPick(it, session) }
            ShuffleMode.Show -> airedPool(seasons, lastAired)
            is ShuffleMode.Season -> airedPool(seasons, lastAired).filter { it.season == mode.number }
        }
        val others = pool - current
        val fresh = others - session.played.toSet()
        if (fresh.isNotEmpty()) {
            val pick = fresh.random(random)
            return NextPick(pick, session.copy(played = (session.played + current + pick).distinct()))
        }
        val pick = others.randomOrNull(random) ?: return null
        return NextPick(pick, session.copy(played = listOf(current, pick)))
    }

    /** Every aired episode of every regular season, in order. */
    private fun airedPool(seasons: List<SeasonInfo>, lastAired: EpisodeRef?): List<EpisodeRef> =
        seasons.filter { it.episodeCount > 0 }.sortedBy { it.number }.flatMap { season ->
            (1..season.episodeCount).map { EpisodeRef(season.number, it) }
        }.filter { lastAired == null || it <= lastAired }
}
