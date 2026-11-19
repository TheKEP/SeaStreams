package com.seastreams.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.domain.model.MediaType

@Dao
interface LastUsedStreamDao {

    /** The last stream started for one movie (season/episode `0`) or episode of [profileId]. */
    @Query(
        """
        SELECT * FROM last_used_streams
        WHERE profileId = :profileId AND tmdbId = :tmdbId AND mediaType = :mediaType
          AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber
        """,
    )
    suspend fun get(profileId: Long, tmdbId: Int, mediaType: MediaType, seasonNumber: Int, episodeNumber: Int): LastUsedStreamEntity?

    @Upsert
    suspend fun upsert(stream: LastUsedStreamEntity)
}
