package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A Next Up card the profile removed from Continue Watching (long-press `Remove`), one row per show. The card for
 * [seasonNumber]/[episodeNumber] stays hidden until the show is watched again after [dismissedAtMillis] or a newer
 * episode than [lastAiredSeason]/[lastAiredEpisode] airs. Watch history is never touched.
 */
@Entity(
    tableName = "next_up_dismissals",
    primaryKeys = ["profileId", "tmdbId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class NextUpDismissalEntity(
    val profileId: Long,
    val tmdbId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val lastAiredSeason: Int?,
    val lastAiredEpisode: Int?,
    val dismissedAtMillis: Long,
)
