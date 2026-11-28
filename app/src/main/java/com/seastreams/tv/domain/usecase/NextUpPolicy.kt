package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.ShowActivity
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.domain.playback.EpisodeNavigator
import com.seastreams.tv.domain.playback.isCompleted
import java.time.ZoneId

/** A show whose newest watched episode is finished: Next Up looks for the episode after [episode]. */
data class NextUpSeed(val tmdbId: Int, val episode: EpisodeRef, val updatedAtMillis: Long, val inLibrary: Boolean)

/**
 * Pure Next Up rules for the Continue Watching row (PRD §3, Milestone 13), kept free of I/O so they can be unit
 * tested on their own. `GetNextUpUseCase` feeds them; `HomeViewModel` only calls [merge].
 */
object NextUpPolicy {

    /** Most recently watched shows checked for a Next Up card, whether or not they are in the Library. */
    const val RECENT_CANDIDATES = 20

    /** Hard cap on shows checked per Home load (each costs one HTTP-cached `/tv/{id}` lookup). */
    const val MAX_CANDIDATES = 40

    /**
     * Shows to check, newest first: each show's newest record (a tie goes to the later episode, as
     * [EpisodeNavigator.latest] picks it) when it is finished. Shows whose newest record is unfinished belong to the
     * in-progress cards instead. Keeps the [RECENT_CANDIDATES] newest plus every Library show, up to [MAX_CANDIDATES],
     * so a caught-up Library show watched long ago still comes back when a new episode airs.
     */
    fun candidates(rows: List<ShowActivity>): List<NextUpSeed> {
        val seeds = rows.groupBy { it.tmdbId }.values
            .mapNotNull { tied -> tied.maxWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber })) }
            .filter { isCompleted(it.positionMs, it.durationMs) }
            .sortedByDescending { it.updatedAtMillis }
            .map { NextUpSeed(it.tmdbId, EpisodeRef(it.seasonNumber, it.episodeNumber), it.updatedAtMillis, it.inLibrary) }
        val recent = seeds.take(RECENT_CANDIDATES).map { it.tmdbId }.toSet()
        return seeds.filter { it.tmdbId in recent || it.inLibrary }.take(MAX_CANDIDATES)
    }

    /** The aired episode after [seed]'s (the same pick as Details' `Play`); `null` at the end of the show or before it airs. */
    fun nextEpisode(seed: NextUpSeed, details: TitleDetails): EpisodeRef? =
        EpisodeNavigator.next(seed.episode, details.seasons, details.lastAired)

    /**
     * When the card's show was last active: the seed's watch time, or — when [next] is the newest aired episode and it
     * aired later than that — the start of its air day, so a newly aired episode moves to the front of the row.
     */
    fun activityAt(seed: NextUpSeed, next: EpisodeRef, details: TitleDetails, zone: ZoneId): Long {
        val aired = details.lastAiredDate?.takeIf { next == details.lastAired }
            ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            ?: return seed.updatedAtMillis
        return maxOf(seed.updatedAtMillis, aired)
    }

    /**
     * A removed card stays hidden while it would show the same episode, nothing newer has aired and the show hasn't
     * been watched since. Watching any episode, or a new episode airing, brings it back.
     */
    fun isDismissed(dismissal: NextUpDismissalEntity?, next: EpisodeRef, lastAired: EpisodeRef?, watchedAtMillis: Long): Boolean {
        if (dismissal == null) return false
        if (EpisodeRef(dismissal.seasonNumber, dismissal.episodeNumber) != next) return false
        if (watchedAtMillis > dismissal.dismissedAtMillis) return false
        val seenAired = dismissal.lastAiredSeason?.let { season -> dismissal.lastAiredEpisode?.let { EpisodeRef(season, it) } }
        val newAiring = lastAired != null && (seenAired == null || lastAired > seenAired)
        return !newAiring
    }

    /** The dismissal `Remove` records for a Next Up [item] at [nowMillis]. */
    fun dismissalFor(profileId: Long, item: ContinueWatchingItem, nowMillis: Long): NextUpDismissalEntity {
        val lastAired = item.nextUp?.lastAired
        return NextUpDismissalEntity(
            profileId = profileId,
            tmdbId = item.media.tmdbId,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            lastAiredSeason = lastAired?.season,
            lastAiredEpisode = lastAired?.episode,
            dismissedAtMillis = nowMillis,
        )
    }

    /**
     * The Continue Watching row: in-progress and Next Up cards together, most recent activity first, capped at
     * [WatchProgressDao.CONTINUE_WATCHING_LIMIT]. A show never gets both kinds of card; the in-progress one wins.
     */
    fun merge(
        inProgress: List<ContinueWatchingItem>,
        nextUp: List<ContinueWatchingItem>,
        limit: Int = WatchProgressDao.CONTINUE_WATCHING_LIMIT,
    ): List<ContinueWatchingItem> {
        if (nextUp.isEmpty()) return inProgress.take(limit)
        val started = inProgress.map { it.media.ref }.toSet()
        return (inProgress + nextUp.filter { it.media.ref !in started })
            .sortedByDescending { it.activityAtMillis }
            .take(limit)
    }
}
