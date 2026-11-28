package com.seastreams.tv.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.seastreams.tv.domain.model.MediaType

/** A movie or show saved to a profile's Library. */
@Entity(
    tableName = "library_items",
    primaryKeys = ["profileId", "tmdbId", "mediaType"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class LibraryItemEntity(
    val profileId: Long,
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val posterPath: String?,
    val certification: String?,
    /** Comma-separated TMDB genre ids, used by the Library genre filter. */
    val genreIds: String,
    val addedAtMillis: Long,
    /**
     * Comma-separated TMDB keyword ids (movies), behind keyword genres such as `Christmas`. `NULL` = not looked up
     * yet (rows saved before v11), filled by `LibraryKeywordBackfill`; `""` = none, or a show (never looked up).
     */
    @ColumnInfo(defaultValue = "NULL") val keywordIds: String? = null,
)
