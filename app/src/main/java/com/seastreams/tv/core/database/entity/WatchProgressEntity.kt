package com.seastreams.tv.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.seastreams.tv.domain.model.MediaType

/**
 * Playback position for a movie or a single episode. Movies use season/episode `0`.
 *
 * [dismissedFromContinueWatching] hides the row from Continue Watching (long-press `Remove`) while
 * keeping its progress. Every progress write builds a fresh entity, so the flag resets to `false` and
 * playing the item again brings it back.
 */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["profileId", "tmdbId", "mediaType", "seasonNumber", "episodeNumber"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId", "updatedAtMillis"])],
)
data class WatchProgressEntity(
    val profileId: Long,
    val tmdbId: Int,
    val mediaType: MediaType,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String,
    val episodeTitle: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "0")
    val dismissedFromContinueWatching: Boolean = false,
)
