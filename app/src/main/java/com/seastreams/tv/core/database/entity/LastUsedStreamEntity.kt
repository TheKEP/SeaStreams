package com.seastreams.tv.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.seastreams.tv.domain.model.MediaType

/**
 * The stream that last started for one movie (season/episode `0`) or one episode, per profile: the
 * stream list's `LAST USED` badge and initial focus (PRD §6, §9). Kept apart from `watch_progress` so
 * clearing progress or marking an episode unwatched never forgets it; deleting the profile does.
 *
 * Addon streams carry no stable id, so a re-fetched list is matched on [streamUrl] first, then on
 * [addonUrl] + [label] + [description] (signed debrid URLs change on every fetch).
 */
@Entity(
    tableName = "last_used_streams",
    primaryKeys = ["profileId", "tmdbId", "mediaType", "seasonNumber", "episodeNumber"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"])],
)
data class LastUsedStreamEntity(
    val profileId: Long,
    val tmdbId: Int,
    val mediaType: MediaType,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val addonUrl: String,
    val streamUrl: String,
    val label: String,
    val description: String,
    val updatedAtMillis: Long,
)
