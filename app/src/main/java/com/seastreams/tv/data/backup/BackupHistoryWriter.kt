package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.NextUpDismissalDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.dao.WatchProgressDao
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import javax.inject.Inject

/** How many Library titles and watch progress records a restore wrote. */
internal data class RestoredHistory(val libraryItems: Int, val watchProgress: Int) {
    operator fun plus(other: RestoredHistory) = RestoredHistory(libraryItems + other.libraryItems, watchProgress + other.watchProgress)
}

/**
 * The Library and Watch Progress part of a restore's Room transaction (lock-free; `BackupRestorer` holds the locks).
 * Both merge without destroying anything on the TV:
 * - **Library:** insert-or-ignore, like the MDBList import: a title already saved keeps its own date added and
 *   metadata (only keyword ids it hasn't looked up yet are filled in from the backup); a missing one arrives with the
 *   backup's date added. A Kids profile only receives titles within its
 *   current limits, judged exactly as `GetLibraryUseCase` judges them on screen (CLAUDE.md rule 6).
 * - **Watch Progress:** each record is written only when [WatchProgressMerge] prefers it to the TV's own, so a TV in
 *   use is never rewound. (Records carry no rating; `GetContinueWatchingUseCase` age-filters them when shown.)
 * - **Removed Next Up cards:** per show, the more recent removal wins (the TV's own on a tie).
 */
internal class BackupHistoryWriter @Inject constructor(
    private val profiles: ProfileDao,
    private val library: LibraryDao,
    private val watchProgress: WatchProgressDao,
    private val ageEngine: AgeRestrictionEngine,
    private val nextUpDismissals: NextUpDismissalDao,
) {

    suspend fun write(profileId: Long, entry: ProfileBackupEntry): RestoredHistory {
        val restriction = profiles.getById(profileId)?.toAgeRestriction()

        val items = entry.library
            .map { it.toEntity(profileId, HistoryBackupValidation.mediaType(it.mediaType)) }
            .filter { ageEngine.isAllowed(it.certification, restriction) }
        val added = if (items.isEmpty()) 0 else library.insertAll(items).count { it != -1L }
        // A title the TV already had keeps its own row, but learns the backup's keyword ids if it has none yet.
        items.forEach { item ->
            item.keywordIds?.let { library.fillMissingKeywordIds(profileId, item.tmdbId, item.mediaType, it) }
        }

        val local = watchProgress.getAll(profileId).associateBy { it.key() }
        val winners = entry.watchProgress
            .map { it.toEntity(profileId, HistoryBackupValidation.mediaType(it.mediaType)) }
            .filter { WatchProgressMerge.backupWins(local[it.key()], it) }
        if (winners.isNotEmpty()) watchProgress.upsertAll(winners)

        val dismissed = nextUpDismissals.getAll(profileId).associateBy { it.tmdbId }
        val newer = entry.nextUpDismissals.map { it.toEntity(profileId) }
            .filter { backup -> dismissed[backup.tmdbId].let { it == null || backup.dismissedAtMillis > it.dismissedAtMillis } }
        if (newer.isNotEmpty()) nextUpDismissals.upsertAll(newer)

        return RestoredHistory(added, winners.size)
    }

    private fun com.seastreams.tv.core.database.entity.WatchProgressEntity.key() =
        listOf(tmdbId, mediaType, seasonNumber, episodeNumber)
}
