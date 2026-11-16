package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.seastreams.tv.domain.model.MediaType

/**
 * Playback position for a movie or a single episode. Movies use season/episode `0`.
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
)
