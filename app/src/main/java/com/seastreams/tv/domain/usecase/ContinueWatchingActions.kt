package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.domain.model.ContinueWatchingItem
import javax.inject.Inject

/**
 * The Continue Watching long-press menu's actions (PRD §1). Both target exactly the card's movie or
 * single episode (season/episode `0` for movies), never the rest of a show.
 */
class ContinueWatchingActions @Inject constructor(
    private val watchProgressDao: WatchProgressDao,
) {
    /** `Remove`: hides the card but keeps its progress; playing it again brings it back. */
    suspend fun remove(profileId: Long, item: ContinueWatchingItem) {
        watchProgressDao.dismissFromContinueWatching(
            profileId,
            item.media.tmdbId,
            item.media.mediaType,
            item.seasonNumber,
            item.episodeNumber,
        )
    }

    /** `Clear progress`: deletes the card's progress record, so that movie or episode is unwatched again. */
    suspend fun clearProgress(profileId: Long, item: ContinueWatchingItem) {
        watchProgressDao.clearItemProgress(
            profileId,
            item.media.tmdbId,
            item.media.mediaType,
            item.seasonNumber,
            item.episodeNumber,
        )
    }
}
