package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.NextUpDismissalDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.domain.model.ContinueWatchingItem
import java.time.Clock
import javax.inject.Inject

/**
 * The Continue Watching long-press menu's actions (PRD §1/§3). Both target exactly the card's movie or
 * single episode (season/episode `0` for movies), never the rest of a show.
 */
class ContinueWatchingActions @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
    private val nextUpDismissals: NextUpDismissalDao,
    private val clock: Clock,
) {
    /**
     * `Remove`: hides the card but keeps its progress; playing it again brings it back. A Next Up card is recorded as
     * a dismissal instead ([NextUpPolicy.isDismissed]): no watch history is touched, and it returns once the show is
     * watched again or a newer episode airs.
     */
    suspend fun remove(profileId: Long, item: ContinueWatchingItem) {
        if (item.isNextUp) {
            nextUpDismissals.upsert(NextUpPolicy.dismissalFor(profileId, item, clock.millis()))
            return
        }
        watchProgressDao.dismissFromContinueWatching(
            profileId,
            item.media.tmdbId,
            item.media.mediaType,
            item.seasonNumber,
            item.episodeNumber,
        )
    }

    /**
     * `Clear progress`: deletes the card's progress record, so that movie or episode is unwatched again. Next Up
     * cards have no progress and their menu doesn't offer it; a call for one does nothing.
     */
    suspend fun clearProgress(profileId: Long, item: ContinueWatchingItem) {
        if (item.isNextUp) return
        watchProgressDao.clearItemProgress(
            profileId,
            item.media.tmdbId,
            item.media.mediaType,
            item.seasonNumber,
            item.episodeNumber,
        )
    }
}
